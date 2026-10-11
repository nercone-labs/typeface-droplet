//! Tests against the OpenType specification. Expected values are derived from the
//! specification text, and rebuilt files are checked with byte-level code that is independent
//! of the crate's own parsers.

use std::io::Cursor;

use sfnt::{Checksum, FVARTable, FontFile, FontFormat, FontRebuilder, HeadTable, NameEncoding, NameRecord, NameTable, OS2Table, SFNTError, Tag};

fn u16_at(data: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes([data[offset], data[offset + 1]])
}

fn u32_at(data: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([data[offset], data[offset + 1], data[offset + 2], data[offset + 3]])
}

/// The checksum exactly as the specification's sample code computes it.
fn reference_checksum(data: &[u8]) -> u32 {
    let mut padded = data.to_vec();
    while !padded.len().is_multiple_of(4) {
        padded.push(0);
    }
    padded.chunks(4).fold(0u32, |sum, word| sum.wrapping_add(u32::from_be_bytes([word[0], word[1], word[2], word[3]])))
}

fn utf16(text: &str) -> Vec<u8> {
    text.encode_utf16().flat_map(u16::to_be_bytes).collect()
}

/// Builds a name table by hand, following the format 0 layout of the specification.
fn name_table(records: &[(u16, u16, u16, u16, Vec<u8>)]) -> Vec<u8> {
    let mut header = Vec::new();
    let mut storage = Vec::new();
    header.extend_from_slice(&0u16.to_be_bytes());
    header.extend_from_slice(&(records.len() as u16).to_be_bytes());
    header.extend_from_slice(&((6 + records.len() * 12) as u16).to_be_bytes());
    for (platform, encoding, language, name, value) in records {
        for field in [*platform, *encoding, *language, *name, value.len() as u16, storage.len() as u16] {
            header.extend_from_slice(&field.to_be_bytes());
        }
        storage.extend_from_slice(value);
    }
    header.extend_from_slice(&storage);
    header
}

fn head_table(mac_style: u16) -> Vec<u8> {
    let mut head = vec![0; 54];
    head[0..2].copy_from_slice(&1u16.to_be_bytes());
    head[4..8].copy_from_slice(&0x0001_8000u32.to_be_bytes());
    head[8..12].copy_from_slice(&0xDEAD_BEEFu32.to_be_bytes());
    head[12..16].copy_from_slice(&HeadTable::MAGIC_NUMBER.to_be_bytes());
    head[18..20].copy_from_slice(&1000u16.to_be_bytes());
    head[44..46].copy_from_slice(&mac_style.to_be_bytes());
    head
}

fn os2_table(weight: u16, fs_selection: u16) -> Vec<u8> {
    let mut os2 = vec![0; 78];
    os2[0..2].copy_from_slice(&4u16.to_be_bytes());
    os2[4..6].copy_from_slice(&weight.to_be_bytes());
    os2[6..8].copy_from_slice(&5u16.to_be_bytes());
    os2[62..64].copy_from_slice(&fs_selection.to_be_bytes());
    os2
}

fn fixed(value: f32) -> [u8; 4] {
    ((value * 65536.0) as i32).to_be_bytes()
}

fn fvar_table(axes: &[(&[u8; 4], f32, f32, f32)], axis_size: u16) -> Vec<u8> {
    let mut fvar = Vec::new();
    for field in [1u16, 0, 16, 2, axes.len() as u16, axis_size, 0, 0] {
        fvar.extend_from_slice(&field.to_be_bytes());
    }
    for (tag, minimum, default, maximum) in axes {
        fvar.extend_from_slice(*tag);
        fvar.extend_from_slice(&fixed(*minimum));
        fvar.extend_from_slice(&fixed(*default));
        fvar.extend_from_slice(&fixed(*maximum));
        fvar.extend_from_slice(&0u16.to_be_bytes());
        fvar.extend_from_slice(&256u16.to_be_bytes());
        fvar.resize(fvar.len() + usize::from(axis_size) - 20, 0xAA);
    }
    fvar
}

