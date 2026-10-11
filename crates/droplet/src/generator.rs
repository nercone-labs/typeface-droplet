use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use fontxml::{Axis, Document, Edit, Element, Family, FamilyList, Font, Item, Transformer};
use serde::Serialize;

use crate::{Action, Config, DocumentKind, FaceStyle, FallbackEntry, FontStore, InstalledFont, Matcher, Role, Rule};

/// What a generation changed in one document.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GenerationReport {
    /// `(family name, role)` of every replaced named family, and `(lang, role)` of every
    /// replaced fallback family.
    pub replaced: Vec<(String, String)>,
    /// `(lang, roles)` of every inserted fallback family.
    pub inserted: Vec<(String, Vec<String>)>,
    /// The file names of the installed fonts the document refers to.
    pub files: BTreeSet<String>,
}

/// Generates a font configuration document from an original one, applying the rules of a
/// configuration. It implements `fontxml::Transformer`.
pub struct Generator<'a> {
    pub kind: DocumentKind,
    pub api: u32,
    pub config: &'a Config,
    /// Installed fonts by font id.
    pub fonts: &'a HashMap<String, InstalledFont>,
    used_prepends: HashSet<usize>,
    pub report: GenerationReport,
}

impl<'a> Generator<'a> {
    /// The weights a variable font is expanded to when the document cannot use `supportedAxes`.
    pub const WEIGHTS: [u16; 9] = [100, 200, 300, 400, 500, 600, 700, 800, 900];

