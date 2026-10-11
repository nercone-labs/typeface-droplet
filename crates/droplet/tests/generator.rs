//! Tests of document generation against the grammar of the Android font parsers
//! (FontListParser, FontCustomizationParser) and the rules of the configuration,
//! on the documents of real Android 13, 15 and 17 systems.

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;

use droplet::{
    Action, AxisInfo, Config, DocumentKind, Face, FaceInfo, FaceStyle, FallbackEntry, FontEntry, FontSource, Generator, InstalledFont, Matcher, Pattern,
    Preset, Role, Rule,
};
use fontxml::{Document, Element, Family, Font};

const API33_FONTS: &str = include_str!("../../fontxml/tests/fixtures/api33/fonts.xml");
const API35_FALLBACK: &str = include_str!("../../fontxml/tests/fixtures/api35/font_fallback.xml");
const API37_FONTS: &str = include_str!("../../fontxml/tests/fixtures/api37/fonts.xml");
const API37_FALLBACK: &str = include_str!("../../fontxml/tests/fixtures/api37/font_fallback.xml");
const CUSTOMIZATION: &str = include_str!("../../fontxml/tests/fixtures/customization/fonts_customization.xml");

fn id(seed: u8) -> String {
    format!("{seed:02x}").repeat(32)
}

fn axis(tag: &str, minimum: f32, default: f32, maximum: f32) -> AxisInfo {
    AxisInfo { tag: tag.to_string(), minimum, default, maximum, hidden: false }
}

fn face(weight: u16, italic: bool, axes: Vec<AxisInfo>) -> FaceInfo {
    FaceInfo {
        index: 0,
        family: Some("Test".to_string()),
        subfamily: None,
        full_name: None,
        postscript_name: None,
        weight,
        italic,
        outline: "truetype".to_string(),
        axes,
        instances: 0,
    }
}

/// Fonts: 1 = variable upright (wght 100-900, opsz), 2 = variable italic, 3 = static bold,
/// 4 = static regular, 5 = variable with wght 300-700 and ital 0-1, 6 = face 2 of a collection.
fn fonts() -> HashMap<String, InstalledFont> {
    let entries = [
        (1, face(400, false, vec![axis("opsz", 14.0, 14.0, 32.0), axis("wght", 100.0, 400.0, 900.0)]), 0),
        (2, face(400, true, vec![axis("wght", 100.0, 400.0, 900.0)]), 0),
        (3, face(700, false, vec![]), 0),
        (4, face(400, false, vec![]), 0),
        (5, face(400, false, vec![axis("wght", 300.0, 400.0, 700.0), axis("ital", 0.0, 0.0, 1.0)]), 0),
        (6, face(400, false, vec![]), 2),
        (7, face(0, false, vec![]), 0),
        (8, face(400, false, vec![axis("GRD ", -50.0, 0.0, 100.0), axis("wght", 100.0, 400.0, 900.0)]), 0),
        (9, face(400, true, vec![axis("wght", 100.0, 400.0, 900.0), axis("ital", 0.0, 0.0, 1.0)]), 0),
    ];
    entries
        .into_iter()
        .map(|(seed, face, index)| {
            let file_name = format!("TypeFaceDroplet-test{seed}.{}", if index > 0 { "ttc" } else { "ttf" });
            (id(seed), InstalledFont { id: id(seed), path: PathBuf::from(format!("/store/{file_name}")), file_name, index, face })
        })
        .collect()
}

fn entry(seed: u8) -> FontEntry {
    FontEntry { id: id(seed), source: FontSource::file(format!("/fonts/{seed}.ttf")), index: if seed == 6 { 2 } else { 0 }, rename: None }
}

fn role(name: &str, faces: &[(u8, Option<FaceStyle>, Option<u16>)]) -> Role {
    Role {
        name: name.to_string(),
        faces: faces.iter().map(|(seed, style, weight)| Face { font: id(*seed), style: *style, weight: *weight, axes: BTreeMap::new() }).collect(),
        axes: BTreeMap::new(),
    }
}

fn config(roles: Vec<Role>, rules: Vec<Rule>) -> Config {
    Config {
        version: Config::VERSION,
        fonts: (1..=9).map(entry).collect(),
        roles,
        rules,
        components: vec![],
        restart: vec![],
        guard_seconds: 0,
        allow_unknown_versions: false,
    }
}

fn replace(matcher: Matcher, role: &str) -> Rule {
    Rule { matcher, action: Action::Replace(role.to_string()) }
}

fn family(pattern: &str) -> Matcher {
    Matcher::Family(Pattern::new(pattern))
}

