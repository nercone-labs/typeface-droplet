use std::thread;
use std::time::{Duration, Instant};

use android::{FontService, ProcessInfo, ProcessTable, SystemProperty};

use crate::{Daemon, DropletError, FontStore, GuardState, Log, State};

/// Which system_server a guard watches.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GuardTarget {
    /// The running system_server with this process ID. If it is already gone when the guard
    /// starts, the guard trips at once.
    Running(u32),
    /// The first system_server whose process ID is not this one: the one started after a
    /// restart of zygote.
    After(u32),
    /// The first system_server of a boot that has not started the framework yet. The watch
    /// lasts until the boot completes, and then for the given time.
    Boot,
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
    /// How long a boot may take from the application of the documents until it completes.
    /// The first boot after a system update optimizes applications and may take many minutes;
    /// a zygote that crashes while it starts is caught much sooner, by counting its starts.
    pub const BOOT_TIMEOUT: Duration = Duration::from_secs(1800);
    /// How many times zygote may start before the first system_server of a boot appears.
    pub const MAX_ZYGOTE_STARTS: usize = 3;
    /// The process names of zygote, which preloads the fonts of every application.
    pub const ZYGOTES: [&'static str; 2] = ["zygote64", "zygote"];
    /// The shortest watch after a boot: without a guard, a configuration that breaks
    /// system_server would be applied at every boot again.
    pub const MINIMUM_BOOT_SECONDS: u32 = 30;
    /// The first argument of guard processes, used to recognize them.
    pub const COMMAND: &'static str = "guard";
    /// The properties that tell a requested shutdown and its reason: set by the framework when
    /// it starts to shut down, and by init (`sys.powerctl`) for every shutdown and reboot.
    pub const SHUTDOWN_PROPERTIES: [&'static str; 2] = ["sys.shutdown.requested", "sys.powerctl"];

    /// Starts a detached guard process.
    pub fn spawn(store: &FontStore, seconds: u32, target: GuardTarget) -> Result<u32, DropletError> {
        let mut arguments =
            vec![Guard::COMMAND.to_string(), "--store".to_string(), store.root.to_string_lossy().into_owned(), "--seconds".to_string(), seconds.to_string()];
        match target {
            GuardTarget::Running(pid) => arguments.extend(["--pid".to_string(), pid.to_string()]),
            GuardTarget::After(pid) => arguments.extend(["--after".to_string(), pid.to_string()]),
            GuardTarget::Boot => arguments.push("--boot".to_string()),
        }
        Daemon::spawn(&arguments)
    }

    /// Stops the guard recorded in `state`, if it is still running.
    pub fn stop(state: &State, store: &FontStore) {
        if let GuardState::Watching { guard, .. } = state.guard
            && Guard::is_guard(guard, store)
            && let Some(process) = ProcessInfo::of(guard)
        {
            let _ = process.kill();
        }
    }

    /// Whether `pid` is a guard of `store`, run by any copy of the engine (the application's own,
    /// or the copy of the store that the boot hook runs). Its command line names both, so a
    /// reused process ID is never mistaken for a guard.
    pub fn is_guard(pid: u32, store: &FontStore) -> bool {
        Daemon::arguments(pid).is_some_and(|arguments| Guard::is_guard_command(&arguments, &store.root.to_string_lossy()))
    }

    /// Whether `arguments` (without the program) are those of a guard of the store at `root`.
    pub fn is_guard_command(arguments: &[String], root: &str) -> bool {
        arguments.first().is_some_and(|command| command == Guard::COMMAND) && arguments.windows(2).any(|pair| pair[0] == "--store" && pair[1] == root)
    }

    /// How long the watch of `target` may last at most, for the state shown to users.
    pub fn longest(seconds: u32, target: GuardTarget) -> u64 {
        u64::from(seconds)
            + match target {
                GuardTarget::Running(_) => 0,
                GuardTarget::After(_) => Guard::START_TIMEOUT.as_secs(),
                GuardTarget::Boot => Guard::BOOT_TIMEOUT.as_secs(),
            }
    }

    /// The single system_server process, if exactly one is running.
    pub fn system_server() -> Option<ProcessInfo> {
        let mut servers = ProcessTable::named(ProcessInfo::SYSTEM_SERVER).ok()?;
        if servers.len() == 1 { servers.pop() } else { None }
    }

    /// Waits for a single system_server whose process ID is not `previous`.
    pub fn wait_for_server(previous: Option<u32>, timeout: Duration) -> Option<ProcessInfo> {
        let started = Instant::now();
        loop {
            match Guard::system_server() {
                Some(server) if Some(server.pid) != previous => return Some(server),
                _ if started.elapsed() > timeout => return None,
                _ => thread::sleep(Guard::POLL_INTERVAL),
            }
        }
    }

    /// Waits for the first system_server of a boot. Fails when zygote keeps crashing before it
    /// can start one (it preloads the fonts), or when the boot does not get that far in time.
    pub fn wait_for_first_server() -> Result<ProcessInfo, Ending> {
        let started = Instant::now();
        let mut zygotes: Vec<u32> = Vec::new();
        loop {
            if let Some(server) = Guard::system_server() {
                return Ok(server);
            }
            if let Some(reason) = Guard::shutdown_request() {
                return Err(if Watch::is_rescue(&reason) {
                    Ending::Failed(format!("the system restarted to rescue itself ({reason})"))
                } else {
                    Ending::Interrupted(reason)
                });
            }
            for name in Guard::ZYGOTES {
                for process in ProcessTable::named(name).unwrap_or_default() {
                    if !zygotes.contains(&process.pid) {
                        zygotes.push(process.pid);
                    }
                }
            }
            if zygotes.len() > Guard::MAX_ZYGOTE_STARTS {
                return Err(Ending::Failed(format!("zygote crashed {} times while starting", zygotes.len() - 1)));
            }
            if started.elapsed() > Guard::BOOT_TIMEOUT {
                return Err(Ending::Failed("system_server did not start".to_string()));
            }
            thread::sleep(Guard::POLL_INTERVAL);
        }
    }

    /// Checks, once the boot has completed, that the installed fonts are still in their
    /// directories: a module mounted after the boot hook may have covered them, and the
    /// documents would then name files that do not exist.
    pub fn check_installed_fonts(store: &FontStore, log: &mut Log) -> Option<Ending> {
        let state = State::load(&store.state_path(), log).ok()?;
        let missing: Vec<&String> =
            state.fonts.iter().filter(|name| !state.directories.iter().any(|directory| directory.join(name.as_str()).exists())).collect();
        if missing.is_empty() {
            None
        } else {
            Some(Ending::Failed(format!(
                "another module covered the installed fonts ({})",
                missing.iter().map(|name| name.as_str()).collect::<Vec<_>>().join(", ")
            )))
        }
    }

    /// Waits until the engine that started this guard has recorded it in the state, which it
    /// does right after starting it, so that a verdict reached at once is not lost.
    pub fn await_ownership(store: &FontStore, log: &mut Log) {
        let started = Instant::now();
        while started.elapsed() < Watch::DECISION_TIMEOUT {
            if State::load(&store.state_path(), log).is_ok_and(|state| Guard::owns(&state)) {
                return;
            }
            thread::sleep(Guard::POLL_INTERVAL);
        }
    }

    /// Whether the state still names this process as its guard. A guard that was replaced (by
    /// a later application, a restoration or a soft restart) has nothing more to decide.
    pub fn owns(state: &State) -> bool {
        matches!(state.guard, GuardState::Watching { guard, .. } if guard == std::process::id())
    }

    /// Runs the watch in the calling process. Returns the outcome, which is also saved.
    pub fn run(store: &FontStore, seconds: u32, target: GuardTarget, log: &mut Log) -> Result<GuardState, DropletError> {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            let _ = rustix::process::setsid();
            // At a shutdown, init asks every process to end before it kills them. The guard
            // stays until it has seen the shutdown request and recorded that the watch was
            // interrupted, rather than tripped by the restart.
            // SAFETY: setting a disposition to SIG_IGN installs no handler and touches no memory.
            unsafe { libc::signal(libc::SIGTERM, libc::SIG_IGN) };
        }
        Guard::await_ownership(store, log);
        let server = match target {
            GuardTarget::Running(pid) => match ProcessInfo::of(pid).filter(|process| process.name == ProcessInfo::SYSTEM_SERVER) {
                Some(server) => server,
                None => return Guard::finish(store, Ending::Failed(format!("system_server (pid {pid}) died before the watch began")), log),
            },
            GuardTarget::After(previous) => match Guard::wait_for_server(Some(previous), Guard::START_TIMEOUT) {
                Some(server) => server,
                None => return Guard::finish(store, Ending::Failed("system_server did not start".to_string()), log),
            },
            GuardTarget::Boot => match Guard::wait_for_first_server() {
                Ok(server) => server,
                Err(ending) => return Guard::finish(store, ending, log),
            },
        };
        let started = Instant::now();
        let mut watch = Watch::new(server);
        if target == GuardTarget::Boot {
            while !SystemProperty::is_boot_completed() {
                if let Some(ending) = watch.observe() {
                    return Guard::finish(store, ending, log);
                }
                if started.elapsed() > Guard::BOOT_TIMEOUT {
                    return Guard::finish(store, Ending::Failed("the system did not finish starting".to_string()), log);
                }
                thread::sleep(Guard::POLL_INTERVAL);
            }
            if let Some(ending) = Guard::check_installed_fonts(store, log) {
                return Guard::finish(store, ending, log);
            }
        }
        let deadline = Instant::now() + Duration::from_secs(u64::from(seconds));
        while Instant::now() < deadline {
            if let Some(ending) = watch.observe() {
                return Guard::finish(store, ending, log);
            }
            thread::sleep(Guard::POLL_INTERVAL);
        }
        Guard::finish(store, Ending::Passed, log)
    }

    /// The reason of a shutdown or reboot that has been requested, if any.
    pub fn shutdown_request() -> Option<String> {
        Guard::SHUTDOWN_PROPERTIES.iter().find_map(|name| SystemProperty::get(name)).filter(|reason| !reason.trim().is_empty())
    }

    /// Saves how the watch ended, unless this guard has been replaced. A failure removes the
    /// generated documents first, without waiting for the lock, so that a restarted
    /// system_server does not find them; while system_server still runs, the font service
    /// reloads the original documents.
    pub fn finish(store: &FontStore, ending: Ending, log: &mut Log) -> Result<GuardState, DropletError> {
        let current = State::load(&store.state_path(), log)?;
        if !Guard::owns(&current) {
            log.info("guard: replaced by a later operation; the watch ends without a verdict");
            return Ok(current.guard);
        }
        if let Ending::Failed(reason) = &ending {
            log.error(format!("guard: {reason}; removing the generated documents"));
            let removed = crate::Engine::detach_documents(log);
            log.info(format!("guard: removed {removed} generated documents"));
            if Guard::system_server().is_some()
                && let Err(error) = FontService::restart()
            {
                log.error(format!("guard: the font service failed to reload the original documents: {error}"));
            }
        }
        let _lock = store.lock()?;
        let mut state = State::load(&store.state_path(), log)?;
        if !Guard::owns(&state) {
            return Ok(state.guard);
        }
        state.guard = match ending {
            Ending::Failed(reason) => {
                state.applied = false;
                state.documents.clear();
                GuardState::Tripped { reason, time: Log::now() }
            }
            Ending::Passed => {
                log.info("guard: system_server stayed up; the configuration is stable");
                GuardState::Passed
            }
            Ending::Interrupted(reason) => {
                log.info(format!("guard: the device shuts down ({reason}) while the system runs; the watch ends without a verdict"));
                GuardState::Idle
            }
        };
        state.save(&store.state_path())?;
        Ok(state.guard)
    }
}

