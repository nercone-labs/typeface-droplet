//! Tests of the configuration format, its validation, wildcard patterns and the font store.

use std::fs;
use std::io::Cursor;
use std::path::PathBuf;

use droplet::{Action, Config, Face, FontEntry, FontInfo, FontSource, FontStore, Log, Matcher, Pattern, Preset, Role, Rule, SourceReader};
use sfnt::{FontFile, NameTable, Tag};

#[test]
fn patterns_match_like_shell_wildcards() {
    let cases = [
        ("google-sans*", "google-sans", true),
        ("google-sans*", "google-sans-text", true),
        ("google-sans-*", "google-sans", false),
        ("Roboto-*", "RobotoFlex-Regular", false),
        ("*Mono*", "GoogleSansMono-Regular", true),
        ("a?c", "abc", true),
        ("a?c", "ac", false),
        ("*a*b*c", "xxaxbxxc", true),
        ("*a*b*c", "xxaxbxxcb", false),
        ("NotoSerif", "NotoSerif-Bold", false),
        ("", "", true),
        ("*", "", true),
        ("ja", "JA", false),
        ("日本*", "日本語", true),
    ];
    for (pattern, name, expected) in cases {
        assert_eq!(Pattern::new(pattern).matches(name), expected, "{pattern} vs {name}");
    }
    assert!(Pattern::new("serif").is_exact());
    assert!(!Pattern::new("serif*").is_exact());
    // Matching never backtracks exponentially.
    assert!(!Pattern::new("*a*a*a*a*a*a*a*a*a*a*b").matches(&"a".repeat(2000)));
}

fn sha(seed: char) -> String {
    seed.to_string().repeat(64)
}

fn valid() -> Config {
    Config {
        version: Config::VERSION,
        fonts: vec![FontEntry { id: sha('a'), source: FontSource::file("/fonts/a.ttf"), index: 0, rename: Some("Roboto".to_string()) }],
        roles: vec![Role {
            name: "sans".to_string(),
            faces: vec![Face { font: sha('a'), style: None, weight: None, axes: Default::default() }],
            axes: Default::default(),
        }],
        rules: vec![Rule { matcher: Matcher::Family(Pattern::new("sans-serif")), action: Action::Replace("sans".to_string()) }],
        components: vec!["com.google.android.gms/.fonts.provider.FontsProvider".to_string()],
        restart: vec!["com.android.systemui".to_string(), Config::HOME.to_string()],
        guard_seconds: 60,
        allow_unknown_versions: false,
    }
}

#[test]
fn json_round_trips_in_the_documented_shape() {
    let config = valid();
    let text = serde_json::to_string(&config).unwrap();
    let value: serde_json::Value = serde_json::from_str(&text).unwrap();
    assert_eq!(value["rules"][0], serde_json::json!({ "match": { "family": "sans-serif" }, "replace": "sans" }));
    assert_eq!(value["guardSeconds"], 60);
    assert_eq!(Config::parse(&text).unwrap(), config);
    let prepend = r#"{"version":1,"rules":[{"match":{"language":"ja"},"prepend":[{"role":"sans","fallbackFor":"serif"}]}]}"#;
    let parsed = Config::parse(prepend).unwrap();
    assert!(matches!(&parsed.rules[0].action, Action::Prepend(entries) if entries[0].fallback_for.as_deref() == Some("serif")));
    assert_eq!(parsed.guard_seconds, 60, "the guard is on by default");
}

