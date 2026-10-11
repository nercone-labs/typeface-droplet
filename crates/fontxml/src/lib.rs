//! Reading and editing of the Android font configuration documents:
//! `fonts.xml`, `font_fallback.xml` (both `<familyset>`) and `fonts_customization.xml`
//! (`<fonts-modification>`), as parsed by `android.graphics.FontListParser` and
//! `android.graphics.fonts.FontCustomizationParser`.
//!
//! Documents are edited losslessly: every element that a transformation keeps is copied from
//! the source byte for byte, together with comments and formatting.

mod document;
mod element;
mod error;
mod node;

pub use document::{Document, Edit, Item, Root, Transformer};
pub use element::{Alias, Axis, Element, Family, FamilyList, Font};
pub use error::FontXMLError;
pub use node::{Attributes, XMLNode};
