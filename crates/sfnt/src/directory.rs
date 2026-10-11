use std::io::{Read, Seek, SeekFrom};

use crate::{ByteReader, ByteWriter, SFNTError, Tag};

/// One entry of a table directory: where a table is stored and its checksum.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TableRecord {
    pub tag: Tag,
    pub checksum: u32,
    pub offset: u32,
    pub length: u32,
}

impl TableRecord {
    pub const SIZE: usize = 16;

    /// Reads the table data. The record must already be validated against the file length,
    /// which `FontFile::read` does for every record it returns.
    pub fn read<R: Read + Seek>(&self, reader: &mut R) -> Result<Vec<u8>, SFNTError> {
        reader.seek(SeekFrom::Start(u64::from(self.offset)))?;
        let mut data = vec![0; self.length as usize];
        reader.read_exact(&mut data)?;
        Ok(data)
    }

    pub fn end(&self) -> u64 {
        u64::from(self.offset) + u64::from(self.length)
    }
}

/// The outline format of a font, as declared by its `sfntVersion`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outline {
    /// TrueType outlines (`0x00010000`, or Apple's `'true'`).
    TrueType,
    /// CFF or CFF2 outlines (`'OTTO'`).
    CFF,
}

impl Outline {
    pub const TRUETYPE_VERSION: u32 = 0x0001_0000;
    pub const APPLE_TRUETYPE_VERSION: u32 = u32::from_be_bytes(*b"true");
    pub const CFF_VERSION: u32 = u32::from_be_bytes(*b"OTTO");

    pub fn of(sfnt_version: u32) -> Option<Outline> {
        match sfnt_version {
            Outline::TRUETYPE_VERSION | Outline::APPLE_TRUETYPE_VERSION => Some(Outline::TrueType),
            Outline::CFF_VERSION => Some(Outline::CFF),
            _ => None,
        }
    }
}

/// The table directory of a single font face (the OpenType "TableDirectory").
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableDirectory {
    pub sfnt_version: u32,
    pub tables: Vec<TableRecord>,
}

impl TableDirectory {
    pub const HEADER_SIZE: usize = 12;
    /// The largest table count accepted when reading, to bound memory use on malformed input.
    pub const MAX_TABLES: u16 = 1024;

    /// Reads a table directory that starts at `offset`.
    pub fn read<R: Read + Seek>(reader: &mut R, offset: u64) -> Result<TableDirectory, SFNTError> {
        reader.seek(SeekFrom::Start(offset))?;
        let mut header = [0; TableDirectory::HEADER_SIZE];
        reader.read_exact(&mut header).map_err(|_| SFNTError::malformed("table directory is truncated"))?;
        let mut cursor = ByteReader::new(&header);
        let sfnt_version = cursor.u32()?;
        if Outline::of(sfnt_version).is_none() {
            return Err(SFNTError::unsupported(format!("unknown sfntVersion 0x{sfnt_version:08X}")));
        }
        let count = cursor.u16()?;
        if count == 0 || count > TableDirectory::MAX_TABLES {
            return Err(SFNTError::malformed(format!("invalid table count {count}")));
        }
        let mut records = vec![0; usize::from(count) * TableRecord::SIZE];
        reader.read_exact(&mut records).map_err(|_| SFNTError::malformed("table records are truncated"))?;
        let mut cursor = ByteReader::new(&records);
        let mut tables = Vec::with_capacity(usize::from(count));
        for _ in 0..count {
            tables.push(TableRecord { tag: cursor.tag()?, checksum: cursor.u32()?, offset: cursor.u32()?, length: cursor.u32()? });
        }
        Ok(TableDirectory { sfnt_version, tables })
    }

    pub fn outline(&self) -> Outline {
        Outline::of(self.sfnt_version).unwrap_or(Outline::TrueType)
    }

    pub fn find(&self, tag: Tag) -> Option<&TableRecord> {
        self.tables.iter().find(|record| record.tag == tag)
    }

    /// Reads the data of the table `tag`, or returns `None` if the face has no such table.
    pub fn read_table<R: Read + Seek>(&self, reader: &mut R, tag: Tag) -> Result<Option<Vec<u8>>, SFNTError> {
        match self.find(tag) {
            Some(record) => Ok(Some(record.read(reader)?)),
            None => Ok(None),
        }
    }

    pub fn size(&self) -> usize {
        TableDirectory::HEADER_SIZE + self.tables.len() * TableRecord::SIZE
    }

    /// Serializes the directory. The binary search fields are derived from the table count,
    /// and the records are written in ascending tag order, as the specification requires.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut tables = self.tables.clone();
        tables.sort_by_key(|record| record.tag);
        let count = tables.len() as u16;
        let entry_selector = if count == 0 { 0 } else { 15 - count.leading_zeros() as u16 };
        let search_range = (1u16 << entry_selector) * 16;
        let mut writer = ByteWriter::with_capacity(self.size());
        writer.u32(self.sfnt_version);
        writer.u16(count);
        writer.u16(search_range);
        writer.u16(entry_selector);
        writer.u16(count * 16 - search_range);
        for record in &tables {
            writer.tag(record.tag);
            writer.u32(record.checksum);
            writer.u32(record.offset);
            writer.u32(record.length);
        }
        writer.into_bytes()
    }
}
