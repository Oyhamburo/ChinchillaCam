use std::{env, process, time::Duration};

use usb_probe::{
    AccessoryIdentity, AoaAccessoryReenumerationPoller, DeviceIdentifier, DryRunAoaPlanner,
    HostAoaControlOptions, LiveAoaControlRunner, ReenumerationWait, RusbUsbDeviceRegistry,
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

    if dry_run == live_control {
        return Err("choose exactly one mode: --dry-run or --live-control".to_string());
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
    println!("bulk interface claim and frame I/O remain the next bounded T5c sub-unit");

    Ok(())
}

fn print_usage() {
    println!("usb-probe --dry-run --device VID:PID");
    println!("usb-probe --live-control --device VID:PID [--control-timeout-ms N] [--reenumeration-wait-ms N]");
    println!("dry-run prints the bounded AOA plan without opening hardware");
    println!("live-control sends AOA control requests only to the selected VID:PID and does not claim physical success");
}
