use std::{
    fs::{self, OpenOptions},
    io,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};

const MAGIC: &str = "CHINCHILLACAM_TRUSTED_PHONES_V1";
const MAX_PHONE_ID_LEN: usize = 128;
const MAX_LABEL_LEN: usize = 256;
const MAX_PUBLIC_KEY_LEN: usize = 4096;
const LOCK_TIMEOUT: Duration = Duration::from_secs(5);
const LOCK_RETRY: Duration = Duration::from_millis(5);
static TEMP_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrustedPhoneIdentity {
    phone_id: String,
    label: String,
    public_key: Vec<u8>,
}

impl TrustedPhoneIdentity {
    pub fn new(
        phone_id: impl Into<String>,
        label: impl Into<String>,
        public_key: Vec<u8>,
    ) -> Result<Self, TrustedPhoneStoreError> {
        let identity = Self {
            phone_id: phone_id.into(),
            label: label.into(),
            public_key,
        };
        identity.validate()?;
        Ok(identity)
    }

    fn validate(&self) -> Result<(), TrustedPhoneStoreError> {
        validate_text("phone id", &self.phone_id, MAX_PHONE_ID_LEN)?;
        validate_text("label", &self.label, MAX_LABEL_LEN)?;
        if self.public_key.is_empty() || self.public_key.len() > MAX_PUBLIC_KEY_LEN {
            return Err(TrustedPhoneStoreError::InvalidIdentity(
                "public key material must be 1..=4096 bytes".to_string(),
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrustedPhoneStoreError {
    InvalidIdentity(String),
    CorruptStore(String),
    Io(String),
}

impl From<io::Error> for TrustedPhoneStoreError {
    fn from(value: io::Error) -> Self {
        Self::Io(value.to_string())
    }
}

pub trait TrustedPhoneStoreWriteCoordinator: Send + Sync {
    fn after_records_loaded(&self, path: &Path) -> Result<(), TrustedPhoneStoreError>;
}

#[derive(Debug)]
struct NoopWriteCoordinator;

impl TrustedPhoneStoreWriteCoordinator for NoopWriteCoordinator {
    fn after_records_loaded(&self, _path: &Path) -> Result<(), TrustedPhoneStoreError> {
        Ok(())
    }
}

#[derive(Clone)]
pub struct FileTrustedPhoneStore {
    path: PathBuf,
    write_coordinator: Arc<dyn TrustedPhoneStoreWriteCoordinator>,
}

impl FileTrustedPhoneStore {
    pub fn new(path: impl AsRef<Path>) -> Self {
        Self::with_write_coordinator(path, Arc::new(NoopWriteCoordinator))
    }

    pub fn with_write_coordinator(
        path: impl AsRef<Path>,
        write_coordinator: Arc<dyn TrustedPhoneStoreWriteCoordinator>,
    ) -> Self {
        Self {
            path: path.as_ref().to_path_buf(),
            write_coordinator,
        }
    }

    pub fn trust(&self, identity: TrustedPhoneIdentity) -> Result<(), TrustedPhoneStoreError> {
        identity.validate()?;
        let _lock = self.acquire_write_lock()?;
        let mut records = self.load_records()?;
        self.write_coordinator.after_records_loaded(&self.path)?;
        records.retain(|record| record.identity.phone_id != identity.phone_id);
        records.push(TrustedPhoneRecord {
            identity,
            revoked: false,
        });
        self.save_records(&records)
    }

    pub fn trusted_identity(
        &self,
        phone_id: &str,
    ) -> Result<Option<TrustedPhoneIdentity>, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        Ok(self
            .load_records()?
            .into_iter()
            .find(|record| record.identity.phone_id == phone_id && !record.revoked)
            .map(|record| record.identity))
    }

    pub fn revoke(&self, phone_id: &str) -> Result<bool, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        let _lock = self.acquire_write_lock()?;
        let mut records = self.load_records()?;
        self.write_coordinator.after_records_loaded(&self.path)?;
        let mut changed = false;
        for record in &mut records {
            if record.identity.phone_id == phone_id {
                changed |= !record.revoked;
                record.revoked = true;
            }
        }
        if changed {
            self.save_records(&records)?;
        }
        Ok(changed)
    }

    pub fn is_revoked(&self, phone_id: &str) -> Result<bool, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        Ok(self
            .load_records()?
            .into_iter()
            .any(|record| record.identity.phone_id == phone_id && record.revoked))
    }

    fn load_records(&self) -> Result<Vec<TrustedPhoneRecord>, TrustedPhoneStoreError> {
        let text = match fs::read_to_string(&self.path) {
            Ok(text) => text,
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(err) => return Err(err.into()),
        };
        let mut lines = text.lines();
        if lines.next() != Some(MAGIC) {
            return Err(TrustedPhoneStoreError::CorruptStore(
                "missing trusted-phone store header".to_string(),
            ));
        }
        lines.map(parse_record).collect()
    }

    fn save_records(&self, records: &[TrustedPhoneRecord]) -> Result<(), TrustedPhoneStoreError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent)?;
        }
        let mut text = String::from(MAGIC);
        text.push('\n');
        for record in records {
            text.push_str(&record.encode());
            text.push('\n');
        }
        let tmp_path = self.unique_temp_path();
        fs::write(&tmp_path, text)?;
        fs::rename(tmp_path, &self.path)?;
        Ok(())
    }

    fn acquire_write_lock(&self) -> Result<StorePathLock, TrustedPhoneStoreError> {
        let lock_path = self.path.with_extension("lock");
        let started = Instant::now();
        loop {
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&lock_path)
            {
                Ok(_) => return Ok(StorePathLock { path: lock_path }),
                Err(err) if err.kind() == io::ErrorKind::AlreadyExists => {
                    if started.elapsed() >= LOCK_TIMEOUT {
                        return Err(TrustedPhoneStoreError::Io(format!(
                            "timed out acquiring trusted-phone store lock {}",
                            lock_path.display()
                        )));
                    }
                    thread::sleep(LOCK_RETRY);
                }
                Err(err) => return Err(err.into()),
            }
        }
    }

    fn unique_temp_path(&self) -> PathBuf {
        let sequence = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let file_name = self
            .path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("trusted-phone-store");
        self.path.with_file_name(format!(
            ".{file_name}.{}.{}.tmp",
            std::process::id(),
            sequence
        ))
    }
}

