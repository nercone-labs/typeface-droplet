use std::ffi::CString;
use std::fmt;
use std::io;
use std::path::{Path, PathBuf};

use crate::Platform;

/// A failed mount operation, with the operation and the path it was applied to.
#[derive(Debug)]
pub struct MountError {
    pub operation: &'static str,
    pub path: PathBuf,
    pub source: io::Error,
}

impl fmt::Display for MountError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} {}: {}", self.operation, self.path.display(), self.source)
    }
}

impl std::error::Error for MountError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
}

/// Mount operations on the calling process's mount namespace.
pub struct Mount;

impl Mount {
    pub fn result(operation: &'static str, path: &Path, result: io::Result<()>) -> Result<(), MountError> {
        result.map_err(|source| MountError { operation, path: path.to_path_buf(), source })
    }

    /// Bind-mounts the file or directory `source` onto `target`.
    pub fn bind(source: &Path, target: &Path) -> Result<(), MountError> {
        Mount::result("bind", target, Platform::bind(source, target))
    }

    /// Bind-mounts `source` onto `target` together with every mount below `source`.
    pub fn bind_recursive(source: &Path, target: &Path) -> Result<(), MountError> {
        Mount::result("bind recursively", target, Platform::bind_recursive(source, target))
    }

    /// Mounts a new tmpfs named `source` (shown in mountinfo) on `target`, with `nosuid` and `nodev`, using the given
    /// comma-separated mount options, such as `mode=0755`.
    pub fn tmpfs(source: &str, target: &Path, options: &str) -> Result<(), MountError> {
        let options = CString::new(options).map_err(|_| MountError {
            operation: "mount tmpfs",
            path: target.to_path_buf(),
            source: io::Error::new(io::ErrorKind::InvalidInput, "mount options contain a NUL byte"),
        })?;
        Mount::result("mount tmpfs", target, Platform::tmpfs(source, target, &options))
    }

    /// Makes an existing bind mount read-only, and also `nosuid`, `nodev` and `noexec`
    /// (a remount replaces every per-mount flag).
    pub fn remount_bind_readonly(target: &Path) -> Result<(), MountError> {
        Mount::result("remount read-only", target, Platform::remount_bind_readonly(target))
    }

    /// Detaches the topmost mount at `target` (a lazy unmount: `umount2(MNT_DETACH)`).
    /// Processes that still use files of the mount keep using them until they close them.
    pub fn detach(target: &Path) -> Result<(), MountError> {
        Mount::result("detach", target, Platform::detach(target))
    }

    /// Makes `target` and every mount below it private, so that later mount and unmount
    /// events neither propagate to nor arrive from other mounts.
    pub fn make_private_recursive(target: &Path) -> Result<(), MountError> {
        Mount::result("make private", target, Platform::make_private_recursive(target))
    }

    /// Makes `target` and every mount below it slaves of their peer groups: they keep
    /// receiving mount and unmount events from their peers, but no longer send any.
    /// Unmounting a slave therefore never unmounts its former peers.
    pub fn make_slave_recursive(target: &Path) -> Result<(), MountError> {
        Mount::result("make slave", target, Platform::make_slave_recursive(target))
    }
}
