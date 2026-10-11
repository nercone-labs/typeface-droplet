use std::ffi::CStr;
use std::fs::File;
use std::io;
use std::path::Path;

/// The system calls used by this crate, on operating systems that do not provide them.
/// Every call fails with `ErrorKind::Unsupported`, so that the rest of the crate,
/// such as mountinfo parsing, stays usable and testable everywhere.
pub struct Platform;

impl Platform {
    pub const SUPPORTED: bool = false;

    pub fn unsupported() -> io::Error {
        io::Error::new(io::ErrorKind::Unsupported, "mount operations require Linux")
    }

    pub fn bind(_source: &Path, _target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn bind_recursive(_source: &Path, _target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn tmpfs(_source: &str, _target: &Path, _options: &CStr) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn remount_bind_readonly(_target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn detach(_target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn make_private_recursive(_target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn make_slave_recursive(_target: &Path) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn enter_mount_namespace(_namespace: &File) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn unshare_mount_namespace() -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn set_xattr(_path: &Path, _name: &str, _value: &[u8]) -> io::Result<()> {
        Err(Platform::unsupported())
    }

    pub fn get_xattr(_path: &Path, _name: &str) -> io::Result<Vec<u8>> {
        Err(Platform::unsupported())
    }
}
