use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom, Write};

use crate::{ByteWriter, Checksum, FontFile, HeadTable, SFNTError, TTCHeader, TableDirectory, TableRecord, Tag};

/// A change to one table of one face.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TableChange {
    /// Store this data as the table, adding the table if the face lacks it.
    Replace(Vec<u8>),
    /// Drop the table from the face.
    Remove,
}

/// Writes a copy of a font file with some tables replaced or removed.
///
/// Unchanged tables are streamed from the source, so memory use does not depend on the font size.
/// The output follows the OpenType layout rules: records are sorted by tag, every table starts
/// on a four-byte boundary and is padded with zeros, tables shared between faces of a collection
/// are stored once, and every table checksum is recomputed. For a single-face file the `head`
/// table's `checksumAdjustment` is recomputed as well; in a collection it is left as it is,
/// because one `head` table may be shared by several faces (its table checksum is still
/// computed as the specification defines it, with `checksumAdjustment` taken as zero).
pub struct FontRebuilder<'a> {
    file: &'a FontFile,
    changes: HashMap<(usize, Tag), TableChange>,
}

enum Block<'a> {
    Source { offset: u32, length: u32 },
    Data(&'a [u8]),
}

impl<'a> FontRebuilder<'a> {
    const COPY_BUFFER_SIZE: usize = 64 * 1024;

    pub fn new(file: &'a FontFile) -> FontRebuilder<'a> {
        FontRebuilder { file, changes: HashMap::new() }
    }

    pub fn replace(&mut self, face: usize, tag: Tag, data: Vec<u8>) -> &mut FontRebuilder<'a> {
        self.changes.insert((face, tag), TableChange::Replace(data));
        self
    }

