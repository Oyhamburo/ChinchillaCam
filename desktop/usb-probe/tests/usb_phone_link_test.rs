//! Task d5 (`odd/tasks/desktop-production-app.md`, sections 3.5 and 4.5): the production USB
//! phone link. Detection is a pure function; the link runs over a fake backend whose accessory
//! channels are crossed transfer pipes, and hands a stream to the worker only once the phone
//! has sent its first bytes.

#[allow(dead_code)]
mod common;

use std::{
    io::{Cursor, Read, Write},
    path::PathBuf,
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, Instant, SystemTime},
};

use common::usb_transfer_pipe::{crossed_transfer_pair, CrossedTransferBulkIo};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, StreamOwned,
};
use usb_probe::{
    parse_usb_device_override, phone_id_for_spki, read_session_frame, select_phone_candidate,
    write_session_frame, AoaObservedDevice, DesktopConnectionWorker, DesktopEvent,
    DesktopTlsIdentity, DesktopWorkerConfig, DeviceIdentifier, FakeVideoDecoder,
    FileTrustedPhoneStore, FrameTransferBudget, FramedUsbStream, PairingQrIssuer, PeekedStream,
    PhoneCandidate, PhoneLink, PhoneLinkError, RecordingDecodedFrameSink, SessionFrame,
    SessionFramePayload, SessionRuntimeConfig, TrustedPhoneIdentity, UsbPhoneBackend, UsbPhoneLink,
    UsbPhoneLinkConfig, UsbProbeError, UsbTlsCiphertextStream,
};

const BACKOFF: Duration = Duration::from_millis(150);
const PHONE_READ_TIMEOUT: Duration = Duration::from_millis(3000);

fn device(vendor_id: u16, product_id: u16, port: u8) -> AoaObservedDevice {
    let location = usb_probe::UsbPhysicalLocation::new(1, vec![port]).unwrap();
    let identifier = DeviceIdentifier::VidPid {
        vendor_id,
        product_id,
    };
    AoaObservedDevice::new(identifier, Some(location))
}

#[test]
fn detection_prefers_accessory_mode_then_samsung() {
    let keyboard = device(0x05ac, 0x024f, 1);
    let samsung = device(0x04e8, 0x6860, 2);
    let accessory = device(0x18d1, 0x2d01, 3);
    let all = [keyboard.clone(), samsung.clone(), accessory.clone()];
    assert_eq!(
        select_phone_candidate(&all, None),
        Some(PhoneCandidate::AccessoryMode(accessory))
    );
    let second_samsung = device(0x04e8, 0x6861, 4);
    let no_accessory = [keyboard, samsung.clone(), second_samsung];
    assert_eq!(
        select_phone_candidate(&no_accessory, None),
        Some(PhoneCandidate::NeedsAccessorySwitch(samsung))
    );
}

#[test]
fn unknown_devices_are_ignored() {
    let others = [device(0x05ac, 0x024f, 1), device(0x18d1, 0x4ee7, 2)];
    assert_eq!(select_phone_candidate(&others, None), None);
    assert_eq!(select_phone_candidate(&[], None), None);
}

#[test]
fn override_selects_explicit_device() {
    let pixel = device(0x18d1, 0x4ee7, 2);
    let others = [device(0x05ac, 0x024f, 1), pixel.clone()];
    let wanted = Some(*pixel.identifier());
    assert_eq!(
        select_phone_candidate(&others, wanted),
        Some(PhoneCandidate::NeedsAccessorySwitch(pixel))
    );
    let absent = parse_usb_device_override(Some("2717:ff48")).unwrap();
    assert_eq!(select_phone_candidate(&others, absent), None);
}

#[test]
fn override_parse_rejects_garbage() {
    assert_eq!(parse_usb_device_override(None), Ok(None));
    assert_eq!(parse_usb_device_override(Some("  ")), Ok(None));
    assert_eq!(
        parse_usb_device_override(Some("18D1:4ee7")),
        Ok(Some(DeviceIdentifier::VidPid {
            vendor_id: 0x18d1,
            product_id: 0x4ee7
        }))
    );
    for garbage in [
        "18d1-4ee7",
        "18d1:4ee",
        "zzzz:4ee7",
        "+8d1:4ee7",
        "18d1:4ee7:1",
    ] {
        assert!(
            matches!(
                parse_usb_device_override(Some(garbage)),
                Err(UsbProbeError::InvalidDeviceIdentifier(_))
            ),
            "{garbage}"
        );
    }
}

