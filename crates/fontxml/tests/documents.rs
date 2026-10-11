//! Tests against the grammar that android.graphics.FontListParser and
//! android.graphics.fonts.FontCustomizationParser accept, using the documents of real
//! Android 13 (API 33), 15 (API 35) and 17 (API 37) systems as fixtures.

use fontxml::{Axis, Document, Edit, Element, Family, Font, FontXMLError, Item, Root, Transformer};

const API33_FONTS: &str = include_str!("fixtures/api33/fonts.xml");
const API35_FONTS: &str = include_str!("fixtures/api35/fonts.xml");
const API35_FALLBACK: &str = include_str!("fixtures/api35/font_fallback.xml");
const API37_FONTS: &str = include_str!("fixtures/api37/fonts.xml");
const API37_FALLBACK: &str = include_str!("fixtures/api37/font_fallback.xml");
const CUSTOMIZATION: &str = include_str!("fixtures/customization/fonts_customization.xml");

const ALL: [&str; 6] = [API33_FONTS, API35_FONTS, API35_FALLBACK, API37_FONTS, API37_FALLBACK, CUSTOMIZATION];

struct KeepAll;

impl Transformer for KeepAll {
    fn edit(&mut self, _: &Document, _: &Item) -> Edit {
        Edit::Keep
    }
}