/// How a watch ended.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Ending {
    /// The system stayed up for the whole watch.
    Passed,
    /// The system failed; the reason is shown to the user.
    Failed(String),
    /// The user shut down or restarted the device while the system was running fine.
    Interrupted(String),
}

/// What a guard watches: one system_server, the shutdown properties, and the processes of
/// the system interface, which fonts it cannot render may crash again and again.
pub struct Watch {
    pub server: ProcessInfo,
    /// The process IDs the system interface has had during the watch.
    pub interfaces: Vec<u32>,
    pub observations: u32,
}

impl Watch {
    pub const INTERFACE: &'static str = "com.android.systemui";
    /// How many times the system interface may restart during a watch. Applying restarts it
    /// once on purpose; a system interface that crashes more often than this trips the guard,
    /// before the rescue party of the system starts to reset settings.
    pub const MAX_INTERFACE_RESTARTS: usize = 3;
    /// The system interface is looked up every this many observations; it needs a scan of `/proc`.
    pub const INTERFACE_PERIOD: u32 = 10;
    /// The state init gives the zygote service: `running`, `restarting`, `stopping` or `stopped`.
    pub const ZYGOTE_STATE: &'static str = "init.svc.zygote";
    /// How long to wait for init to restart or stop zygote after system_server ends.
    pub const DECISION_TIMEOUT: Duration = Duration::from_secs(5);

