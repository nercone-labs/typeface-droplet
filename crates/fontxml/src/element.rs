use quick_xml::escape::escape;

use crate::{Attributes, FontXMLError, XMLNode};

/// A variation axis setting of a font (`<axis tag="wght" stylevalue="400"/>`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Axis {
    pub tag: String,
    pub style_value: String,
}

impl Axis {
    pub const ELEMENT: &'static str = "axis";

    pub fn new(tag: impl Into<String>, style_value: impl Into<String>) -> Axis {
        Axis { tag: tag.into(), style_value: style_value.into() }
    }

    fn from_node(node: &XMLNode) -> Result<Axis, FontXMLError> {
        let tag = node.attribute("tag").ok_or_else(|| FontXMLError::structure("<axis> has no tag attribute"))?;
        let value = node.attribute("stylevalue").ok_or_else(|| FontXMLError::structure("<axis> has no stylevalue attribute"))?;
        if value.parse::<f32>().is_err() {
            return Err(FontXMLError::structure(format!("<axis tag=\"{tag}\"> has an invalid stylevalue \"{value}\"")));
        }
        Ok(Axis::new(tag, value))
    }

    pub fn to_xml(&self) -> String {
        format!("<axis tag=\"{}\" stylevalue=\"{}\"/>", escape(self.tag.as_str()), escape(self.style_value.as_str()))
    }
}

/// A font file reference (`<font>`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Font {
    pub attributes: Attributes,
    /// The file name, relative to the font directory of the document.
    pub file: String,
    pub axes: Vec<Axis>,
}

impl Font {
    pub const ELEMENT: &'static str = "font";
    pub const WEIGHT: &'static str = "weight";
    pub const STYLE: &'static str = "style";
    pub const INDEX: &'static str = "index";
    pub const FALLBACK_FOR: &'static str = "fallbackFor";
    pub const POSTSCRIPT_NAME: &'static str = "postScriptName";
    pub const SUPPORTED_AXES: &'static str = "supportedAxes";
    pub const STYLE_NORMAL: &'static str = "normal";
    pub const STYLE_ITALIC: &'static str = "italic";

    pub fn new(file: impl Into<String>) -> Font {
        Font { attributes: Attributes::new(), file: file.into(), axes: Vec::new() }
    }

    fn from_node(node: &XMLNode) -> Result<Font, FontXMLError> {
        let file = node.text.trim_matches([' ', '\n', '\r', '\t']).to_string();
        if file.is_empty() {
            return Err(FontXMLError::structure("<font> has no file name"));
        }
        for (name, value) in [(Font::WEIGHT, node.attribute(Font::WEIGHT)), (Font::INDEX, node.attribute(Font::INDEX))] {
            if let Some(value) = value
                && value.parse::<i32>().is_err()
            {
                return Err(FontXMLError::structure(format!("<font>{file}</font> has an invalid {name} \"{value}\"")));
            }
        }
        let axes = node.children_named(Axis::ELEMENT).map(Axis::from_node).collect::<Result<_, _>>()?;
        Ok(Font { attributes: node.attributes.clone(), file, axes })
    }

    pub fn weight(&self) -> Option<i32> {
        self.attributes.get(Font::WEIGHT).and_then(|value| value.parse().ok())
    }

    pub fn is_italic(&self) -> bool {
        self.attributes.get(Font::STYLE) == Some(Font::STYLE_ITALIC)
    }

    pub fn index(&self) -> i32 {
        self.attributes.get(Font::INDEX).and_then(|value| value.parse().ok()).unwrap_or(0)
    }

    pub fn fallback_for(&self) -> Option<&str> {
        self.attributes.get(Font::FALLBACK_FOR)
    }

    pub fn supported_axes(&self) -> Vec<&str> {
        self.attributes.get(Font::SUPPORTED_AXES).map(|axes| axes.split(',').map(str::trim).collect()).unwrap_or_default()
    }

    pub fn with(mut self, name: &str, value: impl Into<String>) -> Font {
        self.attributes.set(name, value);
        self
    }

    pub fn with_axis(mut self, axis: Axis) -> Font {
        self.axes.push(axis);
        self
    }

    pub fn to_xml(&self, indent: &str, unit: &str) -> String {
        let attributes = self.attributes.to_xml();
        let file = escape(self.file.as_str());
        if self.axes.is_empty() {
            return format!("{indent}<font{attributes}>{file}</font>");
        }
        let inner = format!("{indent}{unit}");
        let axes: String = self.axes.iter().map(|axis| format!("{inner}{}\n", axis.to_xml())).collect();
        format!("{indent}<font{attributes}>\n{inner}{file}\n{axes}{indent}</font>")
    }
}

/// A font family (`<family>`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Family {
    pub attributes: Attributes,
    pub fonts: Vec<Font>,
}

impl Family {
    pub const ELEMENT: &'static str = "family";
    pub const NAME: &'static str = "name";
    pub const LANG: &'static str = "lang";
    pub const VARIANT: &'static str = "variant";
    pub const IGNORE: &'static str = "ignore";
    pub const FALLBACK: &'static str = "fallback";
    pub const CUSTOMIZATION_TYPE: &'static str = "customizationType";
    pub const OPERATION: &'static str = "operation";