/// Applies a fixed edit to the item named `name`.
struct EditNamed(&'static str, Edit);

impl Transformer for EditNamed {
    fn edit(&mut self, _: &Document, item: &Item) -> Edit {
        if !matches!(item.element, Element::Alias(_)) && item.element.name() == Some(self.0) { self.1.clone() } else { Edit::Keep }
    }
}

#[test]
fn keeping_every_item_reproduces_the_source_exactly() {
    for source in ALL {
        let document = Document::parse(source).unwrap();
        assert_eq!(document.transform(&mut KeepAll), source);
    }
}

#[test]
fn reads_roots_and_versions() {
    let fonts = Document::parse(API33_FONTS).unwrap();
    assert_eq!(fonts.root, Root::FamilySet);
    assert_eq!(fonts.attributes.get("version"), Some("23"));
    let fallback = Document::parse(API37_FALLBACK).unwrap();
    assert_eq!(fallback.root, Root::FamilySet);
    assert_eq!(fallback.attributes.get("version"), None);
    let customization = Document::parse(CUSTOMIZATION).unwrap();
    assert_eq!(customization.root, Root::FontsModification);
    assert_eq!(customization.attributes.get("version"), Some("1"));
}

#[test]
fn reads_fonts_as_the_framework_does() {
    let document = Document::parse(API37_FALLBACK).unwrap();
    let Element::Family(sans) = &document.named("sans-serif").unwrap().element else { panic!("sans-serif is a family") };
    // The file name is the element text with leading and trailing XML whitespace removed,
    // and text after child elements belongs to it as well.
    assert_eq!(sans.fonts[0].file, "Roboto-Regular.ttf");
    assert_eq!(sans.fonts[0].supported_axes(), vec!["wght", "ital"]);
    assert_eq!(sans.fonts[0].axes, vec![Axis::new("wdth", "100.0")]);
    // Absent weight, style and index mean 400, upright and 0.
    assert_eq!(sans.fonts[0].weight(), None);
    assert!(!sans.fonts[0].is_italic());
    assert_eq!(sans.fonts[0].index(), 0);

    let Element::Family(serif) = &document.named("serif").unwrap().element else { panic!("serif is a family") };
    assert_eq!(serif.fonts.len(), 4);
    assert_eq!(serif.fonts[1].weight(), Some(700));
    assert!(serif.fonts[2].is_italic());

    let japanese = document
        .items
        .iter()
        .find_map(|item| match &item.element {
            Element::Family(family) if family.languages() == vec!["ja"] => Some(family),
            _ => None,
        })
        .unwrap();
    assert_eq!(japanese.fonts[0].file, "NotoSansCJK-Regular.ttc");
    assert_eq!(japanese.fonts[0].index(), 0);
    assert_eq!(japanese.fonts[1].fallback_for(), Some("serif"));
    let chinese = document
        .items
        .iter()
        .find_map(|item| match &item.element {
            Element::Family(family) if family.languages().contains(&"zh-Bopo") => Some(family),
            _ => None,
        })
        .unwrap();
    assert_eq!(chinese.languages(), vec!["zh-Hant", "zh-Bopo"]);

    let aliases: Vec<_> = document.items.iter().filter_map(|item| if let Element::Alias(alias) = &item.element { Some(alias) } else { None }).collect();
    let thin = aliases.iter().find(|alias| alias.name() == Some("sans-serif-thin")).unwrap();
    assert_eq!((thin.to(), thin.weight()), (Some("sans-serif"), Some(100)));
}

#[test]
fn named_lookup_ignores_aliases() {
    let document = Document::parse(API37_FALLBACK).unwrap();
    assert!(document.named("arial").is_none(), "arial is only an alias");
    assert!(matches!(document.named("monospace").unwrap().element, Element::Family(_)));
}

#[test]
fn reads_customization_families_and_lists() {
    let document = Document::parse(CUSTOMIZATION).unwrap();
    let Element::Family(google) = &document.named("google-sans").unwrap().element else { panic!() };
    assert_eq!(google.attributes.get(Family::CUSTOMIZATION_TYPE), Some("new-named-family"));
    assert_eq!(google.fonts.len(), 3);
    let Element::FamilyList(list) = &document.named("google-sans-flex").unwrap().element else { panic!() };
    assert_eq!(list.families.len(), 1);
    assert_eq!(list.families[0].fonts[0].file, "GoogleSansFlex-Regular.ttf");
    let locale = document
        .items
        .iter()
        .find_map(|item| match &item.element {
            Element::Family(family) if family.attributes.get(Family::OPERATION) == Some("prepend") => Some(family),
            _ => None,
        })
        .unwrap();
    assert_eq!(locale.languages(), vec!["ja"]);
    assert_eq!(locale.name(), None);
}

#[test]
fn replace_changes_only_the_item() {
    let source = API33_FONTS;
    let document = Document::parse(source).unwrap();
    let item = document.named("serif").unwrap().clone();
    let replacement = Family::new().with(Family::NAME, "serif").with_fonts(vec![Font::new("New.ttf").with(Font::WEIGHT, "400").with(Font::STYLE, "normal")]);
    let output = document.transform(&mut EditNamed("serif", Edit::Replace(Element::Family(replacement.clone()))));
    assert_eq!(&output[..item.span.start], &source[..item.span.start]);
    let tail = &source[item.span.end..];
    assert!(output.ends_with(tail));
    let reparsed = Document::parse(output.as_str()).unwrap();
    assert_eq!(reparsed.items.len(), document.items.len());
    assert_eq!(reparsed.named("serif").unwrap().element, Element::Family(replacement));
    for (before, after) in document.items.iter().zip(&reparsed.items) {
        if before.element.name() != Some("serif") {
            assert_eq!(before.element, after.element);
        }
    }
}

#[test]
fn prepend_inserts_before_the_item_with_its_indentation() {
    let document = Document::parse(API37_FALLBACK).unwrap();
    let inserted = Family::new().with(Family::LANG, "ja").with_fonts(vec![Font::new("A.ttf")]);
    let output = document.transform(&mut EditNamed("monospace", Edit::Prepend(vec![Element::Family(inserted.clone())])));
    let reparsed = Document::parse(output.as_str()).unwrap();
    assert_eq!(reparsed.items.len(), document.items.len() + 1);
    let index = reparsed.items.iter().position(|item| item.element.name() == Some("monospace")).unwrap();
    assert_eq!(reparsed.items[index - 1].element, Element::Family(inserted));
    let indent = document.indentation(document.named("monospace").unwrap());
    assert_eq!(reparsed.indentation(&reparsed.items[index - 1]), indent);
    assert_eq!(reparsed.indentation(&reparsed.items[index]), indent);
}

#[test]
fn remove_drops_the_item_and_its_line() {
    let document = Document::parse(API37_FALLBACK).unwrap();
    let output = document.transform(&mut EditNamed("cursive", Edit::Remove));
    let reparsed = Document::parse(output.as_str()).unwrap();
    assert_eq!(reparsed.items.len(), document.items.len() - 1);
    assert!(reparsed.named("cursive").is_none());
    assert!(!output.contains("\n  \n"), "no blank indented line is left");
}

#[test]
fn remove_handles_crlf_line_breaks() {
    let source = "<familyset>\r\n  <family name=\"a\"><font>A.ttf</font></family>\r\n  <family name=\"b\"><font>B.ttf</font></family>\r\n</familyset>\r\n";
    let document = Document::parse(source).unwrap();
    let output = document.transform(&mut EditNamed("a", Edit::Remove));
    assert_eq!(output, "<familyset>\r\n  <family name=\"b\"><font>B.ttf</font></family>\r\n</familyset>\r\n");
}

#[test]
fn attribute_values_with_whitespace_round_trip() {
    let family = Family::new().with(Family::NAME, "tab\there\nline\rend").with_fonts(vec![Font::new("A.ttf")]);
    let xml = format!("<familyset>\n{}\n</familyset>", family.to_xml("  ", "  "));
    assert!(!xml.contains('\t'), "a literal tab would become a space");
    assert_eq!(Document::parse(xml.as_str()).unwrap().items[0].element, Element::Family(family));
}

#[test]
fn serialization_escapes_and_round_trips() {
    let family = Family::new()
        .with(Family::NAME, "a\"<b>&'c")
        .with_fonts(vec![Font::new("x&y.ttf").with(Font::WEIGHT, "700").with_axis(Axis::new("wdth", "87.5")).with_axis(Axis::new("GRAD", "-25"))]);
    let xml = format!("<familyset>\n{}\n</familyset>", family.to_xml("    ", "    "));
    let document = Document::parse(xml.as_str()).unwrap();
    assert_eq!(document.items[0].element, Element::Family(family));
}

#[test]
fn decodes_entities_in_text_and_attributes() {
    let document = Document::parse("<familyset><family name=\"a&amp;b\"><font weight=\"4&#48;0\">x&#x26;y.ttf</font></family></familyset>").unwrap();
    let Element::Family(family) = &document.items[0].element else { panic!() };
    assert_eq!(family.name(), Some("a&b"));
    assert_eq!(family.fonts[0].weight(), Some(400));
    assert_eq!(family.fonts[0].file, "x&y.ttf");
}

#[test]
fn unknown_elements_are_kept_and_skipped() {
    let source = "<familyset>\n  <future-thing a=\"1\"><x/></future-thing>\n  <family name=\"s\"><font>S.ttf<future/></font></family>\n</familyset>\n";
    let document = Document::parse(source).unwrap();
    assert_eq!(document.items[0].element, Element::Other("future-thing".to_string()));
    let Element::Family(family) = &document.items[1].element else { panic!() };
    assert_eq!(family.fonts[0].file, "S.ttf");
    assert_eq!(document.transform(&mut KeepAll), source);
}

#[test]
fn rejects_documents_the_framework_cannot_read() {
    let structure = |source: &str| matches!(Document::parse(source), Err(FontXMLError::Structure(_)));
    let syntax = |source: &str| matches!(Document::parse(source), Err(FontXMLError::Syntax { .. }));
    assert!(structure("<fonts/>"), "unknown root element");
    assert!(structure(""), "no root element");
    assert!(syntax("<familyset></familyset><familyset/>"), "two root elements");
    assert!(syntax("<familyset><family></familyset>"), "mismatched end tag");
    assert!(structure("<!DOCTYPE x [<!ENTITY a \"b\">]><familyset/>"), "DOCTYPE");
    assert!(structure("<familyset><family><font>&custom;</font></family></familyset>"), "unknown entity");
    assert!(structure("<familyset><family><font> </font></family></familyset>"), "font without a file name");
    assert!(structure("<familyset><family><font weight=\"bold\">A.ttf</font></family></familyset>"), "non-numeric weight");
    assert!(structure("<familyset><family><font index=\"x\">A.ttc</font></family></familyset>"), "non-numeric index");
    assert!(structure("<familyset><family><font>A.ttf<axis tag=\"wght\" stylevalue=\"heavy\"/></font></family></familyset>"), "non-numeric axis");
    assert!(structure("<familyset><family name=\"a\" name=\"b\"/></familyset>"), "duplicate attribute");
    assert!(structure("text<familyset/>"), "text outside the root");
}