/// Lays out tables in the given order, deliberately unaligned and with stale checksums,
/// with the directory records in the given (possibly unsorted) order.
fn single_font(tables: &[(&[u8; 4], Vec<u8>)]) -> Vec<u8> {
    let mut data = Vec::new();
    data.extend_from_slice(&0x0001_0000u32.to_be_bytes());
    data.extend_from_slice(&(tables.len() as u16).to_be_bytes());
    data.extend_from_slice(&[0; 6]);
    let mut offset = 12 + tables.len() * 16;
    for (tag, table) in tables {
        data.extend_from_slice(*tag);
        data.extend_from_slice(&0x1234_5678u32.to_be_bytes());
        data.extend_from_slice(&(offset as u32).to_be_bytes());
        data.extend_from_slice(&(table.len() as u32).to_be_bytes());
        offset += table.len() + 1;
    }
    for (_, table) in tables {
        data.extend_from_slice(table);
        data.push(0xFF);
    }
    data
}

fn sample_names() -> Vec<u8> {
    name_table(&[
        (3, 1, 0x409, 1, utf16("Sample Medium")),
        (3, 1, 0x409, 2, utf16("Regular")),
        (3, 1, 0x409, 4, utf16("Sample Medium")),
        (3, 1, 0x409, 6, utf16("Sample-Medium")),
        (3, 1, 0x409, 16, utf16("Sample")),
        (3, 1, 0x409, 17, utf16("Medium")),
        (1, 0, 0, 1, b"Sample Medium".to_vec()),
        (1, 0, 0, 2, b"Regular".to_vec()),
        (3, 1, 0x411, 1, utf16("サンプル")),
        (3, 2, 0x411, 1, vec![0x83, 0x54]),
    ])
}

fn sample_font() -> Vec<u8> {
    single_font(&[
        (b"name", sample_names()),
        (b"glyf", vec![1, 2, 3, 4, 5, 6, 7]),
        (b"head", head_table(0)),
        (b"OS/2", os2_table(500, 0x0040)),
        (b"DSIG", vec![0, 0, 0, 1, 0, 0, 0, 0]),
    ])
}

/// Checks the structural rules of a single-face file and returns its table map.
fn check_single(data: &[u8]) -> Vec<(Tag, usize, usize)> {
    let count = usize::from(u16_at(data, 4));
    let entry_selector = (count as f64).log2().floor() as u16;
    let search_range = 2u16.pow(u32::from(entry_selector)) * 16;
    assert_eq!(u16_at(data, 6), search_range, "searchRange");
    assert_eq!(u16_at(data, 8), entry_selector, "entrySelector");
    assert_eq!(u16_at(data, 10), count as u16 * 16 - search_range, "rangeShift");
    let mut tables = Vec::new();
    let mut previous: Option<[u8; 4]> = None;
    for index in 0..count {
        let record = 12 + index * 16;
        let tag = [data[record], data[record + 1], data[record + 2], data[record + 3]];
        if let Some(previous) = previous {
            assert!(previous < tag, "records must be sorted by tag");
        }
        previous = Some(tag);
        let offset = u32_at(data, record + 8) as usize;
        let length = u32_at(data, record + 12) as usize;
        assert_eq!(offset % 4, 0, "tables must start on a four-byte boundary");
        assert!(offset + length <= data.len());
        let padded_end = offset + length.next_multiple_of(4);
        assert!(data[offset + length..padded_end.min(data.len())].iter().all(|byte| *byte == 0), "padding must be zero");
        let mut table = data[offset..offset + length].to_vec();
        if &tag == b"head" {
            table[8..12].fill(0);
        }
        assert_eq!(u32_at(data, record + 4), reference_checksum(&table), "checksum of {:?}", Tag(tag));
        tables.push((Tag(tag), offset, length));
    }
    assert_eq!(data.len() % 4, 0, "file length must be padded");
    tables
}