#[test]
fn peeked_stream_serves_prefix_then_inner() {
    let mut stream = PeekedStream::new(b"abc".to_vec(), Cursor::new(b"def".to_vec()));
    let mut two = [0; 2];
    assert_eq!(stream.read(&mut two).unwrap(), 2);
    assert_eq!(&two, b"ab");
    let mut rest = Vec::new();
    stream.read_to_end(&mut rest).unwrap();
    assert_eq!(rest, b"cdef");
    stream.write_all(b"xy").unwrap();
}

// --- Fake backend ------------------------------------------------------------------------

#[derive(Default)]
struct FakeUsb {
    devices: Vec<AoaObservedDevice>,
    scan_error: Option<UsbProbeError>,
    calls: Vec<String>,
}

/// Switching turns the device into an accessory at the same port; every open yields a fresh
/// crossed pipe whose phone end goes to `phones`.
struct FakeBackend {
    usb: Arc<Mutex<FakeUsb>>,
    phones: mpsc::Sender<CrossedTransferBulkIo>,
}

impl UsbPhoneBackend for FakeBackend {
    type Io = CrossedTransferBulkIo;

    fn scan(&mut self) -> Result<Vec<AoaObservedDevice>, UsbProbeError> {
        let mut usb = self.usb.lock().unwrap();
        usb.calls.push("scan".to_string());
        match &usb.scan_error {
            Some(error) => Err(error.clone()),
            None => Ok(usb.devices.clone()),
        }
    }

    fn switch_to_accessory(&mut self, device: &AoaObservedDevice) -> Result<(), UsbProbeError> {
        let mut usb = self.usb.lock().unwrap();
        usb.calls.push(format!("switch {}", device.identifier()));
        let location = device.physical_location().cloned();
        let accessory = DeviceIdentifier::VidPid {
            vendor_id: 0x18d1,
            product_id: 0x2d00,
        };
        for listed in usb.devices.iter_mut().filter(|listed| *listed == device) {
            *listed = AoaObservedDevice::new(accessory, location.clone());
        }
        Ok(())
    }

    fn open_accessory(&mut self, device: &AoaObservedDevice) -> Result<Self::Io, UsbProbeError> {
        let mut usb = self.usb.lock().unwrap();
        usb.calls.push(format!("open {}", device.identifier()));
        let (desktop, phone) = crossed_transfer_pair();
        let _ = self.phones.send(phone);
        Ok(desktop)
    }
}

struct Harness {
    usb: Arc<Mutex<FakeUsb>>,
    phones: mpsc::Receiver<CrossedTransferBulkIo>,
    link: UsbPhoneLink<FakeBackend>,
}

impl Harness {
    fn new(devices: Vec<AoaObservedDevice>) -> Self {
        let usb = Arc::new(Mutex::new(FakeUsb {
            devices,
            ..FakeUsb::default()
        }));
        let (sender, phones) = mpsc::channel();
        let backend = FakeBackend {
            usb: Arc::clone(&usb),
            phones: sender,
        };
        let config = UsbPhoneLinkConfig {
            device_override: None,
            retry_backoff: BACKOFF,
            first_bytes_wait: Duration::from_millis(20),
            transfer_timeout: Duration::from_millis(1000),
        };
        Self {
            usb,
            phones,
            link: UsbPhoneLink::new(backend, config),
        }
    }

    fn calls(&self) -> Vec<String> {
        self.usb.lock().unwrap().calls.clone()
    }

    fn poll_none(&mut self) {
        assert!(matches!(self.link.poll_phone(), Ok(None)));
    }

    fn next_phone(&self) -> UsbTlsCiphertextStream<CrossedTransferBulkIo> {
        phone_usb_stream(self.phones.recv_timeout(Duration::from_secs(2)).unwrap())
    }
}

fn phone_usb_stream(io: CrossedTransferBulkIo) -> UsbTlsCiphertextStream<CrossedTransferBulkIo> {
    let budget = FrameTransferBudget::new(PHONE_READ_TIMEOUT, 65_536, 512).unwrap();
    UsbTlsCiphertextStream::new(FramedUsbStream::new(io, budget))
}

