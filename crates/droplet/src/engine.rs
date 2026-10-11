use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs;
use std::io;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

use android::{ActivityManager, Capability, FontService, PackageManager, ProcessCapabilities, ProcessTable, SystemProperty};
use fontxml::{Document, Element};
use mount::{Mount, MountNamespace, MountTable};
use serde::Serialize;

use crate::{
    Components, Config, DocumentKind, DropletError, FontDirectory, FontStore, GenerationReport, Generator, Guard, GuardState, GuardTarget, InstalledFont, Log,
    MountedDocument, State,
};

/// The outcome of `Engine::apply`.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyReport {
    pub generation: u64,
    pub documents: BTreeMap<DocumentKind, GenerationReport>,
    pub fonts: Vec<InstalledFont>,
    /// Whether the font service now serves the generated configuration; `None` when the
    /// configuration refers to no installed font, so there is nothing to look for.
    pub reloaded: Option<bool>,
    pub restarted: Vec<String>,
    pub guard: Option<u32>,
}

/// One family of a system document, as listed for user interfaces.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FamilyInfo {
    pub kind: DocumentKind,
    /// `family`, `family-list` or `alias`.
    pub element: String,
    pub name: Option<String>,
    pub languages: Vec<String>,
    pub variant: Option<String>,
    pub files: Vec<String>,
    /// For an alias: the family it refers to.
    pub to: Option<String>,
}

/// The state of the system as the engine sees it.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusReport {
    pub api: Option<u32>,
    pub uid: u32,
    /// Whether the system shows a generated document (of any store) now.
    pub applied: bool,
    pub generation: u64,
    /// Documents currently covered by a generated document of the store.
    pub mounted: Vec<DocumentKind>,
    pub directories: Vec<PathBuf>,
    /// Installed fonts the font service is currently serving.
    pub served: Vec<String>,
    pub guard: GuardState,
    pub components: Vec<crate::ComponentRecord>,
    pub documents: Vec<DocumentKind>,
}

/// The engine: applies and reverts configurations on the running system.
///
/// Every mount is made in the mount namespace of init, which the processes started from
/// zygote descend from; the engine moves itself there first. It must run as root in a
/// single-threaded process.
pub struct Engine {
    pub store: FontStore,
    pub log: Log,
}

impl Engine {
    pub fn new(store: FontStore) -> Engine {
        let log = Log::new(Some(store.log_path()).filter(|path| path.parent().is_some_and(|parent| parent.is_dir())));
        Engine { store, log }
    }

    pub fn uid() -> u32 {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        return rustix::process::geteuid().as_raw();
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        return u32::MAX;
    }

    /// The capabilities the engine uses. Running as uid 0 does not imply them: a root manager
    /// can drop them (the "Restrict" policy of Magisk drops every one).
    pub const CAPABILITIES: [Capability; 5] = [
        // Copies in skeletons keep the owners of the originals.
        Capability::CHOWN,
        // Configurations and fonts are read from the private directory of the application.
        Capability::DAC_READ_SEARCH,
        // Processes of other users are restarted.
        Capability::KILL,
        // `/proc` is mounted with `hidepid`, and shows init only with it.
        Capability::SYS_PTRACE,
        // Mounts and mount namespaces.
        Capability::SYS_ADMIN,
    ];

    /// Requires running as root (uid 0) with every capability in `Engine::CAPABILITIES`.
    pub fn require_root() -> Result<(), DropletError> {
        let uid = Engine::uid();
        if uid != 0 {
            return Err(DropletError::Refused(format!("the engine must run as root (uid 0), not uid {uid}")));
        }
        let capabilities = ProcessCapabilities::current().map_err(|error| DropletError::io("read the capabilities of the engine", error))?;
        let missing: Vec<Capability> = Engine::CAPABILITIES.into_iter().filter(|capability| !capabilities.effective.contains(*capability)).collect();
        if missing.is_empty() { Ok(()) } else { Err(DropletError::Restricted(missing)) }
    }

    pub fn enter_init_namespace() -> Result<MountNamespace, DropletError> {
        Engine::require_root()?;
        let namespace = MountNamespace::init().map_err(|error| DropletError::io("open the mount namespace of init", error))?;
        namespace.enter().map_err(|error| DropletError::io("enter the mount namespace of init", error))?;
        Ok(namespace)
    }

