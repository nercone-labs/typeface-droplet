use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

use crate::DropletError;

/// A detached process that runs this engine executable with other arguments.
///
/// It runs in a session of its own (and so in a process group of its own), with no standard
/// streams, so that neither the end of the session that started the engine nor a closed pipe
/// ends it.
pub struct Daemon;

impl Daemon {
    /// Starts the engine with `arguments` and returns its process ID.
    pub fn spawn(arguments: &[String]) -> Result<u32, DropletError> {
        let executable = std::env::current_exe().map_err(|error| DropletError::io("find the engine executable", error))?;
        let mut command = Command::new(executable);
        command.args(arguments).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        Daemon::detach(&mut command);
        let child = command.spawn().map_err(|error| DropletError::io(format!("start {}", arguments.first().map_or("the engine", String::as_str)), error))?;
        Ok(child.id())
    }

    /// Makes the child the leader of a new session. `setsid` refuses a process that already
    /// leads a process group, so the child must not be made one first (`process_group(0)`): it
    /// calls `setsid` itself, between `fork` and `exec`, when it is a member of the parent's group.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn detach(command: &mut Command) {
        // SAFETY: the closure runs in the child between fork and exec; it only calls setsid,
        // which is async-signal-safe, and reads errno.
        unsafe {
            command.pre_exec(|| if libc::setsid() < 0 { Err(std::io::Error::last_os_error()) } else { Ok(()) });
        }
    }

    /// On other systems (where the engine is only built and tested), a process group of its own.
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn detach(command: &mut Command) {
        command.process_group(0);
    }
}