fn rebuild(original: &[u8], change: impl FnOnce(&mut FontRebuilder)) -> Vec<u8> {
    let mut source = Cursor::new(original.to_vec());
    let file = FontFile::read(&mut source).unwrap();
    let mut rebuilder = FontRebuilder::new(&file);
    change(&mut rebuilder);
    let mut target = Cursor::new(Vec::new());
    rebuilder.write(&mut source, &mut target).unwrap();
    target.into_inner()
}

#[test]
fn checksum_follows_the_specification() {
    assert_eq!(Checksum::of(&[]), 0);
    assert_eq!(Checksum::of(&[0, 0, 0, 1, 0, 0, 0, 2]), 3);
    assert_eq!(Checksum::of(&[0x01]), 0x0100_0000);
    assert_eq!(Checksum::of(&[0xFF; 5]), 0xFFFF_FFFFu32.wrapping_add(0xFF00_0000));
    let data: Vec<u8> = (0..1001u32).map(|value| (value * 7) as u8).collect();
    for split in [0, 1, 2, 3, 5, 500, 1001] {
        let mut checksum = Checksum::new();
        checksum.update(&data[..split]);
        checksum.update(&data[split..]);
        assert_eq!(checksum.value(), reference_checksum(&data), "split at {split}");
    }
}

#[test]
fn tag_parsing_pads_with_spaces() {
    assert_eq!(Tag::parse("CFF"), Some(Tag(*b"CFF ")));
    assert_eq!(Tag::parse("wght"), Some(Tag(*b"wght")));
    assert_eq!(Tag::parse(""), None);
    assert_eq!(Tag::parse("toolong"), None);
    assert_eq!(Tag::parse("wg\u{7F}t"), None);
    assert_eq!(Tag(*b"OS/2").to_string(), "OS/2");
}

#[test]
fn reads_structure_and_style_tables() {
    let data = sample_font();
    let mut cursor = Cursor::new(data);
    let file = FontFile::read(&mut cursor).unwrap();
    assert_eq!(file.format(), FontFormat::TTF);
    let face = &file.faces()[0];
    assert_eq!(face.tables.len(), 5);
    let os2 = OS2Table::parse(&face.read_table(&mut cursor, Tag::OS2).unwrap().unwrap()).unwrap();
    assert_eq!(os2.weight_class, 500);
    assert!(!os2.is_italic());
    let head = HeadTable::parse(&face.read_table(&mut cursor, Tag::HEAD).unwrap().unwrap()).unwrap();
    assert_eq!(head.units_per_em, 1000);
    assert!(!head.is_italic());
    let name = NameTable::parse(&face.read_table(&mut cursor, Tag::NAME).unwrap().unwrap()).unwrap();
    assert_eq!(name.family().as_deref(), Some("Sample"));
    assert_eq!(name.subfamily().as_deref(), Some("Medium"));
    assert_eq!(name.text(NameRecord::FAMILY).as_deref(), Some("Sample Medium"));
}

#[test]
fn rebuild_without_changes_produces_a_valid_layout() {
    let original = sample_font();
    let rebuilt = rebuild(&original, |_| {});
    let tables = check_single(&rebuilt);
    let tags: Vec<String> = tables.iter().map(|(tag, ..)| tag.to_string()).collect();
    assert_eq!(tags, ["DSIG", "OS/2", "glyf", "head", "name"]);
    // The whole-file checksum must equal 0xB1B0AFBA, as defined for checksumAdjustment.
    assert_eq!(reference_checksum(&rebuilt), HeadTable::CHECKSUM_TARGET);
    // Table contents other than checksumAdjustment are preserved.
    let (_, offset, length) = tables.iter().find(|(tag, ..)| *tag == Tag::GLYF).unwrap();
    assert_eq!(&rebuilt[*offset..*offset + *length], &[1, 2, 3, 4, 5, 6, 7]);
}