    pub fn api() -> u32 {
        SystemProperty::sdk().unwrap_or(0)
    }

    /// Detaches every generated document from the system documents, in the mount namespace
    /// of init. Returns how many mounts were detached.
    pub fn detach_documents(log: &mut Log) -> usize {
        if let Err(error) = Engine::enter_init_namespace() {
            log.error(error.to_string());
            return 0;
        }
        match Engine::detach_generated() {
            Ok(detached) => detached,
            Err(error) => {
                log.error(error.to_string());
                0
            }
        }
    }

    /// Detaches, in the current mount namespace, every mount that shows a generated document.
    /// Generated documents may be stacked; they are removed topmost first.
    pub fn detach_generated() -> Result<usize, DropletError> {
        let mut detached = 0;
        for kind in DocumentKind::ALL {
            loop {
                let table = MountTable::current().map_err(|error| DropletError::io("read mountinfo", error))?;
                if !kind.shows_generated(&table) {
                    break;
                }
                Mount::detach(&kind.mount_point())?;
                detached += 1;
            }
        }
        Ok(detached)
    }

    /// Reads the original system documents: what the system shows with the generated
    /// documents removed. When some are mounted, they are removed inside a private copy of
    /// the mount namespace, which affects no other process.
    pub fn originals(&mut self) -> Result<BTreeMap<DocumentKind, Option<String>>, DropletError> {
        let global = MountNamespace::current().map_err(|error| DropletError::io("open the current mount namespace", error))?;
        let table = MountTable::current().map_err(|error| DropletError::io("read mountinfo", error))?;
        let covered = DocumentKind::ALL.iter().any(|kind| kind.shows_generated(&table));
        if covered {
            MountNamespace::isolate()?;
            Engine::detach_generated()?;
        }
        let mut originals = BTreeMap::new();
        let mut failure = None;
        for kind in DocumentKind::ALL {
            match fs::read_to_string(kind.path()) {
                Ok(text) => {
                    originals.insert(kind, Some(text));
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    originals.insert(kind, None);
                }
                Err(error) => {
                    failure = Some(DropletError::io_at("read", kind.path(), error));
                    break;
                }
            }
        }
        if covered {
            global.enter().map_err(|error| DropletError::io("return to the mount namespace of init", error))?;
        }
        match failure {
            Some(error) => Err(error),
            None => Ok(originals),
        }
    }

    /// Lists the families of the original system documents.
    pub fn families(&mut self) -> Result<Vec<FamilyInfo>, DropletError> {
        if Engine::uid() == 0 {
            Engine::enter_init_namespace()?;
        }
        let originals = if Engine::uid() == 0 { self.originals()? } else { Engine::read_documents() };
        let mut families = Vec::new();
        for (kind, text) in originals {
            let Some(text) = text else { continue };
            let document = Document::parse(text).map_err(|source| DropletError::Document { path: kind.path().to_path_buf(), source })?;
            for item in &document.items {
                let (element, languages, variant, to) = match &item.element {
                    Element::Family(family) => {
                        ("family", family.languages().into_iter().map(str::to_string).collect(), family.attributes.get("variant").map(str::to_string), None)
                    }
                    Element::FamilyList(_) => ("family-list", Vec::new(), None, None),
                    Element::Alias(alias) => ("alias", Vec::new(), None, alias.to().map(str::to_string)),
                    Element::Other(_) => continue,
                };
                families.push(FamilyInfo {
                    kind,
                    element: element.to_string(),
                    name: item.element.name().map(str::to_string),
                    languages,
                    variant,
                    files: item.element.fonts().iter().map(|font| font.file.clone()).collect(),
                    to,
                });
            }
        }
        Ok(families)
    }

    pub fn read_documents() -> BTreeMap<DocumentKind, Option<String>> {
        DocumentKind::ALL.iter().map(|kind| (*kind, fs::read_to_string(kind.path()).ok())).collect()
    }

