use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// One line of `/proc/<pid>/mountinfo` (see proc_pid_mountinfo(5)).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountEntry {
    pub id: u32,
    pub parent_id: u32,
    pub device: String,
    /// The path inside the source file system that is mounted.
    pub root: PathBuf,
    pub mount_point: PathBuf,
    pub options: String,
    /// Optional fields such as `shared:1` or `master:2`.
    pub propagation: Vec<String>,
    pub file_system: String,
    pub source: String,
    pub super_options: String,
}

impl MountEntry {
    pub fn parse(line: &str) -> Option<MountEntry> {
        let mut fields = line.split(' ');
        let id = fields.next()?.parse().ok()?;
        let parent_id = fields.next()?.parse().ok()?;
        let device = fields.next()?.to_string();
        let root = PathBuf::from(MountEntry::unescape(fields.next()?));
        let mount_point = PathBuf::from(MountEntry::unescape(fields.next()?));
        let options = fields.next()?.to_string();
        let mut propagation = Vec::new();
        loop {
            let field = fields.next()?;
            if field == "-" {
                break;
            }
            propagation.push(field.to_string());
        }
        let file_system = MountEntry::unescape(fields.next()?);
        let source = MountEntry::unescape(fields.next()?);
        let super_options = fields.next().unwrap_or_default().to_string();
        Some(MountEntry { id, parent_id, device, root, mount_point, options, propagation, file_system, source, super_options })
    }

    /// Decodes the octal escapes (`\040` for a space, and so on) the kernel uses in paths.
    pub fn unescape(field: &str) -> String {
        let bytes = field.as_bytes();
        let mut output = Vec::with_capacity(bytes.len());
        let mut index = 0;
        while index < bytes.len() {
            if bytes[index] == b'\\' && index + 4 <= bytes.len() && bytes[index + 1..index + 4].iter().all(|digit| (b'0'..=b'7').contains(digit)) {
                let value = bytes[index + 1..index + 4].iter().fold(0u32, |value, digit| value * 8 + u32::from(digit - b'0'));
                if let Ok(byte) = u8::try_from(value) {
                    output.push(byte);
                    index += 4;
                    continue;
                }
            }
            output.push(bytes[index]);
            index += 1;
        }
        String::from_utf8_lossy(&output).into_owned()
    }

    pub fn is_shared(&self) -> bool {
        self.propagation.iter().any(|field| field.starts_with("shared:"))
    }
}

/// The mounts visible to a process, in mount order.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MountTable {
    pub entries: Vec<MountEntry>,
}

impl MountTable {
    pub fn parse(text: &str) -> MountTable {
        MountTable { entries: text.lines().filter_map(MountEntry::parse).collect() }
    }

    /// The mounts of the calling process's mount namespace.
    pub fn current() -> io::Result<MountTable> {
        Ok(MountTable::parse(&fs::read_to_string("/proc/self/mountinfo")?))
    }

    pub fn of_process(pid: u32) -> io::Result<MountTable> {
        Ok(MountTable::parse(&fs::read_to_string(format!("/proc/{pid}/mountinfo"))?))
    }

    /// Every mount whose mount point is exactly `path`, bottom first.
    pub fn at<'a>(&'a self, path: &'a Path) -> impl Iterator<Item = &'a MountEntry> + 'a {
        self.entries.iter().filter(move |entry| entry.mount_point == path)
    }

    /// The topmost mount at `path`: the one that path lookups resolve to.
    /// A mount stacked on another mount at the same path has that mount as its parent,
    /// so the topmost one is the mount that is not the parent of any other mount there.
    pub fn top(&self, path: &Path) -> Option<&MountEntry> {
        let stack: Vec<&MountEntry> = self.entries.iter().filter(|entry| entry.mount_point == path).collect();
        stack.iter().copied().find(|entry| !stack.iter().any(|other| other.parent_id == entry.id))
    }

    /// The mount that contains `path`: the topmost mount at the deepest mount point that is
    /// `path` or one of its ancestors.
    pub fn containing(&self, path: &Path) -> Option<&MountEntry> {
        let deepest = self.entries.iter().filter(|entry| path.starts_with(&entry.mount_point)).max_by_key(|entry| entry.mount_point.components().count())?;
        self.top(&deepest.mount_point)
    }
}