#[test]
fn rebuild_replaces_adds_and_removes_tables() {
    let original = sample_font();
    let rebuilt = rebuild(&original, |rebuilder| {
        rebuilder.replace(0, Tag::GLYF, vec![9; 13]);
        rebuilder.replace(0, Tag(*b"zzzz"), vec![8; 2]);
        rebuilder.remove(0, Tag::DSIG);
    });
    let tables = check_single(&rebuilt);
    let tags: Vec<String> = tables.iter().map(|(tag, ..)| tag.to_string()).collect();
    assert_eq!(tags, ["OS/2", "glyf", "head", "name", "zzzz"]);
    let (_, offset, length) = tables.iter().find(|(tag, ..)| *tag == Tag::GLYF).unwrap();
    assert_eq!(&rebuilt[*offset..*offset + *length], &[9; 13]);
    assert_eq!(reference_checksum(&rebuilt), HeadTable::CHECKSUM_TARGET);
}

#[test]
fn added_tables_are_written_in_tag_order_deterministically() {
    let original = sample_font();
    let outputs: Vec<Vec<u8>> = (0..4)
        .map(|_| {
            rebuild(&original, |rebuilder| {
                for tag in [b"zzz1", b"aaa1", b"mmm1", b"bbb1"] {
                    rebuilder.replace(0, Tag(*tag), tag.to_vec());
                }
            })
        })
        .collect();
    assert!(outputs.windows(2).all(|pair| pair[0] == pair[1]));
    check_single(&outputs[0]);
    assert_eq!(reference_checksum(&outputs[0]), HeadTable::CHECKSUM_TARGET);
}

#[test]
fn set_family_rewrites_family_records_only() {
    let mut name = NameTable::parse(&sample_names()).unwrap();
    name.set_family("Droplet");
    let text = |platform: u16, encoding: u16, language: u16, id: u16| {
        name.records
            .iter()
            .find(|record| (record.platform_id, record.encoding_id, record.language_id, record.name_id) == (platform, encoding, language, id))
            .and_then(NameRecord::text)
    };
    assert_eq!(text(3, 1, 0x409, 1).as_deref(), Some("Droplet"));
    assert_eq!(text(3, 1, 0x409, 16).as_deref(), Some("Droplet"));
    // ID 4 takes the typographic subfamily of the same language.
    assert_eq!(text(3, 1, 0x409, 4).as_deref(), Some("Droplet Medium"));
    assert_eq!(text(3, 1, 0x409, 2).as_deref(), Some("Regular"));
    assert_eq!(text(3, 1, 0x409, 6).as_deref(), Some("Sample-Medium"));
    assert_eq!(text(3, 1, 0x409, 17).as_deref(), Some("Medium"));
    assert_eq!(text(1, 0, 0, 1).as_deref(), Some("Droplet"));
    assert_eq!(text(3, 1, 0x411, 1).as_deref(), Some("Droplet"));
    // A record in an unknown encoding (Windows Shift-JIS) cannot hold the new name and is removed.
    assert!(!name.records.iter().any(|record| record.encoding_id == 2 && record.name_id == 1));
}

#[test]
fn set_family_removes_records_the_encoding_cannot_represent() {
    let mut name = NameTable::parse(&sample_names()).unwrap();
    name.set_family("ドロップレット");
    assert!(!name.records.iter().any(|record| record.platform_id == 1 && record.name_id == 1));
    assert!(name.records.iter().any(|record| record.platform_id == 1 && record.name_id == 2));
    assert_eq!(name.text(1).as_deref(), Some("ドロップレット"));
}

#[test]
fn name_table_serialization_is_sorted_and_round_trips() {
    let mut name = NameTable::parse(&sample_names()).unwrap();
    name.set_family("Droplet");
    let bytes = name.to_bytes().unwrap();
    let count = usize::from(u16_at(&bytes, 2));
    assert_eq!(usize::from(u16_at(&bytes, 4)), 6 + count * 12, "storage offset");
    let keys: Vec<(u16, u16, u16, u16)> = (0..count)
        .map(|index| {
            let record = 6 + index * 12;
            (u16_at(&bytes, record), u16_at(&bytes, record + 2), u16_at(&bytes, record + 4), u16_at(&bytes, record + 6))
        })
        .collect();
    let mut sorted = keys.clone();
    sorted.sort();
    assert_eq!(keys, sorted, "name records must be sorted");
    let mut reparsed = NameTable::parse(&bytes).unwrap();
    reparsed.records.sort_by_key(|record| (record.platform_id, record.encoding_id, record.language_id, record.name_id));
    let mut expected = name.records.clone();
    expected.sort_by_key(|record| (record.platform_id, record.encoding_id, record.language_id, record.name_id));
    assert_eq!(reparsed.records, expected);
}

