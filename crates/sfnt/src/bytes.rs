use crate::{SFNTError, Tag};

/// A big-endian cursor over a byte slice, as used by every OpenType structure.
pub struct ByteReader<'a> {
    data: &'a [u8],
    position: usize,
}

impl<'a> ByteReader<'a> {
    pub fn new(data: &'a [u8]) -> ByteReader<'a> {
        ByteReader { data, position: 0 }
    }

    pub fn at(data: &'a [u8], position: usize) -> Result<ByteReader<'a>, SFNTError> {
        let mut reader = ByteReader::new(data);
        reader.seek(position)?;
        Ok(reader)
    }

    pub fn position(&self) -> usize {
        self.position
    }

    pub fn seek(&mut self, position: usize) -> Result<(), SFNTError> {
        if position > self.data.len() {
            return Err(SFNTError::malformed("offset points outside of the data"));
        }
        self.position = position;
        Ok(())
    }

    pub fn bytes(&mut self, length: usize) -> Result<&'a [u8], SFNTError> {
        let end = self.position.checked_add(length).filter(|end| *end <= self.data.len()).ok_or_else(|| SFNTError::malformed("data is truncated"))?;
        let bytes = &self.data[self.position..end];
        self.position = end;
        Ok(bytes)
    }

    pub fn u8(&mut self) -> Result<u8, SFNTError> {
        Ok(self.bytes(1)?[0])
    }

    pub fn u16(&mut self) -> Result<u16, SFNTError> {
        let bytes = self.bytes(2)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    pub fn i16(&mut self) -> Result<i16, SFNTError> {
        Ok(self.u16()? as i16)
    }

    pub fn u32(&mut self) -> Result<u32, SFNTError> {
        let bytes = self.bytes(4)?;
        Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    /// Reads a 16.16 signed fixed-point number.
    pub fn fixed(&mut self) -> Result<f32, SFNTError> {
        Ok(self.u32()? as i32 as f32 / 65536.0)
    }

    pub fn tag(&mut self) -> Result<Tag, SFNTError> {
        let bytes = self.bytes(4)?;
        Ok(Tag([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }
}

/// A big-endian byte buffer builder.
#[derive(Default)]
pub struct ByteWriter {
    data: Vec<u8>,
}

impl ByteWriter {
    pub fn new() -> ByteWriter {
        ByteWriter::default()
    }

    pub fn with_capacity(capacity: usize) -> ByteWriter {
        ByteWriter { data: Vec::with_capacity(capacity) }
    }

    pub fn len(&self) -> usize {
        self.data.len()
    }

    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    pub fn u16(&mut self, value: u16) {
        self.data.extend_from_slice(&value.to_be_bytes());
    }

    pub fn u32(&mut self, value: u32) {
        self.data.extend_from_slice(&value.to_be_bytes());
    }

    pub fn tag(&mut self, tag: Tag) {
        self.data.extend_from_slice(&tag.0);
    }

    pub fn bytes(&mut self, bytes: &[u8]) {
        self.data.extend_from_slice(bytes);
    }

    /// Appends zero bytes until the length is a multiple of four.
    pub fn align(&mut self) {
        while !self.data.len().is_multiple_of(4) {
            self.data.push(0);
        }
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.data
    }
}
