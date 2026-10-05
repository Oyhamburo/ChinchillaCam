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
const MAX_STORE_FILE_LEN: u64 = 65_536;
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
    StoreTooLarge { length: u64, max: u64 },
    Io(String),
}

impl From<io::Error> for TrustedPhoneStoreError {
    fn from(value: io::Error) -> Self {
        Self::Io(value.to_string())
    }
}

/// A single point-in-time read of one phone's trust state, computed from one loaded
/// snapshot of the store so a concurrent `trust`/`revoke` cannot be combined into an
/// inconsistent answer by two separate reads.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PhoneTrustSnapshot {
    Trusted(Vec<u8>),
    Revoked,
    Unknown,
}

/// One phone as reported by `FileTrustedPhoneStore::list`: identity and state only, never
/// the stored public key material.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrustedPhoneSummary {
    pub phone_id: String,
    pub label: String,
    pub revoked: bool,
}

/// Outcome of `FileTrustedPhoneStore::trust_unless_revoked`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrustUnlessRevoked {
    /// `identity.phone_id` was not revoked; it is now trusted, exactly as `trust` would
    /// have left it.
    Trusted,
    /// `identity.phone_id` is currently revoked; nothing was written.
    Refused,
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

    /// Persists `identity` exactly like `trust`, unless `identity.phone_id` is currently
    /// revoked, in which case it refuses without writing anything. Calling `is_revoked`
    /// and `trust` as two separate operations each takes the store's lock on its own,
    /// leaving a window between them in which another caller's `revoke` can commit and
    /// then be silently undone by `trust`'s unconditional upsert (the time-of-check/
    /// time-of-use bug found in native review readback of `PairedPhoneCandidate::confirm`,
    /// task m4b). This checks and writes under a SINGLE lock hold and a SINGLE loaded
    /// snapshot instead, so there is no such window. `trust`, `revoke`, and `is_revoked`
    /// keep their existing behavior unchanged; `confirm` is the only caller of this method.
    pub fn trust_unless_revoked(
        &self,
        identity: TrustedPhoneIdentity,
    ) -> Result<TrustUnlessRevoked, TrustedPhoneStoreError> {
        identity.validate()?;
        let _lock = self.acquire_write_lock()?;
        let mut records = self.load_records()?;
        self.write_coordinator.after_records_loaded(&self.path)?;
        if records
            .iter()
            .any(|record| record.identity.phone_id == identity.phone_id && record.revoked)
        {
            return Ok(TrustUnlessRevoked::Refused);
        }
        records.retain(|record| record.identity.phone_id != identity.phone_id);
        records.push(TrustedPhoneRecord {
            identity,
            revoked: false,
        });
        self.save_records(&records)?;
        Ok(TrustUnlessRevoked::Trusted)
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

    /// Removes every record for `phone_id` (trusted or revoked) under the write lock and
    /// with the same atomic temp-file + rename write as `trust`/`revoke`. Afterwards the
    /// phone is unknown, so it can be paired again. Returns false if it was absent.
    pub fn forget(&self, phone_id: &str) -> Result<bool, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        let _lock = self.acquire_write_lock()?;
        let mut records = self.load_records()?;
        self.write_coordinator.after_records_loaded(&self.path)?;
        let before = records.len();
        records.retain(|record| record.identity.phone_id != phone_id);
        if records.len() == before {
            return Ok(false);
        }
        self.save_records(&records)?;
        Ok(true)
    }

    /// Lists every stored phone from one loaded snapshot, sorted by label then phone_id.
    pub fn list(&self) -> Result<Vec<TrustedPhoneSummary>, TrustedPhoneStoreError> {
        let mut phones: Vec<TrustedPhoneSummary> = self
            .load_records()?
            .into_iter()
            .map(|record| TrustedPhoneSummary {
                phone_id: record.identity.phone_id,
                label: record.identity.label,
                revoked: record.revoked,
            })
            .collect();
        phones.sort_by(|a, b| (&a.label, &a.phone_id).cmp(&(&b.label, &b.phone_id)));
        Ok(phones)
    }

    pub fn is_revoked(&self, phone_id: &str) -> Result<bool, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        Ok(self
            .load_records()?
            .into_iter()
            .any(|record| record.identity.phone_id == phone_id && record.revoked))
    }

    /// Reads trust and revocation state for `phone_id` from a single loaded snapshot.
    /// Callers that need both "is it trusted" and "is it revoked" must use this instead
    /// of calling `trusted_identity` and `is_revoked` separately: two independent reads
    /// can straddle a concurrent `trust`/`revoke` and combine into an answer that never
    /// existed at any single instant.
    ///
    /// Considers ALL records matching `phone_id`, not just the first one: `trust`/`revoke`
    /// never produce more than one record per phone_id, but a hand-edited or externally
    /// written file could. If any matching record is revoked, the phone is `Revoked`
    /// regardless of ordering -- a later revocation must never lose to an earlier
    /// duplicate (native review finding R3-snapshot-first-match). Otherwise, if the
    /// remaining (non-revoked) records disagree on the public key, the file is
    /// inconsistent and this fails closed with an error rather than picking one arbitrarily.
    pub fn phone_trust_snapshot(
        &self,
        phone_id: &str,
    ) -> Result<PhoneTrustSnapshot, TrustedPhoneStoreError> {
        validate_lookup_id(phone_id)?;
        let matching: Vec<TrustedPhoneRecord> = self
            .load_records()?
            .into_iter()
            .filter(|record| record.identity.phone_id == phone_id)
            .collect();
        let Some(first) = matching.first() else {
            return Ok(PhoneTrustSnapshot::Unknown);
        };
        if matching.iter().any(|record| record.revoked) {
            return Ok(PhoneTrustSnapshot::Revoked);
        }
        let first_key = &first.identity.public_key;
        if matching
            .iter()
            .any(|record| &record.identity.public_key != first_key)
        {
            return Err(TrustedPhoneStoreError::CorruptStore(format!(
                "trusted-phone store has multiple distinct public keys for phone_id {phone_id}"
            )));
        }
        Ok(PhoneTrustSnapshot::Trusted(first_key.clone()))
    }

    fn load_records(&self) -> Result<Vec<TrustedPhoneRecord>, TrustedPhoneStoreError> {
        let metadata = match fs::metadata(&self.path) {
            Ok(metadata) => metadata,
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(err) => return Err(err.into()),
        };
        if metadata.len() > MAX_STORE_FILE_LEN {
            return Err(TrustedPhoneStoreError::StoreTooLarge {
                length: metadata.len(),
                max: MAX_STORE_FILE_LEN,
            });
        }
        let text = fs::read_to_string(&self.path)?;
        let mut lines = text.lines();
        if lines.next() != Some(MAGIC) {
            return Err(TrustedPhoneStoreError::CorruptStore(
                "missing trusted-phone store header".to_string(),
            ));
        }
        lines.map(parse_record).collect()
    }

    fn save_records(&self, records: &[TrustedPhoneRecord]) -> Result<(), TrustedPhoneStoreError> {
        self.ensure_parent_dir()?;
        let mut text = String::from(MAGIC);
        text.push('\n');
        for record in records {
            text.push_str(&record.encode());
            text.push('\n');
        }
        let tmp_path = self.unique_temp_path();
        let tmp_guard = TempFileGuard::new(tmp_path.clone());
        fs::write(&tmp_path, text)?;
        fs::rename(&tmp_path, &self.path)?;
        tmp_guard.disarm();
        Ok(())
    }

    fn acquire_write_lock(&self) -> Result<StorePathLock, TrustedPhoneStoreError> {
        self.ensure_parent_dir()?;
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

    fn ensure_parent_dir(&self) -> Result<(), TrustedPhoneStoreError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent)?;
        }
        Ok(())
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