#[test]
fn name_storage_shares_only_identical_strings() {
    let mut name = NameTable::parse(&name_table(&[(3, 1, 0x409, 1, utf16("AB")), (3, 1, 0x409, 2, utf16("B")), (3, 1, 0x409, 3, utf16("AB"))])).unwrap();
    name.set_family("AB");
    let bytes = name.to_bytes().unwrap();
    let storage = usize::from(u16_at(&bytes, 4));
    let entries: Vec<(usize, usize)> = (0..usize::from(u16_at(&bytes, 2)))
        .map(|index| (usize::from(u16_at(&bytes, 6 + index * 12 + 8)), usize::from(u16_at(&bytes, 6 + index * 12 + 10))))
        .collect();
    // "AB" twice: one copy. "B" is stored on its own, never inside "AB".
    assert_eq!(bytes.len() - storage, 6);
    assert_eq!(entries.iter().filter(|(length, _)| *length == 4).map(|(_, offset)| offset).collect::<std::collections::HashSet<_>>().len(), 1);
    let (_, b_offset) = entries.iter().find(|(length, _)| *length == 2).unwrap();
    assert_eq!(&bytes[storage + b_offset..storage + b_offset + 2], &[0, b'B']);
    assert!(entries.iter().all(|(length, offset)| *length != 4 || *b_offset >= offset + length || b_offset + 2 <= *offset));
}

#[test]
fn name_table_format_1_keeps_language_tags() {
    let mut table = vec![];
    for field in [1u16, 1, 6 + 12 + 2 + 4] {
        table.extend_from_slice(&field.to_be_bytes());
    }
    let family = utf16("A");
    let language = utf16("ja-JP");
    for field in [3u16, 1, 0x8000, 1, family.len() as u16, 0] {
        table.extend_from_slice(&field.to_be_bytes());
    }
    for field in [1u16, language.len() as u16, family.len() as u16] {
        table.extend_from_slice(&field.to_be_bytes());
    }
    table.extend_from_slice(&family);
    table.extend_from_slice(&language);
    let name = NameTable::parse(&table).unwrap();
    assert_eq!(name.language_tags, vec![language.clone()]);
    let reparsed = NameTable::parse(&name.to_bytes().unwrap()).unwrap();
    assert_eq!(reparsed.format, 1);
    assert_eq!(reparsed.language_tags, vec![language]);
    assert_eq!(reparsed.text(1).as_deref(), Some("A"));
}

#[test]
fn mac_roman_encoding_round_trips() {
    let encoding = NameEncoding::MacRoman;
    let bytes: Vec<u8> = (0x20..=0xFF).filter(|byte| *byte != 0x7F).collect();
    let text = encoding.decode(&bytes).unwrap();
    assert_eq!(encoding.encode(&text).unwrap(), bytes);
    assert_eq!(encoding.decode(&[0x80, 0xA5, 0xDB]).unwrap(), "Ä•€");
    assert_eq!(encoding.encode("日本"), None);
    // Windows encoding 1 and Unicode encodings 0 to 3 are BMP-only; 10 and 4 are full Unicode.
    assert_eq!(NameEncoding::of(3, 1), Some(NameEncoding::UCS2));
    assert_eq!(NameEncoding::of(3, 0), Some(NameEncoding::UCS2));
    assert_eq!(NameEncoding::of(0, 3), Some(NameEncoding::UCS2));
    assert_eq!(NameEncoding::of(3, 10), Some(NameEncoding::UTF16BE));
    assert_eq!(NameEncoding::of(0, 4), Some(NameEncoding::UTF16BE));
    assert_eq!(NameEncoding::UCS2.encode("𠮷"), None, "a character outside the BMP needs a surrogate pair");
    assert_eq!(NameEncoding::UTF16BE.encode("𠮷"), Some(vec![0xD8, 0x42, 0xDF, 0xB7]));
    assert_eq!(NameEncoding::of(1, 0), Some(NameEncoding::MacRoman));
    assert_eq!(NameEncoding::of(3, 2), None);
    assert_eq!(NameEncoding::of(1, 1), None);
}

