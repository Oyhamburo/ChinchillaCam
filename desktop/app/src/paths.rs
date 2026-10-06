use std::{fs, io, path::PathBuf, process::Command};
use usb_probe::{phone_id_for_spki, DesktopTlsIdentity};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppPaths {
    pub root: PathBuf,
    pub identity_dir: PathBuf,
    pub trusted_phones: PathBuf,
}

impl AppPaths {
    pub fn under(root: PathBuf) -> Self {
        Self {
            identity_dir: root.join("identity"),
            trusted_phones: root.join("trusted-phones.txt"),
            root,
        }
    }

    pub fn macos_default() -> io::Result<Self> {
        let home = std::env::var_os("HOME")
            .filter(|home| !home.is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "HOME is not set"))?;
        Ok(Self::under(
            PathBuf::from(home).join("Library/Application Support/ChinchillaCam"),
        ))
    }

    pub fn prepare(&self) -> io::Result<()> {
        fs::create_dir_all(&self.root)?;
        private_dir(&self.root)?;
        fs::create_dir_all(&self.identity_dir)?;
        private_dir(&self.identity_dir)
    }
}

#[cfg(unix)]
fn private_dir(path: &std::path::Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn private_dir(_path: &std::path::Path) -> io::Result<()> {
    Ok(())
}

pub fn desktop_display_name() -> String {
    let name = Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok());
    sanitize_desktop_name(name.as_deref().unwrap_or(""))
}

pub fn sanitize_desktop_name(raw: &str) -> String {
    let clean: String = raw
        .chars()
        .filter(|character| !character.is_control())
        .collect();
    let clean = clean.trim();
    let mut bounded = String::new();
    for character in clean.chars() {
        if bounded.len() + character.len_utf8() > 64 {
            break;
        }
        bounded.push(character);
    }
    let bounded = bounded.trim();
    if bounded.is_empty() {
        "Mi computadora".to_string()
    } else {
        bounded.to_string()
    }
}

pub fn desktop_id_for(identity: &DesktopTlsIdentity) -> String {
    phone_id_for_spki(identity.spki_der_p256())
}
