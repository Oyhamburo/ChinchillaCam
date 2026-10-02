//! Shared test-only helpers. Files under `tests/common/` are compiled as a submodule of the
//! test crate that declares `mod common;`, not as their own test binary.

pub mod duplex;
pub mod usb_transfer_pipe;
