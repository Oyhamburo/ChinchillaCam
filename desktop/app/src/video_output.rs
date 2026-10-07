use crate::nv12::nv12_to_rgba;
use std::{
    io,
    sync::{mpsc::Receiver, Arc, Mutex},
    thread::{self, JoinHandle},
};
use usb_probe::{DecodedVideoFrame, PixelFormat};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RgbaVideoFrame {
    pub width: u32,
    pub height: u32,
    pub pts_us: u64,
    pub pixels: Arc<[u8]>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VideoSessionId(u64);

#[derive(Default)]
struct FrameState {
    frame: Option<RgbaVideoFrame>,
    owner: Option<VideoSessionId>,
    sequence: u64,
    next_session: u64,
}

#[derive(Clone, Default)]
pub struct LatestVideoFrame(Arc<Mutex<FrameState>>);

impl LatestVideoFrame {
    pub fn new_session(&self) -> VideoSessionId {
        let mut state = self.0.lock().unwrap_or_else(|err| err.into_inner());
        state.next_session += 1;
        VideoSessionId(state.next_session)
    }

    pub fn publish(&self, session: VideoSessionId, frame: RgbaVideoFrame) {
        let mut state = self.0.lock().unwrap_or_else(|err| err.into_inner());
        state.frame = Some(frame);
        state.owner = Some(session);
        state.sequence += 1;
    }

    pub fn snapshot(&self) -> (u64, Option<RgbaVideoFrame>) {
        let state = self.0.lock().unwrap_or_else(|err| err.into_inner());
        (state.sequence, state.frame.clone())
    }

    pub fn clear_if_owner(&self, session: VideoSessionId) {
        let mut state = self.0.lock().unwrap_or_else(|err| err.into_inner());
        if state.owner == Some(session) {
            state.frame = None;
            state.owner = None;
            state.sequence += 1;
        }
    }
}

pub fn spawn_frame_presenter(
    frames: Receiver<DecodedVideoFrame>,
    slot: LatestVideoFrame,
    on_frame: impl Fn() + Send + 'static,
) -> io::Result<JoinHandle<()>> {
    let session = slot.new_session();
    thread::Builder::new()
        .name("decoded-frame-presenter".into())
        .spawn(move || {
            while let Ok(mut frame) = frames.recv() {
                while let Ok(newer) = frames.try_recv() {
                    frame = newer;
                }
                if frame.pixel_format() != PixelFormat::Nv12 {
                    continue;
                }
                if let Ok(pixels) = nv12_to_rgba(frame.width(), frame.height(), frame.data()) {
                    slot.publish(
                        session,
                        RgbaVideoFrame {
                            width: frame.width(),
                            height: frame.height(),
                            pts_us: frame.pts_us(),
                            pixels: pixels.into(),
                        },
                    );
                    on_frame();
                }
            }
            slot.clear_if_owner(session);
            on_frame();
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    fn nv12_frame(pts_us: u64) -> DecodedVideoFrame {
        DecodedVideoFrame::new(7, pts_us, 1, 1, PixelFormat::Nv12, vec![16, 128, 128])
    }

    #[test]
    fn presenter_publishes_only_the_newest_frame() {
        let slot = LatestVideoFrame::default();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let (sender, frames) = mpsc::channel();
        for pts_us in [1, 2, 3] {
            sender.send(nv12_frame(pts_us)).unwrap();
        }
        drop(sender);
        let snapshots = Arc::clone(&seen);
        let shared = slot.clone();
        spawn_frame_presenter(frames, slot.clone(), move || {
            snapshots.lock().unwrap().push(shared.snapshot());
        })
        .unwrap()
        .join()
        .unwrap();

        let seen = seen.lock().unwrap();
        let presented: Vec<_> = seen
            .iter()
            .filter_map(|(_, frame)| frame.as_ref().map(|frame| frame.pts_us))
            .collect();
        assert_eq!(presented, [3]);
        assert_eq!(slot.snapshot(), (2, None));
        assert_eq!(seen.len(), 2); // One repaint for the frame, one for disconnect.
    }

    #[test]
    fn disconnect_clears_its_frame_and_repaints() {
        let slot = LatestVideoFrame::default();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let (sender, frames) = mpsc::channel();
        sender.send(nv12_frame(42)).unwrap();
        drop(sender);
        let snapshots = seen.clone();
        let shared = slot.clone();
        spawn_frame_presenter(frames, slot.clone(), move || {
            snapshots.lock().unwrap().push(shared.snapshot());
        })
        .unwrap()
        .join()
        .unwrap();

        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 2);
        assert_eq!(seen[0].0, 1);
        assert_eq!(seen[0].1.as_ref().unwrap().pts_us, 42);
        assert_eq!(seen[0].1.as_ref().unwrap().pixels.as_ref(), &[0, 0, 0, 255]);
        assert_eq!(seen[1], (2, None));
        assert_eq!(slot.snapshot(), (2, None));
    }

    #[test]
    fn older_session_cannot_clear_newer_frame() {
        let slot = LatestVideoFrame::default();
        let older = slot.new_session();
        let newer = slot.new_session();
        let frame = RgbaVideoFrame {
            width: 1,
            height: 1,
            pts_us: 99,
            pixels: Arc::from([0, 0, 0, 255]),
        };
        slot.publish(older, frame.clone());
        slot.publish(newer, frame.clone());
        slot.clear_if_owner(older);
        assert_eq!(slot.snapshot(), (2, Some(frame)));
        slot.clear_if_owner(newer);
        assert_eq!(slot.snapshot(), (3, None));
        slot.clear_if_owner(newer);
        assert_eq!(slot.snapshot(), (3, None));
    }

    #[test]
    fn invalid_frames_are_skipped_and_later_valid_frames_publish() {
        let slot = LatestVideoFrame::default();
        let seen = Arc::new(Mutex::new(Vec::new()));
        for frame in [
            DecodedVideoFrame::new(7, 1, 1, 1, PixelFormat::Bgra, vec![0; 4]),
            DecodedVideoFrame::new(7, 2, 1, 1, PixelFormat::Unknown, vec![]),
            DecodedVideoFrame::new(7, 3, 1, 1, PixelFormat::Nv12, vec![16]),
            nv12_frame(4),
        ] {
            // Joining each disconnected receiver ensures invalid inputs were actually consumed,
            // rather than silently discarded by the latest-wins queue drain.
            let (sender, frames) = mpsc::channel();
            sender.send(frame).unwrap();
            drop(sender);
            let snapshots = seen.clone();
            let shared = slot.clone();
            spawn_frame_presenter(frames, slot.clone(), move || {
                snapshots.lock().unwrap().push(shared.snapshot());
            })
            .unwrap()
            .join()
            .unwrap();
        }
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 5); // Three disconnects, then publish and clear.
        assert_eq!(seen[3].1.as_ref().unwrap().pts_us, 4);
        assert_eq!(slot.snapshot(), (2, None));
    }

    #[test]
    fn snapshot_recovers_from_a_poisoned_mutex() {
        let slot = LatestVideoFrame::default();
        let shared = slot.clone();
        assert!(std::thread::spawn(move || {
            let _guard = shared.0.lock().unwrap();
            panic!("poison for test");
        })
        .join()
        .is_err());
        assert_eq!(slot.snapshot(), (0, None));
        let session = slot.new_session();
        slot.publish(
            session,
            RgbaVideoFrame {
                width: 1,
                height: 1,
                pts_us: 5,
                pixels: Arc::from([0, 0, 0, 255]),
            },
        );
        assert_eq!(slot.snapshot().0, 1);
        slot.clear_if_owner(session);
        assert_eq!(slot.snapshot(), (2, None));
    }
}