#[test]
fn link_waits_for_first_phone_bytes_before_handing_stream() {
    let mut harness = Harness::new(vec![device(0x18d1, 0x2d00, 3)]);
    for _ in 0..3 {
        harness.poll_none();
    }
    assert_eq!(harness.calls(), ["scan", "open 18d1:2d00"]);

    let mut phone = harness.next_phone();
    assert!(
        phone.framed_stream().io().outgoing().read_timeouts() >= 3,
        "the initial idle timeouts must not poison the waiting stream"
    );
    phone.write_all(b"client-hello").unwrap();
    let mut stream = harness
        .link
        .poll_phone()
        .unwrap()
        .expect("stream once the phone spoke");
    let mut buffer = [0; 64];
    let read = stream.read(&mut buffer).unwrap();
    assert_eq!(&buffer[..read], b"client-hello");
    stream.write_all(b"server-hello").unwrap();
    let read = phone.read(&mut buffer).unwrap();
    assert_eq!(&buffer[..read], b"server-hello");
}

#[test]
fn samsung_device_is_switched_then_opened() {
    let mut harness = Harness::new(vec![device(0x05ac, 0x024f, 1), device(0x04e8, 0x6860, 2)]);
    harness.poll_none();
    assert_eq!(harness.calls(), ["scan", "switch 04e8:6860"]);
    harness.poll_none();
    assert_eq!(
        harness.calls(),
        ["scan", "switch 04e8:6860", "scan", "open 18d1:2d00"]
    );
}

#[test]
fn scan_failure_backs_off_and_reports_once() {
    let mut harness = Harness::new(Vec::new());
    let error = UsbProbeError::UsbControlTransferFailed("libusb busy".to_string());
    harness.usb.lock().unwrap().scan_error = Some(error.clone());
    assert_eq!(
        harness.link.poll_phone().err(),
        Some(PhoneLinkError::Usb(error.clone()))
    );
    harness.poll_none();
    harness.poll_none();
    assert_eq!(harness.calls(), ["scan"]);

    thread::sleep(BACKOFF + Duration::from_millis(30));
    assert_eq!(
        harness.link.poll_phone().err(),
        Some(PhoneLinkError::Usb(error))
    );
    assert_eq!(harness.calls(), ["scan", "scan"]);
}

#[test]
fn closed_phone_drops_stream_and_reopens_on_next_poll() {
    let mut harness = Harness::new(vec![device(0x18d1, 0x2d00, 3)]);
    harness.poll_none();
    let first_phone = harness.phones.recv_timeout(Duration::from_secs(2)).unwrap();
    first_phone.outgoing().disconnect();
    harness.poll_none();
    assert_eq!(harness.calls(), ["scan", "open 18d1:2d00"]);

    harness.poll_none();
    assert_eq!(
        harness.calls(),
        ["scan", "open 18d1:2d00", "scan", "open 18d1:2d00"]
    );
    let mut phone = harness.next_phone();
    phone.write_all(b"again").unwrap();
    let mut stream = harness.link.poll_phone().unwrap().expect("reopened stream");
    let mut buffer = [0; 16];
    let read = stream.read(&mut buffer).unwrap();
    assert_eq!(&buffer[..read], b"again");
}

// --- Worker integration -------------------------------------------------------------------

