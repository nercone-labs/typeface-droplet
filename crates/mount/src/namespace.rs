use std::fs::{self, File};
use std::io;
use std::os::unix::fs::MetadataExt;
use std::path::Path;

use crate::{Mount, MountError, Platform};

/// A handle to a mount namespace (an open `/proc/<pid>/ns/mnt` link).
///
/// Entering or creating a mount namespace changes the namespace of the calling thread only,
/// and the kernel refuses it while other threads share the file system attributes;
/// the calling process should therefore be single-threaded.
pub struct MountNamespace {
    file: File,
}

impl MountNamespace {
    pub fn of_process(pid: u32) -> io::Result<MountNamespace> {
        Ok(MountNamespace { file: File::open(format!("/proc/{pid}/ns/mnt"))? })
    }

    /// The mount namespace of init (process 1). On Android this is the namespace that
    /// zygote, and through it every application process, descends from.
    pub fn init() -> io::Result<MountNamespace> {
        MountNamespace::of_process(1)
    }

    pub fn current() -> io::Result<MountNamespace> {
        Ok(MountNamespace { file: File::open("/proc/thread-self/ns/mnt").or_else(|_| File::open("/proc/self/ns/mnt"))? })
    }

    /// Moves the calling thread into this namespace.
    pub fn enter(&self) -> io::Result<()> {
        Platform::enter_mount_namespace(&self.file)
    }

    /// Whether this handle refers to the same namespace as `other`.
    pub fn is(&self, other: &MountNamespace) -> io::Result<bool> {
        let (this, other) = (self.file.metadata()?, other.file.metadata()?);
        Ok(this.dev() == other.dev() && this.ino() == other.ino())
    }

    /// Moves the calling thread into a new mount namespace that starts as a copy of the
    /// current one, and makes every mount in it private, so that unmounting or mounting
    /// inside it never affects any other namespace.
    pub fn isolate() -> Result<MountNamespace, MountError> {
        Platform::unshare_mount_namespace().map_err(|source| MountError {
            operation: "unshare mount namespace",
            path: Path::new("/").to_path_buf(),
            source,
        })?;
        Mount::make_private_recursive(Path::new("/"))?;
        MountNamespace::current().map_err(|source| MountError { operation: "open mount namespace", path: "/proc/self/ns/mnt".into(), source })
    }

    /// Whether the namespace file still exists (the process may have exited).
    pub fn exists(pid: u32) -> bool {
        fs::symlink_metadata(format!("/proc/{pid}/ns/mnt")).is_ok()
    }
}
