use std::collections::BTreeMap;
use usb_probe::quality_control::{
    encode_quality_state, encode_set_quality, parse_quality_state, parse_set_quality,
    subscribe_command, CameraOption, CameraSelection, FpsOption, QualityMode, QualityState,
    ResolutionOption, SetQuality, QUALITY_STATE_COMMAND, SET_QUALITY_COMMAND,
};

fn state() -> QualityState {
    QualityState {
        req: Some(7),
        error: None,
        mode: QualityMode::Manual,
        selected_camera: CameraSelection::Id("rear".into()),
        cameras: vec![CameraOption {
            id: "rear".into(),
            label: "Rear".into(),
        }],
        resolutions: vec![ResolutionOption {
            width: 1280,
            height: 720,
            enabled: true,
            reason: None,
        }],
        frame_rates: vec![FpsOption {
            fps: 30,
            enabled: false,
            reason: Some("busy".into()),
        }],
        applied_width: 1280,
        applied_height: 720,
        applied_fps: 30,
        summary: "720p at 30 fps".into(),
    }
}

#[test]
fn quality_state_round_trips_with_indexed_options() {
    let expected = state();
    let (command, arguments) = encode_quality_state(&expected);
    assert_eq!(command, QUALITY_STATE_COMMAND);
    assert_eq!(parse_quality_state(&command, &arguments), Ok(expected));
}

// Literal §4 frame-7 map: Android can mirror each key/value without a Rust encoder.
#[test]
fn literal_protocol_fixture() {
    let map = BTreeMap::from(
        [
            ("v", "1"),
            ("req", "7"),
            ("mode", "manual"),
            ("camera.selected", "rear"),
            ("camera.count", "1"),
            ("camera.0.id", "rear"),
            ("camera.0.label", "Rear"),
            ("res.count", "1"),
            ("res.0", "1280x720"),
            ("res.0.enabled", "1"),
            ("fps.count", "1"),
            ("fps.0", "30"),
            ("fps.0.enabled", "0"),
            ("fps.0.reason", "busy"),
            ("applied.res", "1280x720"),
            ("applied.fps", "30"),
            ("summary", "720p at 30 fps"),
        ]
        .map(|(k, v)| (k.to_string(), v.to_string())),
    );
    assert_eq!(parse_quality_state("quality_state", &map), Ok(state()));
    assert_eq!(encode_quality_state(&state()).1, map);
}