fn generate(kind: DocumentKind, api: u32, config: &Config, source: &str) -> (String, Document, droplet::GenerationReport) {
    let fonts = fonts();
    let mut generator = Generator::new(kind, api, config, &fonts);
    let text = generator.generate(&Document::parse(source).unwrap()).unwrap();
    let document = Document::parse(text.as_str()).unwrap();
    (text, document, generator.report)
}

fn named<'a>(document: &'a Document, name: &str) -> &'a Element {
    &document.named(name).unwrap().element
}

fn attribute<'a>(font: &'a Font, name: &str) -> Option<&'a str> {
    font.attributes.get(name)
}

#[test]
fn supported_axes_document_uses_one_font_per_variable_face() {
    let config =
        config(vec![role("sans", &[(1, Some(FaceStyle::Normal), None), (2, Some(FaceStyle::Italic), None)])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, report) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 2);
    assert_eq!(fonts[0].file, "TypeFaceDroplet-test1.ttf");
    assert_eq!(attribute(fonts[0], Font::SUPPORTED_AXES), Some("wght"));
    assert_eq!(attribute(fonts[0], Font::STYLE), Some("normal"));
    assert_eq!(attribute(fonts[1], Font::STYLE), Some("italic"));
    assert!(fonts.iter().all(|font| font.axes.is_empty()));
    assert_eq!(report.replaced, vec![("sans-serif".to_string(), "sans".to_string())]);
    assert_eq!(report.files.len(), 2);
}

