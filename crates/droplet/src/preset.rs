use serde::{Deserialize, Serialize};

use crate::{Action, Config, Face, FaceStyle, FallbackEntry, FontEntry, Matcher, Pattern, Role, Rule};

/// The fonts of the three standard roles, as input to the recommended configuration.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Preset {
    #[serde(default)]
    pub sans: Option<FontEntry>,
    #[serde(default)]
    pub sans_italic: Option<FontEntry>,
    #[serde(default)]
    pub serif: Option<FontEntry>,
    #[serde(default)]
    pub serif_italic: Option<FontEntry>,
    #[serde(default)]
    pub mono: Option<FontEntry>,
    #[serde(default)]
    pub mono_italic: Option<FontEntry>,
}

impl Preset {
    pub const SANS: &'static str = "sans";
    pub const SERIF: &'static str = "serif";
    pub const MONO: &'static str = "mono";

    /// Family names replaced by each standard role.
    pub const SANS_FAMILIES: [&'static str; 4] = ["sans-serif", "sans-serif-condensed", "roboto-flex", "google-sans"];
    pub const SERIF_FAMILIES: [&'static str; 1] = ["serif"];
    pub const MONO_FAMILIES: [&'static str; 2] = ["monospace", "serif-monospace"];

    /// Family name patterns of each standard role, tried after the font files, so that a
    /// family such as google-sans-mono that uses a monospace file gets the monospace role.
    pub const SANS_FAMILY_PATTERNS: [&'static str; 1] = ["google-sans-*"];

    /// Font files (without extension) whose named families are replaced even when the family
    /// name is not listed above, for OEM configurations that define Roboto, Google Sans and so
    /// on under other names. They are tried after every family name, monospace first.
    pub const MONO_FILES: [&'static str; 5] = ["DroidSansMono", "CutiveMono", "RobotoMono*", "GoogleSansMono*", "GoogleSansCode*"];
    pub const SERIF_FILES: [&'static str; 2] = ["NotoSerif", "NotoSerif-*"];
    pub const SANS_FILES: [&'static str; 4] = ["Roboto-*", "RobotoStatic-*", "RobotoFlex-*", "GoogleSans*"];

    /// Languages whose fallback chain gets the replacement fonts inserted before their first
    /// family, so that text in families that are not replaced (casual, cursive, ...) still
    /// uses the replacement CJK glyphs.
    pub const FALLBACK_LANGUAGES: [&'static str; 1] = ["ja"];

    /// The components of the Google Play services downloadable font provider and its prefetch
    /// service. With them disabled, applications that request fonts from the provider (such
    /// as Google Sans in Google applications) fall back to the system fonts.
    pub const COMPONENTS: [&'static str; 2] =
        ["com.google.android.gms/.fonts.provider.FontsProvider", "com.google.android.gms/.fonts.update.UpdateSchedulerService"];

    pub const RESTART: [&'static str; 2] = ["com.android.systemui", Config::HOME];

    /// The family names written into the fonts of each standard role, so that applications
    /// that look fonts up by name find the replacement under the original name.
    pub const SANS_NAME: &'static str = "Roboto";
    pub const SERIF_NAME: &'static str = "Noto Serif";
    pub const MONO_NAME: &'static str = "Droid Sans Mono";

    /// The recommended configuration: the behavior of TypeFace Magic v2.0.0, except that the
    /// family pattern google-sans-* is tried after the font files instead of before them.
    pub fn config(&self) -> Config {
        let mut config = Config {
            version: Config::VERSION,
            fonts: Vec::new(),
            roles: Vec::new(),
            rules: Vec::new(),
            components: Preset::COMPONENTS.iter().map(|component| component.to_string()).collect(),
            restart: Preset::RESTART.iter().map(|target| target.to_string()).collect(),
            guard_seconds: 60,
            allow_unknown_versions: false,
        };
        let roles = [
            (Preset::SANS, Preset::SANS_NAME, &self.sans, &self.sans_italic),
            (Preset::SERIF, Preset::SERIF_NAME, &self.serif, &self.serif_italic),
            (Preset::MONO, Preset::MONO_NAME, &self.mono, &self.mono_italic),
        ];
        for (role, name, upright, italic) in roles {
            let mut faces = Vec::new();
            for (font, style) in [(upright, FaceStyle::Normal), (italic, FaceStyle::Italic)] {
                if let Some(font) = font {
                    let entry = FontEntry { rename: font.rename.clone().or_else(|| Some(name.to_string())), ..font.clone() };
                    faces.push(Face { font: entry.id.clone(), style: Some(style), weight: None, axes: Default::default() });
                    if config.font(&entry.id).is_none() {
                        config.fonts.push(entry);
                    }
                }
            }
            if !faces.is_empty() {
                config.roles.push(Role { name: role.to_string(), faces, axes: Default::default() });
            }
        }
        let has = |role: &str| config.role(role).is_some();
        let mut rules = Vec::new();
        for (role, families) in
            [(Preset::SANS, &Preset::SANS_FAMILIES[..]), (Preset::SERIF, &Preset::SERIF_FAMILIES[..]), (Preset::MONO, &Preset::MONO_FAMILIES[..])]
        {
            if has(role) {
                rules.extend(families.iter().map(|family| Rule { matcher: Matcher::Family(Pattern::new(*family)), action: Action::Replace(role.to_string()) }));
            }
        }
        for (role, files) in [(Preset::MONO, &Preset::MONO_FILES[..]), (Preset::SERIF, &Preset::SERIF_FILES[..]), (Preset::SANS, &Preset::SANS_FILES[..])] {
            if has(role) {
                rules.extend(files.iter().map(|file| Rule { matcher: Matcher::File(Pattern::new(*file)), action: Action::Replace(role.to_string()) }));
            }
        }
        if has(Preset::SANS) {
            rules.extend(
                Preset::SANS_FAMILY_PATTERNS
                    .iter()
                    .map(|family| Rule { matcher: Matcher::Family(Pattern::new(*family)), action: Action::Replace(Preset::SANS.to_string()) }),
            );
        }
        if has(Preset::SANS) {
            let mut entries = vec![FallbackEntry { role: Preset::SANS.to_string(), fallback_for: None }];
            if has(Preset::SERIF) {
                entries.push(FallbackEntry { role: Preset::SERIF.to_string(), fallback_for: Some("serif".to_string()) });
            }
            if has(Preset::MONO) {
                entries.push(FallbackEntry { role: Preset::MONO.to_string(), fallback_for: Some("monospace".to_string()) });
            }
            rules.extend(
                Preset::FALLBACK_LANGUAGES
                    .iter()
                    .map(|language| Rule { matcher: Matcher::Language(Pattern::new(*language)), action: Action::Prepend(entries.clone()) }),
            );
        }
        config.rules = rules;
        config
    }
}
