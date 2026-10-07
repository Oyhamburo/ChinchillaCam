use chinchillacam_app::{
    commands::{
        choose_automatic, choose_camera, choose_fps, choose_resolution, command_for, forget_command,
    },
    view_model::{AppAction, AppState},
};
use usb_probe::{
    quality_control::{CameraSelection, FpsOption, QualityMode, QualityState, ResolutionOption},
    DesktopCommand,
};

fn quality() -> QualityState {
    QualityState {
        req: None,
        error: None,
        mode: QualityMode::Manual,
        selected_camera: CameraSelection::Id("0".into()),
        cameras: vec![],
        resolutions: vec![
            ResolutionOption {
                width: 1920,
                height: 1080,
                enabled: true,
                reason: None,
            },
            ResolutionOption {
                width: 1280,
                height: 720,
                enabled: true,
                reason: None,
            },
            ResolutionOption {
                width: 640,
                height: 480,
                enabled: false,
                reason: None,
            },
        ],
        frame_rates: vec![
            FpsOption {
                fps: 30,
                enabled: true,
                reason: None,
            },
            FpsOption {
                fps: 24,
                enabled: true,
                reason: None,
            },
            FpsOption {
                fps: 15,
                enabled: false,
                reason: None,
            },
        ],
        applied_width: 1280,
        applied_height: 720,
        applied_fps: 30,
        summary: String::new(),
    }
}

#[test]
fn quality_selection_keeps_applied_values_when_enabled_and_falls_back_when_not() {
    let mut state = quality();
    assert_eq!(
        choose_resolution(&state, 1920, 1080),
        DesktopCommand::SetQuality {
            camera: None,
            mode: QualityMode::Manual,
            manual: Some((1920, 1080, 30))
        }
    );
    state.applied_fps = 15;
    assert_eq!(
        choose_resolution(&state, 1920, 1080),
        DesktopCommand::SetQuality {
            camera: None,
            mode: QualityMode::Manual,
            manual: Some((1920, 1080, 30))
        }
    );
    state.applied_width = 640;
    state.applied_height = 480;
    assert_eq!(
        choose_fps(&state, 24),
        DesktopCommand::SetQuality {
            camera: None,
            mode: QualityMode::Manual,
            manual: Some((1920, 1080, 24))
        }
    );
    state.applied_width = 1280;
    state.applied_height = 720;
    assert_eq!(
        choose_fps(&state, 24),
        DesktopCommand::SetQuality {
            camera: None,
            mode: QualityMode::Manual,
            manual: Some((1280, 720, 24))
        }
    );
}

#[test]
fn camera_preserves_mode_and_auto_quality_preserves_camera() {
    let mut state = quality();
    assert_eq!(
        choose_camera(&state, CameraSelection::Id("1".into())),
        DesktopCommand::SetQuality {
            camera: Some(CameraSelection::Id("1".into())),
            mode: QualityMode::Manual,
            manual: Some((1280, 720, 30))
        }
    );
    assert_eq!(
        choose_camera(&state, CameraSelection::Auto),
        DesktopCommand::SetQuality {
            camera: Some(CameraSelection::Auto),
            mode: QualityMode::Manual,
            manual: Some((1280, 720, 30))
        }
    );
    assert_eq!(
        choose_automatic(&state),
        DesktopCommand::SetQuality {
            camera: None,
            mode: QualityMode::Auto,
            manual: None
        }
    );
    state.mode = QualityMode::Auto;
    assert_eq!(
        choose_camera(&state, CameraSelection::Id("1".into())),
        DesktopCommand::SetQuality {
            camera: Some(CameraSelection::Id("1".into())),
            mode: QualityMode::Auto,
            manual: None
        }
    );
}

#[test]
fn every_action_maps_to_its_command() {
    let state = AppState::default();
    for (action, expected) in [
        (AppAction::StartPairing, DesktopCommand::StartPairing),
        (AppAction::CancelPairing, DesktopCommand::CancelPairing),
        (
            AppAction::ConfirmPairing,
            DesktopCommand::ConfirmPairing {
                label: state.confirm_label().to_owned(),
            },
        ),
        (AppAction::RejectPairing, DesktopCommand::RejectPairing),
        (AppAction::Disconnect, DesktopCommand::Disconnect),
    ] {
        assert_eq!(command_for(action, &state), expected);
    }
    assert_eq!(
        forget_command("phone-1"),
        DesktopCommand::ForgetPhone {
            phone_id: "phone-1".into()
        }
    );
}
