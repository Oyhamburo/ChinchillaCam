use chinchillacam_app::{
    bootstrap::start_worker,
    paths::{desktop_id_for, AppPaths},
};
use std::{
    io::{Read, Write},
    sync::mpsc,
    time::{Duration, SystemTime},
};
use usb_probe::{
    DesktopEvent, DesktopTlsIdentityStore, FakeVideoDecoder, IdleReadTimeoutControl, PhoneLink,
    PhoneLinkError, RecordingDecodedFrameSink, UsbProbeError,
};

struct EmptyLink;
struct NeverStream;
impl Read for NeverStream {
    fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
        unreachable!()
    }
}
impl Write for NeverStream {
    fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
        unreachable!()
    }
    fn flush(&mut self) -> std::io::Result<()> {
        unreachable!()
    }
}
impl IdleReadTimeoutControl for NeverStream {
    fn set_idle_read_timeout(&mut self, _: Option<Duration>) -> Result<(), UsbProbeError> {
        Ok(())
    }
}
impl PhoneLink for EmptyLink {
    type Stream = NeverStream;
    fn poll_phone(&mut self) -> Result<Option<Self::Stream>, PhoneLinkError> {
        Ok(None)
    }
}

#[test]
fn bootstrap_creates_private_identity_and_emits_trusted_phones() {
    let root = std::env::temp_dir().join(format!(
        "chinchillacam-app-bootstrap-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let paths = AppPaths::under(root.clone());
    let boot = || {
        let (tx, rx) = mpsc::channel();
        let handle = start_worker(
            &paths,
            EmptyLink,
            "Mi computadora",
            || FakeVideoDecoder::new(RecordingDecodedFrameSink::default()),
            move |event| {
                let _ = tx.send(event);
            },
        )
        .unwrap();
        assert!(
            matches!(rx.recv_timeout(Duration::from_secs(2)).unwrap(), DesktopEvent::TrustedPhones(ref phones) if phones.is_empty())
        );
        assert!(matches!(
            rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            DesktopEvent::WaitingForPhone
        ));
        assert!(handle.shutdown());
        desktop_id_for(
            &DesktopTlsIdentityStore::new(paths.identity_dir.clone())
                .load_or_create("Mi computadora")
                .unwrap(),
        )
    };
    let first = boot();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(&paths.identity_dir)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
    }
    assert!(paths.identity_dir.join("desktop-p256.pkcs8.der").exists());
    assert_eq!(first, boot());
    std::fs::remove_dir_all(root).unwrap();
}
