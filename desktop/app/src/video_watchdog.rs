use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoWarning {
    NoFrames,
}

/// Counts published frames rather than slot revisions: clearing a session is not a frame.
#[derive(Debug, Default, Clone)]
pub struct VideoWatchdog {
    last_frame: Option<(Instant, u64)>,
}

impl VideoWatchdog {
    pub fn observe(
        &mut self,
        now: Instant,
        connected: bool,
        frame_sequence: u64,
    ) -> Option<VideoWarning> {
        if !connected {
            self.last_frame = None;
            return None;
        }
        match self.last_frame {
            Some((since, sequence)) if sequence == frame_sequence => {
                (now.saturating_duration_since(since) >= Duration::from_secs(5))
                    .then_some(VideoWarning::NoFrames)
            }
            _ => {
                self.last_frame = Some((now, frame_sequence));
                None
            }
        }
    }
}
