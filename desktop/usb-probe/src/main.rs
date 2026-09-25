use std::{env, process, time::Duration};

use usb_probe::{
    AccessoryIdentity, AoaAccessoryHandleRegistry, AoaAccessoryReenumerationPoller, BulkFrame,
    DeviceIdentifier, DryRunAoaPlanner, FrameTransferBudget, FramedUsbStream,
    HostAoaControlOptions, LiveAoaControlRunner, ReenumerationWait, RusbAoaAccessoryHandleRegistry,
    RusbClaimedBulkIo, RusbUsbDeviceRegistry,
};

fn main() {
    if let Err(error) = run(env::args().skip(1)) {
        eprintln!("error: {error}");
        process::exit(2);
    }
}

fn run(args: impl IntoIterator<Item = String>) -> Result<(), String> {
    let mut selected_device = None;
    let mut dry_run = false;
    let mut live_control = false;
    let mut live_bulk_smoke = false;
    let mut control_timeout = Duration::from_millis(250);
    let mut reenumeration_wait = Duration::from_millis(1500);
    let mut args = args.into_iter();

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--device" => {
                selected_device =
                    Some(args.next().ok_or_else(|| {
                        "--device requires an explicit VID:PID value".to_string()
                    })?);
            }
            "--dry-run" => dry_run = true,
            "--live-control" => live_control = true,
            "--live-bulk-smoke" => live_bulk_smoke = true,
            "--control-timeout-ms" => {
                let value = args
                    .next()
                    .ok_or_else(|| "--control-timeout-ms requires a value".to_string())?;
                control_timeout = Duration::from_millis(
                    value
                        .parse::<u64>()
                        .map_err(|_| "--control-timeout-ms must be an integer".to_string())?,
                );
            }
            "--reenumeration-wait-ms" => {
                let value = args
                    .next()
                    .ok_or_else(|| "--reenumeration-wait-ms requires a value".to_string())?;
                reenumeration_wait = Duration::from_millis(
                    value
                        .parse::<u64>()
                        .map_err(|_| "--reenumeration-wait-ms must be an integer".to_string())?,
                );
            }
            "--help" | "-h" => {
                print_usage();
                return Ok(());
            }
            other => return Err(format!("unknown argument {other}")),
        }
    }

    let selected_modes = [dry_run, live_control, live_bulk_smoke]
        .iter()
        .filter(|selected| **selected)
        .count();
    if selected_modes != 1 {
        return Err(
            "choose exactly one mode: --dry-run, --live-control, or --live-bulk-smoke".to_string(),
        );
    }

    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "safe host CLI boundary",
        "0.5.0",
        "https://example.invalid/chinchillacam",
        "prototype-t5a",
    );
    if dry_run {
        let plan = DryRunAoaPlanner::plan(selected_device.as_deref(), &identity)
            .map_err(|error| format!("{error:?}"))?;

        println!("selected device: {}", plan.selected_device());
        for (index, step) in plan.steps().iter().enumerate() {
            println!("{}. {step}", index + 1);
        }
        return Ok(());
    }

    let selected_device = DeviceIdentifier::parse_required(selected_device.as_deref())
        .map_err(|error| format!("{error:?}"))?;
    let options = HostAoaControlOptions::new(
        selected_device,
        control_timeout,
        ReenumerationWait::bounded(reenumeration_wait),
    );
    let result = LiveAoaControlRunner::new(RusbUsbDeviceRegistry::default())
        .start_accessory_and_poll(&identity, options, AoaAccessoryReenumerationPoller::rusb())
        .map_err(|error| format!("{error:?}"))?;

    println!("selected device: {}", result.selected_device());
    println!("AOA protocol: {}", result.protocol().value());
    println!("{}", result.reenumeration_wait_description());
    println!(
        "physically matched AOA device: {}",
        result
            .accessory_device()
            .map(|device| device.to_string())
            .unwrap_or_else(|| "not observed".to_string())
    );

    if live_control {
        println!("bulk interface claim and frame I/O remain disabled unless --live-bulk-smoke is selected");
        return Ok(());
    }

    let accessory_device = result
        .accessory_device()
        .ok_or_else(|| "AOA accessory was not observed after bounded poll".to_string())?;
    let mut accessory_registry = RusbAoaAccessoryHandleRegistry::default();
    let bound_handle = accessory_registry
        .open_bound_accessory_handle(
            accessory_device
                .required_physical_location()
                .map_err(|error| format!("{error:?}"))?,
        )
        .map_err(|error| format!("{error:?}"))?;
    let (handle, accessory_identifier, _) = bound_handle.into_parts();
    let bulk_io = RusbClaimedBulkIo::claim_accessory(handle, accessory_identifier)
        .map_err(|error| format!("{error:?}"))?;
    let claim = bulk_io.claim().clone();
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 64, 8)
        .map_err(|error| format!("{error:?}"))?;
    let mut stream = FramedUsbStream::new(bulk_io, budget);
    let probe_frame = BulkFrame::new(1, b"chinchillacam-usb-probe".to_vec())
        .map_err(|error| format!("{error:?}"))?;
    stream
        .write_frame(&probe_frame)
        .map_err(|error| format!("{error:?}"))?;
    let response = stream.read_frame().map_err(|error| format!("{error:?}"))?;

    println!(
        "bulk smoke frame attempted on interface {} endpoints {:02x}/{:02x}; response stream {} payload {} bytes; hardware compatibility still requires documented smoke evidence",
        claim.interface_number(),
        claim.endpoints().in_endpoint(),
        claim.endpoints().out_endpoint(),
        response.stream_id(),
        response.payload().len()
    );

    Ok(())
}

fn print_usage() {
    println!("usb-probe --dry-run --device VID:PID");
    println!("usb-probe --live-control --device VID:PID [--control-timeout-ms N] [--reenumeration-wait-ms N]");
    println!("usb-probe --live-bulk-smoke --device VID:PID [--control-timeout-ms N] [--reenumeration-wait-ms N]");
    println!("dry-run prints the bounded AOA plan without opening hardware");
    println!("live-control sends AOA control requests only to the selected VID:PID and does not claim physical success");
    println!("live-bulk-smoke also opens the physically bound AOA device, claims validated bulk endpoints, writes one small framed probe, and reads one framed response");
}
