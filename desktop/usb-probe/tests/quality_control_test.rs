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