    /// Applies a configuration: installs its fonts, generates the documents, mounts them,
    /// reloads the font service, disables the components, restarts the configured
    /// processes and starts the guard.
    ///
    /// With `boot`, the configuration is not applied if the guard tripped since the last
    /// manual application.
    pub fn apply(&mut self, config: &Config, boot: bool) -> Result<ApplyReport, DropletError> {
        let problems = config.validate();
        if !problems.is_empty() {
            return Err(DropletError::Config(problems));
        }
        Engine::require_root()?;
        self.store.prepare()?;
        let _lock = self.store.lock()?;
        self.log = Log::new(Some(self.store.log_path()));
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        let fresh_boot = !state.is_current_boot();
        state.adopt_boot();
        if boot && let GuardState::Tripped { reason, .. } = &state.guard {
            let message = format!("not applied at boot, because the guard tripped earlier: {reason}. Apply manually to try again.");
            self.log.warning(message.clone());
            state.save(&self.store.state_path())?;
            return Err(DropletError::Refused(message));
        }
        Guard::stop(&state);
        state.guard = GuardState::Idle;
        self.log.info(format!(
            "applying a configuration with {} roles and {} rules{}",
            config.roles.len(),
            config.rules.len(),
            if boot { " at boot" } else { "" }
        ));

        Engine::enter_init_namespace()?;
        let api = Engine::api();
        let originals = self.originals()?;

        // Install the fonts.
        let mut installed: HashMap<String, InstalledFont> = HashMap::new();
        for entry in config.used_fonts() {
            installed.insert(entry.id.clone(), self.store.install(entry, &mut self.log)?);
        }
        if fresh_boot {
            let keep: HashSet<String> = installed.values().map(|font| font.file_name.clone()).collect();
            self.store.collect_fonts(&keep, &mut self.log);
        }

        // Generate and check every document before touching the system.
        let generation = state.generation + 1;
        let directory = self.store.generation_directory(generation);
        fs::create_dir_all(&directory).map_err(|error| DropletError::io_at("create", &directory, error))?;
        let mut documents = BTreeMap::new();
        let mut files: Vec<(DocumentKind, PathBuf, GenerationReport)> = Vec::new();
        for (kind, text) in &originals {
            let Some(text) = text else { continue };
            let document = Document::parse(text.as_str()).map_err(|source| DropletError::Document { path: kind.path().to_path_buf(), source })?;
            let mut generator = Generator::new(*kind, api, config, &installed);
            let output = generator.generate(&document).map_err(|reason| DropletError::Generation { path: kind.path().to_path_buf(), reason })?;
            let path = directory.join(kind.file_name());
            self.store.write(&path, output.as_bytes())?;
            for (family, role) in &generator.report.replaced {
                self.log.info(format!("{}: {family} -> {role}", kind.file_name()));
            }
            for (language, roles) in &generator.report.inserted {
                self.log.info(format!("{}: inserted {} before lang={language}", kind.file_name(), roles.join("+")));
            }
            files.push((*kind, path, generator.report.clone()));
            documents.insert(*kind, generator.report);
        }

        // Add the fonts to the font directories the documents refer to.
        for (kind, _, report) in &files {
            if report.files.is_empty() {
                continue;
            }
            let font_directory = FontDirectory::new(kind.font_directory());
            if !font_directory.path.is_dir() {
                return Err(DropletError::Refused(format!("{} refers to {}, which does not exist", kind.path().display(), font_directory.path.display())));
            }
            let table = MountTable::current().map_err(|error| DropletError::io("read mountinfo", error))?;
            if !font_directory.is_prepared(&table) {
                let staging = PathBuf::from(format!("/dev/typeface-droplet-{}", std::process::id()));
                font_directory.prepare(&staging, &mut self.log)?;
                state.directories.push(font_directory.path.clone());
                state.save(&self.store.state_path())?;
            }
            for name in &report.files {
                let Some(font) = installed.values().find(|font| &font.file_name == name) else { continue };
                if font_directory.add(&font.path, name)? {
                    self.log.info(format!("added {name} to {}", font_directory.path.display()));
                    if !state.fonts.contains(name) {
                        state.fonts.push(name.clone());
                    }
                }
            }
        }

        // Replace the documents.
        Engine::detach_documents(&mut self.log);
        state.documents.clear();
        state.applied = false;
        state.generation = generation;
        state.restart = config.restart.clone();
        for (kind, path, _) in &files {
            // Recorded before mounting, so that a failure in between leaves a state that
            // still names every document that may be mounted.
            state.documents.push(MountedDocument { kind: *kind, file: path.clone() });
            state.save(&self.store.state_path())?;
            Mount::bind(path, &kind.mount_point())?;
            Mount::remount_bind_readonly(&kind.mount_point())?;
        }
        state.applied = true;
        state.save(&self.store.state_path())?;
        self.log.info(format!("mounted generation {generation}"));

        // Watch system_server from before the reload: parsing the documents happens in it.
        let server = Guard::system_server().map(|server| server.pid);
        let guard = match (config.guard_seconds, server) {
            (0, _) => None,
            (seconds, Some(pid)) => {
                let guard = Guard::spawn(&self.store, seconds, GuardTarget::Running(pid))?;
                state.guard = GuardState::Watching { guard, until: Log::now() + u64::from(seconds) };
                state.save(&self.store.state_path())?;
                self.log.info(format!("guard {guard} watches system_server {pid} for {seconds} seconds"));
                Some(guard)
            }
            (_, None) => {
                self.log.warning("cannot find system_server; the guard does not run");
                None
            }
        };

        // Reload the font service and check that it serves the new fonts.
        let expected: BTreeSet<&String> = documents.values().flat_map(|report| report.files.iter()).collect();
        let reload = FontService::restart().and_then(|()| {
            if expected.is_empty() {
                return Ok(None);
            }
            let dump = FontService::dump()?;
            Ok(Some(FontService::paths(&dump).iter().any(|path| expected.iter().any(|name| path.ends_with(name.as_str())))))
        });
        let reloaded = match reload {
            Ok(reloaded) => reloaded,
            Err(error) => {
                // Roll back: the original documents are mounted again before anything else reads ours.
                self.log.error(format!("the font service failed to reload: {error}; restoring the original documents"));
                Guard::stop(&state);
                Engine::detach_documents(&mut self.log);
                let crashed = Guard::system_server().map(|server| server.pid) != server;
                if let Err(error) = FontService::restart() {
                    self.log.error(format!("the font service failed to reload the original documents: {error}"));
                }
                state.applied = false;
                state.documents.clear();
                state.guard = if crashed {
                    GuardState::Tripped { reason: "system_server died while reloading the fonts".to_string(), time: Log::now() }
                } else {
                    GuardState::Idle
                };
                state.save(&self.store.state_path())?;
                return Err(error.into());
            }
        };
        match reloaded {
            Some(true) => self.log.info("the font service serves the new configuration"),
            Some(false) => self.log.warning("the font service did not reload the configuration; a soft restart is needed"),
            None => self.log.info("no rule matched; the documents are unchanged"),
        }

        state.components = Components::apply(&config.components, &state.components, &mut self.log);
        state.save(&self.store.state_path())?;
        let restarted = self.restart_processes(&config.restart);
        self.store.collect_documents(generation, &mut self.log);
        Ok(ApplyReport { generation, documents, fonts: installed.into_values().collect(), reloaded, restarted, guard })
    }