#[test]
fn worker_reconnects_phone_through_usb_phone_link() {
    let desktop = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("USB Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let store_path = temp_store_path();
    let spki = phone_identity.spki_der_p256().to_vec();
    let trusted = TrustedPhoneIdentity::new(phone_id.clone(), "USB Phone", spki).unwrap();
    FileTrustedPhoneStore::new(&store_path)
        .trust(trusted)
        .unwrap();

    let harness = Harness::new(vec![device(0x04e8, 0x6860, 2)]);
    let (sender, events) = mpsc::channel();
    let issuer =
        PairingQrIssuer::new("studio-desktop", "Studio Desktop", desktop.clone(), 120).unwrap();
    let worker = DesktopConnectionWorker::spawn(
        worker_config(),
        harness.link,
        desktop.clone(),
        Arc::new(FileTrustedPhoneStore::new(&store_path)),
        issuer,
        || FakeVideoDecoder::new(RecordingDecodedFrameSink::default()),
        move |event| {
            let _ = sender.send(event);
        },
    )
    .expect("worker spawns");

    let phone_io = harness.phones.recv_timeout(Duration::from_secs(4)).unwrap();
    let cert = desktop.certificate_der().to_vec();
    let hello_id = phone_id.clone();
    let (phone_done, phone_result) = mpsc::channel();
    let phone = thread::spawn(move || {
        let result = std::panic::catch_unwind(|| {
            run_reconnecting_phone(phone_io, &cert, &phone_identity, &hello_id)
        });
        let _ = phone_done.send(result);
    });

    let seen = wait_for(&events, |e| matches!(e, DesktopEvent::SessionEnded(_)));
    phone_result
        .recv_timeout(Duration::from_secs(4))
        .expect("reconnecting phone must finish within the bound")
        .expect("reconnecting phone must not panic");
    phone.join().unwrap();
    let connected = DesktopEvent::Connected {
        phone_id,
        label: Some("USB Phone".to_string()),
    };
    assert!(seen.contains(&connected), "{seen:?}");
    assert!(
        seen.iter()
            .any(|e| matches!(e, DesktopEvent::Metrics(m) if m.total_chunks > 0)),
        "{seen:?}"
    );
    assert!(worker.shutdown(), "worker must stop within its bound");
    let _ = std::fs::remove_file(&store_path);
    let _ = std::fs::remove_file(store_path.with_extension("lock"));
}

fn worker_config() -> DesktopWorkerConfig {
    DesktopWorkerConfig {
        poll_interval: Duration::from_millis(20),
        handshake_timeout: Duration::from_millis(2000),
        idle_read_timeout: Duration::from_millis(20),
        session: SessionRuntimeConfig {
            poll_slice: Duration::from_millis(30),
            frame_budget: Duration::from_millis(2000),
            keepalive_interval: Duration::from_millis(150),
            dead_threshold: Duration::from_millis(600),
        },
        metrics_window: Duration::from_secs(5),
        metrics_interval: Duration::from_millis(50),
        ..DesktopWorkerConfig::default()
    }
}

/// TLS client, HELLO/ACCEPT, a few video chunks, then close_notify.
fn run_reconnecting_phone(
    io: CrossedTransferBulkIo,
    desktop_cert: &[u8],
    identity: &DesktopTlsIdentity,
    device_id: &str,
) {
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(CertificateDer::from(desktop_cert.to_vec()))
        .unwrap();
    let key = PrivatePkcs8KeyDer::from(identity.private_key_pkcs8_der().to_vec());
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_client_auth_cert(
            vec![CertificateDer::from(identity.certificate_der().to_vec())],
            PrivateKeyDer::Pkcs8(key),
        )
        .unwrap();
    let server = ServerName::try_from("localhost").unwrap();
    let mut client = ClientConnection::new(Arc::new(config), server).unwrap();
    let mut stream = phone_usb_stream(io);
    while client.is_handshaking() {
        client.complete_io(&mut stream).unwrap();
    }
    let mut phone = StreamOwned::new(client, stream);
    let hello = SessionFramePayload::HandshakeHello {
        device_id: device_id.to_string(),
        app_name: "ChinchillaCam".to_string(),
        capabilities: vec!["video".to_string()],
    };
    let payloads = [
        hello,
        SessionFramePayload::video_chunk_v2_codec_config(0, 0, vec![0x00, 0x00, 0x01]),
        SessionFramePayload::video_chunk_v2_key(1, 10_000, vec![0x65, 0x88, 0x84]),
        SessionFramePayload::video_chunk_v2_delta(2, 20_000, vec![0x41, 0x9a]),
        SessionFramePayload::Keepalive,
    ];
    for (sequence, payload) in (1..).zip(payloads) {
        write_session_frame(
            &mut phone,
            &SessionFrame::new(sequence, "usb-link", payload),
        )
        .unwrap();
        if sequence == 1 {
            let accept = read_session_frame(&mut phone, Instant::now() + PHONE_READ_TIMEOUT);
            assert!(matches!(
                accept.unwrap().payload(),
                SessionFramePayload::HandshakeAccept { .. }
            ));
        }
        thread::sleep(Duration::from_millis(30));
    }
    phone.conn.send_close_notify();
    phone.conn.complete_io(&mut phone.sock).unwrap();
}

fn wait_for(
    events: &mpsc::Receiver<DesktopEvent>,
    done: impl Fn(&DesktopEvent) -> bool,
) -> Vec<DesktopEvent> {
    let deadline = Instant::now() + Duration::from_secs(8);
    let mut seen = Vec::new();
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        match events.recv_timeout(left) {
            Ok(event) => {
                let stop = done(&event);
                seen.push(event);
                if stop {
                    return seen;
                }
            }
            Err(error) => panic!("expected event not seen ({error:?}); saw {seen:?}"),
        }
    }
}

fn temp_store_path() -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!(
        "chinchillacam-usb-phone-link-{}-{nanos}.txt",
        std::process::id()
    ))
}
