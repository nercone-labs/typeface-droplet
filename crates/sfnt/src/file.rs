use std::io::{Read, Seek, SeekFrom};

use crate::{ByteReader, Outline, SFNTError, TableDirectory};

/// The header of a font collection file (TTC).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TTCHeader {
    pub major_version: u16,
    pub minor_version: u16,
    pub offsets: Vec<u32>,
}

impl TTCHeader {
    pub const TAG: u32 = u32::from_be_bytes(*b"ttcf");
    /// The largest face count accepted when reading, to bound memory use on malformed input.
    pub const MAX_FONTS: u32 = 1024;

    /// The size of the serialized header for `count` faces.
    pub fn size(major_version: u16, count: usize) -> usize {
        let signature = if major_version >= 2 { 12 } else { 0 };
        12 + count * 4 + signature
    }
}

/// The file container format.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FontFormat {
    /// A single face with TrueType outlines.
    TTF,
    /// A single face with CFF outlines.
    OTF,
    /// A collection of faces.
    TTC,
}

impl FontFormat {
    /// The conventional file extension for the format.
    pub fn extension(&self) -> &'static str {
        match self {
            FontFormat::TTF => "ttf",
            FontFormat::OTF => "otf",
            FontFormat::TTC => "ttc",
        }
    }
}

/// An SFNT font file: a single face or a collection of faces.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FontFile {
    Single(TableDirectory),
    Collection(TTCHeader, Vec<TableDirectory>),
}

impl FontFile {
    /// Reads the structure of a font file and checks that every table lies inside the file.
    /// Table contents are not read.
    pub fn read<R: Read + Seek>(reader: &mut R) -> Result<FontFile, SFNTError> {
        let length = reader.seek(SeekFrom::End(0))?;
        reader.seek(SeekFrom::Start(0))?;
        let mut signature = [0; 4];
        reader.read_exact(&mut signature).map_err(|_| SFNTError::malformed("file is too short"))?;
        let file = if u32::from_be_bytes(signature) == TTCHeader::TAG {
            let header = FontFile::read_ttc_header(reader)?;
            let mut faces = Vec::with_capacity(header.offsets.len());
            for offset in &header.offsets {
                faces.push(TableDirectory::read(reader, u64::from(*offset))?);
            }
            FontFile::Collection(header, faces)
        } else {
            FontFile::Single(TableDirectory::read(reader, 0)?)
        };
        for face in file.faces() {
            for record in &face.tables {
                if record.end() > length {
                    return Err(SFNTError::malformed(format!("table {} lies outside of the file", record.tag)));
                }
            }
        }
        Ok(file)
    }

    pub fn read_ttc_header<R: Read + Seek>(reader: &mut R) -> Result<TTCHeader, SFNTError> {
        let mut fixed = [0; 8];
        reader.read_exact(&mut fixed).map_err(|_| SFNTError::malformed("TTC header is truncated"))?;
        let mut cursor = ByteReader::new(&fixed);
        let major_version = cursor.u16()?;
        let minor_version = cursor.u16()?;
        if major_version != 1 && major_version != 2 {
            return Err(SFNTError::unsupported(format!("unknown TTC version {major_version}.{minor_version}")));
        }
        let count = cursor.u32()?;
        if count == 0 || count > TTCHeader::MAX_FONTS {
            return Err(SFNTError::malformed(format!("invalid TTC face count {count}")));
        }
        let mut offsets = vec![0; count as usize * 4];
        reader.read_exact(&mut offsets).map_err(|_| SFNTError::malformed("TTC header is truncated"))?;
        let mut cursor = ByteReader::new(&offsets);
        let offsets = (0..count).map(|_| cursor.u32()).collect::<Result<Vec<_>, _>>()?;
        Ok(TTCHeader { major_version, minor_version, offsets })
    }

    pub fn faces(&self) -> &[TableDirectory] {
        match self {
            FontFile::Single(face) => std::slice::from_ref(face),
            FontFile::Collection(_, faces) => faces,
        }
    }

    pub fn format(&self) -> FontFormat {
        match self {
            FontFile::Single(face) if face.outline() == Outline::CFF => FontFormat::OTF,
            FontFile::Single(_) => FontFormat::TTF,
            FontFile::Collection(..) => FontFormat::TTC,
        }
    }
}
