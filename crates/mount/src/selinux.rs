use std::io;
use std::path::Path;

use crate::Platform;

/// The SELinux security context of a file, stored in the `security.selinux` extended attribute.
pub struct SELinuxContext;

impl SELinuxContext {
    pub const ATTRIBUTE: &'static str = "security.selinux";
    /// The context of files on the system partition, readable by every Android domain.
    pub const SYSTEM_FILE: &'static str = "u:object_r:system_file:s0";

    /// Reads the context of `path` without following a final symbolic link.
    pub fn get(path: &Path) -> io::Result<String> {
        let value = Platform::get_xattr(path, SELinuxContext::ATTRIBUTE)?;
        let end = value.iter().position(|byte| *byte == 0).unwrap_or(value.len());
        String::from_utf8(value[..end].to_vec()).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "context is not UTF-8"))
    }

    /// Sets the context of `path` without following a final symbolic link.
    /// The value is stored NUL-terminated, as libselinux's `setfilecon` stores it.
    pub fn set(path: &Path, context: &str) -> io::Result<()> {
        let mut value = Vec::with_capacity(context.len() + 1);
        value.extend_from_slice(context.as_bytes());
        value.push(0);
        Platform::set_xattr(path, SELinuxContext::ATTRIBUTE, &value)
    }
}
