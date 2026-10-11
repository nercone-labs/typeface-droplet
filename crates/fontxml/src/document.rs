use std::ops::Range;

use crate::{Attributes, Element, FontXMLError, XMLNode};

/// The root element of a font configuration document.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Root {
    /// `<familyset>`: fonts.xml and font_fallback.xml.
    FamilySet,
    /// `<fonts-modification>`: fonts_customization.xml.
    FontsModification,
}

impl Root {
    pub fn element_name(&self) -> &'static str {
        match self {
            Root::FamilySet => "familyset",
            Root::FontsModification => "fonts-modification",
        }
    }

    pub fn from_element_name(name: &str) -> Option<Root> {
        match name {
            "familyset" => Some(Root::FamilySet),
            "fonts-modification" => Some(Root::FontsModification),
            _ => None,
        }
    }
}

/// A child element of the root, with the byte range it occupies in the source.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub element: Element,
    pub span: Range<usize>,
}

/// How a transformation treats one item.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Edit {
    /// Keep the item exactly as written in the source.
    Keep,
    /// Replace the item with another element.
    Replace(Element),
    /// Insert elements before the item, and keep the item.
    Prepend(Vec<Element>),
    /// Remove the item.
    Remove,
}

/// Decides the edit of each item of a document.
pub trait Transformer {
    fn edit(&mut self, document: &Document, item: &Item) -> Edit;
}

/// A parsed font configuration document that keeps its source text,
/// so that untouched parts, comments and formatting are reproduced byte for byte.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Document {
    pub source: String,
    pub root: Root,
    pub attributes: Attributes,
    pub items: Vec<Item>,
}

impl Document {
    pub fn parse(source: impl Into<String>) -> Result<Document, FontXMLError> {
        let source = source.into();
        let node = XMLNode::parse(&source)?;
        let root = Root::from_element_name(&node.name).ok_or_else(|| FontXMLError::structure(format!("unknown root element <{}>", node.name)))?;
        let items = node
            .children
            .iter()
            .map(|child| Ok(Item { element: Element::from_node(child)?, span: child.span.clone() }))
            .collect::<Result<Vec<_>, FontXMLError>>()?;
        Ok(Document { root, attributes: node.attributes, items, source })
    }

    /// The item whose family, family list or alias has the name `name`.
    pub fn named(&self, name: &str) -> Option<&Item> {
        self.items.iter().find(|item| !matches!(item.element, Element::Alias(_)) && item.element.name() == Some(name))
    }

    /// The whitespace between the start of the line and the item, if the item starts a line.
    pub fn indentation(&self, item: &Item) -> &str {
        let line_start = self.source[..item.span.start].rfind('\n').map_or(0, |index| index + 1);
        let prefix = &self.source[line_start..item.span.start];
        if prefix.chars().all(|character| character == ' ' || character == '\t') { prefix } else { "" }
    }

    /// Produces the source text with the edits of `transformer` applied.
    pub fn transform(&self, transformer: &mut impl Transformer) -> String {
        let mut output = String::with_capacity(self.source.len() + self.source.len() / 4);
        let mut position = 0;
        for item in &self.items {
            let edit = transformer.edit(self, item);
            if edit == Edit::Keep {
                continue;
            }
            let indent = self.indentation(item);
            let unit = if indent.is_empty() { "  " } else { indent };
            let serialize = |element: &Element| element.to_xml(indent, unit).map(|xml| xml[indent.len()..].to_string());
            match edit {
                Edit::Keep => {}
                Edit::Replace(element) => {
                    if let Some(xml) = serialize(&element) {
                        output.push_str(&self.source[position..item.span.start]);
                        output.push_str(&xml);
                        position = item.span.end;
                    }
                }
                Edit::Prepend(elements) => {
                    output.push_str(&self.source[position..item.span.start]);
                    for element in &elements {
                        if let Some(xml) = serialize(element) {
                            output.push_str(&xml);
                            output.push('\n');
                            output.push_str(indent);
                        }
                    }
                    position = item.span.start;
                }
                Edit::Remove => {
                    // The item goes with its indentation and the line break before it (LF or CRLF).
                    let bytes = self.source.as_bytes();
                    let mut start = item.span.start - indent.len();
                    if start > 0 && bytes[start - 1] == b'\n' {
                        start -= 1;
                        if start > 0 && bytes[start - 1] == b'\r' {
                            start -= 1;
                        }
                    }
                    output.push_str(&self.source[position..start.max(position)]);
                    position = item.span.end;
                }
            }
        }
        output.push_str(&self.source[position..]);
        output
    }
}
