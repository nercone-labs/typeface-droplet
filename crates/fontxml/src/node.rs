use std::ops::Range;

use quick_xml::XmlVersion;
use quick_xml::escape::{escape, resolve_predefined_entity};
use quick_xml::events::{BytesStart, Event};
use quick_xml::reader::Reader;

use crate::FontXMLError;

/// The attributes of an element, in document order.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Attributes(pub Vec<(String, String)>);

impl Attributes {
    pub fn new() -> Attributes {
        Attributes::default()
    }

    pub fn get(&self, name: &str) -> Option<&str> {
        self.0.iter().find(|(key, _)| key == name).map(|(_, value)| value.as_str())
    }

    /// Sets an attribute, keeping its position if it already exists.
    pub fn set(&mut self, name: &str, value: impl Into<String>) {
        let value = value.into();
        match self.0.iter_mut().find(|(key, _)| key == name) {
            Some(entry) => entry.1 = value,
            None => self.0.push((name.to_string(), value)),
        }
    }

    pub fn remove(&mut self, name: &str) -> Option<String> {
        let index = self.0.iter().position(|(key, _)| key == name)?;
        Some(self.0.remove(index).1)
    }

    /// Serializes the attributes as ` name="value"` pairs with XML escaping. Tabs and line
    /// breaks are written as character references, because a parser normalizes literal ones
    /// in attribute values to spaces.
    pub fn to_xml(&self) -> String {
        self.0.iter().map(|(key, value)| format!(" {key}=\"{}\"", Attributes::escape(value))).collect()
    }

    /// Escapes an attribute value so that a conforming parser reads back exactly `value`.
    pub fn escape(value: &str) -> String {
        escape(value).replace('\t', "&#9;").replace('\n', "&#10;").replace('\r', "&#13;")
    }
}

/// A generic XML element: its name, attributes, child elements, concatenated text content,
/// and the byte range it occupies in the source document.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct XMLNode {
    pub name: String,
    pub attributes: Attributes,
    pub children: Vec<XMLNode>,
    pub text: String,
    pub span: Range<usize>,
}

impl XMLNode {
    /// Parses a complete document and returns its root element.
    ///
    /// The document must be well-formed: exactly one root element, matching end tags,
    /// and only predefined or character entity references.
    pub fn parse(source: &str) -> Result<XMLNode, FontXMLError> {
        let mut reader = Reader::from_str(source);
        let config = reader.config_mut();
        config.check_end_names = true;
        config.check_comments = true;
        let mut stack: Vec<XMLNode> = Vec::new();
        let mut root: Option<XMLNode> = None;
        loop {
            let start = reader.buffer_position() as usize;
            let syntax =
                |error: &dyn std::fmt::Display, reader: &Reader<&[u8]>| FontXMLError::Syntax { position: reader.error_position(), message: error.to_string() };
            let event = reader.read_event().map_err(|error| syntax(&error, &reader))?;
            let end = reader.buffer_position() as usize;
            match event {
                Event::Start(tag) | Event::Empty(tag) if root.is_some() && stack.is_empty() => {
                    return Err(FontXMLError::Syntax { position: start as u64, message: format!("second root element <{}>", tag.name().as_ref()) });
                }
                Event::Start(tag) => stack.push(XMLNode::open(&tag, start)?),
                Event::Empty(tag) => {
                    let mut node = XMLNode::open(&tag, start)?;
                    node.span.end = end;
                    XMLNode::attach(node, &mut stack, &mut root);
                }
                Event::End(_) => {
                    let mut node = stack.pop().ok_or_else(|| FontXMLError::structure("unexpected end tag"))?;
                    node.span.end = end;
                    XMLNode::attach(node, &mut stack, &mut root);
                }
                Event::Text(text) => {
                    let content = text.xml_content(XmlVersion::Implicit1_0);
                    match stack.last_mut() {
                        Some(node) => node.text.push_str(&content),
                        None if content.trim().is_empty() => {}
                        None => return Err(FontXMLError::structure("text outside of the root element")),
                    }
                }
                Event::CData(data) => match stack.last_mut() {
                    Some(node) => node.text.push_str(&data.xml_content(XmlVersion::Implicit1_0)),
                    None => return Err(FontXMLError::structure("CDATA outside of the root element")),
                },
                Event::GeneralRef(reference) => {
                    let resolved = match reference.resolve_char_ref() {
                        Ok(Some(character)) => character.to_string(),
                        Ok(None) => {
                            let name: &str = &reference;
                            resolve_predefined_entity(name).map(str::to_string).ok_or_else(|| FontXMLError::structure(format!("unknown entity &{name};")))?
                        }
                        Err(error) => return Err(syntax(&error, &reader)),
                    };
                    match stack.last_mut() {
                        Some(node) => node.text.push_str(&resolved),
                        None => return Err(FontXMLError::structure("entity outside of the root element")),
                    }
                }
                Event::DocType(_) => return Err(FontXMLError::structure("DOCTYPE declarations are not allowed")),
                Event::Decl(_) | Event::PI(_) | Event::Comment(_) => {}
                Event::Eof => break,
            }
        }
        if !stack.is_empty() {
            return Err(FontXMLError::structure("document ends inside an element"));
        }
        root.ok_or_else(|| FontXMLError::structure("document has no root element"))
    }

    pub fn open(tag: &BytesStart, start: usize) -> Result<XMLNode, FontXMLError> {
        let name = tag.name().as_ref().to_string();
        let mut attributes = Attributes::new();
        for attribute in tag.attributes() {
            let attribute = attribute.map_err(|error| FontXMLError::structure(format!("invalid attribute in <{name}>: {error}")))?;
            let key = attribute.key.as_ref().to_string();
            let value = attribute
                .normalized_value(XmlVersion::Implicit1_0)
                .map_err(|error| FontXMLError::structure(format!("invalid value of {key} in <{name}>: {error}")))?;
            if attributes.get(&key).is_some() {
                return Err(FontXMLError::structure(format!("duplicate attribute {key} in <{name}>")));
            }
            attributes.0.push((key, value.into_owned()));
        }
        Ok(XMLNode { name, attributes, children: Vec::new(), text: String::new(), span: start..start })
    }

    pub fn attach(node: XMLNode, stack: &mut [XMLNode], root: &mut Option<XMLNode>) {
        match stack.last_mut() {
            Some(parent) => parent.children.push(node),
            None => *root = Some(node),
        }
    }

    pub fn attribute(&self, name: &str) -> Option<&str> {
        self.attributes.get(name)
    }

    pub fn children_named<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a XMLNode> + 'a {
        self.children.iter().filter(move |child| child.name == name)
    }
}