#[test]
fn unknown_versions_and_fields_are_refused() {
    assert!(Config::parse(r#"{"version":2}"#).is_err());
    assert!(Config::parse(r#"{}"#).is_err());
    assert!(Config::parse(r#"{"version":1,"unknownField":true}"#).is_err());
    assert!(Config::parse(r#"{"version":1,"rules":[{"match":{"color":"red"},"replace":"x"}]}"#).is_err());
    assert!(Config::parse("not json").is_err());
}

#[test]
fn validation_reports_every_problem() {
    assert!(valid().validate().is_empty(), "{:?}", valid().validate());
    let check = |change: &dyn Fn(&mut Config), expected: &str| {
        let mut config = valid();
        change(&mut config);
        let problems = config.validate();
        assert!(problems.iter().any(|problem| problem.contains(expected)), "expected \"{expected}\" in {problems:?}");
    };
    check(&|config| config.fonts[0].id = "ABC".to_string(), "not a lowercase SHA-256");
    check(&|config| config.fonts.push(config.fonts[0].clone()), "listed twice");
    check(&|config| config.fonts[0].source.path = PathBuf::from("relative.ttf"), "absolute path");
    check(&|config| config.fonts[0].rename = Some("bad\nname".to_string()), "printable");
    check(&|config| config.roles.push(config.roles[0].clone()), "defined twice");
    check(&|config| config.roles[0].faces.clear(), "has no faces");
    check(&|config| config.roles[0].faces[0].font = sha('b'), "unknown font");
    check(&|config| config.roles[0].faces[0].weight = Some(1001), "outside 1 to 1000");
    check(
        &|config| {
            config.roles[0].axes.insert("wght".to_string(), 400.0);
        },
        "controlled by weights",
    );
    check(
        &|config| {
            config.roles[0].axes.insert("toolong".to_string(), 1.0);
        },
        "invalid axis tag",
    );
    check(
        &|config| {
            config.roles[0].faces[0].axes.insert("opsz".to_string(), f32::NAN);
        },
        "non-finite",
    );
    check(&|config| config.rules[0].action = Action::Replace("missing".to_string()), "unknown role");
    check(&|config| config.rules[0].action = Action::Prepend(vec![]), "needs a language match");
    check(&|config| config.rules[0].matcher = Matcher::Family(Pattern::new("")), "empty pattern");
    check(&|config| config.components.push("nopackage".to_string()), "package/class");
    check(&|config| config.restart.push("not a package".to_string()), "not a package name");
    check(&|config| config.guard_seconds = 3601, "at most");
}

#[test]
fn used_fonts_are_those_of_roles_that_rules_use() {
    let mut config = valid();
    config.fonts.push(FontEntry { id: sha('c'), source: FontSource::file("/fonts/c.ttf"), index: 0, rename: None });
    config.roles.push(Role {
        name: "unused".to_string(),
        faces: vec![Face { font: sha('c'), style: None, weight: None, axes: Default::default() }],
        axes: Default::default(),
    });
    let used: Vec<&str> = config.used_fonts().iter().map(|font| font.id.as_str()).collect();
    assert_eq!(used, [sha('a').as_str()]);
}

#[test]
fn preset_without_fonts_has_no_rules() {
    let config = Preset::default().config();
    assert!(config.roles.is_empty());
    assert!(config.rules.is_empty());
}

/// A minimal single-face TrueType font with head, name and OS/2 tables.
fn synthetic_font() -> Vec<u8> {
    let utf16 = |text: &str| -> Vec<u8> { text.encode_utf16().flat_map(u16::to_be_bytes).collect() };
    let mut name = Vec::new();
    let records = [(1u16, utf16("Sample")), (2, utf16("Bold")), (4, utf16("Sample Bold")), (6, utf16("Sample-Bold"))];
    for field in [0u16, records.len() as u16, (6 + records.len() * 12) as u16] {
        name.extend_from_slice(&field.to_be_bytes());
    }
    let mut storage = Vec::new();
    for (id, value) in &records {
        for field in [3u16, 1, 0x409, *id, value.len() as u16, storage.len() as u16] {
            name.extend_from_slice(&field.to_be_bytes());
        }
        storage.extend_from_slice(value);
    }
    name.extend_from_slice(&storage);
    let mut head = vec![0; 54];
    head[0..2].copy_from_slice(&1u16.to_be_bytes());
    head[12..16].copy_from_slice(&0x5F0F_3CF5u32.to_be_bytes());
    let mut os2 = vec![0; 78];
    os2[4..6].copy_from_slice(&700u16.to_be_bytes());
    os2[62..64].copy_from_slice(&0x0020u16.to_be_bytes());
    let tables: [(&[u8; 4], Vec<u8>); 3] = [(b"OS/2", os2), (b"head", head), (b"name", name)];
    let mut data = Vec::new();
    data.extend_from_slice(&0x0001_0000u32.to_be_bytes());
    data.extend_from_slice(&3u16.to_be_bytes());
    data.extend_from_slice(&[0; 6]);
    let mut offset = 12 + 3 * 16;
    for (tag, table) in &tables {
        data.extend_from_slice(*tag);
        data.extend_from_slice(&0u32.to_be_bytes());
        data.extend_from_slice(&(offset as u32).to_be_bytes());
        data.extend_from_slice(&(table.len() as u32).to_be_bytes());
        offset += table.len().next_multiple_of(4);
    }
    for (_, table) in &tables {
        data.extend_from_slice(table);
        data.resize(data.len().next_multiple_of(4), 0);
    }
    data
}

fn temporary(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("droplet-test-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).unwrap();
    path
}

#[test]
fn source_ranges_read_as_whole_files() {
    let directory = temporary("range");
    let font = synthetic_font();
    let mut container = b"PREFIX".to_vec();
    container.extend_from_slice(&font);
    container.extend_from_slice(b"SUFFIX");
    let path = directory.join("container.bin");
    fs::write(&path, &container).unwrap();
    let source = FontSource { path: path.clone(), offset: 6, length: Some(font.len() as u64) };
    let info = FontInfo::inspect(&source).unwrap();
    assert_eq!(info.format, "ttf");
    assert_eq!(info.size, font.len() as u64);
    assert_eq!((info.faces[0].family.as_deref(), info.faces[0].weight), (Some("Sample"), 700));
    assert!(SourceReader::open(&FontSource::file(directory.join("font.ttf"))).is_err(), "a missing file cannot be opened");
    fs::write(directory.join("font.ttf"), &font).unwrap();
    assert_eq!(
        SourceReader::open(&source).unwrap().sha256().unwrap(),
        SourceReader::open(&FontSource::file(directory.join("font.ttf"))).unwrap().sha256().unwrap()
    );
    assert!(SourceReader::open(&FontSource { path, offset: 6, length: Some(container.len() as u64) }).is_err(), "a range beyond the file is refused");
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn store_installs_renamed_fonts_once_and_checks_their_content() {
    let directory = temporary("store");
    let font = synthetic_font();
    let source = directory.join("Sample.ttf");
    fs::write(&source, &font).unwrap();
    let id = SourceReader::open(&FontSource::file(&source)).unwrap().sha256().unwrap();
    let store = FontStore::new(directory.join("store"), None);
    store.prepare().unwrap();
    let mut log = Log::new(None);
    let entry = FontEntry { id: id.clone(), source: FontSource::file(&source), index: 0, rename: Some("Droplet".to_string()) };

    let installed = store.install(&entry, &mut log).unwrap();
    assert!(installed.file_name.starts_with(FontStore::FILE_PREFIX) && installed.file_name.ends_with(".ttf"));
    assert_eq!(installed.face.family.as_deref(), Some("Droplet"));
    assert_eq!(installed.face.full_name.as_deref(), Some("Droplet Bold"));
    assert_eq!(installed.face.postscript_name.as_deref(), Some("Sample-Bold"));
    assert_eq!(installed.face.weight, 700);
    let data = fs::read(&installed.path).unwrap();
    let mut cursor = Cursor::new(data);
    let file = FontFile::read(&mut cursor).unwrap();
    let name = NameTable::parse(&file.faces()[0].read_table(&mut cursor, Tag::NAME).unwrap().unwrap()).unwrap();
    assert_eq!(name.text(1).as_deref(), Some("Droplet"));

    // The same entry maps to the same file, which is not written again.
    let again = store.install(&entry, &mut log).unwrap();
    assert_eq!(again.file_name, installed.file_name);
    assert_eq!(log.lines.iter().filter(|line| line.message.starts_with("installed")).count(), 1);

    // Another name, or no name, is another file; an unrenamed font is copied as it is.
    let plain = store.install(&FontEntry { rename: None, ..entry.clone() }, &mut log).unwrap();
    assert_ne!(plain.file_name, installed.file_name);
    assert_eq!(fs::read(&plain.path).unwrap(), font);

    // An installed font is reused even when its source has moved away.
    let moved = FontEntry { source: FontSource::file(directory.join("moved.ttf")), ..entry.clone() };
    assert_eq!(store.install(&moved, &mut log).unwrap().file_name, installed.file_name);

    // A source whose content no longer matches its id is refused.
    let mut changed = font.clone();
    changed.push(0);
    fs::write(&source, &changed).unwrap();
    let error = store.install(&FontEntry { rename: Some("Other".to_string()), ..entry.clone() }, &mut log).unwrap_err();
    assert!(error.to_string().contains("no longer has the content"), "{error}");
    let error = store.install(&FontEntry { index: 1, ..entry }, &mut log).unwrap_err();
    assert!(error.to_string().contains("has no face 1"), "{error}");
    fs::remove_dir_all(directory).unwrap();
}