    pub fn new() -> Family {
        Family { attributes: Attributes::new(), fonts: Vec::new() }
    }

    fn from_node(node: &XMLNode) -> Result<Family, FontXMLError> {
        let fonts = node.children_named(Font::ELEMENT).map(Font::from_node).collect::<Result<_, _>>()?;
        Ok(Family { attributes: node.attributes.clone(), fonts })
    }

    pub fn name(&self) -> Option<&str> {
        self.attributes.get(Family::NAME)
    }

    /// The comma-separated BCP 47 language tags of the family.
    pub fn languages(&self) -> Vec<&str> {
        self.attributes.get(Family::LANG).map(|lang| lang.split(',').map(str::trim).filter(|tag| !tag.is_empty()).collect()).unwrap_or_default()
    }

    pub fn is_ignored(&self) -> bool {
        matches!(self.attributes.get(Family::IGNORE), Some("true" | "1"))
    }

    pub fn with(mut self, name: &str, value: impl Into<String>) -> Family {
        self.attributes.set(name, value);
        self
    }

    pub fn with_fonts(mut self, fonts: Vec<Font>) -> Family {
        self.fonts = fonts;
        self
    }

    pub fn to_xml(&self, indent: &str, unit: &str) -> String {
        let inner = format!("{indent}{unit}");
        let fonts: String = self.fonts.iter().map(|font| format!("{}\n", font.to_xml(&inner, unit))).collect();
        format!("{indent}<family{}>\n{fonts}{indent}</family>", self.attributes.to_xml())
    }
}

impl Default for Family {
    fn default() -> Family {
        Family::new()
    }
}

/// A named list of families (`<family-list>`), introduced for font_fallback.xml.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FamilyList {
    pub attributes: Attributes,
    pub families: Vec<Family>,
}

impl FamilyList {
    pub const ELEMENT: &'static str = "family-list";

    fn from_node(node: &XMLNode) -> Result<FamilyList, FontXMLError> {
        let families = node.children_named(Family::ELEMENT).map(Family::from_node).collect::<Result<_, _>>()?;
        Ok(FamilyList { attributes: node.attributes.clone(), families })
    }

    pub fn name(&self) -> Option<&str> {
        self.attributes.get(Family::NAME)
    }

    pub fn to_xml(&self, indent: &str, unit: &str) -> String {
        let inner = format!("{indent}{unit}");
        let families: String = self.families.iter().map(|family| format!("{}\n", family.to_xml(&inner, unit))).collect();
        format!("{indent}<family-list{}>\n{families}{indent}</family-list>", self.attributes.to_xml())
    }
}

/// An alias of a named family (`<alias name="..." to="..." weight="..."/>`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Alias {
    pub attributes: Attributes,
}

impl Alias {
    pub const ELEMENT: &'static str = "alias";

    pub fn name(&self) -> Option<&str> {
        self.attributes.get("name")
    }

    pub fn to(&self) -> Option<&str> {
        self.attributes.get("to")
    }

    pub fn weight(&self) -> Option<i32> {
        self.attributes.get("weight").and_then(|value| value.parse().ok())
    }

    pub fn to_xml(&self, indent: &str) -> String {
        format!("{indent}<alias{}/>", self.attributes.to_xml())
    }
}

/// A child element of the document root.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Element {
    Family(Family),
    FamilyList(FamilyList),
    Alias(Alias),
    /// An element this crate does not model. The Android parsers skip such elements.
    Other(String),
}

impl Element {
    pub fn from_node(node: &XMLNode) -> Result<Element, FontXMLError> {
        Ok(match node.name.as_str() {
            Family::ELEMENT => Element::Family(Family::from_node(node)?),
            FamilyList::ELEMENT => Element::FamilyList(FamilyList::from_node(node)?),
            Alias::ELEMENT => Element::Alias(Alias { attributes: node.attributes.clone() }),
            other => Element::Other(other.to_string()),
        })
    }

    /// The `name` attribute of a family, family list or alias.
    pub fn name(&self) -> Option<&str> {
        match self {
            Element::Family(family) => family.name(),
            Element::FamilyList(list) => list.name(),
            Element::Alias(alias) => alias.name(),
            Element::Other(_) => None,
        }
    }

    /// Every font of the element, including the fonts of the families of a family list.
    pub fn fonts(&self) -> Vec<&Font> {
        match self {
            Element::Family(family) => family.fonts.iter().collect(),
            Element::FamilyList(list) => list.families.iter().flat_map(|family| &family.fonts).collect(),
            _ => Vec::new(),
        }
    }

    /// Serializes the element. `indent` is the indentation of the element itself
    /// and `unit` is added for each nesting level.
    pub fn to_xml(&self, indent: &str, unit: &str) -> Option<String> {
        match self {
            Element::Family(family) => Some(family.to_xml(indent, unit)),
            Element::FamilyList(list) => Some(list.to_xml(indent, unit)),
            Element::Alias(alias) => Some(alias.to_xml(indent)),
            Element::Other(_) => None,
        }
    }
}