    /// Removes the generated documents, reloads the font service, restores the components
    /// and restarts the configured processes. The font directory skeletons stay until the
    /// next boot, so that running processes never lose a font file they may still load.
    /// Every step runs even when an earlier one fails; the first failure is returned.
    /// A tripped guard stays tripped: only a manual application clears it.
    pub fn revert(&mut self) -> Result<Vec<String>, DropletError> {
        Engine::require_root()?;
        self.store.prepare()?;
        let _lock = self.store.lock()?;
        self.log = Log::new(Some(self.store.log_path()));
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.adopt_boot();
        Guard::stop(&state);
        if let GuardState::Watching { .. } = state.guard {
            state.guard = GuardState::Idle;
        }
        let detached = Engine::detach_documents(&mut self.log);
        self.log.info(format!("removed {detached} generated documents"));
        state.applied = false;
        state.documents.clear();
        state.save(&self.store.state_path())?;
        let reload = FontService::restart();
        if let Err(error) = &reload {
            self.log.error(format!("the font service failed to reload: {error}"));
        }
        state.components = Components::apply(&[], &state.components, &mut self.log);
        state.save(&self.store.state_path())?;
        let restarted = self.restart_processes(&state.restart.clone());
        reload?;
        Ok(restarted)
    }

    /// Restarts zygote, and with it every application and system_server, so that the font
    /// configuration is read as at boot. Used when the font service cannot reload by itself.
    /// The mounts are kept, as they belong to init, and a guard watches the new system_server.
    pub fn soft_restart(&mut self, guard_seconds: u32) -> Result<Option<u32>, DropletError> {
        Engine::require_root()?;
        self.store.prepare()?;
        let _lock = self.store.lock()?;
        self.log = Log::new(Some(self.store.log_path()));
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.adopt_boot();
        Guard::stop(&state);
        if let GuardState::Watching { .. } = state.guard {
            state.guard = GuardState::Idle;
        }
        let previous = Guard::system_server().map(|server| server.pid);
        self.log.info("restarting zygote");
        let restart = SystemProperty::set("ctl.restart", "zygote").map_err(|error| DropletError::io("restart zygote", error));
        // Only a restart that was accepted gets a guard; the guard waits for the new system_server.
        let guard = match (&restart, previous) {
            (Ok(()), Some(previous)) if guard_seconds > 0 && state.applied => {
                let guard = Guard::spawn(&self.store, guard_seconds, GuardTarget::After(previous))?;
                state.guard = GuardState::Watching { guard, until: Log::now() + u64::from(guard_seconds) + Guard::START_TIMEOUT.as_secs() };
                Some(guard)
            }
            _ => None,
        };
        state.save(&self.store.state_path())?;
        restart?;
        Ok(guard)
    }

