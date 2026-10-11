use std::ffi::CStr;
use std::fs::File;
use std::io;
use std::os::fd::AsFd;
use std::path::Path;

use rustix::fs::XattrFlags;
use rustix::mount::{MountFlags, MountPropagationFlags, UnmountFlags};
use rustix::thread::{LinkNameSpaceType, UnshareFlags};

/// The system calls used by this crate, on Linux and Android.
pub struct Platform;

impl Platform {
    pub const SUPPORTED: bool = true;

    pub fn bind(source: &Path, target: &Path) -> io::Result<()> {
        Ok(rustix::mount::mount_bind(source, target)?)
    }

    pub fn bind_recursive(source: &Path, target: &Path) -> io::Result<()> {
        Ok(rustix::mount::mount_bind_recursive(source, target)?)
    }

    pub fn tmpfs(source: &str, target: &Path, options: &CStr) -> io::Result<()> {
        Ok(rustix::mount::mount(source, target, "tmpfs", MountFlags::NOSUID | MountFlags::NODEV, options)?)
    }

    pub fn remount_bind_readonly(target: &Path) -> io::Result<()> {
        Ok(rustix::mount::mount_remount(target, MountFlags::BIND | MountFlags::RDONLY | MountFlags::NOSUID | MountFlags::NODEV | MountFlags::NOEXEC, "")?)
    }

    pub fn detach(target: &Path) -> io::Result<()> {
        Ok(rustix::mount::unmount(target, UnmountFlags::DETACH)?)
    }

    pub fn make_private_recursive(target: &Path) -> io::Result<()> {
        Ok(rustix::mount::mount_change(target, MountPropagationFlags::PRIVATE | MountPropagationFlags::REC)?)
    }

    pub fn make_slave_recursive(target: &Path) -> io::Result<()> {
        Ok(rustix::mount::mount_change(target, MountPropagationFlags::DOWNSTREAM | MountPropagationFlags::REC)?)
    }

    pub fn enter_mount_namespace(namespace: &File) -> io::Result<()> {
        Ok(rustix::thread::move_into_link_name_space(namespace.as_fd(), Some(LinkNameSpaceType::Mount))?)
    }

    pub fn unshare_mount_namespace() -> io::Result<()> {
        // SAFETY: only the mount namespace (and, implied by it, the file system attributes)
        // is unshared; the file descriptor table stays shared, which is the case the
        // safety contract of `unshare_unsafe` is about.
        Ok(unsafe { rustix::thread::unshare_unsafe(UnshareFlags::NEWNS) }?)
    }

    pub fn set_xattr(path: &Path, name: &str, value: &[u8]) -> io::Result<()> {
        Ok(rustix::fs::lsetxattr(path, name, value, XattrFlags::empty())?)
    }

    pub fn get_xattr(path: &Path, name: &str) -> io::Result<Vec<u8>> {
        let mut buffer = vec![0u8; 256];
        loop {
            match rustix::fs::lgetxattr(path, name, &mut buffer[..]) {
                Ok(length) => {
                    buffer.truncate(length);
                    return Ok(buffer);
                }
                Err(rustix::io::Errno::RANGE) if buffer.len() < 65536 => buffer.resize(buffer.len() * 4, 0),
                Err(error) => return Err(error.into()),
            }
        }
    }
}
