use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::{
    DecodedFrameCounter, DecodedFrameSink, DecodedFrameSinkError, DecodedVideoFrame,
    EncodedVideoChunk, EncodedVideoFrameKind, VideoDecoder, VideoDecoderError,
};

/// Why a threaded decoder or its channel sink could not be built.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ThreadedVideoDecoderError {
    InvalidQueueCapacity,
    QueueCapacityTooLarge { capacity: usize, max: usize },
    InvalidJoinTimeout,
    Spawn(String),
}

/// Bounds for a [`ThreadedVideoDecoder`]: how many encoded chunks may wait for the worker, and
/// how long dropping the decoder waits for the worker to finish.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ThreadedVideoDecoderConfig {
    queue_capacity: usize,
    join_timeout: Duration,
}

impl ThreadedVideoDecoderConfig {
    /// Upper bound on any queue built here; `sync_channel` preallocates its slots.
    pub const MAX_QUEUE_CAPACITY: usize = 4096;

    pub fn new(
        queue_capacity: usize,
        join_timeout: Duration,
    ) -> Result<Self, ThreadedVideoDecoderError> {
        validate_capacity(queue_capacity)?;
        if join_timeout.is_zero() {
            return Err(ThreadedVideoDecoderError::InvalidJoinTimeout);
        }
        Ok(Self {
            queue_capacity,
            join_timeout,
        })
    }
}

fn validate_capacity(capacity: usize) -> Result<(), ThreadedVideoDecoderError> {
    if capacity == 0 {
        return Err(ThreadedVideoDecoderError::InvalidQueueCapacity);
    }
    if capacity > ThreadedVideoDecoderConfig::MAX_QUEUE_CAPACITY {
        return Err(ThreadedVideoDecoderError::QueueCapacityTooLarge {
            capacity,
            max: ThreadedVideoDecoderConfig::MAX_QUEUE_CAPACITY,
        });
    }
    Ok(())
}

/// State the worker publishes and the caller reads.
#[derive(Default)]
struct Shared {
    failure: Mutex<Option<VideoDecoderError>>,
    frames_emitted: AtomicU64,
    dropped_decodes: AtomicU64,
}

impl Shared {
    fn failure(&self) -> Option<VideoDecoderError> {
        self.failure
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// Keeps the first failure: later ones are consequences of it.
    fn record_failure(&self, error: VideoDecoderError) {
        let mut failure = self
            .failure
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        failure.get_or_insert(error);
    }
}

/// Runs a [`VideoDecoder`] on its own worker thread so decoding (and session creation) never
/// blocks the caller. The inner decoder is built on the worker from a factory, so it need not
/// be `Send`. `decode_encoded_video` only does a non-blocking `try_send` into a bounded FIFO
/// drained in order by one worker; a full queue is [`VideoDecoderError::Backpressure`].
///
/// Inner errors surface one chunk late: a `Failure` (or a panic in the factory or decoder) is
/// recorded as sticky, the worker stops decoding, and every later call returns that error. An
/// inner `Backpressure` (e.g. a full frame channel) drops that chunk and every following
/// `Delta` until the next `Key` (codec configs still pass), all counted in
/// [`dropped_decodes`](Self::dropped_decodes).
///
/// Drop closes the queue and waits at most the join timeout for the worker's completion signal
/// (std has no timed join); a worker still stuck in the inner decoder is detached and exits on
/// its own once that decode returns.
pub struct ThreadedVideoDecoder {
    sender: Option<SyncSender<EncodedVideoChunk>>,
    shared: Arc<Shared>,
    worker_done: Receiver<()>,
    worker: Option<JoinHandle<()>>,
    join_timeout: Duration,
}

impl ThreadedVideoDecoder {
    pub fn spawn<D, F>(
        thread_name: &str,
        config: ThreadedVideoDecoderConfig,
        factory: F,
    ) -> Result<Self, ThreadedVideoDecoderError>
    where
        D: VideoDecoder + DecodedFrameCounter + 'static,
        F: FnOnce() -> D + Send + 'static,
    {
        let (sender, receiver) = mpsc::sync_channel(config.queue_capacity);
        let (done_signal, worker_done) = mpsc::channel::<()>();
        let shared = Arc::new(Shared::default());
        let worker_shared = Arc::clone(&shared);

        let worker = thread::Builder::new()
            .name(thread_name.to_string())
            .spawn(move || {
                // Dropped when the thread body ends, including after a caught panic.
                let _done_signal = done_signal;
                let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
                    run_worker(factory, &receiver, &worker_shared)
                }));
                if outcome.is_err() {
                    worker_shared.record_failure(VideoDecoderError::Failure(
                        "decoder worker panicked".to_string(),
                    ));
                }
                // The queue receiver drops only after any failure is recorded, so a caller
                // that sees the queue disconnected also sees the cause.
                drop(receiver);
            })
            .map_err(|error| ThreadedVideoDecoderError::Spawn(error.to_string()))?;

        Ok(Self {
            sender: Some(sender),
            shared,
            worker_done,
            worker: Some(worker),
            join_timeout: config.join_timeout,
        })
    }

    /// Chunks the worker dropped because the inner decoder reported backpressure, plus the
    /// deltas it skipped afterwards while waiting for the next keyframe.
    pub fn dropped_decodes(&self) -> u64 {
        self.shared.dropped_decodes.load(Ordering::Acquire)
    }
}

