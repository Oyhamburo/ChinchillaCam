use chinchillacam_app::{
    commands::{command_for, forget_command},
    view_model::{AppAction, AppState},
};
use usb_probe::DesktopCommand;

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
