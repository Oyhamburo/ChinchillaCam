use crate::view_model::{AppAction, AppState};
use usb_probe::{
    quality_control::{CameraSelection, QualityMode, QualityState},
    DesktopCommand,
};

pub fn choose_automatic(_state: &QualityState) -> DesktopCommand {
    DesktopCommand::SetQuality {
        camera: None,
        mode: QualityMode::Auto,
        manual: None,
    }
}

pub fn choose_camera(state: &QualityState, camera: CameraSelection) -> DesktopCommand {
    DesktopCommand::SetQuality {
        camera: Some(camera),
        mode: state.mode,
        manual: (state.mode == QualityMode::Manual).then_some((
            state.applied_width,
            state.applied_height,
            state.applied_fps,
        )),
    }
}

pub fn choose_resolution(state: &QualityState, width: u32, height: u32) -> DesktopCommand {
    let fps = state
        .frame_rates
        .iter()
        .find(|option| option.fps == state.applied_fps && option.enabled)
        .or_else(|| {
            state
                .frame_rates
                .iter()
                .filter(|option| option.enabled)
                .max_by_key(|option| option.fps)
        })
        .map_or(state.applied_fps, |option| option.fps);
    DesktopCommand::SetQuality {
        camera: None,
        mode: QualityMode::Manual,
        manual: Some((width, height, fps)),
    }
}

pub fn choose_fps(state: &QualityState, fps: u32) -> DesktopCommand {
    let resolution = state
        .resolutions
        .iter()
        .find(|option| {
            option.width == state.applied_width
                && option.height == state.applied_height
                && option.enabled
        })
        .or_else(|| {
            state
                .resolutions
                .iter()
                .filter(|option| option.enabled)
                .max_by_key(|option| u64::from(option.width) * u64::from(option.height))
        });
    let (width, height) = resolution
        .map_or((state.applied_width, state.applied_height), |option| {
            (option.width, option.height)
        });
    DesktopCommand::SetQuality {
        camera: None,
        mode: QualityMode::Manual,
        manual: Some((width, height, fps)),
    }
}

pub fn command_for(action: AppAction, state: &AppState) -> DesktopCommand {
    match action {
        AppAction::StartPairing => DesktopCommand::StartPairing,
        AppAction::CancelPairing => DesktopCommand::CancelPairing,
        AppAction::ConfirmPairing => DesktopCommand::ConfirmPairing {
            label: state.confirm_label().to_owned(),
        },
        AppAction::RejectPairing => DesktopCommand::RejectPairing,
        AppAction::Disconnect => DesktopCommand::Disconnect,
    }
}

pub fn forget_command(phone_id: &str) -> DesktopCommand {
    DesktopCommand::ForgetPhone {
        phone_id: phone_id.to_owned(),
    }
}