    pub fn new(server: ProcessInfo) -> Watch {
        Watch { server, interfaces: Vec::new(), observations: 0 }
    }

    /// Looks at the system once. Returns how the watch ends, or `None` while all is well.
    /// A shutdown is looked at first: during a shutdown, system_server ends as well.
    pub fn observe(&mut self) -> Option<Ending> {
        if let Some(reason) = Guard::shutdown_request() {
            return Some(if Watch::is_rescue(&reason) {
                Ending::Failed(format!("the system restarted to rescue itself ({reason})"))
            } else {
                Ending::Interrupted(reason)
            });
        }
        if !self.server.is_alive() {
            return Some(self.after_server_death());
        }
        self.observations += 1;
        if self.observations % Watch::INTERFACE_PERIOD == 1 {
            for process in ProcessTable::named(Watch::INTERFACE).unwrap_or_default() {
                if !self.interfaces.contains(&process.pid) {
                    self.interfaces.push(process.pid);
                }
            }
            if self.interfaces.len() > Watch::MAX_INTERFACE_RESTARTS + 1 {
                return Some(Ending::Failed(format!("the system interface restarted {} times", self.interfaces.len() - 1)));
            }
        }
        None
    }

    /// Tells why system_server ended. A crash ends zygote too, and init restarts it; a shutdown
    /// or a stop of the framework stops zygote for good. Until init has decided, which takes
    /// moments, the cause is unknown; if it stays unknown, the death counts as a crash.
    pub fn after_server_death(&self) -> Ending {
        let crash = Ending::Failed(format!("system_server (pid {}) died", self.server.pid));
        let started = Instant::now();
        while started.elapsed() < Watch::DECISION_TIMEOUT {
            if let Some(reason) = Guard::shutdown_request() {
                return if Watch::is_rescue(&reason) { crash } else { Ending::Interrupted(reason) };
            }
            match SystemProperty::get(Watch::ZYGOTE_STATE).as_deref().map(str::trim) {
                Some("stopping" | "stopped") => return Ending::Interrupted("the framework was stopped".to_string()),
                Some("restarting") => return crash,
                _ => {}
            }
            if Guard::system_server().is_some_and(|server| server.pid != self.server.pid) {
                return crash;
            }
            thread::sleep(Guard::POLL_INTERVAL);
        }
        crash
    }

    /// Whether a shutdown reason names the rescue party, which restarts a system that keeps crashing.
    pub fn is_rescue(reason: &str) -> bool {
        reason.to_ascii_lowercase().contains("rescue")
    }
}
