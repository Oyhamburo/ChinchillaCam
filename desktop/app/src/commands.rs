use crate::view_model::{AppAction, AppState};
use usb_probe::DesktopCommand;

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