#[test]
fn legacy_document_expands_variable_faces_to_nine_weights() {
    let config = config(vec![role("sans", &[(1, None, None), (2, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontsXML, 37, &config, API37_FONTS);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 18);
    for (index, font) in fonts.iter().enumerate() {
        let weight = 100 * (index % 9 + 1);
        assert_eq!(font.weight(), Some(weight as i32));
        assert_eq!(font.is_italic(), index >= 9, "the style is detected from the font");
        assert_eq!(attribute(font, Font::SUPPORTED_AXES), None, "fonts.xml never uses supportedAxes");
        assert_eq!(font.axes[0].tag, "wght");
        assert_eq!(font.axes[0].style_value, weight.to_string());
    }
}

#[test]
fn weights_are_clamped_to_the_axis_range_and_ital_is_expanded() {
    let config = config(vec![role("sans", &[(5, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontsXML, 33, &config, API33_FONTS);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 18, "an ital axis without an explicit italic face yields italic instances");
    let value = |font: &Font, tag: &str| font.axes.iter().find(|axis| axis.tag == tag).map(|axis| axis.style_value.clone());
    assert_eq!(value(fonts[0], "wght").as_deref(), Some("300"), "weight 100 is clamped to the minimum 300");
    assert_eq!(value(fonts[8], "wght").as_deref(), Some("700"), "weight 900 is clamped to the maximum 700");
    assert_eq!(value(fonts[0], "ital").as_deref(), Some("0"));
    assert!(fonts[9].is_italic());
    assert_eq!(value(fonts[9], "ital").as_deref(), Some("1"));

    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 1);
    assert_eq!(attribute(fonts[0], Font::SUPPORTED_AXES), Some("wght,ital"));
    assert_eq!(attribute(fonts[0], Font::STYLE), None, "the style comes from the ital axis");
}

#[test]
fn static_faces_use_their_weight_class_and_pinned_weights() {
    let config = config(vec![role("sans", &[(4, None, None), (3, None, None), (1, None, Some(550))])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 3);
    assert_eq!(fonts[0].weight(), Some(400));
    assert_eq!(fonts[1].weight(), Some(700));
    // A pinned weight on a variable face disables supportedAxes for the whole role.
    assert_eq!(fonts[2].weight(), Some(550));
    assert!(fonts.iter().all(|font| attribute(font, Font::SUPPORTED_AXES).is_none()));
    assert_eq!(fonts[2].axes[0].style_value, "550");
}

#[test]
fn supported_axes_follow_the_variable_family_rules_of_the_framework() {
    // SystemFonts.resolveVarFamilyType accepts one wght font, or two wght fonts of which one is
    // italic. Any other set of variable fonts must be expanded to fixed weights instead.
    for faces in [
        vec![(1, Some(FaceStyle::Normal), None), (1, Some(FaceStyle::Normal), None)],
        vec![(1, Some(FaceStyle::Normal), None), (2, Some(FaceStyle::Italic), None), (1, Some(FaceStyle::Normal), None)],
        vec![(1, Some(FaceStyle::Italic), None), (2, Some(FaceStyle::Italic), None)],
    ] {
        let config = config(vec![role("sans", &faces)], vec![replace(family("sans-serif"), "sans")]);
        let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
        let fonts = named(&document, "sans-serif").fonts();
        assert_eq!(fonts.len(), 9 * faces.len(), "{faces:?}");
        assert!(fonts.iter().all(|font| attribute(font, Font::SUPPORTED_AXES).is_none()));
    }
}

#[test]
fn weight_classes_outside_the_valid_range_become_400() {
    // android.graphics.fonts.FontStyle throws for weights outside 1 to 1000.
    let config = config(vec![role("sans", &[(7, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    assert_eq!(named(&document, "sans-serif").fonts()[0].weight(), Some(400));
    assert_eq!(Generator::weight_class(0), 400);
    assert_eq!(Generator::weight_class(1001), 400);
    assert_eq!(Generator::weight_class(1), 1);
    assert_eq!(Generator::weight_class(1000), 1000);
}

#[test]
fn short_axis_tags_match_and_are_written_with_four_characters() {
    let mut sans = role("sans", &[(8, None, None)]);
    sans.axes.insert("GRD".to_string(), 150.0);
    let config = config(vec![sans], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let axes = &named(&document, "sans-serif").fonts()[0].axes;
    assert_eq!(axes.len(), 1);
    // FontVariationAxis accepts only four-character tags.
    assert_eq!((axes[0].tag.as_str(), axes[0].style_value.as_str()), ("GRD ", "100"));
}

#[test]
fn a_face_with_an_ital_axis_covers_both_styles_even_if_flagged_italic() {
    let config = config(vec![role("sans", &[(9, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts.len(), 1);
    assert_eq!(attribute(fonts[0], Font::SUPPORTED_AXES), Some("wght,ital"));
}

#[test]
fn fixed_axes_apply_only_to_axes_the_font_has_and_are_clamped() {
    let mut sans = role("sans", &[(1, None, None)]);
    sans.axes.insert("opsz".to_string(), 99.0);
    sans.axes.insert("wdth".to_string(), 75.0);
    let config = config(vec![sans], vec![replace(family("sans-serif"), "sans")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let fonts = named(&document, "sans-serif").fonts();
    assert_eq!(fonts[0].axes.len(), 1);
    assert_eq!((fonts[0].axes[0].tag.as_str(), fonts[0].axes[0].style_value.as_str()), ("opsz", "32"));
}

#[test]
fn collections_write_the_face_index() {
    let config = config(vec![role("mono", &[(6, None, None)])], vec![replace(family("monospace"), "mono")]);
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    assert_eq!(attribute(named(&document, "monospace").fonts()[0], Font::INDEX), Some("2"));
}

#[test]
fn named_families_keep_their_attributes_and_gain_none() {
    let config = config(vec![role("sans", &[(1, None, None)])], vec![replace(family("*"), "sans")]);
    let original = Document::parse(API37_FALLBACK).unwrap();
    let (_, document, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    for (before, after) in original.items.iter().zip(&document.items) {
        if let (Element::Family(before), Element::Family(after)) = (&before.element, &after.element) {
            // FontListParser throws if a named family has lang, variant or ignore.
            assert_eq!(before.attributes, after.attributes);
        }
    }
}

#[test]
fn file_rules_match_stems_of_named_families_only() {
    let config = config(vec![role("sans", &[(4, None, None)])], vec![replace(Matcher::File(Pattern::new("Roboto*")), "sans")]);
    let (_, document, report) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    let replaced: Vec<&str> = report.replaced.iter().map(|(name, _)| name.as_str()).collect();
    assert_eq!(replaced, ["sans-serif", "sans-serif-condensed", "roboto", "roboto-flex"]);
    let original = Document::parse(API37_FALLBACK).unwrap();
    for (before, after) in original.items.iter().zip(&document.items) {
        if before.element.name().is_none() {
            assert_eq!(before.element, after.element, "unnamed families are never matched by file rules");
        }
    }
    assert_eq!(Generator::stem("Roboto-Regular.TTF"), "Roboto-Regular");
    assert_eq!(Generator::stem("NotoSansCJK-Regular.ttc"), "NotoSansCJK-Regular");
    assert_eq!(Generator::stem("font.woff"), "font.woff");
}

#[test]
fn the_first_matching_rule_wins() {
    let config =
        config(vec![role("a", &[(3, None, None)]), role("b", &[(4, None, None)])], vec![replace(family("serif"), "a"), replace(family("serif*"), "b")]);
    let (_, _, report) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    assert!(report.replaced.contains(&("serif".to_string(), "a".to_string())));
    assert!(report.replaced.contains(&("serif-monospace".to_string(), "b".to_string())));
}

#[test]
fn prepend_inserts_once_before_the_first_matching_language() {
    let rule = Rule {
        matcher: Matcher::Language(Pattern::new("ja")),
        action: Action::Prepend(vec![
            FallbackEntry { role: "sans".to_string(), fallback_for: None },
            FallbackEntry { role: "serif".to_string(), fallback_for: Some("serif".to_string()) },
        ]),
    };
    let config = config(vec![role("sans", &[(1, None, None)]), role("serif", &[(4, None, None)])], vec![rule]);
    let original = Document::parse(API37_FALLBACK).unwrap();
    let (_, document, report) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    assert_eq!(document.items.len(), original.items.len() + 1);
    assert_eq!(report.inserted, vec![("ja".to_string(), vec!["sans".to_string(), "serif".to_string()])]);
    let first_ja = original.items.iter().position(|item| matches!(&item.element, Element::Family(f) if f.languages().first() == Some(&"ja"))).unwrap();
    let Element::Family(inserted) = &document.items[first_ja].element else { panic!() };
    assert_eq!(inserted.attributes.get(Family::LANG), Some("ja"));
    assert_eq!(inserted.name(), None);
    assert_eq!(inserted.fonts.len(), 2);
    assert_eq!(inserted.fonts[0].fallback_for(), None);
    assert_eq!(inserted.fonts[1].fallback_for(), Some("serif"));
    assert_eq!(document.items[first_ja + 1].element, original.items[first_ja].element);
}

#[test]
fn language_replace_keeps_the_family_attributes() {
    let config = config(vec![role("sans", &[(4, None, None)])], vec![replace(Matcher::Language(Pattern::new("und-Arab")), "sans")]);
    let original = Document::parse(API35_FALLBACK).unwrap();
    let (_, document, report) = generate(DocumentKind::FontFallbackXML, 35, &config, API35_FALLBACK);
    let arabic: Vec<usize> = original
        .items
        .iter()
        .enumerate()
        .filter(|(_, item)| matches!(&item.element, Element::Family(f) if f.languages().first() == Some(&"und-Arab")))
        .map(|(index, _)| index)
        .collect();
    assert!(arabic.len() >= 2);
    assert_eq!(report.replaced.len(), arabic.len());
    for index in arabic {
        let (Element::Family(before), Element::Family(after)) = (&original.items[index].element, &document.items[index].element) else { panic!() };
        assert_eq!(before.attributes, after.attributes, "lang and variant stay");
        assert_eq!(after.fonts[0].file, "TypeFaceDroplet-test4.ttf");
    }
}

#[test]
fn customization_documents_replace_named_families_and_never_get_fallbacks() {
    let rules = vec![
        replace(family("google-sans*"), "sans"),
        Rule { matcher: Matcher::Language(Pattern::new("ja")), action: Action::Prepend(vec![FallbackEntry { role: "sans".to_string(), fallback_for: None }]) },
    ];
    let config = config(vec![role("sans", &[(1, None, None)])], rules);
    let original = Document::parse(CUSTOMIZATION).unwrap();
    let (_, document, report) = generate(DocumentKind::FontsCustomizationXML, 35, &config, CUSTOMIZATION);
    assert_eq!(document.items.len(), original.items.len(), "fallback families cannot be inserted into a customization");
    assert!(report.inserted.is_empty());
    let Element::Family(google) = named(&document, "google-sans") else { panic!() };
    assert_eq!(google.attributes.get(Family::CUSTOMIZATION_TYPE), Some("new-named-family"));
    assert_eq!(attribute(&google.fonts[0], Font::SUPPORTED_AXES), Some("wght"));
    let Element::FamilyList(list) = named(&document, "google-sans-flex") else { panic!() };
    assert_eq!(list.attributes.get(Family::CUSTOMIZATION_TYPE), Some("new-named-family"));
    assert_eq!(list.families.len(), 1);
    assert_eq!(list.families[0].attributes.0.len(), 0, "families of a family list have no attributes");
    assert_eq!(named(&document, "vendor-display"), named(&original, "vendor-display"));

    // Before Android 15 the customization parser does not read supportedAxes.
    let (_, document, _) = generate(DocumentKind::FontsCustomizationXML, 34, &config, CUSTOMIZATION);
    assert_eq!(named(&document, "google-sans").fonts().len(), 9);
}

#[test]
fn output_ends_with_the_marker_and_generated_documents_are_refused() {
    let config = config(vec![role("sans", &[(1, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let (text, _, _) = generate(DocumentKind::FontFallbackXML, 37, &config, API37_FALLBACK);
    assert!(text.ends_with(&format!("{}\n", DocumentKind::MARKER)));
    let fonts = fonts();
    let mut generator = Generator::new(DocumentKind::FontFallbackXML, 37, &config, &fonts);
    assert!(generator.generate(&Document::parse(text.as_str()).unwrap()).is_err());
}

#[test]
fn unknown_versions_and_roots_are_refused_unless_allowed() {
    let mut config = config(vec![role("sans", &[(1, None, None)])], vec![replace(family("sans-serif"), "sans")]);
    let future = API37_FONTS.replace("<familyset version=\"23\">", "<familyset version=\"24\">");
    let fonts = fonts();
    let document = Document::parse(future.as_str()).unwrap();
    assert!(Generator::new(DocumentKind::FontsXML, 38, &config, &fonts).generate(&document).is_err());
    config.allow_unknown_versions = true;
    assert!(Generator::new(DocumentKind::FontsXML, 38, &config, &fonts).generate(&document).is_ok());
    let customization = Document::parse(CUSTOMIZATION).unwrap();
    assert!(Generator::new(DocumentKind::FontsXML, 37, &config, &fonts).generate(&customization).is_err(), "wrong root element for the kind");
}

#[test]
fn preset_reproduces_typeface_magic() {
    let preset =
        Preset { sans: Some(entry(1)), sans_italic: Some(entry(2)), serif: Some(entry(4)), serif_italic: None, mono: Some(entry(3)), mono_italic: None };
    let config = preset.config();
    assert!(config.validate().is_empty(), "{:?}", config.validate());
    assert_eq!(config.font(&id(1)).unwrap().rename.as_deref(), Some("Roboto"));
    assert_eq!(config.font(&id(4)).unwrap().rename.as_deref(), Some("Noto Serif"));
    assert_eq!(config.font(&id(3)).unwrap().rename.as_deref(), Some("Droid Sans Mono"));
    let order: Vec<(&str, &str)> = config
        .rules
        .iter()
        .map(|rule| {
            let kind = match &rule.matcher {
                Matcher::Family(_) => "family",
                Matcher::File(_) => "file",
                Matcher::Language(_) => "language",
            };
            let role = match &rule.action {
                Action::Replace(role) => role.as_str(),
                Action::Prepend(_) => "fallback",
            };
            (kind, role)
        })
        .collect();
    // Exact family names first, then files in the order monospace, serif, sans-serif, then the
    // family pattern google-sans-*, so that a family using a monospace file stays monospace.
    let mut expected = Vec::new();
    expected.extend(std::iter::repeat_n(("family", "sans"), 4));
    expected.push(("family", "serif"));
    expected.extend(std::iter::repeat_n(("family", "mono"), 2));
    expected.extend(std::iter::repeat_n(("file", "mono"), 5));
    expected.extend(std::iter::repeat_n(("file", "serif"), 2));
    expected.extend(std::iter::repeat_n(("file", "sans"), 4));
    expected.push(("family", "sans"));
    expected.push(("language", "fallback"));
    assert_eq!(order, expected);
    let Action::Prepend(entries) = &config.rules.last().unwrap().action else { panic!() };
    assert_eq!(entries.iter().map(|entry| entry.fallback_for.as_deref()).collect::<Vec<_>>(), [None, Some("serif"), Some("monospace")]);
    assert_eq!(config.components.len(), 2);
    assert_eq!(config.restart, ["com.android.systemui", Config::HOME]);

    let (_, document, report) = generate(DocumentKind::FontsXML, 33, &config, API33_FONTS);
    assert!(report.inserted.len() == 1);
    // A Google Sans family that uses a monospace file gets the monospace role.
    let customization = CUSTOMIZATION.replace(
        "<alias name=\"google-sans-medium\"",
        "<family customizationType=\"new-named-family\" name=\"google-sans-mono\"><font weight=\"400\" style=\"normal\">GoogleSansMono-Regular.ttf</font></family>\n    <alias name=\"google-sans-medium\"",
    );
    let (_, custom, _) = generate(DocumentKind::FontsCustomizationXML, 35, &config, &customization);
    assert_eq!(named(&custom, "google-sans-mono").fonts()[0].file, "TypeFaceDroplet-test3.ttf");
    assert_eq!(named(&custom, "google-sans-text").fonts()[0].file, "TypeFaceDroplet-test1.ttf");
    for name in ["sans-serif", "sans-serif-condensed", "serif", "monospace", "serif-monospace"] {
        assert!(named(&document, name).fonts().iter().all(|font| font.file.starts_with("TypeFaceDroplet-")), "{name}");
    }
}
