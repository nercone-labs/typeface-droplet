use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use android::{ProcessInfo, ProcessTable};

use crate::{DropletError, FontStore, GuardState, Log, State};

/// Which system_server a guard watches.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GuardTarget {
    /// The running system_server with this process ID. If it is already gone when the guard
    /// starts, the guard trips at once.
    Running(u32),
    /// The first system_server whose process ID is not this one: the one started after a
    /// restart of zygote.
    After(u32),
}

/// Watches system_server after the font configuration is reloaded. If system_server dies
/// during the watch, the generated documents are removed at once, so that the restarted
/// system_server reads the original configuration. This prevents a broken font from
/// sending the device into a boot loop.
pub struct Guard;

impl Guard {
    pub const POLL_INTERVAL: Duration = Duration::from_millis(100);
    /// How long to wait for a new system_server after a restart of zygote.
    pub const START_TIMEOUT: Duration = Duration::from_secs(120);
    /// The first argument of guard processes, used to recognize them.
    pub const COMMAND: &'static str = "guard";

    /// Starts a detached guard process running this executable, in a process group of its
    /// own, so that the end of the session that started the engine does not end the guard.
    pub fn spawn(store: &FontStore, seconds: u32, target: GuardTarget) -> Result<u32, DropletError> {
        let executable = std::env::current_exe().map_err(|error| DropletError::io("find the engine executable", error))?;
        let mut command = Command::new(executable);
        command.arg(Guard::COMMAND).arg("--store").arg(&store.root).arg("--seconds").arg(seconds.to_string());
        match target {
            GuardTarget::Running(pid) => command.arg("--pid").arg(pid.to_string()),
            GuardTarget::After(pid) => command.arg("--after").arg(pid.to_string()),
        };
        let child = command
            .process_group(0)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| DropletError::io("start the guard", error))?;
        Ok(child.id())
    }

    /// Stops the guard recorded in `state`, if it is still running.
    pub fn stop(state: &State) {
        if let GuardState::Watching { guard, .. } = state.guard
            && Guard::is_guard(guard)
            && let Some(process) = ProcessInfo::of(guard)
        {
            let _ = process.kill();
        }
    }

    /// Whether `pid` is a guard process of this engine: it runs this executable with the
    /// guard command. Checking both keeps a reused process ID from being mistaken for a guard.
    pub fn is_guard(pid: u32) -> bool {
        let command = std::fs::read(format!("/proc/{pid}/cmdline"))
            .map(|cmdline| cmdline.split(|byte| *byte == 0).nth(1) == Some(Guard::COMMAND.as_bytes()))
            .unwrap_or(false);
        let executable: Option<PathBuf> = std::fs::read_link(format!("/proc/{pid}/exe")).ok();
        command && executable.is_some() && executable == std::env::current_exe().ok()
    }

    /// The single system_server process, if exactly one is running.
    pub fn system_server() -> Option<ProcessInfo> {
        let mut servers = ProcessTable::named(ProcessInfo::SYSTEM_SERVER).ok()?;
        if servers.len() == 1 { servers.pop() } else { None }
    }

    /// Runs the watch in the calling process. Returns the outcome, which is also saved.
    pub fn run(store: &FontStore, seconds: u32, target: GuardTarget, log: &mut Log) -> Result<GuardState, DropletError> {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        let _ = rustix::process::setsid();
        let server = match target {
            GuardTarget::Running(pid) => match ProcessInfo::of(pid).filter(|process| process.name == ProcessInfo::SYSTEM_SERVER) {
                Some(server) => server,
                None => return Guard::finish(store, Some(format!("system_server (pid {pid}) died before the watch began")), log),
            },
            GuardTarget::After(previous) => {
                let started = Instant::now();
                loop {
                    match Guard::system_server() {
                        Some(server) if server.pid != previous => break server,
                        _ if started.elapsed() > Guard::START_TIMEOUT => {
                            return Guard::finish(store, Some("system_server did not start".to_string()), log);
                        }
                        _ => thread::sleep(Guard::POLL_INTERVAL),
                    }
                }
            }
        };
        let deadline = Instant::now() + Duration::from_secs(u64::from(seconds));
        while Instant::now() < deadline {
            if !server.is_alive() {
                return Guard::finish(store, Some(format!("system_server (pid {}) died during the watch", server.pid)), log);
            }
            thread::sleep(Guard::POLL_INTERVAL);
        }
        Guard::finish(store, None, log)
    }

    pub fn finish(store: &FontStore, failure: Option<String>, log: &mut Log) -> Result<GuardState, DropletError> {
        // Remove the documents first, without waiting for the lock: the restarted
        // system_server must not find them.
        if let Some(reason) = &failure {
            log.error(format!("guard: {reason}; removing the generated documents"));
            let removed = crate::Engine::detach_documents(log);
            log.info(format!("guard: removed {removed} generated documents"));
        }
        let _lock = store.lock()?;
        let mut state = State::load(&store.state_path(), log)?;
        state.guard = match failure {
            Some(reason) => {
                state.applied = false;
                state.documents.clear();
                GuardState::Tripped { reason, time: Log::now() }
            }
            None => {
                log.info("guard: system_server stayed up; the configuration is stable");
                GuardState::Passed
            }
        };
        state.save(&store.state_path())?;
        Ok(state.guard)
    }
}