    pub fn new(kind: DocumentKind, api: u32, config: &'a Config, fonts: &'a HashMap<String, InstalledFont>) -> Generator<'a> {
        Generator { kind, api, config, fonts, used_prepends: HashSet::new(), report: GenerationReport::default() }
    }

    /// Generates the document and checks the result. Returns the generated text.
    pub fn generate(&mut self, document: &Document) -> Result<String, String> {
        self.kind.check(document, self.config.allow_unknown_versions)?;
        if document.source.contains(DocumentKind::MARKER) {
            return Err("the original document is itself a generated document".to_string());
        }
        let mut text = document.transform(self);
        if !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str(DocumentKind::MARKER);
        text.push('\n');
        self.verify(document, &text)?;
        Ok(text)
    }

    pub fn verify(&self, original: &Document, text: &str) -> Result<(), String> {
        let generated = Document::parse(text).map_err(|error| format!("the result does not parse: {error}"))?;
        if generated.root != original.root || generated.attributes != original.attributes {
            return Err("the root element changed".to_string());
        }
        if generated.items.len() != original.items.len() + self.report.inserted.len() {
            return Err(format!("the result has {} elements instead of {}", generated.items.len(), original.items.len() + self.report.inserted.len()));
        }
        let known: HashSet<&str> = self.fonts.values().map(|font| font.file_name.as_str()).collect();
        for item in &generated.items {
            for font in item.element.fonts() {
                if font.file.starts_with(FontStore::FILE_PREFIX) && !known.contains(font.file.as_str()) {
                    return Err(format!("the result refers to {}, which is not installed", font.file));
                }
            }
        }
        for (name, _) in &self.report.replaced {
            if original.named(name).is_some() && generated.named(name).is_none() {
                return Err(format!("family {name} disappeared"));
            }
        }
        Ok(())
    }

    /// The file name without a font extension (`.ttf`, `.otf`, `.ttc`, in any case).
    pub fn stem(file: &str) -> &str {
        match file.rsplit_once('.') {
            Some((stem, extension)) if ["ttf", "otf", "ttc"].contains(&extension.to_ascii_lowercase().as_str()) => stem,
            _ => file,
        }
    }

    pub fn matches(rule: &Rule, element: &Element) -> bool {
        match (&rule.matcher, element) {
            (Matcher::Family(pattern), Element::Family(family)) => family.name().is_some_and(|name| pattern.matches(name)) && !family.fonts.is_empty(),
            (Matcher::Family(pattern), Element::FamilyList(list)) => list.name().is_some_and(|name| pattern.matches(name)) && !element.fonts().is_empty(),
            (Matcher::File(pattern), Element::Family(_) | Element::FamilyList(_)) => {
                element.name().is_some() && element.fonts().iter().any(|font| pattern.matches(Generator::stem(&font.file)))
            }
            (Matcher::Language(pattern), Element::Family(family)) => {
                family.name().is_none()
                    && !family.is_ignored()
                    && !family.fonts.is_empty()
                    && family.languages().first().is_some_and(|language| pattern.matches(language))
            }
            _ => false,
        }
    }

    pub fn role(&self, name: &str) -> Option<&'a Role> {
        self.config.role(name)
    }

    /// The `<font>` elements of a role.
    ///
    /// - When the document understands `supportedAxes`, and the faces form a variable family
    ///   that `SystemFonts.resolveVarFamilyType` accepts (one face variable in `wght`, or two
    ///   such faces of which exactly one is italic), each face becomes one font with
    ///   `supportedAxes="wght"`, or `"wght,ital"` for a single face with an `ital` axis.
    /// - Otherwise a variable face is expanded to the weights 100 to 900, each pinning `wght`
    ///   (clamped to the axis range); a face with an `ital` axis and no explicit italic face
    ///   also yields italic instances with `ital` = 1.
    /// - A static face, or a face with a fixed `weight`, becomes one font of its weight.
    ///   A weight class outside 1 to 1000, which the framework rejects, becomes 400.
    /// - Fixed axes apply to the axes the font has, clamped to their range, and are written
    ///   with the four-character tag of the font.
    pub fn role_fonts(&self, role: &Role, fallback_for: Option<&str>) -> Vec<Font> {
        let faces: Vec<_> = role.faces.iter().filter_map(|face| Some((face, self.fonts.get(&face.font)?))).collect();
        // A face with an ital axis and no explicit style is an upright font that also covers italic.
        let style_of = |face: &crate::Face, font: &InstalledFont| {
            face.style.unwrap_or(if font.face.italic && font.face.italic_axis().is_none() { FaceStyle::Italic } else { FaceStyle::Normal })
        };
        let italics = faces.iter().filter(|(face, font)| style_of(face, font) == FaceStyle::Italic).count();
        let variable = faces.iter().all(|(face, font)| face.weight.is_none() && font.face.weight_axis().is_some());
        let family = match faces.len() {
            1 => true,
            2 => italics == 1,
            _ => false,
        };
        let supported = self.kind.supports_axes(self.api) && variable && family;
        let style_name = |style: FaceStyle| match style {
            FaceStyle::Normal => Font::STYLE_NORMAL,
            FaceStyle::Italic => Font::STYLE_ITALIC,
        };
        let mut fonts = Vec::new();
        for (face, font) in &faces {
            let style = style_of(face, font);
            let mut values: BTreeMap<&str, f32> = role.axes.iter().map(|(tag, value)| (tag.as_str(), *value)).collect();
            values.extend(face.axes.iter().map(|(tag, value)| (tag.as_str(), *value)));
            let fixed: Vec<Axis> = values
                .iter()
                .filter_map(|(tag, value)| {
                    let axis = font.face.axis(tag)?;
                    Some(Axis::new(axis.tag.clone(), Generator::number(value.clamp(axis.minimum, axis.maximum))))
                })
                .collect();
            let italic_axis = font.face.italic_axis().is_some() && italics == 0;
            let base = |mut element: Font| -> Font {
                if font.index != 0 {
                    element = element.with(Font::INDEX, font.index.to_string());
                }
                if let Some(target) = fallback_for {
                    element = element.with(Font::FALLBACK_FOR, target);
                }
                element
            };
            match (font.face.weight_axis(), face.weight) {
                (Some(_), None) if supported => {
                    let mut element = Font::new(&font.file_name).with(Font::WEIGHT, "400");
                    if !italic_axis {
                        element = element.with(Font::STYLE, style_name(style));
                    }
                    element = base(element).with(Font::SUPPORTED_AXES, if italic_axis { "wght,ital" } else { "wght" });
                    element.axes = fixed;
                    fonts.push(element);
                }
                (Some(weight_axis), pinned) => {
                    let weights: Vec<u16> = match pinned {
                        Some(weight) => vec![weight],
                        None => Generator::WEIGHTS.to_vec(),
                    };
                    let styles: Vec<(FaceStyle, Option<&str>)> =
                        if italic_axis { vec![(FaceStyle::Normal, Some("0")), (FaceStyle::Italic, Some("1"))] } else { vec![(style, None)] };
                    for (instance_style, italic_value) in styles {
                        for weight in &weights {
                            let value = f32::from(*weight).clamp(weight_axis.minimum, weight_axis.maximum);
                            let mut element =
                                base(Font::new(&font.file_name).with(Font::WEIGHT, weight.to_string()).with(Font::STYLE, style_name(instance_style)));
                            element.axes.push(Axis::new(weight_axis.tag.clone(), Generator::number(value)));
                            if let (Some(italic), Some(axis)) = (italic_value, font.face.italic_axis()) {
                                element.axes.push(Axis::new(axis.tag.clone(), italic));
                            }
                            element.axes.extend(fixed.iter().cloned());
                            fonts.push(element);
                        }
                    }
                }
                (None, weight) => {
                    let weight = weight.unwrap_or_else(|| Generator::weight_class(font.face.weight));
                    let mut element = base(Font::new(&font.file_name).with(Font::WEIGHT, weight.to_string()).with(Font::STYLE, style_name(style)));
                    element.axes = fixed;
                    fonts.push(element);
                }
            }
        }
        fonts
    }

    /// The OS/2 weight class as a font weight: the OpenType specification and
    /// `android.graphics.fonts.FontStyle` allow 1 to 1000; any other value is treated as 400.
    pub fn weight_class(value: u16) -> u16 {
        if (1..=1000).contains(&value) { value } else { 400 }
    }

    /// Formats an axis value the way Java's `Float.parseFloat` reads it back exactly.
    pub fn number(value: f32) -> String {
        format!("{value}")
    }

    pub fn record_files(&mut self, fonts: &[Font]) {
        for font in fonts {
            self.report.files.insert(font.file.clone());
        }
    }

    pub fn replace(&mut self, item: &Item, role: &Role) -> Edit {
        let fonts = self.role_fonts(role, None);
        if fonts.is_empty() {
            return Edit::Keep;
        }
        self.record_files(&fonts);
        let label = match &item.element {
            Element::Family(family) => family.name().map(str::to_string).unwrap_or_else(|| format!("lang={}", family.languages().join(","))),
            other => other.name().unwrap_or_default().to_string(),
        };
        self.report.replaced.push((label, role.name.clone()));
        match &item.element {
            Element::Family(family) => Edit::Replace(Element::Family(Family { attributes: family.attributes.clone(), fonts })),
            Element::FamilyList(list) => Edit::Replace(Element::FamilyList(FamilyList {
                attributes: list.attributes.clone(),
                families: vec![Family { attributes: Default::default(), fonts }],
            })),
            _ => Edit::Keep,
        }
    }

    pub fn prepend(&mut self, item: &Item, entries: &[FallbackEntry]) -> Edit {
        let Element::Family(family) = &item.element else { return Edit::Keep };
        let mut fonts = Vec::new();
        let mut roles = Vec::new();
        for entry in entries {
            if let Some(role) = self.role(&entry.role) {
                fonts.extend(self.role_fonts(role, entry.fallback_for.as_deref()));
                roles.push(role.name.clone());
            }
        }
        if fonts.is_empty() {
            return Edit::Keep;
        }
        self.record_files(&fonts);
        let lang = family.attributes.get(Family::LANG).unwrap_or_default().to_string();
        self.report.inserted.push((lang.clone(), roles));
        Edit::Prepend(vec![Element::Family(Family::new().with(Family::LANG, lang).with_fonts(fonts))])
    }
}

impl Transformer for Generator<'_> {
    fn edit(&mut self, _document: &Document, item: &Item) -> Edit {
        for (index, rule) in self.config.rules.iter().enumerate() {
            if !Generator::matches(rule, &item.element) {
                continue;
            }
            match &rule.action {
                Action::Replace(role) => {
                    let Some(role) = self.role(role) else { continue };
                    return self.replace(item, role);
                }
                Action::Prepend(entries) => {
                    if !self.kind.allows_fallback() || self.used_prepends.contains(&index) {
                        continue;
                    }
                    self.used_prepends.insert(index);
                    return self.prepend(item, entries);
                }
            }
        }
        Edit::Keep
    }
}
