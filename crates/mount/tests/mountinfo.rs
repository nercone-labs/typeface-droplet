//! Tests of mountinfo parsing against the format documented in proc_pid_mountinfo(5).

use std::path::Path;

use mount::{MountEntry, MountTable};

#[test]
fn parses_the_example_of_the_manual() {
    let entry = MountEntry::parse("36 35 98:0 /mnt1 /mnt2 rw,noatime master:1 - ext3 /dev/root rw,errors=continue").unwrap();
    assert_eq!(entry.id, 36);
    assert_eq!(entry.parent_id, 35);
    assert_eq!(entry.device, "98:0");
    assert_eq!(entry.root, Path::new("/mnt1"));
    assert_eq!(entry.mount_point, Path::new("/mnt2"));
    assert_eq!(entry.options, "rw,noatime");
    assert_eq!(entry.propagation, vec!["master:1"]);
    assert_eq!(entry.file_system, "ext3");
    assert_eq!(entry.source, "/dev/root");
    assert_eq!(entry.super_options, "rw,errors=continue");
    assert!(!entry.is_shared());
}

#[test]
fn parses_any_number_of_optional_fields() {
    let none = MountEntry::parse("29 1 254:0 / / ro,nodev,relatime - ext4 /dev/block/dm-0 ro,seclabel").unwrap();
    assert!(none.propagation.is_empty());
    let two = MountEntry::parse("3523 27 0:51 / /mnt/a rw shared:17 master:16 - fuse /dev/fuse rw").unwrap();
    assert_eq!(two.propagation, vec!["shared:17", "master:16"]);
    assert!(two.is_shared());
    assert!(MountEntry::parse("1 2 3:4 / /x rw shared:1").is_none(), "the separator is required");
}

#[test]
fn decodes_octal_escapes() {
    let entry = MountEntry::parse(r"40 29 0:5 /a\040b /mnt/with\040space\011tab\012line\134slash rw - tmpfs tmpfs rw").unwrap();
    assert_eq!(entry.root, Path::new("/a b"));
    assert_eq!(entry.mount_point, Path::new("/mnt/with space\ttab\nline\\slash"));
    assert_eq!(MountEntry::unescape(r"\04"), r"\04", "an incomplete escape is literal");
    assert_eq!(MountEntry::unescape(r"\777"), r"\777", "an escape above 0xFF is literal");
}

#[test]
fn finds_the_topmost_mount_by_parent_chain() {
    // The bind mounts are listed out of order on purpose: the topmost mount at a path is the
    // one stacked last, which is defined by the parent relation and not by the line order.
    let table = MountTable::parse(
        "29 1 254:0 / / ro - ext4 /dev/block/dm-0 ro\n\
         300 200 254:5 /adb/b.xml /system/etc/fonts.xml ro - ext4 /dev/block/dm-5 rw\n\
         200 29 254:5 /adb/a.xml /system/etc/fonts.xml ro - ext4 /dev/block/dm-5 rw\n\
         400 29 0:60 / /system/fonts rw - tmpfs tmpfs rw\n",
    );
    let path = Path::new("/system/etc/fonts.xml");
    assert_eq!(table.at(path).count(), 2);
    assert_eq!(table.top(path).unwrap().root, Path::new("/adb/b.xml"));
    assert_eq!(table.containing(Path::new("/system/fonts/Roboto.ttf")).unwrap().id, 400);
    assert_eq!(table.containing(Path::new("/system/etc/hosts")).unwrap().id, 29);
    assert!(table.top(Path::new("/vendor")).is_none());
}
