use std::fmt;

/// A four-byte OpenType tag, such as a table tag or a variation axis tag.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Tag(pub [u8; 4]);

impl Tag {
    pub const HEAD: Tag = Tag(*b"head");
    pub const NAME: Tag = Tag(*b"name");
    pub const FVAR: Tag = Tag(*b"fvar");
    pub const OS2: Tag = Tag(*b"OS/2");
    pub const DSIG: Tag = Tag(*b"DSIG");
    pub const CFF: Tag = Tag(*b"CFF ");
    pub const CFF2: Tag = Tag(*b"CFF2");
    pub const GLYF: Tag = Tag(*b"glyf");

    /// Parses a tag from text. Tags shorter than four characters are padded with spaces,
    /// as the OpenType specification requires.
    pub fn parse(text: &str) -> Option<Tag> {
        let bytes = text.as_bytes();
        if bytes.is_empty() || bytes.len() > 4 || !bytes.iter().all(|byte| (0x20..=0x7E).contains(byte)) {
            return None;
        }
        let mut tag = [b' '; 4];
        tag[..bytes.len()].copy_from_slice(bytes);
        Some(Tag(tag))
    }

    /// Returns the tag as text, or `None` if it contains bytes outside printable ASCII.
    pub fn text(&self) -> Option<&str> {
        if self.0.iter().all(|byte| (0x20..=0x7E).contains(byte)) { std::str::from_utf8(&self.0).ok() } else { None }
    }
}

impl fmt::Display for Tag {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.text() {
            Some(text) => formatter.write_str(text),
            None => write!(formatter, "0x{:08X}", u32::from_be_bytes(self.0)),
        }
    }
}

impl fmt::Debug for Tag {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "Tag({self})")
    }
}