/// Removes its temp file on drop unless `disarm`ed, so a failed or interrupted
/// `save_records` (a partial write, or an error before the final rename) does not leave
/// a stray `.tmp` file behind (native review suggestion R3-temp-file-leak-on-panic).
struct TempFileGuard {
    path: PathBuf,
    disarmed: bool,
}

impl TempFileGuard {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            disarmed: false,
        }
    }

    fn disarm(mut self) {
        self.disarmed = true;
    }
}

impl Drop for TempFileGuard {
    fn drop(&mut self) {
        if !self.disarmed {
            let _ = fs::remove_file(&self.path);
        }
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
    if !text.len().is_multiple_of(2) {
        return Err(TrustedPhoneStoreError::CorruptStore(
            "hex public key length must be even".to_string(),
        ));
    }
    let mut bytes = Vec::with_capacity(text.len() / 2);
    for pair in text.as_bytes().as_chunks::<2>().0 {
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

/// Direct unit tests of `TempFileGuard`, a private type reachable only from inside this
/// module: cheaper and more deterministic than forcing a real `save_records` failure
/// between its `write` and `rename` (there is no test seam between those two calls, and
/// simulating an OS-level rename failure would be either non-deterministic or
/// platform-specific). Addresses native review suggestion R3-temp-guard-cleanup-untested.
#[cfg(test)]
mod tests {
    use super::TempFileGuard;
    use std::{
        fs,
        path::PathBuf,
        time::{SystemTime, UNIX_EPOCH},
    };

    #[test]
    fn temp_file_guard_removes_file_on_drop_unless_disarmed() {
        let path = unique_temp_guard_path("armed");
        fs::write(&path, b"leftover").unwrap();
        assert!(path.exists());

        drop(TempFileGuard::new(path.clone()));

        assert!(
            !path.exists(),
            "expected an armed TempFileGuard to remove its temp file on drop"
        );
    }

    #[test]
    fn temp_file_guard_leaves_file_when_disarmed() {
        let path = unique_temp_guard_path("disarmed");
        fs::write(&path, b"kept").unwrap();

        let guard = TempFileGuard::new(path.clone());
        guard.disarm();

        assert!(
            path.exists(),
            "expected a disarmed TempFileGuard to leave its temp file in place"
        );
        fs::remove_file(&path).unwrap();
    }

    fn unique_temp_guard_path(name: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "chinchillacam-temp-file-guard-{name}-{}-{nanos}.tmp",
            std::process::id()
        ))
    }
}
