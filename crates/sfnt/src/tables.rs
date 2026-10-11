use crate::{ByteReader, SFNTError, Tag};

/// One axis of the `fvar` table.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct VariationAxis {
    pub tag: Tag,
    pub minimum: f32,
    pub default: f32,
    pub maximum: f32,
    pub flags: u16,
    pub name_id: u16,
}

impl VariationAxis {
    pub const WEIGHT: Tag = Tag(*b"wght");
    pub const WIDTH: Tag = Tag(*b"wdth");
    pub const ITALIC: Tag = Tag(*b"ital");
    pub const SLANT: Tag = Tag(*b"slnt");
    pub const OPTICAL_SIZE: Tag = Tag(*b"opsz");

    /// The "hidden axis" flag: the axis should not be exposed in user interfaces.
    pub const HIDDEN: u16 = 0x0001;

    pub fn contains(&self, value: f32) -> bool {
        self.minimum <= value && value <= self.maximum
    }

    pub fn clamp(&self, value: f32) -> f32 {
        value.clamp(self.minimum, self.maximum)
    }
}

/// The font variations table (`fvar`), version 1.
#[derive(Clone, Debug, PartialEq)]
pub struct FVARTable {
    pub major_version: u16,
    pub minor_version: u16,
    pub axes: Vec<VariationAxis>,
    pub instance_count: u16,
}

impl FVARTable {
    pub const AXIS_RECORD_SIZE: usize = 20;

    pub fn parse(data: &[u8]) -> Result<FVARTable, SFNTError> {
        let mut cursor = ByteReader::new(data);
        let major_version = cursor.u16()?;
        let minor_version = cursor.u16()?;
        if major_version != 1 {
            return Err(SFNTError::unsupported(format!("unknown fvar version {major_version}.{minor_version}")));
        }
        let axes_offset = usize::from(cursor.u16()?);
        cursor.u16()?;
        let axis_count = cursor.u16()?;
        let axis_size = usize::from(cursor.u16()?);
        let instance_count = cursor.u16()?;
        if axis_count > 0 && axis_size < FVARTable::AXIS_RECORD_SIZE {
            return Err(SFNTError::malformed(format!("fvar axis record size {axis_size} is too small")));
        }
        let mut axes = Vec::with_capacity(usize::from(axis_count));
        for index in 0..usize::from(axis_count) {
            let mut record = ByteReader::at(data, axes_offset + index * axis_size)?;
            let axis = VariationAxis {
                tag: record.tag()?,
                minimum: record.fixed()?,
                default: record.fixed()?,
                maximum: record.fixed()?,
                flags: record.u16()?,
                name_id: record.u16()?,
            };
            if !(axis.minimum <= axis.default && axis.default <= axis.maximum) {
                return Err(SFNTError::malformed(format!("fvar axis {} has an invalid range", axis.tag)));
            }
            axes.push(axis);
        }
        Ok(FVARTable { major_version, minor_version, axes, instance_count })
    }

    pub fn axis(&self, tag: Tag) -> Option<&VariationAxis> {
        self.axes.iter().find(|axis| axis.tag == tag)
    }
}

/// The fields of the `OS/2` table that describe the style of a face.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OS2Table {
    pub version: u16,
    pub weight_class: u16,
    pub width_class: u16,
    pub fs_selection: u16,
}

impl OS2Table {
    pub const ITALIC: u16 = 0x0001;
    pub const BOLD: u16 = 0x0020;
    pub const REGULAR: u16 = 0x0040;
    pub const OBLIQUE: u16 = 0x0200;
    /// The offset of `fsSelection`; the table must be at least this long plus two bytes.
    pub const FS_SELECTION_OFFSET: usize = 62;

    pub fn parse(data: &[u8]) -> Result<OS2Table, SFNTError> {
        let mut cursor = ByteReader::new(data);
        let version = cursor.u16()?;
        cursor.i16()?;
        let weight_class = cursor.u16()?;
        let width_class = cursor.u16()?;
        cursor.seek(OS2Table::FS_SELECTION_OFFSET)?;
        let fs_selection = cursor.u16()?;
        Ok(OS2Table { version, weight_class, width_class, fs_selection })
    }

    pub fn is_italic(&self) -> bool {
        self.fs_selection & (OS2Table::ITALIC | OS2Table::OBLIQUE) != 0
    }
}

/// The fields of the font header table (`head`) used by this crate.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HeadTable {
    pub major_version: u16,
    pub minor_version: u16,
    pub font_revision: u32,
    pub checksum_adjustment: u32,
    pub units_per_em: u16,
    pub mac_style: u16,
}

impl HeadTable {
    pub const MAGIC_NUMBER: u32 = 0x5F0F_3CF5;
    pub const CHECKSUM_ADJUSTMENT_OFFSET: usize = 8;
    /// The constant that the whole-font checksum plus `checksumAdjustment` must equal.
    pub const CHECKSUM_TARGET: u32 = 0xB1B0_AFBA;
    pub const MAC_STYLE_ITALIC: u16 = 0x0002;
    pub const SIZE: usize = 54;

    pub fn parse(data: &[u8]) -> Result<HeadTable, SFNTError> {
        if data.len() < HeadTable::SIZE {
            return Err(SFNTError::malformed("head table is truncated"));
        }
        let mut cursor = ByteReader::new(data);
        let major_version = cursor.u16()?;
        let minor_version = cursor.u16()?;
        if major_version != 1 {
            return Err(SFNTError::unsupported(format!("unknown head version {major_version}.{minor_version}")));
        }
        let font_revision = cursor.u32()?;
        let checksum_adjustment = cursor.u32()?;
        if cursor.u32()? != HeadTable::MAGIC_NUMBER {
            return Err(SFNTError::malformed("head table has a wrong magic number"));
        }
        cursor.u16()?;
        let units_per_em = cursor.u16()?;
        cursor.seek(44)?;
        let mac_style = cursor.u16()?;
        Ok(HeadTable { major_version, minor_version, font_revision, checksum_adjustment, units_per_em, mac_style })
    }

    pub fn is_italic(&self) -> bool {
        self.mac_style & HeadTable::MAC_STYLE_ITALIC != 0
    }
}
