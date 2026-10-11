//! Reading and rewriting of SFNT font files: OpenType (TrueType or CFF outlines) and
//! font collections (TTC), following the OpenType specification
//! (<https://learn.microsoft.com/typography/opentype/spec/>).
//!
//! The crate reads only the structures it is asked for, so even very large fonts are never
//! loaded into memory as a whole. `FontRebuilder` writes a copy of a font with tables replaced
//! or removed, streaming the unchanged tables.

mod bytes;
mod checksum;
mod directory;
mod error;
mod file;
mod name;
mod rebuilder;
mod tables;
mod tag;

pub use bytes::{ByteReader, ByteWriter};
pub use checksum::Checksum;
pub use directory::{Outline, TableDirectory, TableRecord};
pub use error::SFNTError;
pub use file::{FontFile, FontFormat, TTCHeader};
pub use name::{NameEncoding, NameRecord, NameTable};
pub use rebuilder::{FontRebuilder, TableChange};
pub use tables::{FVARTable, HeadTable, OS2Table, VariationAxis};
pub use tag::Tag;
