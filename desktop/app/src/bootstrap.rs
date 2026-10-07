use crate::paths::{desktop_id_for, AppPaths};
use crate::video_output::{spawn_frame_presenter, LatestVideoFrame};
use std::{io, sync::Arc};
use usb_probe::{
    DecodedFrameCounter, DesktopConnectionWorker, DesktopEvent, DesktopTlsIdentityError,
    DesktopTlsIdentityStore, DesktopWorkerConfig, DesktopWorkerHandle, DesktopWorkerSpawnError,
    FileTrustedPhoneStore, PairingQrIssuer, PairingQrIssuerError, PhoneLink, VideoDecoder,
};

#[derive(Debug)]
pub enum BootstrapError {
    Paths(io::Error),
    Identity(DesktopTlsIdentityError),
    PairingQr(PairingQrIssuerError),
    Worker(DesktopWorkerSpawnError),
    UsbConfig(usb_probe::UsbProbeError),
    UnsupportedPlatform,
}

impl BootstrapError {
    pub fn user_message(&self) -> &'static str {
        match self {
            Self::Paths(_) => "No se pudo preparar la carpeta privada de ChinchillaCam.",
            Self::Identity(_) => "No se pudo cargar la identidad segura de esta computadora.",
            Self::PairingQr(_) => "No se pudo preparar el QR de vinculación.",
            Self::Worker(_) => "No se pudo iniciar la conexión con el teléfono.",
            Self::UsbConfig(_) => "La configuración USB no es válida.",
            Self::UnsupportedPlatform => "Esta versión todavía no admite tu sistema operativo.",
        }
    }
}

pub fn start_worker<L, F, D>(
    paths: &AppPaths,
    link: L,
    desktop_name: &str,
    decoder_factory: F,
    events: impl Fn(DesktopEvent) + Send + 'static,
) -> Result<DesktopWorkerHandle, BootstrapError>
where
    L: PhoneLink,
    F: FnMut() -> D + Send + 'static,
    D: VideoDecoder + DecodedFrameCounter,
{
    paths.prepare().map_err(BootstrapError::Paths)?;
    let identity = DesktopTlsIdentityStore::new(paths.identity_dir.clone())
        .load_or_create(desktop_name)
        .map_err(BootstrapError::Identity)?;
    let issuer = PairingQrIssuer::new(
        &desktop_id_for(&identity),
        desktop_name,
        identity.clone(),
        120,
    )
    .map_err(BootstrapError::PairingQr)?;
    DesktopConnectionWorker::spawn(
        DesktopWorkerConfig::default(),
        link,
        identity,
        Arc::new(FileTrustedPhoneStore::new(&paths.trusted_phones)),
        issuer,
        decoder_factory,
        events,
    )
    .map_err(BootstrapError::Worker)
}

pub fn presenting_decoder_factory<D, M>(
    slot: LatestVideoFrame,
    on_frame: impl Fn() + Clone + Send + 'static,
    mut make_decoder: M,
) -> impl FnMut() -> D
where
    M: FnMut(usb_probe::ChannelDecodedFrameSink) -> D,
{
    move || {
        let (sink, frames) =
            usb_probe::ChannelDecodedFrameSink::bounded(4).expect("valid frame queue capacity");
        // If the presenter cannot spawn, its receiver disconnects; the decoder still starts
        // and reports rejected frames rather than killing the connection worker thread.
        if let Err(error) = spawn_frame_presenter(frames, slot.clone(), on_frame.clone()) {
            eprintln!("Could not start decoded-frame presenter: {error}");
        }
        make_decoder(sink)
    }
}

#[cfg(target_os = "macos")]
pub fn start_production_worker(
    paths: &AppPaths,
    slot: LatestVideoFrame,
    on_frame: impl Fn() + Clone + Send + 'static,
    events: impl Fn(DesktopEvent) + Send + 'static,
) -> Result<DesktopWorkerHandle, BootstrapError> {
    use std::time::Duration;
    use usb_probe::{
        RusbPhoneBackend, ThreadedVideoDecoder, ThreadedVideoDecoderConfig, UsbPhoneLink,
        UsbPhoneLinkConfig, VideoToolboxDecoder,
    };
    let config = UsbPhoneLinkConfig::from_env().map_err(BootstrapError::UsbConfig)?;
    start_worker(
        paths,
        UsbPhoneLink::new(RusbPhoneBackend, config),
        &crate::paths::desktop_display_name(),
        presenting_decoder_factory(slot, on_frame, |sink| {
            ThreadedVideoDecoder::spawn(
                "videotoolbox-decoder",
                ThreadedVideoDecoderConfig::new(16, Duration::from_secs(2))
                    .expect("valid decoder config"),
                move || VideoToolboxDecoder::new(sink),
            )
            .expect("video decoder thread")
        }),
        events,
    )
}

#[cfg(not(target_os = "macos"))]
pub fn start_production_worker(
    _paths: &AppPaths,
    _slot: LatestVideoFrame,
    _on_frame: impl Fn() + Clone + Send + 'static,
    _events: impl Fn(DesktopEvent) + Send + 'static,
) -> Result<DesktopWorkerHandle, BootstrapError> {
    Err(BootstrapError::UnsupportedPlatform)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::mpsc, time::Duration};
    use usb_probe::{
        EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind, FakeVideoDecoder,
        PresentationTimestamp,
    };

    #[test]
    fn production_frames_reach_the_shared_slot() {
        let slot = LatestVideoFrame::default();
        let (sender, updates) = mpsc::channel();
        let mut factory = presenting_decoder_factory(
            slot.clone(),
            move || {
                sender.send(()).unwrap();
            },
            FakeVideoDecoder::new,
        );
        let mut decoder = factory();
        let limits = EncodedVideoChunkLimits::new(1280 * 720 * 3 / 2).unwrap();
        let chunk = |kind, payload| {
            EncodedVideoChunk::new(
                1,
                PresentationTimestamp::from_micros(42),
                kind,
                payload,
                &limits,
            )
            .unwrap()
        };
        decoder
            .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, vec![1]))
            .unwrap();
        let mut pixels = vec![16; 1280 * 720];
        pixels.extend(vec![128; 1280 * 720 / 2]);
        decoder
            .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, pixels))
            .unwrap();
        updates.recv_timeout(Duration::from_secs(3)).unwrap();
        let (sequence, frame) = slot.snapshot();
        assert_eq!(sequence, 1);
        let frame = frame.unwrap();
        assert_eq!((frame.width, frame.height, frame.pts_us), (1280, 720, 42));
        assert_eq!(&frame.pixels[..4], &[0, 0, 0, 255]);
        drop(decoder);
        updates.recv_timeout(Duration::from_secs(3)).unwrap();
        assert_eq!(slot.snapshot(), (2, None));
    }
}
