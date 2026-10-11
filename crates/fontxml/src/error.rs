use std::fmt;

/// An error raised while reading an Android font configuration document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FontXMLError {
    /// The document is not well-formed XML.
    Syntax { position: u64, message: String },
    /// The document is well-formed, but its structure is not a font configuration.
    Structure(String),
}

impl FontXMLError {
    pub fn structure(reason: impl Into<String>) -> FontXMLError {
        FontXMLError::Structure(reason.into())
    }
}

impl fmt::Display for FontXMLError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FontXMLError::Syntax { position, message } => write!(formatter, "XML syntax error at byte {position}: {message}"),
            FontXMLError::Structure(reason) => write!(formatter, "invalid font configuration: {reason}"),
        }
    }
}

impl std::error::Error for FontXMLError {}
