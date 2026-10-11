use std::collections::HashMap;

use crate::{ByteReader, ByteWriter, SFNTError};

/// The text encoding of a name record, determined by its platform and encoding IDs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NameEncoding {
    /// UTF-16BE limited to the Basic Multilingual Plane: the Unicode platform encodings 0 to 3
    /// and the Windows Symbol (0) and Unicode BMP (1) encodings.
    UCS2,
    /// UTF-16BE: the Unicode platform encoding 4 and the Windows full Unicode encoding (10).
    UTF16BE,
    /// Mac OS Roman, used by the Macintosh platform with the Roman script.
    MacRoman,
}

impl NameEncoding {
    pub fn of(platform_id: u16, encoding_id: u16) -> Option<NameEncoding> {
        match (platform_id, encoding_id) {
            (NameRecord::PLATFORM_UNICODE, 0..=3) | (NameRecord::PLATFORM_WINDOWS, 0 | 1) => Some(NameEncoding::UCS2),
            (NameRecord::PLATFORM_UNICODE, 4) | (NameRecord::PLATFORM_WINDOWS, 10) => Some(NameEncoding::UTF16BE),
            (NameRecord::PLATFORM_MACINTOSH, 0) => Some(NameEncoding::MacRoman),
            _ => None,
        }
    }

    pub fn decode(&self, bytes: &[u8]) -> Option<String> {
        match self {
            NameEncoding::UCS2 | NameEncoding::UTF16BE => {
                if !bytes.len().is_multiple_of(2) {
                    return None;
                }
                let units = bytes.as_chunks::<2>().0.iter().map(|unit| u16::from_be_bytes([unit[0], unit[1]]));
                char::decode_utf16(units).collect::<Result<String, _>>().ok()
            }
            NameEncoding::MacRoman => {
                Some(bytes.iter().map(|byte| if *byte < 0x80 { char::from(*byte) } else { NameEncoding::MAC_ROMAN_HIGH[usize::from(*byte - 0x80)] }).collect())
            }
        }
    }

    /// Encodes text, or returns `None` if the encoding cannot represent it.
    pub fn encode(&self, text: &str) -> Option<Vec<u8>> {
        match self {
            NameEncoding::UCS2 if text.chars().any(|character| u32::from(character) > 0xFFFF) => None,
            NameEncoding::UCS2 | NameEncoding::UTF16BE => Some(text.encode_utf16().flat_map(u16::to_be_bytes).collect()),
            NameEncoding::MacRoman => text
                .chars()
                .map(|character| {
                    if character.is_ascii() {
                        Some(character as u8)
                    } else {
                        NameEncoding::MAC_ROMAN_HIGH.iter().position(|candidate| *candidate == character).map(|index| 0x80 + index as u8)
                    }
                })
                .collect(),
        }
    }

    /// The characters of Mac OS Roman bytes 0x80 to 0xFF.
    pub const MAC_ROMAN_HIGH: [char; 128] = [
        'Ä', 'Å', 'Ç', 'É', 'Ñ', 'Ö', 'Ü', 'á', 'à', 'â', 'ä', 'ã', 'å', 'ç', 'é', 'è', //
        'ê', 'ë', 'í', 'ì', 'î', 'ï', 'ñ', 'ó', 'ò', 'ô', 'ö', 'õ', 'ú', 'ù', 'û', 'ü', //
        '†', '°', '¢', '£', '§', '•', '¶', 'ß', '®', '©', '™', '´', '¨', '≠', 'Æ', 'Ø', //
        '∞', '±', '≤', '≥', '¥', 'µ', '∂', '∑', '∏', 'π', '∫', 'ª', 'º', 'Ω', 'æ', 'ø', //
        '¿', '¡', '¬', '√', 'ƒ', '≈', '∆', '«', '»', '…', '\u{A0}', 'À', 'Ã', 'Õ', 'Œ', 'œ', //
        '–', '—', '“', '”', '‘', '’', '÷', '◊', 'ÿ', 'Ÿ', '⁄', '€', '‹', '›', 'ﬁ', 'ﬂ', //
        '‡', '·', '‚', '„', '‰', 'Â', 'Ê', 'Á', 'Ë', 'È', 'Í', 'Î', 'Ï', 'Ì', 'Ó', 'Ô', //
        '\u{F8FF}', 'Ò', 'Ú', 'Û', 'Ù', 'ı', 'ˆ', '˜', '¯', '˘', '˙', '˚', '¸', '˝', '˛', 'ˇ',
    ];
}