#[test]
fn fvar_axis_records_use_the_declared_size() {
    for axis_size in [20, 24] {
        let fvar = FVARTable::parse(&fvar_table(&[(b"wght", 100.0, 400.0, 900.0), (b"ital", 0.0, 0.0, 1.0)], axis_size)).unwrap();
        assert_eq!(fvar.axes.len(), 2);
        let weight = fvar.axis(Tag(*b"wght")).unwrap();
        assert_eq!((weight.minimum, weight.default, weight.maximum), (100.0, 400.0, 900.0));
        assert_eq!(weight.name_id, 256);
        assert_eq!(fvar.axis(Tag(*b"ital")).unwrap().maximum, 1.0);
    }
    assert!(matches!(FVARTable::parse(&fvar_table(&[(b"wght", 100.0, 400.0, 900.0)], 16)), Err(SFNTError::Malformed(_))));
    let mut future = fvar_table(&[], 20);
    future[0..2].copy_from_slice(&2u16.to_be_bytes());
    assert!(matches!(FVARTable::parse(&future), Err(SFNTError::Unsupported(_))));
    assert!(matches!(FVARTable::parse(&fvar_table(&[(b"wght", 900.0, 400.0, 100.0)], 20)), Err(SFNTError::Malformed(_))));
}

#[test]
fn style_flags_follow_the_specification() {
    assert!(OS2Table::parse(&os2_table(400, 0x0001)).unwrap().is_italic());
    assert!(OS2Table::parse(&os2_table(400, 0x0200)).unwrap().is_italic());
    assert!(!OS2Table::parse(&os2_table(400, 0x0020)).unwrap().is_italic());
    assert!(HeadTable::parse(&head_table(0x0002)).unwrap().is_italic());
    let mut wrong = head_table(0);
    wrong[12] = 0;
    assert!(matches!(HeadTable::parse(&wrong), Err(SFNTError::Malformed(_))));
}

#[test]
fn rejects_unknown_versions_and_out_of_range_tables() {
    let mut unknown = sample_font();
    unknown[0..4].copy_from_slice(b"wOFF");
    assert!(matches!(FontFile::read(&mut Cursor::new(unknown)), Err(SFNTError::Unsupported(_))));

    let mut outside = sample_font();
    outside[12 + 12..12 + 16].copy_from_slice(&0x00FF_FFFFu32.to_be_bytes());
    assert!(matches!(FontFile::read(&mut Cursor::new(outside)), Err(SFNTError::Malformed(_))));

    assert!(matches!(FontFile::read(&mut Cursor::new(vec![0, 1])), Err(SFNTError::Malformed(_))));

    let mut ttc = b"ttcf".to_vec();
    ttc.extend_from_slice(&3u16.to_be_bytes());
    ttc.extend_from_slice(&0u16.to_be_bytes());
    ttc.extend_from_slice(&1u32.to_be_bytes());
    ttc.extend_from_slice(&16u32.to_be_bytes());
    assert!(matches!(FontFile::read(&mut Cursor::new(ttc)), Err(SFNTError::Unsupported(_))));
}

