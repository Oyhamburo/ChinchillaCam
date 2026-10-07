//! Text arguments carried in session frame 7; invalid messages are rejected without ending a session.
use std::collections::BTreeMap;

pub const QUALITY_CONTROL_CAPABILITY: &str = "quality-control-v1";
pub const QUALITY_SUBSCRIBE_COMMAND: &str = "quality_subscribe";
pub const QUALITY_STATE_COMMAND: &str = "quality_state";
pub const SET_QUALITY_COMMAND: &str = "set_quality";

type Arguments = BTreeMap<String, String>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QualityControlError {
    WrongCommand,
    Invalid(&'static str),
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QualityError {
    Unsupported,
    Invalid,
    Unavailable,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QualityMode {
    Auto,
    Manual,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CameraSelection {
    Auto,
    Id(String),
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CameraOption {
    pub id: String,
    pub label: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolutionOption {
    pub width: u32,
    pub height: u32,
    pub enabled: bool,
    pub reason: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FpsOption {
    pub fps: u32,
    pub enabled: bool,
    pub reason: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QualityState {
    pub req: Option<u64>,
    pub error: Option<QualityError>,
    pub mode: QualityMode,
    pub selected_camera: CameraSelection,
    pub cameras: Vec<CameraOption>,
    pub resolutions: Vec<ResolutionOption>,
    pub frame_rates: Vec<FpsOption>,
    pub applied_width: u32,
    pub applied_height: u32,
    pub applied_fps: u32,
    pub summary: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SetQuality {
    pub req: u64,
    pub camera: Option<CameraSelection>,
    pub mode: QualityMode,
    pub manual: Option<(u32, u32, u32)>,
}

fn put(map: &mut Arguments, key: impl Into<String>, value: impl ToString) {
    map.insert(key.into(), value.to_string());
}
fn mode(value: QualityMode) -> &'static str {
    match value {
        QualityMode::Auto => "auto",
        QualityMode::Manual => "manual",
    }
}
fn selection(value: &CameraSelection) -> &str {
    match value {
        CameraSelection::Auto => "auto",
        CameraSelection::Id(id) => id,
    }
}
fn resolution(width: u32, height: u32) -> String {
    format!("{width}x{height}")
}

pub fn subscribe_command() -> (String, Arguments) {
    (
        QUALITY_SUBSCRIBE_COMMAND.into(),
        Arguments::from([("v".into(), "1".into())]),
    )
}
pub fn encode_set_quality(value: &SetQuality) -> (String, Arguments) {
    let mut map = Arguments::new();
    put(&mut map, "v", 1);
    put(&mut map, "req", value.req);
    put(&mut map, "mode", mode(value.mode));
    if let Some(camera) = &value.camera {
        put(&mut map, "camera", selection(camera));
    }
    if let Some((w, h, fps)) = value.manual {
        put(&mut map, "width", w);
        put(&mut map, "height", h);
        put(&mut map, "fps", fps);
    }
    (SET_QUALITY_COMMAND.into(), map)
}
pub fn encode_quality_state(value: &QualityState) -> (String, Arguments) {
    let mut map = Arguments::new();
    put(&mut map, "v", 1);
    if let Some(req) = value.req {
        put(&mut map, "req", req);
    }
    if let Some(error) = value.error {
        put(
            &mut map,
            "error",
            match error {
                QualityError::Unsupported => "unsupported",
                QualityError::Invalid => "invalid",
                QualityError::Unavailable => "unavailable",
            },
        );
    }
    put(&mut map, "mode", mode(value.mode));
    put(
        &mut map,
        "camera.selected",
        selection(&value.selected_camera),
    );
    put(&mut map, "camera.count", value.cameras.len());
    for (i, camera) in value.cameras.iter().enumerate() {
        put(&mut map, format!("camera.{i}.id"), &camera.id);
        put(&mut map, format!("camera.{i}.label"), &camera.label);
    }
    put(&mut map, "res.count", value.resolutions.len());
    for (i, res) in value.resolutions.iter().enumerate() {
        put(
            &mut map,
            format!("res.{i}"),
            resolution(res.width, res.height),
        );
        put(
            &mut map,
            format!("res.{i}.enabled"),
            if res.enabled { "1" } else { "0" },
        );
        if let Some(reason) = &res.reason {
            put(&mut map, format!("res.{i}.reason"), reason);
        }
    }
    put(&mut map, "fps.count", value.frame_rates.len());
    for (i, fps) in value.frame_rates.iter().enumerate() {
        put(&mut map, format!("fps.{i}"), fps.fps);
        put(
            &mut map,
            format!("fps.{i}.enabled"),
            if fps.enabled { "1" } else { "0" },
        );
        if let Some(reason) = &fps.reason {
            put(&mut map, format!("fps.{i}.reason"), reason);
        }
    }
    put(
        &mut map,
        "applied.res",
        resolution(value.applied_width, value.applied_height),
    );
    put(&mut map, "applied.fps", value.applied_fps);
    put(&mut map, "summary", &value.summary);
    (QUALITY_STATE_COMMAND.into(), map)
}

fn validate(command: &str, expected: &str, map: &Arguments) -> Result<(), QualityControlError> {
    if command != expected {
        return Err(QualityControlError::WrongCommand);
    }
    if map.iter().any(|(k, v)| k.len() > 256 || v.len() > 256) {
        return Err(QualityControlError::Invalid("argument size"));
    }
    if get(map, "v")? != "1" {
        return Err(QualityControlError::Invalid("version"));
    }
    Ok(())
}
fn get<'a>(map: &'a Arguments, key: &str) -> Result<&'a str, QualityControlError> {
    map.get(key)
        .map(String::as_str)
        .ok_or(QualityControlError::Invalid("missing key"))
}
fn number<T: std::str::FromStr>(value: &str) -> Result<T, QualityControlError> {
    if value.is_empty() || value.starts_with('0') || !value.bytes().all(|b| b.is_ascii_digit()) {
        return Err(QualityControlError::Invalid("number"));
    }
    value
        .parse()
        .map_err(|_| QualityControlError::Invalid("number"))
}
fn count(map: &Arguments, key: &str) -> Result<usize, QualityControlError> {
    let raw = get(map, key)?;
    let n = if raw == "0" { 0 } else { number(raw)? };
    if n > 16 {
        return Err(QualityControlError::Invalid("option count"));
    }
    Ok(n)
}
fn size(value: &str) -> Result<(u32, u32), QualityControlError> {
    let (w, h) = value
        .split_once('x')
        .ok_or(QualityControlError::Invalid("resolution"))?;
    Ok((number(w)?, number(h)?))
}
fn parse_mode(value: &str) -> Result<QualityMode, QualityControlError> {
    match value {
        "auto" => Ok(QualityMode::Auto),
        "manual" => Ok(QualityMode::Manual),
        _ => Err(QualityControlError::Invalid("mode")),
    }
}
fn parse_selection(value: &str) -> Result<CameraSelection, QualityControlError> {
    if value == "auto" {
        Ok(CameraSelection::Auto)
    } else if value.is_empty() {
        Err(QualityControlError::Invalid("camera"))
    } else {
        Ok(CameraSelection::Id(value.into()))
    }
}
fn enabled(value: &str) -> Result<bool, QualityControlError> {
    match value {
        "1" => Ok(true),
        "0" => Ok(false),
        _ => Err(QualityControlError::Invalid("enabled")),
    }
}
pub fn parse_set_quality(
    command: &str,
    map: &Arguments,
) -> Result<SetQuality, QualityControlError> {
    validate(command, SET_QUALITY_COMMAND, map)?;
    let mode = parse_mode(get(map, "mode")?)?;
    let manual = if mode == QualityMode::Manual {
        Some((
            number(get(map, "width")?)?,
            number(get(map, "height")?)?,
            number(get(map, "fps")?)?,
        ))
    } else {
        None
    };
    Ok(SetQuality {
        req: number(get(map, "req")?)?,
        camera: map.get("camera").map(|v| parse_selection(v)).transpose()?,
        mode,
        manual,
    })
}
pub fn parse_quality_state(
    command: &str,
    map: &Arguments,
) -> Result<QualityState, QualityControlError> {
    validate(command, QUALITY_STATE_COMMAND, map)?;
    let mut cameras = Vec::new();
    for i in 0..count(map, "camera.count")? {
        cameras.push(CameraOption {
            id: get(map, &format!("camera.{i}.id"))?.into(),
            label: get(map, &format!("camera.{i}.label"))?.into(),
        });
    }
    let mut resolutions = Vec::new();
    for i in 0..count(map, "res.count")? {
        let (width, height) = size(get(map, &format!("res.{i}"))?)?;
        resolutions.push(ResolutionOption {
            width,
            height,
            enabled: enabled(get(map, &format!("res.{i}.enabled"))?)?,
            reason: map.get(&format!("res.{i}.reason")).cloned(),
        });
    }
    let mut frame_rates = Vec::new();
    for i in 0..count(map, "fps.count")? {
        frame_rates.push(FpsOption {
            fps: number(get(map, &format!("fps.{i}"))?)?,
            enabled: enabled(get(map, &format!("fps.{i}.enabled"))?)?,
            reason: map.get(&format!("fps.{i}.reason")).cloned(),
        });
    }
    let (applied_width, applied_height) = size(get(map, "applied.res")?)?;
    let error = map
        .get("error")
        .map(|v| match v.as_str() {
            "unsupported" => Ok(QualityError::Unsupported),
            "invalid" => Ok(QualityError::Invalid),
            "unavailable" => Ok(QualityError::Unavailable),
            _ => Err(QualityControlError::Invalid("error")),
        })
        .transpose()?;
    Ok(QualityState {
        req: map.get("req").map(|v| number(v)).transpose()?,
        error,
        mode: parse_mode(get(map, "mode")?)?,
        selected_camera: parse_selection(get(map, "camera.selected")?)?,
        cameras,
        resolutions,
        frame_rates,
        applied_width,
        applied_height,
        applied_fps: number(get(map, "applied.fps")?)?,
        summary: get(map, "summary")?.into(),
    })
}
