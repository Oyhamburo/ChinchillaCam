use std::{env, process};

use usb_probe::{AccessoryIdentity, DryRunAoaPlanner};

fn main() {
    if let Err(error) = run(env::args().skip(1)) {
        eprintln!("error: {error}");
        process::exit(2);
    }
}

fn run(args: impl IntoIterator<Item = String>) -> Result<(), String> {
    let mut selected_device = None;
    let mut dry_run = false;
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
            "--help" | "-h" => {
                print_usage();
                return Ok(());
            }
            other => return Err(format!("unknown argument {other}")),
        }
    }

    if !dry_run {
        return Err(
            "only --dry-run is implemented; hardware opening remains separately gated".to_string(),
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
    let plan = DryRunAoaPlanner::plan(selected_device.as_deref(), &identity)
        .map_err(|error| format!("{error:?}"))?;

    println!("selected device: {}", plan.selected_device());
    for (index, step) in plan.steps().iter().enumerate() {
        println!("{}. {step}", index + 1);
    }

    Ok(())
}

fn print_usage() {
    println!("usb-probe --dry-run --device VID:PID");
    println!("dry-run prints the bounded AOA plan without opening hardware");
}