/// Builds a version 2 collection of two faces that share `glyf` and `head` but have their own `name`.
fn sample_collection() -> Vec<u8> {
    let glyf = vec![7; 10];
    let head = head_table(0);
    let names = [name_table(&[(3, 1, 0x409, 1, utf16("First"))]), name_table(&[(3, 1, 0x409, 1, utf16("Second"))])];
    let header_size = 12 + 2 * 4 + 12;
    let directory_size = 12 + 3 * 16;
    let data_start = header_size + 2 * directory_size;
    let glyf_offset = data_start;
    let head_offset = glyf_offset + glyf.len();
    let name_offsets = [head_offset + head.len(), head_offset + head.len() + names[0].len()];
    let mut data = b"ttcf".to_vec();
    for field in [2u16, 0] {
        data.extend_from_slice(&field.to_be_bytes());
    }
    for field in [2u32, header_size as u32, (header_size + directory_size) as u32] {
        data.extend_from_slice(&field.to_be_bytes());
    }
    data.extend_from_slice(b"DSIG");
    data.extend_from_slice(&[0, 0, 0, 8, 0, 0, 0, 0]);
    for name_offset in name_offsets.iter().zip(&names) {
        data.extend_from_slice(&0x0001_0000u32.to_be_bytes());
        data.extend_from_slice(&3u16.to_be_bytes());
        data.extend_from_slice(&[0; 6]);
        for (tag, offset, length) in [(b"glyf", glyf_offset, glyf.len()), (b"head", head_offset, head.len()), (b"name", *name_offset.0, name_offset.1.len())] {
            data.extend_from_slice(tag);
            data.extend_from_slice(&0u32.to_be_bytes());
            data.extend_from_slice(&(offset as u32).to_be_bytes());
            data.extend_from_slice(&(length as u32).to_be_bytes());
        }
    }
    data.extend_from_slice(&glyf);
    data.extend_from_slice(&head);
    data.extend_from_slice(&names[0]);
    data.extend_from_slice(&names[1]);
    data
}

#[test]
fn collection_rebuild_changes_one_face_and_keeps_shared_tables_shared() {
    let original = sample_collection();
    let file = FontFile::read(&mut Cursor::new(original.clone())).unwrap();
    assert_eq!(file.format(), FontFormat::TTC);
    assert_eq!(file.faces().len(), 2);
    let renamed = name_table(&[(3, 1, 0x409, 1, utf16("Renamed"))]);
    let rebuilt = rebuild(&original, |rebuilder| {
        rebuilder.replace(1, Tag::NAME, renamed.clone());
    });
    assert_eq!(&rebuilt[0..4], b"ttcf");
    assert_eq!(u16_at(&rebuilt, 4), 2);
    assert_eq!(u32_at(&rebuilt, 8), 2);
    // The collection signature no longer matches the data and must be cleared.
    assert_eq!(&rebuilt[20..32], &[0; 12]);
    let directories = [u32_at(&rebuilt, 12) as usize, u32_at(&rebuilt, 16) as usize];
    let record = |face: usize, tag: &[u8; 4]| -> (usize, usize, u32) {
        let directory = directories[face];
        let count = usize::from(u16_at(&rebuilt, directory + 4));
        (0..count)
            .map(|index| directory + 12 + index * 16)
            .find(|record| &rebuilt[*record..*record + 4] == tag)
            .map(|record| (u32_at(&rebuilt, record + 8) as usize, u32_at(&rebuilt, record + 12) as usize, u32_at(&rebuilt, record + 4)))
            .unwrap()
    };
    for tag in [b"glyf", b"head"] {
        assert_eq!(record(0, tag), record(1, tag), "shared {tag:?} must stay shared");
    }
    let (offset, length, checksum) = record(1, b"name");
    assert_eq!(&rebuilt[offset..offset + length], renamed.as_slice());
    assert_eq!(checksum, reference_checksum(&renamed));
    assert_eq!(offset % 4, 0);
    let (offset, length, _) = record(0, b"name");
    assert_eq!(NameTable::parse(&rebuilt[offset..offset + length]).unwrap().text(1).as_deref(), Some("First"));
    // In a collection the head table is copied unchanged, and its table checksum is the
    // checksum defined with checksumAdjustment taken as zero.
    let (offset, length, checksum) = record(0, b"head");
    assert_eq!(&rebuilt[offset..offset + length], head_table(0).as_slice());
    let mut zeroed = head_table(0);
    zeroed[8..12].fill(0);
    assert_eq!(checksum, reference_checksum(&zeroed));
}