    pub fn remove(&mut self, face: usize, tag: Tag) -> &mut FontRebuilder<'a> {
        self.changes.insert((face, tag), TableChange::Remove);
        self
    }

    /// Writes the rebuilt file to `target`. `source` must be the file this rebuilder was created from.
    pub fn write<R: Read + Seek, W: Write + Seek>(&self, source: &mut R, target: &mut W) -> Result<(), SFNTError> {
        for (face, _) in self.changes.keys() {
            if *face >= self.file.faces().len() {
                return Err(SFNTError::malformed(format!("face {face} does not exist")));
            }
        }
        let single = matches!(self.file, FontFile::Single(_));
        let head_data = if single { self.zeroed_head(source)? } else { None };

        // Plan the blocks of table data and which block each record of each face uses.
        let mut blocks: Vec<(u64, Block)> = Vec::new();
        let mut heads: Vec<usize> = Vec::new();
        let mut shared: HashMap<(u32, u32), usize> = HashMap::new();
        let mut layouts: Vec<Vec<(Tag, usize)>> = Vec::new();
        for (index, face) in self.file.faces().iter().enumerate() {
            let mut layout = Vec::new();
            for record in &face.tables {
                let block = match (self.changes.get(&(index, record.tag)), head_data.as_deref()) {
                    (Some(TableChange::Remove), _) => continue,
                    (_, Some(head)) if record.tag == Tag::HEAD => {
                        blocks.push((u64::from(record.offset), Block::Data(head)));
                        blocks.len() - 1
                    }
                    (Some(TableChange::Replace(data)), _) => {
                        blocks.push((u64::from(record.offset), Block::Data(data)));
                        blocks.len() - 1
                    }
                    (None, _) => *shared.entry((record.offset, record.length)).or_insert_with(|| {
                        blocks.push((u64::from(record.offset), Block::Source { offset: record.offset, length: record.length }));
                        blocks.len() - 1
                    }),
                };
                if record.tag == Tag::HEAD {
                    heads.push(block);
                }
                layout.push((record.tag, block));
            }
            // Added tables, in tag order so that the output does not depend on hashing.
            let mut added: Vec<(Tag, &Vec<u8>)> = self
                .changes
                .iter()
                .filter_map(|((target_face, tag), change)| match change {
                    TableChange::Replace(data) if *target_face == index && face.find(*tag).is_none() => Some((*tag, data)),
                    _ => None,
                })
                .collect();
            added.sort_by_key(|(tag, _)| *tag);
            for (tag, data) in added {
                let data: &[u8] = match head_data.as_deref() {
                    Some(head) if tag == Tag::HEAD => head,
                    _ => data,
                };
                blocks.push((u64::MAX, Block::Data(data)));
                if tag == Tag::HEAD {
                    heads.push(blocks.len() - 1);
                }
                layout.push((tag, blocks.len() - 1));
            }
            if layout.len() > usize::from(TableDirectory::MAX_TABLES) {
                return Err(SFNTError::malformed(format!("face {index} would have more than {} tables", TableDirectory::MAX_TABLES)));
            }
            layout.sort_by_key(|(tag, _)| *tag);
            layouts.push(layout);
        }

        // Assign offsets: headers first, then blocks in their original order.
        let header_size = match self.file {
            FontFile::Single(_) => 0,
            FontFile::Collection(header, faces) => TTCHeader::size(header.major_version, faces.len()),
        };
        let directory_sizes: Vec<usize> = layouts.iter().map(|layout| TableDirectory::HEADER_SIZE + layout.len() * TableRecord::SIZE).collect();
        let headers_end = header_size + directory_sizes.iter().sum::<usize>();
        let mut order: Vec<usize> = (0..blocks.len()).collect();
        order.sort_by_key(|index| (blocks[*index].0, *index));
        let mut offsets = vec![0u32; blocks.len()];
        let mut lengths = vec![0u32; blocks.len()];
        let mut position = headers_end as u64;
        for index in &order {
            let length = match &blocks[*index].1 {
                Block::Source { length, .. } => u64::from(*length),
                Block::Data(data) => data.len() as u64,
            };
            offsets[*index] = u32::try_from(position).map_err(|_| SFNTError::malformed("font is larger than 4 GiB"))?;
            lengths[*index] = u32::try_from(length).map_err(|_| SFNTError::malformed("table is larger than 4 GiB"))?;
            position += length.next_multiple_of(4);
        }
        u32::try_from(position).map_err(|_| SFNTError::malformed("font is larger than 4 GiB"))?;

        // Write the table data, computing checksums on the way.
        target.seek(SeekFrom::Start(headers_end as u64))?;
        let mut checksums = vec![0u32; blocks.len()];
        let mut buffer = vec![0; FontRebuilder::COPY_BUFFER_SIZE];
        for index in &order {
            let mut checksum = Checksum::new();
            let length = match &blocks[*index].1 {
                Block::Source { offset, length } => {
                    source.seek(SeekFrom::Start(u64::from(*offset)))?;
                    let mut remaining = *length as usize;
                    while remaining > 0 {
                        let chunk = &mut buffer[..remaining.min(FontRebuilder::COPY_BUFFER_SIZE)];
                        source.read_exact(chunk)?;
                        checksum.update(chunk);
                        target.write_all(chunk)?;
                        remaining -= chunk.len();
                    }
                    *length as usize
                }
                Block::Data(data) => {
                    checksum.update(data);
                    target.write_all(data)?;
                    data.len()
                }
            };
            target.write_all(&[0; 3][..length.next_multiple_of(4) - length])?;
            checksums[*index] = checksum.value();
            // The checksum of head is defined with checksumAdjustment (bytes 8 to 11, one
            // aligned word) set to zero; removing that word from the sum gives exactly that.
            if heads.contains(index) && length >= HeadTable::CHECKSUM_ADJUSTMENT_OFFSET + 4 {
                let mut adjustment = [0; 4];
                match &blocks[*index].1 {
                    Block::Source { offset, .. } => {
                        source.seek(SeekFrom::Start(u64::from(*offset) + HeadTable::CHECKSUM_ADJUSTMENT_OFFSET as u64))?;
                        source.read_exact(&mut adjustment)?;
                    }
                    Block::Data(data) => adjustment.copy_from_slice(&data[HeadTable::CHECKSUM_ADJUSTMENT_OFFSET..HeadTable::CHECKSUM_ADJUSTMENT_OFFSET + 4]),
                }
                checksums[*index] = checksums[*index].wrapping_sub(u32::from_be_bytes(adjustment));
            }
        }

        // Write the headers.
        let mut headers = ByteWriter::with_capacity(headers_end);
        if let FontFile::Collection(header, _) = self.file {
            headers.u32(TTCHeader::TAG);
            headers.u16(header.major_version);
            headers.u16(header.minor_version);
            headers.u32(layouts.len() as u32);
            let mut offset = header_size;
            for size in &directory_sizes {
                headers.u32(offset as u32);
                offset += size;
            }
            if header.major_version >= 2 {
                // The digital signature of the collection does not match the new data.
                headers.u32(0);
                headers.u32(0);
                headers.u32(0);
            }
        }
        for (face, layout) in self.file.faces().iter().zip(&layouts) {
            let directory = TableDirectory {
                sfnt_version: face.sfnt_version,
                tables: layout
                    .iter()
                    .map(|(tag, block)| TableRecord { tag: *tag, checksum: checksums[*block], offset: offsets[*block], length: lengths[*block] })
                    .collect(),
            };
            headers.bytes(&directory.to_bytes());
        }
        let headers = headers.into_bytes();
        target.seek(SeekFrom::Start(0))?;
        target.write_all(&headers)?;

        if single && let Some((_, block)) = layouts[0].iter().find(|(tag, _)| *tag == Tag::HEAD) {
            let total = order.iter().fold(Checksum::of(&headers), |sum, index| sum.wrapping_add(checksums[*index]));
            let adjustment = HeadTable::CHECKSUM_TARGET.wrapping_sub(total);
            target.seek(SeekFrom::Start(u64::from(offsets[*block]) + HeadTable::CHECKSUM_ADJUSTMENT_OFFSET as u64))?;
            target.write_all(&adjustment.to_be_bytes())?;
        }
        target.seek(SeekFrom::Start(position))?;
        target.flush()?;
        Ok(())
    }

    /// Reads the `head` table of a single-face file with `checksumAdjustment` set to zero,
    /// which is how the table checksum of `head` is defined.
    pub fn zeroed_head<R: Read + Seek>(&self, source: &mut R) -> Result<Option<Vec<u8>>, SFNTError> {
        if let Some(TableChange::Remove) = self.changes.get(&(0, Tag::HEAD)) {
            return Ok(None);
        }
        let mut data = match self.changes.get(&(0, Tag::HEAD)) {
            Some(TableChange::Replace(data)) => data.clone(),
            _ => match self.file.faces()[0].read_table(source, Tag::HEAD)? {
                Some(data) => data,
                None => return Ok(None),
            },
        };
        HeadTable::parse(&data)?;
        data[HeadTable::CHECKSUM_ADJUSTMENT_OFFSET..HeadTable::CHECKSUM_ADJUSTMENT_OFFSET + 4].fill(0);
        Ok(Some(data))
    }
}
