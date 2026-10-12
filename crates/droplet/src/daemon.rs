use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

use crate::DropletError;

/// A detached process that runs this engine executable with other arguments.
///
/// It runs in a process group of its own, with no standard streams, so that neither the end
/// of the session that started the engine nor a closed pipe ends it.
pub struct Daemon;

impl Daemon {
    /// Starts the engine with `arguments` and returns its process ID.
    pub fn spawn(arguments: &[String]) -> Result<u32, DropletError> {
        let executable = std::env::current_exe().map_err(|error| DropletError::io("find the engine executable", error))?;
        let child = Command::new(executable)
            .args(arguments)
            .process_group(0)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| DropletError::io(format!("start {}", arguments.first().map_or("the engine", String::as_str)), error))?;
        Ok(child.id())
    }

    /// The arguments of the process `pid`, without the program, or `None` if it is gone.
    pub fn arguments(pid: u32) -> Option<Vec<String>> {
        let cmdline = std::fs::read(format!("/proc/{pid}/cmdline")).ok()?;
        Some(
            cmdline
                .split(|byte| *byte == 0)
                .skip(1)
                .filter(|argument| !argument.is_empty())
                .map(|argument| String::from_utf8_lossy(argument).into_owned())
                .collect(),
        )
    }
}