/// One record of the `name` table.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NameRecord {
    pub platform_id: u16,
    pub encoding_id: u16,
    pub language_id: u16,
    pub name_id: u16,
    pub value: Vec<u8>,
}

impl NameRecord {
    pub const PLATFORM_UNICODE: u16 = 0;
    pub const PLATFORM_MACINTOSH: u16 = 1;
    pub const PLATFORM_WINDOWS: u16 = 3;
    pub const WINDOWS_ENGLISH_US: u16 = 0x0409;

    pub const FAMILY: u16 = 1;
    pub const SUBFAMILY: u16 = 2;
    pub const FULL_NAME: u16 = 4;
    pub const POSTSCRIPT_NAME: u16 = 6;
    pub const TYPOGRAPHIC_FAMILY: u16 = 16;
    pub const TYPOGRAPHIC_SUBFAMILY: u16 = 17;
    pub const WWS_FAMILY: u16 = 21;

    pub fn encoding(&self) -> Option<NameEncoding> {
        NameEncoding::of(self.platform_id, self.encoding_id)
    }

    /// Decodes the value, or returns `None` if its encoding is unknown or the bytes are invalid.
    pub fn text(&self) -> Option<String> {
        self.encoding()?.decode(&self.value)
    }

    pub fn same_language(&self, other: &NameRecord) -> bool {
        self.platform_id == other.platform_id && self.encoding_id == other.encoding_id && self.language_id == other.language_id
    }
}

/// The `name` table (format 0 or 1).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NameTable {
    pub format: u16,
    pub records: Vec<NameRecord>,
    /// Language-tag strings of format 1, stored as UTF-16BE bytes.
    pub language_tags: Vec<Vec<u8>>,
}

impl NameTable {
    pub fn parse(data: &[u8]) -> Result<NameTable, SFNTError> {
        let mut cursor = ByteReader::new(data);
        let format = cursor.u16()?;
        if format > 1 {
            return Err(SFNTError::unsupported(format!("unknown name table format {format}")));
        }
        let count = cursor.u16()?;
        let storage = usize::from(cursor.u16()?);
        let string = |offset: u16, length: u16| -> Result<Vec<u8>, SFNTError> {
            Ok(ByteReader::at(data, storage + usize::from(offset))?.bytes(usize::from(length))?.to_vec())
        };
        let mut records = Vec::with_capacity(usize::from(count));
        for _ in 0..count {
            let platform_id = cursor.u16()?;
            let encoding_id = cursor.u16()?;
            let language_id = cursor.u16()?;
            let name_id = cursor.u16()?;
            let length = cursor.u16()?;
            let offset = cursor.u16()?;
            records.push(NameRecord { platform_id, encoding_id, language_id, name_id, value: string(offset, length)? });
        }
        let mut language_tags = Vec::new();
        if format == 1 {
            let count = cursor.u16()?;
            for _ in 0..count {
                let length = cursor.u16()?;
                let offset = cursor.u16()?;
                language_tags.push(string(offset, length)?);
            }
        }
        Ok(NameTable { format, records, language_tags })
    }