#[derive(Debug)]
struct StorePathLock {
    path: PathBuf,
}

impl Drop for StorePathLock {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct TrustedPhoneRecord {
    identity: TrustedPhoneIdentity,
    revoked: bool,
}

impl TrustedPhoneRecord {
    fn encode(&self) -> String {
        format!(
            "{}\t{}\t{}\t{}",
            if self.revoked { "revoked" } else { "trusted" },
            self.identity.phone_id,
            self.identity.label,
            encode_hex(&self.identity.public_key)
        )
    }
}

fn parse_record(line: &str) -> Result<TrustedPhoneRecord, TrustedPhoneStoreError> {
    let fields: Vec<&str> = line.split('\t').collect();
    if fields.len() != 4 {
        return Err(TrustedPhoneStoreError::CorruptStore(
            "trusted-phone record must have four fields".to_string(),
        ));
    }
    let revoked = match fields[0] {
        "trusted" => false,
        "revoked" => true,
        _ => {
            return Err(TrustedPhoneStoreError::CorruptStore(
                "trusted-phone record has unknown state".to_string(),
            ))
        }
    };
    Ok(TrustedPhoneRecord {
        identity: TrustedPhoneIdentity::new(fields[1], fields[2], decode_hex(fields[3])?)?,
        revoked,
    })
}

fn validate_lookup_id(phone_id: &str) -> Result<(), TrustedPhoneStoreError> {
    validate_text("phone id", phone_id, MAX_PHONE_ID_LEN)
}

fn validate_text(name: &str, value: &str, max_len: usize) -> Result<(), TrustedPhoneStoreError> {
    if value.trim().is_empty() || value.len() > max_len {
        return Err(TrustedPhoneStoreError::InvalidIdentity(format!(
            "{name} must be non-empty and at most {max_len} bytes"
        )));
    }
    if value
        .bytes()
        .any(|byte| matches!(byte, b'\t' | b'\n' | b'\r'))
    {
        return Err(TrustedPhoneStoreError::InvalidIdentity(format!(
            "{name} must not contain control separators"
        )));
    }
    Ok(())
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

fn decode_hex(text: &str) -> Result<Vec<u8>, TrustedPhoneStoreError> {
    if text.len() % 2 != 0 {
        return Err(TrustedPhoneStoreError::CorruptStore(
            "hex public key length must be even".to_string(),
        ));
    }
    let mut bytes = Vec::with_capacity(text.len() / 2);
    for pair in text.as_bytes().chunks_exact(2) {
        bytes.push((hex_nibble(pair[0])? << 4) | hex_nibble(pair[1])?);
    }
    Ok(bytes)
}

fn hex_nibble(byte: u8) -> Result<u8, TrustedPhoneStoreError> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => Err(TrustedPhoneStoreError::CorruptStore(
            "hex public key contains non-hex characters".to_string(),
        )),
    }
}