    /// Kills the processes of the given packages (`@home` is the default home application),
    /// as the low-memory killer does; the system restarts persistent and visible ones.
    pub fn restart_processes(&mut self, targets: &[String]) -> Vec<String> {
        let mut restarted = Vec::new();
        for target in targets {
            let package = if target == Config::HOME {
                let user = ActivityManager::current_user().unwrap_or(0);
                match PackageManager::home_package(user) {
                    Ok(Some(package)) => package,
                    Ok(None) | Err(_) => {
                        self.log.warning("cannot resolve the home application");
                        continue;
                    }
                }
            } else {
                target.clone()
            };
            let processes = ProcessTable::of_package(&package).unwrap_or_default();
            for process in &processes {
                if let Err(error) = process.kill() {
                    self.log.warning(format!("cannot restart {} ({}): {error}", process.name, process.pid));
                }
            }
            if !processes.is_empty() {
                self.log.info(format!("restarted {package}"));
                restarted.push(package);
            }
        }
        // Give the system a moment to bring persistent processes back before returning.
        if !restarted.is_empty() {
            thread::sleep(Duration::from_millis(500));
        }
        restarted
    }

    /// The current state, checked against the live mounts and the font service.
    pub fn status(&mut self) -> Result<StatusReport, DropletError> {
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.adopt_boot();
        let uid = Engine::uid();
        let mut mounted = Vec::new();
        if uid == 0 {
            Engine::enter_init_namespace()?;
            let table = MountTable::current().map_err(|error| DropletError::io("read mountinfo", error))?;
            mounted = DocumentKind::ALL.into_iter().filter(|kind| kind.shows_generated(&table)).collect();
        }
        let served = if uid == 0 {
            FontService::dump()
                .map(|dump| {
                    let mut names: Vec<String> = FontService::paths(&dump)
                        .iter()
                        .filter_map(|path| path.rsplit('/').next())
                        .filter(|name| name.starts_with(FontStore::FILE_PREFIX))
                        .map(str::to_string)
                        .collect();
                    names.sort();
                    names.dedup();
                    names
                })
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        Ok(StatusReport {
            api: SystemProperty::sdk(),
            uid,
            applied: !mounted.is_empty(),
            generation: state.generation,
            mounted,
            directories: state.directories,
            served,
            guard: state.guard,
            components: state.components,
            documents: DocumentKind::ALL.into_iter().filter(|kind| kind.path().exists()).collect(),
        })
    }
}