    /// Serializes the table. Records are sorted by platform, encoding, language and name ID,
    /// as the specification requires, and identical strings share storage.
    pub fn to_bytes(&self) -> Result<Vec<u8>, SFNTError> {
        let mut records: Vec<&NameRecord> = self.records.iter().collect();
        records.sort_by_key(|record| (record.platform_id, record.encoding_id, record.language_id, record.name_id));
        let header = 6 + records.len() * 12 + if self.format == 1 { 2 + self.language_tags.len() * 4 } else { 0 };
        // Identical strings share storage; a string is never placed inside another one.
        let mut storage: Vec<u8> = Vec::new();
        let mut placed: HashMap<Vec<u8>, usize> = HashMap::new();
        let mut place = |value: &[u8]| -> Result<(u16, u16), SFNTError> {
            let offset = *placed.entry(value.to_vec()).or_insert_with(|| {
                storage.extend_from_slice(value);
                storage.len() - value.len()
            });
            let length = u16::try_from(value.len()).map_err(|_| SFNTError::malformed("name string is too long"))?;
            let offset = u16::try_from(offset).map_err(|_| SFNTError::malformed("name storage is too large"))?;
            Ok((length, offset))
        };
        let mut writer = ByteWriter::with_capacity(header);
        writer.u16(self.format);
        writer.u16(u16::try_from(records.len()).map_err(|_| SFNTError::malformed("too many name records"))?);
        writer.u16(u16::try_from(header).map_err(|_| SFNTError::malformed("name table header is too large"))?);
        for record in &records {
            let (length, offset) = place(&record.value)?;
            writer.u16(record.platform_id);
            writer.u16(record.encoding_id);
            writer.u16(record.language_id);
            writer.u16(record.name_id);
            writer.u16(length);
            writer.u16(offset);
        }
        if self.format == 1 {
            writer.u16(u16::try_from(self.language_tags.len()).map_err(|_| SFNTError::malformed("too many language tags"))?);
            for tag in &self.language_tags {
                let (length, offset) = place(tag)?;
                writer.u16(length);
                writer.u16(offset);
            }
        }
        writer.bytes(&storage);
        Ok(writer.into_bytes())
    }

    /// Returns the preferred text of `name_id`: Windows US English first, then any other
    /// decodable record in Windows, Unicode, Macintosh order.
    pub fn text(&self, name_id: u16) -> Option<String> {
        let rank = |record: &NameRecord| match (record.platform_id, record.language_id) {
            (NameRecord::PLATFORM_WINDOWS, NameRecord::WINDOWS_ENGLISH_US) => 0,
            (NameRecord::PLATFORM_WINDOWS, _) => 1,
            (NameRecord::PLATFORM_UNICODE, _) => 2,
            _ => 3,
        };
        let mut candidates: Vec<&NameRecord> = self.records.iter().filter(|record| record.name_id == name_id).collect();
        candidates.sort_by_key(|record| rank(record));
        candidates.into_iter().find_map(NameRecord::text)
    }

    /// The family name that applications see: the typographic family (ID 16) if present,
    /// otherwise the legacy family (ID 1).
    pub fn family(&self) -> Option<String> {
        self.text(NameRecord::TYPOGRAPHIC_FAMILY).or_else(|| self.text(NameRecord::FAMILY))
    }

    /// The subfamily (style) name: the typographic subfamily (ID 17) if present, otherwise ID 2.
    pub fn subfamily(&self) -> Option<String> {
        self.text(NameRecord::TYPOGRAPHIC_SUBFAMILY).or_else(|| self.text(NameRecord::SUBFAMILY))
    }

    /// Replaces the family name of the face with `family`.
    ///
    /// - IDs 1, 16 and 21 (family, typographic family, WWS family) become `family`.
    /// - ID 4 (full name) becomes `family` followed by the subfamily name of the same language
    ///   (ID 17, otherwise ID 2), unless that subfamily is "Regular".
    /// - Records of these IDs whose encoding is unknown, or cannot represent the new text,
    ///   are removed, so that no record keeps the old name.
    /// - Every other record, including the PostScript name (ID 6), is left unchanged.
    pub fn set_family(&mut self, family: &str) {
        let original = self.records.clone();
        let subfamily_of = |record: &NameRecord| -> Option<String> {
            let find = |name_id| original.iter().find(|candidate| candidate.name_id == name_id && candidate.same_language(record));
            find(NameRecord::TYPOGRAPHIC_SUBFAMILY).or_else(|| find(NameRecord::SUBFAMILY)).and_then(NameRecord::text)
        };
        self.records = original
            .iter()
            .filter_map(|record| {
                let text = match record.name_id {
                    NameRecord::FAMILY | NameRecord::TYPOGRAPHIC_FAMILY | NameRecord::WWS_FAMILY => family.to_string(),
                    NameRecord::FULL_NAME => match subfamily_of(record) {
                        Some(subfamily) if !subfamily.is_empty() && subfamily != "Regular" => format!("{family} {subfamily}"),
                        _ => family.to_string(),
                    },
                    _ => return Some(record.clone()),
                };
                let value = record.encoding()?.encode(&text)?;
                Some(NameRecord { value, ..record.clone() })
            })
            .collect();
    }
}
