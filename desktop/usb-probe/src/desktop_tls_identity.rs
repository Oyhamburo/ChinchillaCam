use std::{fmt, fs, io::Write, path::PathBuf};

use rcgen::{
    Certificate, CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopTlsIdentityError {
    GenerationFailed(String),
    Io(String),
    InsecureStorage,
    CorruptKeyFile,
    KeyFileTooLarge,
    PartialState,
    UnsupportedPlatform,
}

impl fmt::Display for DesktopTlsIdentityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::GenerationFailed(error) => {
                write!(formatter, "desktop TLS identity generation failed: {error}")
            }
            Self::Io(error) => write!(formatter, "desktop TLS identity storage failed: {error}"),
            Self::InsecureStorage => {
                write!(formatter, "desktop TLS identity storage is not owner-only")
            }
            Self::CorruptKeyFile => write!(formatter, "desktop TLS identity key file is corrupt"),
            Self::KeyFileTooLarge => {
                write!(formatter, "desktop TLS identity key file is too large")
            }
            Self::PartialState => {
                write!(formatter, "desktop TLS identity storage has partial state")
            }
            Self::UnsupportedPlatform => write!(
                formatter,
                "desktop TLS identity storage is unsupported on this platform"
            ),
        }
    }
}

impl std::error::Error for DesktopTlsIdentityError {}

type Result<T> = std::result::Result<T, DesktopTlsIdentityError>;

#[derive(Clone, PartialEq, Eq)]
pub struct DesktopTlsIdentity {
    certificate_der: Vec<u8>,
    spki_der_p256: Vec<u8>,
    private_key_pkcs8_der: Vec<u8>,
}

impl DesktopTlsIdentity {
    pub fn generate_ephemeral(desktop_name: &str) -> Result<Self> {
        let key_pair = KeyPair::generate(&PKCS_ECDSA_P256_SHA256)
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        Self::from_key_pair(desktop_name, key_pair)
    }

    fn from_private_key_der(desktop_name: &str, private_key_der: &[u8]) -> Result<Self> {
        let key_pair = KeyPair::from_der_and_sign_algo(private_key_der, &PKCS_ECDSA_P256_SHA256)
            .map_err(|_| DesktopTlsIdentityError::CorruptKeyFile)?;
        Self::from_key_pair(desktop_name, key_pair)
    }

    fn from_key_pair(desktop_name: &str, key_pair: KeyPair) -> Result<Self> {
        validate_desktop_name(desktop_name)?;
        let mut distinguished_name = DistinguishedName::new();
        distinguished_name.push(DnType::CommonName, desktop_name);
        let mut params = CertificateParams::default();
        params.distinguished_name = distinguished_name;
        params.key_pair = Some(key_pair);
        let certificate = Certificate::from_params(params)
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        let certificate_der = certificate
            .serialize_der()
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        let spki_der_p256 = certificate.get_key_pair().public_key_der();
        let private_key_pkcs8_der = certificate.serialize_private_key_der();
        Ok(Self {
            certificate_der,
            spki_der_p256,
            private_key_pkcs8_der,
        })
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.certificate_der
    }
    pub fn spki_der_p256(&self) -> &[u8] {
        &self.spki_der_p256
    }
    pub fn qr_trust_material(&self) -> &[u8] {
        self.spki_der_p256()
    }

    /// Sensitive PKCS#8 private key material for the TLS backend only.
    /// Do not log or expose this value outside the local TLS identity boundary.
    pub fn private_key_pkcs8_der(&self) -> &[u8] {
        &self.private_key_pkcs8_der
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesktopTlsIdentityStore {
    private_dir: PathBuf,
}

impl DesktopTlsIdentityStore {
    pub fn new(private_dir: PathBuf) -> Self {
        Self { private_dir }
    }

    #[cfg(windows)]
    pub fn load_or_create(&self, _desktop_name: &str) -> Result<DesktopTlsIdentity> {
        Err(DesktopTlsIdentityError::UnsupportedPlatform)
    }

    #[cfg(unix)]
    pub fn load_or_create(&self, desktop_name: &str) -> Result<DesktopTlsIdentity> {
        use std::os::unix::{fs::OpenOptionsExt, fs::PermissionsExt};
        validate_desktop_name(desktop_name)?;
        prepare_private_dir(&self.private_dir)?;
        let key_path = self.private_dir.join("desktop-p256.pkcs8.der");
        let tmp_path = self.private_dir.join("desktop-p256.pkcs8.der.tmp");
        if fs::symlink_metadata(&tmp_path).is_ok() {
            return Err(DesktopTlsIdentityError::PartialState);
        }
        match fs::symlink_metadata(&key_path) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink()
                    || !metadata.is_file()
                    || metadata.permissions().mode() & 0o777 != 0o600
                {
                    return Err(DesktopTlsIdentityError::InsecureStorage);
                }
                if metadata.len() > 8 * 1024 {
                    return Err(DesktopTlsIdentityError::KeyFileTooLarge);
                }
                return DesktopTlsIdentity::from_private_key_der(
                    desktop_name,
                    &fs::read(key_path).map_err(io_error)?,
                );
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(io_error(error)),
        }
        let identity = DesktopTlsIdentity::generate_ephemeral(desktop_name)?;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp_path)
            .map_err(io_error)?;
        file.write_all(identity.private_key_pkcs8_der())
            .map_err(io_error)?;
        file.sync_all().map_err(io_error)?;
        drop(file);
        if let Err(error) = fs::hard_link(&tmp_path, &key_path) {
            let _ = fs::remove_file(&tmp_path);
            return Err(if error.kind() == std::io::ErrorKind::AlreadyExists {
                DesktopTlsIdentityError::PartialState
            } else {
                io_error(error)
            });
        }
        fs::remove_file(&tmp_path).map_err(io_error)?;
        fs::File::open(&self.private_dir)
            .and_then(|dir| dir.sync_all())
            .map_err(io_error)?;
        Ok(identity)
    }
}

#[cfg(unix)]
fn prepare_private_dir(path: &PathBuf) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink()
                || !metadata.is_dir()
                || metadata.permissions().mode() & 0o777 != 0o700
            {
                return Err(DesktopTlsIdentityError::InsecureStorage);
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir_all(path).map_err(io_error)?;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(io_error)?;
        }
        Err(error) => return Err(io_error(error)),
    }
    Ok(())
}

fn validate_desktop_name(desktop_name: &str) -> Result<()> {
    if desktop_name.trim().is_empty() || desktop_name.len() > 64 {
        return Err(DesktopTlsIdentityError::GenerationFailed(
            "desktop name must be non-empty and at most 64 bytes".to_string(),
        ));
    }
    Ok(())
}

fn io_error(error: std::io::Error) -> DesktopTlsIdentityError {
    DesktopTlsIdentityError::Io(error.to_string())
}