fn run_worker<D, F>(factory: F, receiver: &Receiver<EncodedVideoChunk>, shared: &Shared)
where
    D: VideoDecoder + DecodedFrameCounter,
    F: FnOnce() -> D,
{
    let mut decoder = factory();
    let mut awaiting_key = false;
    while let Ok(chunk) = receiver.recv() {
        match chunk.frame_kind() {
            EncodedVideoFrameKind::Key => awaiting_key = false,
            EncodedVideoFrameKind::Delta if awaiting_key => {
                shared.dropped_decodes.fetch_add(1, Ordering::AcqRel);
                continue;
            }
            EncodedVideoFrameKind::Delta | EncodedVideoFrameKind::CodecConfig => {}
        }
        let result = decoder.decode_encoded_video(chunk);
        shared
            .frames_emitted
            .store(decoder.frames_emitted(), Ordering::Release);
        match result {
            Ok(()) => {}
            Err(VideoDecoderError::Backpressure) => {
                awaiting_key = true;
                shared.dropped_decodes.fetch_add(1, Ordering::AcqRel);
            }
            Err(error) => {
                shared.record_failure(error);
                return;
            }
        }
    }
}

impl VideoDecoder for ThreadedVideoDecoder {
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        if let Some(failure) = self.shared.failure() {
            return Err(failure);
        }
        let Some(sender) = self.sender.as_ref() else {
            return Err(worker_stopped());
        };
        match sender.try_send(chunk) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(_)) => Err(VideoDecoderError::Backpressure),
            Err(TrySendError::Disconnected(_)) => {
                Err(self.shared.failure().unwrap_or_else(worker_stopped))
            }
        }
    }
}

fn worker_stopped() -> VideoDecoderError {
    VideoDecoderError::Failure("decoder worker stopped".to_string())
}

impl DecodedFrameCounter for ThreadedVideoDecoder {
    fn frames_emitted(&self) -> u64 {
        self.shared.frames_emitted.load(Ordering::Acquire)
    }
}

impl Drop for ThreadedVideoDecoder {
    fn drop(&mut self) {
        // Closing the queue lets an idle worker leave its `recv` loop.
        drop(self.sender.take());
        match self.worker_done.recv_timeout(self.join_timeout) {
            Ok(()) | Err(RecvTimeoutError::Disconnected) => {
                if let Some(worker) = self.worker.take() {
                    // The worker body already finished; a panic was caught and recorded.
                    let _ = worker.join();
                }
            }
            // Still stuck inside the inner decoder: detach rather than hang the caller.
            Err(RecvTimeoutError::Timeout) => drop(self.worker.take()),
        }
    }
}

/// A [`DecodedFrameSink`] that hands decoded frames to a consumer thread over a bounded queue.
/// It never blocks: a full queue is [`DecodedFrameSinkError::Backpressure`] and a consumer that
/// went away is [`DecodedFrameSinkError::Rejected`].
#[derive(Debug, Clone)]
pub struct ChannelDecodedFrameSink {
    sender: SyncSender<DecodedVideoFrame>,
}

impl ChannelDecodedFrameSink {
    /// Builds the sink and the consumer end of its queue.
    pub fn bounded(
        capacity: usize,
    ) -> Result<(Self, Receiver<DecodedVideoFrame>), ThreadedVideoDecoderError> {
        validate_capacity(capacity)?;
        let (sender, receiver) = mpsc::sync_channel(capacity);
        Ok((Self { sender }, receiver))
    }
}

impl DecodedFrameSink for ChannelDecodedFrameSink {
    fn push_decoded_frame(
        &mut self,
        frame: DecodedVideoFrame,
    ) -> Result<(), DecodedFrameSinkError> {
        self.sender.try_send(frame).map_err(|error| match error {
            TrySendError::Full(_) => DecodedFrameSinkError::Backpressure,
            TrySendError::Disconnected(_) => {
                DecodedFrameSinkError::Rejected("decoded frame consumer disconnected".to_string())
            }
        })
    }
}
