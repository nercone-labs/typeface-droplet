use std::collections::{BTreeMap, HashSet};
use std::path::PathBuf;

use android::ComponentName;
use serde::{Deserialize, Serialize};
use sfnt::Tag;

use crate::Pattern;

/// Where the bytes of a font file are: a whole file, or a range of a file
/// (such as an uncompressed asset inside an APK).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FontSource {
    pub path: PathBuf,
    #[serde(default, skip_serializing_if = "FontSource::is_zero")]
    pub offset: u64,
    /// The length of the range; the rest of the file when absent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub length: Option<u64>,
}

impl FontSource {
    pub fn file(path: impl Into<PathBuf>) -> FontSource {
        FontSource { path: path.into(), offset: 0, length: None }
    }

    pub fn is_zero(value: &u64) -> bool {
        *value == 0
    }
}

/// A font file known to the configuration.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FontEntry {
    /// The lowercase hexadecimal SHA-256 of the source bytes.
    pub id: String,
    pub source: FontSource,
    /// The face of a font collection (TTC) to use; 0 for a single-face file.
    #[serde(default)]
    pub index: u32,
    /// A family name to write into the font's `name` table. Applications that look fonts up
    /// by name rather than through the system configuration (such as Firefox) see this name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rename: Option<String>,
}

/// The style a face provides.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FaceStyle {
    Normal,
    Italic,
}

/// One font of a role.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Face {
    /// The `id` of a `FontEntry`.
    pub font: String,
    /// The style; detected from the font (OS/2 and head flags) when absent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub style: Option<FaceStyle>,
    /// A fixed weight. For a static font it overrides the weight class of the font;
    /// for a variable font it pins the `wght` axis to this weight.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub weight: Option<u16>,
    /// Fixed values of variation axes other than `wght` and `ital`, overriding those of the role.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub axes: BTreeMap<String, f32>,
}

/// A named set of faces that replaces font families.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Role {
    pub name: String,
    pub faces: Vec<Face>,
    /// Fixed values of variation axes other than `wght` and `ital`, for every face.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub axes: BTreeMap<String, f32>,
}

/// What a rule matches.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub enum Matcher {
    /// A named family or family list whose name matches.
    Family(Pattern),
    /// A named family or family list that uses a font file whose name, without the
    /// `.ttf`/`.otf`/`.ttc` extension, matches.
    File(Pattern),
    /// An unnamed (fallback) family whose first language tag matches.
    Language(Pattern),
}

/// A family inserted before a matched fallback family.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FallbackEntry {
    pub role: String,
    /// The generic family this font is a fallback for (`serif`, `monospace`, ...),
    /// written as the `fallbackFor` attribute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fallback_for: Option<String>,
}

/// What a rule does to the elements it matches.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Action {
    /// Replace the fonts of the element with the faces of the role.
    Replace(String),
    /// Insert a fallback family made of these entries before the first matched family.
    /// Only valid with `Matcher::Language`, and only in `<familyset>` documents.
    Prepend(Vec<FallbackEntry>),
}

/// A rule. Rules are tried in order; the first rule that matches an element decides its edit.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Rule {
    #[serde(rename = "match")]
    pub matcher: Matcher,
    #[serde(flatten)]
    pub action: Action,
}

/// The complete configuration of the engine.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    pub version: u32,
    #[serde(default)]
    pub fonts: Vec<FontEntry>,
    #[serde(default)]
    pub roles: Vec<Role>,
    #[serde(default)]
    pub rules: Vec<Rule>,
    /// Components (`package/class`) to disable for every user while the configuration is applied.
    #[serde(default)]
    pub components: Vec<String>,
    /// Packages whose processes are restarted after applying, so that they load the new fonts.
    /// `@home` stands for the default home application.
    #[serde(default)]
    pub restart: Vec<String>,
    /// How long to watch system_server after reloading the fonts; 0 disables the guard.
    #[serde(default = "Config::default_guard_seconds")]
    pub guard_seconds: u32,
    /// Accept font configuration documents whose version this engine does not know.
    #[serde(default)]
    pub allow_unknown_versions: bool,
}

impl Config {
    pub const VERSION: u32 = 1;
    pub const HOME: &'static str = "@home";
    pub const MAX_GUARD_SECONDS: u32 = 3600;
    pub const MAX_NAME_LENGTH: usize = 63;

    pub fn default_guard_seconds() -> u32 {
        60
    }

    pub fn parse(text: &str) -> Result<Config, String> {
        let value: serde_json::Value = serde_json::from_str(text).map_err(|error| format!("invalid configuration JSON: {error}"))?;
        let version = value.get("version").and_then(serde_json::Value::as_u64);
        if version != Some(u64::from(Config::VERSION)) {
            return Err(format!("unsupported configuration version {version:?} (this engine reads version {})", Config::VERSION));
        }
        serde_json::from_value(value).map_err(|error| format!("invalid configuration: {error}"))
    }

    pub fn font(&self, id: &str) -> Option<&FontEntry> {
        self.fonts.iter().find(|font| font.id == id)
    }

    pub fn role(&self, name: &str) -> Option<&Role> {
        self.roles.iter().find(|role| role.name == name)
    }

    /// The roles that some rule uses.
    pub fn used_roles(&self) -> Vec<&Role> {
        let names: HashSet<&str> = self
            .rules
            .iter()
            .flat_map(|rule| match &rule.action {
                Action::Replace(role) => vec![role.as_str()],
                Action::Prepend(entries) => entries.iter().map(|entry| entry.role.as_str()).collect(),
            })
            .collect();
        self.roles.iter().filter(|role| names.contains(role.name.as_str())).collect()
    }

