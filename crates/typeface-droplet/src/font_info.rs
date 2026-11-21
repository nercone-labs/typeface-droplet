use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};

use serde::Serialize;
use sfnt::{FVARTable, FontFile, HeadTable, NameRecord, NameTable, OS2Table, Outline, SFNTError, TableDirectory, Tag, VariationAxis};
use sha2::{Digest, Sha256};

use crate::FontSource;

/// A reader over the byte range of a `FontSource`. Offsets are relative to the range,
/// so the font parsers see the range as a whole file.
pub struct SourceReader {
    file: File,
    start: u64,
    length: u64,
    position: u64,
}

impl SourceReader {
    pub fn open(source: &FontSource) -> io::Result<SourceReader> {
        let file = File::open(&source.path)?;
        let size = file.metadata()?.len();
        let length = match source.length {
            Some(length) => length,
            None => size.checked_sub(source.offset).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "offset is beyond the file"))?,
        };
        if source.offset.checked_add(length).is_none_or(|end| end > size) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "range is beyond the file"));
        }
        let mut reader = SourceReader { file, start: source.offset, length, position: 0 };
        reader.seek(SeekFrom::Start(0))?;
        Ok(reader)
    }

    pub fn length(&self) -> u64 {
        self.length
    }

    /// The lowercase hexadecimal SHA-256 of the whole range.
    pub fn sha256(&mut self) -> io::Result<String> {
        self.seek(SeekFrom::Start(0))?;
        let mut hasher = Sha256::new();
        let mut buffer = vec![0; 256 * 1024];
        loop {
            let read = self.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            hasher.update(&buffer[..read]);
        }
        self.seek(SeekFrom::Start(0))?;
        Ok(hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect())
    }
}

impl Read for SourceReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let remaining = self.length.saturating_sub(self.position);
        let limit = buffer.len().min(usize::try_from(remaining).unwrap_or(usize::MAX));
        let read = self.file.read(&mut buffer[..limit])?;
        self.position += read as u64;
        Ok(read)
    }
}

impl Seek for SourceReader {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        let target = match position {
            SeekFrom::Start(offset) => Some(offset),
            SeekFrom::End(delta) => self.length.checked_add_signed(delta),
            SeekFrom::Current(delta) => self.position.checked_add_signed(delta),
        }
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek before the start of the range"))?;
        self.file.seek(SeekFrom::Start(self.start + target))?;
        self.position = target;
        Ok(target)
    }
}

/// A variation axis, as shown to users.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AxisInfo {
    pub tag: String,
    pub minimum: f32,
    pub default: f32,
    pub maximum: f32,
    pub hidden: bool,
}

/// The description of one face of a font file.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FaceInfo {
    pub index: u32,
    pub family: Option<String>,
    pub subfamily: Option<String>,
    pub full_name: Option<String>,
    pub postscript_name: Option<String>,
    /// The OS/2 weight class (400 when the face has no OS/2 table).
    pub weight: u16,
    pub italic: bool,
    pub outline: String,
    pub axes: Vec<AxisInfo>,
    pub instances: u16,
}

impl FaceInfo {
    pub fn read<R: Read + Seek>(reader: &mut R, directory: &TableDirectory, index: u32) -> Result<FaceInfo, SFNTError> {
        let name = directory.read_table(reader, Tag::NAME)?.map(|data| NameTable::parse(&data)).transpose()?;
        let os2 = directory.read_table(reader, Tag::OS2)?.map(|data| OS2Table::parse(&data)).transpose()?;
        let head = directory.read_table(reader, Tag::HEAD)?.map(|data| HeadTable::parse(&data)).transpose()?;
        let fvar = directory.read_table(reader, Tag::FVAR)?.map(|data| FVARTable::parse(&data)).transpose()?;
        let text = |id| name.as_ref().and_then(|name| name.text(id));
        Ok(FaceInfo {
            index,
            family: name.as_ref().and_then(NameTable::family),
            subfamily: name.as_ref().and_then(NameTable::subfamily),
            full_name: text(NameRecord::FULL_NAME),
            postscript_name: text(NameRecord::POSTSCRIPT_NAME),
            weight: os2.map_or(400, |os2| os2.weight_class),
            italic: os2.is_some_and(|os2| os2.is_italic()) || head.is_some_and(|head| head.is_italic()),
            outline: match directory.outline() {
                Outline::TrueType => "truetype".to_string(),
                Outline::CFF => "cff".to_string(),
            },
            axes: fvar
                .as_ref()
                .map(|fvar| {
                    fvar.axes
                        .iter()
                        .map(|axis| AxisInfo {
                            tag: axis.tag.to_string(),
                            minimum: axis.minimum,
                            default: axis.default,
                            maximum: axis.maximum,
                            hidden: axis.flags & VariationAxis::HIDDEN != 0,
                        })
                        .collect()
                })
                .unwrap_or_default(),
            instances: fvar.map_or(0, |fvar| fvar.instance_count),
        })
    }

    /// The axis `tag`. Tags are compared as OpenType tags, so `"GRD"` finds the axis `"GRD "`.
    pub fn axis(&self, tag: &str) -> Option<&AxisInfo> {
        let tag = Tag::parse(tag)?;
        self.axes.iter().find(|axis| Tag::parse(&axis.tag) == Some(tag))
    }

    /// The `wght` axis, if the face is a variable font with a weight axis.
    pub fn weight_axis(&self) -> Option<&AxisInfo> {
        self.axis("wght")
    }

    /// The `ital` axis, if it reaches 1 (fully italic).
    pub fn italic_axis(&self) -> Option<&AxisInfo> {
        self.axis("ital").filter(|axis| axis.minimum <= 0.0 && axis.maximum >= 1.0)
    }
}

/// The description of a font file.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FontInfo {
    /// `ttf`, `otf` or `ttc`.
    pub format: String,
    pub size: u64,
    pub faces: Vec<FaceInfo>,
}

impl FontInfo {
    pub fn inspect(source: &FontSource) -> Result<FontInfo, SFNTError> {
        let mut reader = SourceReader::open(source)?;
        FontInfo::read(&mut reader)
    }

    pub fn read(reader: &mut SourceReader) -> Result<FontInfo, SFNTError> {
        let file = FontFile::read(reader)?;
        let faces = file.faces().iter().enumerate().map(|(index, directory)| FaceInfo::read(reader, directory, index as u32)).collect::<Result<_, _>>()?;
        Ok(FontInfo { format: file.format().extension().to_string(), size: reader.length(), faces })
    }
}
