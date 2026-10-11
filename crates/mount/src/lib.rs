//! Linux mount namespaces, bind and tmpfs mounts, `/proc/<pid>/mountinfo` parsing and
//! SELinux file labels.
//!
//! The system calls are provided by `Platform`. On operating systems other than Linux and
//! Android, `Platform` fails every call with `ErrorKind::Unsupported`, while the parsing
//! types stay fully usable.

mod mountinfo;
mod namespace;
mod operation;
mod selinux;

#[cfg(any(target_os = "linux", target_os = "android"))]
#[path = "platform/linux.rs"]
mod platform;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
#[path = "platform/unsupported.rs"]
mod platform;

pub use mountinfo::{MountEntry, MountTable};
pub use namespace::MountNamespace;
pub use operation::{Mount, MountError};
pub use platform::Platform;
pub use selinux::SELinuxContext;