// The Android quality_state encoder emits these exact text entries, including localized labels.
#[test]
fn android_quality_state_fixture_parses() {
    let map = BTreeMap::from(
        [
            ("v", "1"),
            ("req", "7"),
            ("mode", "manual"),
            ("camera.selected", "0"),
            ("camera.count", "2"),
            ("camera.0.id", "0"),
            ("camera.0.label", "Trasera 1"),
            ("camera.1.id", "1"),
            ("camera.1.label", "Frontal 1"),
            ("res.count", "4"),
            ("res.0", "1920x1080"),
            ("res.0.enabled", "1"),
            ("res.1", "1280x720"),
            ("res.1.enabled", "1"),
            ("res.2", "960x540"),
            ("res.2.enabled", "0"),
            ("res.2.reason", "La cámara no admite esta resolución."),
            ("res.3", "640x480"),
            ("res.3.enabled", "1"),
            ("fps.count", "3"),
            ("fps.0", "30"),
            ("fps.0.enabled", "1"),
            ("fps.1", "24"),
            ("fps.1.enabled", "1"),
            ("fps.2", "15"),
            ("fps.2.enabled", "0"),
            ("fps.2.reason", "La cámara no admite estos FPS."),
            ("applied.res", "1280x720"),
            ("applied.fps", "30"),
            ("summary", "Calidad: Manual (1280 × 720, 30 FPS)"),
        ]
        .map(|(key, value)| (key.to_string(), value.to_string())),
    );
    let parsed = parse_quality_state("quality_state", &map).unwrap();
    assert_eq!(parsed.req, Some(7));
    assert_eq!(parsed.mode, QualityMode::Manual);
    assert_eq!(parsed.selected_camera, CameraSelection::Id("0".into()));
    assert_eq!(
        parsed.cameras,
        vec![
            CameraOption {
                id: "0".into(),
                label: "Trasera 1".into()
            },
            CameraOption {
                id: "1".into(),
                label: "Frontal 1".into()
            },
        ]
    );
    assert_eq!(
        parsed.resolutions,
        [
            (1920, 1080, true, None),
            (1280, 720, true, None),
            (
                960,
                540,
                false,
                Some("La cámara no admite esta resolución.")
            ),
            (640, 480, true, None)
        ]
        .into_iter()
        .map(|(width, height, enabled, reason)| ResolutionOption {
            width,
            height,
            enabled,
            reason: reason.map(str::to_string),
        })
        .collect::<Vec<_>>()
    );
    assert_eq!(
        parsed.frame_rates,
        [
            (30, true, None),
            (24, true, None),
            (15, false, Some("La cámara no admite estos FPS."))
        ]
        .into_iter()
        .map(|(fps, enabled, reason)| FpsOption {
            fps,
            enabled,
            reason: reason.map(str::to_string),
        })
        .collect::<Vec<_>>()
    );
    assert_eq!(
        (
            parsed.applied_width,
            parsed.applied_height,
            parsed.applied_fps
        ),
        (1280, 720, 30)
    );
    assert_eq!(parsed.summary, "Calidad: Manual (1280 × 720, 30 FPS)");
    assert_eq!(parsed.error, None);
}

#[test]
fn parser_rejects_limits_and_accepts_unknown_keys() {
    let (_, mut map) = encode_quality_state(&state());
    map.insert("camera.count".into(), "17".into());
    assert!(parse_quality_state(QUALITY_STATE_COMMAND, &map).is_err());
    map.insert("camera.count".into(), "1".into());
    for key in ["res.count", "fps.count"] {
        map.insert(key.into(), "17".into());
        assert!(parse_quality_state(QUALITY_STATE_COMMAND, &map).is_err());
        map.insert(key.into(), "1".into());
    }
    map.insert("extra".into(), "x".repeat(257));
    assert!(parse_quality_state(QUALITY_STATE_COMMAND, &map).is_err());
    map.insert("extra".into(), "ignored".into());
    assert_eq!(
        parse_quality_state(QUALITY_STATE_COMMAND, &map),
        Ok(state())
    );
    for bad in ["007", "-1", "0", "+1"] {
        map.insert("applied.fps".into(), bad.into());
        assert!(
            parse_quality_state(QUALITY_STATE_COMMAND, &map).is_err(),
            "{bad}"
        );
    }
    map.insert("applied.fps".into(), "30".into());
    map.remove("fps.0.enabled");
    assert!(parse_quality_state(QUALITY_STATE_COMMAND, &map).is_err());
}

#[test]
fn set_quality_round_trip_and_missing_manual_fps() {
    let set = SetQuality {
        req: 8,
        camera: Some(CameraSelection::Auto),
        mode: QualityMode::Manual,
        manual: Some((1280, 720, 30)),
    };
    let (command, mut map) = encode_set_quality(&set);
    assert_eq!(command, SET_QUALITY_COMMAND);
    assert_eq!(parse_set_quality(&command, &map), Ok(set));
    map.remove("fps");
    assert!(parse_set_quality(&command, &map).is_err());
    assert_eq!(
        subscribe_command().1.get("v").map(String::as_str),
        Some("1")
    );
    let auto = SetQuality {
        req: 9,
        camera: None,
        mode: QualityMode::Auto,
        manual: None,
    };
    let (command, map) = encode_set_quality(&auto);
    assert_eq!(parse_set_quality(&command, &map), Ok(auto));
}