    /// The fonts that a used role uses.
    pub fn used_fonts(&self) -> Vec<&FontEntry> {
        let ids: HashSet<&str> = self.used_roles().iter().flat_map(|role| role.faces.iter().map(|face| face.font.as_str())).collect();
        self.fonts.iter().filter(|font| ids.contains(font.id.as_str())).collect()
    }

    /// Checks every reference and value, and returns a description of each problem.
    pub fn validate(&self) -> Vec<String> {
        let mut problems = Vec::new();
        if self.version != Config::VERSION {
            problems.push(format!("unsupported configuration version {}", self.version));
        }
        let mut ids = HashSet::new();
        for font in &self.fonts {
            if font.id.len() != 64 || !font.id.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)) {
                problems.push(format!("font id \"{}\" is not a lowercase SHA-256", font.id));
            }
            if !ids.insert(font.id.as_str()) {
                problems.push(format!("font id {} is listed twice", font.id));
            }
            if !font.source.path.is_absolute() {
                problems.push(format!("font source {} is not an absolute path", font.source.path.display()));
            }
            if let Some(name) = &font.rename
                && !Config::is_valid_name(name)
            {
                problems.push(format!("font name \"{name}\" must be 1 to {} printable characters", Config::MAX_NAME_LENGTH));
            }
        }
        let mut names = HashSet::new();
        for role in &self.roles {
            if !Config::is_valid_name(&role.name) {
                problems.push(format!("role name \"{}\" must be 1 to {} printable characters", role.name, Config::MAX_NAME_LENGTH));
            }
            if !names.insert(role.name.as_str()) {
                problems.push(format!("role {} is defined twice", role.name));
            }
            if role.faces.is_empty() {
                problems.push(format!("role {} has no faces", role.name));
            }
            problems.extend(Config::axis_problems(&role.axes, &format!("role {}", role.name)));
            for face in &role.faces {
                if self.font(&face.font).is_none() {
                    problems.push(format!("role {} uses an unknown font {}", role.name, face.font));
                }
                if let Some(weight) = face.weight
                    && !(1..=1000).contains(&weight)
                {
                    problems.push(format!("role {} has a face with weight {weight} outside 1 to 1000", role.name));
                }
                problems.extend(Config::axis_problems(&face.axes, &format!("a face of role {}", role.name)));
            }
        }
        for (index, rule) in self.rules.iter().enumerate() {
            let pattern = match &rule.matcher {
                Matcher::Family(pattern) | Matcher::File(pattern) | Matcher::Language(pattern) => pattern,
            };
            if pattern.text().is_empty() {
                problems.push(format!("rule {} has an empty pattern", index + 1));
            }
            match &rule.action {
                Action::Replace(role) => {
                    if self.role(role).is_none() {
                        problems.push(format!("rule {} uses an unknown role {role}", index + 1));
                    }
                }
                Action::Prepend(entries) => {
                    if !matches!(rule.matcher, Matcher::Language(_)) {
                        problems.push(format!("rule {} prepends a fallback family, which needs a language match", index + 1));
                    }
                    if entries.is_empty() {
                        problems.push(format!("rule {} prepends an empty family", index + 1));
                    }
                    for entry in entries {
                        if self.role(&entry.role).is_none() {
                            problems.push(format!("rule {} uses an unknown role {}", index + 1, entry.role));
                        }
                        if entry.fallback_for.as_deref().is_some_and(|name| !Config::is_valid_name(name)) {
                            problems.push(format!("rule {} has an invalid fallbackFor", index + 1));
                        }
                    }
                }
            }
        }
        for component in &self.components {
            if ComponentName::parse(component).is_none() {
                problems.push(format!("component \"{component}\" is not of the form package/class"));
            }
        }
        for target in &self.restart {
            if target != Config::HOME && !Config::is_package_name(target) {
                problems.push(format!("restart target \"{target}\" is not a package name or {}", Config::HOME));
            }
        }
        if self.guard_seconds > Config::MAX_GUARD_SECONDS {
            problems.push(format!("guardSeconds must be at most {}", Config::MAX_GUARD_SECONDS));
        }
        problems
    }

    pub fn axis_problems(axes: &BTreeMap<String, f32>, owner: &str) -> Vec<String> {
        axes.iter()
            .filter_map(|(tag, value)| match Tag::parse(tag) {
                None => Some(format!("{owner} has an invalid axis tag \"{tag}\"")),
                Some(tag) if tag == sfnt::VariationAxis::WEIGHT || tag == sfnt::VariationAxis::ITALIC => {
                    Some(format!("{owner} sets {tag}, which is controlled by weights and styles"))
                }
                Some(_) if !value.is_finite() => Some(format!("{owner} has a non-finite value for {tag}")),
                Some(_) => None,
            })
            .collect()
    }

    pub fn is_valid_name(name: &str) -> bool {
        !name.is_empty() && name.chars().count() <= Config::MAX_NAME_LENGTH && !name.chars().any(char::is_control)
    }

    pub fn is_package_name(name: &str) -> bool {
        let segments: Vec<&str> = name.split('.').collect();
        segments.len() >= 2
            && segments.iter().all(|segment| {
                segment.chars().next().is_some_and(|first| first.is_ascii_alphabetic())
                    && segment.chars().all(|character| character.is_ascii_alphanumeric() || character == '_')
            })
    }
}
