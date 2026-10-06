use crate::paths::{desktop_id_for, AppPaths};
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

#[cfg(target_os = "macos")]
pub fn start_production_worker(
    paths: &AppPaths,
    events: impl Fn(DesktopEvent) + Send + 'static,
) -> Result<DesktopWorkerHandle, BootstrapError> {
    use std::time::Duration;
    use usb_probe::{
        ChannelDecodedFrameSink, RusbPhoneBackend, ThreadedVideoDecoder,
        ThreadedVideoDecoderConfig, UsbPhoneLink, UsbPhoneLinkConfig, VideoToolboxDecoder,
    };
    let config = UsbPhoneLinkConfig::from_env().map_err(BootstrapError::UsbConfig)?;
    start_worker(
        paths,
        UsbPhoneLink::new(RusbPhoneBackend, config),
        &crate::paths::desktop_display_name(),
        || {
            let (sink, frames) =
                ChannelDecodedFrameSink::bounded(4).expect("valid frame queue capacity");
            // Step 3's OBS output will replace this drain; never block the decoder on a full queue.
            std::thread::Builder::new()
                .name("decoded-frame-drain".into())
                .spawn(move || while frames.recv().is_ok() {})
                .expect("decoded frame drain thread");
            ThreadedVideoDecoder::spawn(
                "videotoolbox-decoder",
                ThreadedVideoDecoderConfig::new(16, Duration::from_secs(2))
                    .expect("valid decoder config"),
                move || VideoToolboxDecoder::new(sink),
            )
            .expect("video decoder thread")
        },
        events,
    )
}

#[cfg(not(target_os = "macos"))]
pub fn start_production_worker(
    _paths: &AppPaths,
    _events: impl Fn(DesktopEvent) + Send + 'static,
) -> Result<DesktopWorkerHandle, BootstrapError> {
    Err(BootstrapError::UnsupportedPlatform)
}
