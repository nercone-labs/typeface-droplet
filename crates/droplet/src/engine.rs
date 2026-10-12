use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

use android::{ActivityManager, Capability, FontService, PackageManager, ProcessCapabilities, ProcessTable, SystemProperty};
use fontxml::{Document, Element};
use mount::{Mount, MountNamespace, MountTable};
use serde::Serialize;

use crate::{
    BootHook, Components, Config, Daemon, DocumentKind, DropletError, FontDirectory, FontStore, GenerationReport, Generator, Guard, GuardState, GuardTarget,
    InstalledFont, Log, MountedDocument, State, SystemDocument,
};

/// When a configuration is applied, which decides what the engine does around the mounts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ApplyMode {
    /// The user applies it. A tripped guard is cleared.
    Manual,
    /// The application applies it again after a boot. A tripped guard is respected.
    Boot,
    /// The boot hook applies it before zygote starts. A tripped guard is respected, and the
    /// framework is not running yet: it reads the documents when it starts, so there is no
    /// font service to reload, no component to change and no process to restart.
    EarlyBoot,
}

impl ApplyMode {
    pub fn respects_tripped_guard(&self) -> bool {
        *self != ApplyMode::Manual
    }

    pub fn framework_is_running(&self) -> bool {
        *self != ApplyMode::EarlyBoot
    }
}

/// How the font service took the generated documents.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ReloadOutcome {
    /// The font service serves the generated configuration to every process started from now on.
    Served,
    /// The font service did not reload (it reloads only with fs-verity); a soft restart is needed.
    Ignored,
    /// No rule matched, so the documents name no installed font and nothing changes.
    Unchanged,
    /// The framework has not started yet and reads the documents when it starts.
    AtStart,
}

/// The outcome of `Engine::apply`.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyReport {
    pub generation: u64,
    pub documents: BTreeMap<DocumentKind, GenerationReport>,
    pub fonts: Vec<InstalledFont>,
    pub reload: ReloadOutcome,
    pub restarted: Vec<String>,
    pub guard: Option<u32>,
}

/// The outcome of `Engine::boot`.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase", tag = "outcome")]
pub enum BootReport {
    /// The saved configuration was applied.
    Applied { report: ApplyReport },
    /// The saved configuration is already applied in this boot.
    AlreadyApplied,
    /// No configuration is saved.
    NothingSaved,
    /// The application is not installed; a cleanup process removes the engine's traces once
    /// the system has started and the package manager confirms it.
    Uninstalled { cleanup: u32 },
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
    /// Whether a configuration is saved for the boot hook.
    pub saved: bool,
    pub generation: u64,
    /// Documents currently covered by a generated document.
    pub mounted: Vec<DocumentKind>,
    pub directories: Vec<PathBuf>,
    /// Installed fonts the font service is currently serving.
    pub served: Vec<String>,
    pub guard: GuardState,
    pub components: Vec<crate::ComponentRecord>,
    pub documents: Vec<DocumentKind>,
    pub hook: BootHook,
}

/// A document generated for this application, ready to be mounted.
#[derive(Clone, Debug)]
pub struct GeneratedDocument {
    pub kind: DocumentKind,
    pub path: PathBuf,
    pub report: GenerationReport,
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
    /// The name of the temporary directory a font directory skeleton is built in.
    pub const STAGING_PREFIX: &'static str = "/dev/typeface-droplet-";
    /// How long `cleanup` waits for the system to finish starting.
    pub const CLEANUP_TIMEOUT: Duration = Duration::from_secs(3600);
    pub const CLEANUP_COMMAND: &'static str = "cleanup";
    /// The directories the package manager installs applications into, as seen before the
    /// framework starts. Applications live in `<directory>/<package>-<suffix>` or, since
    /// Android 11, in `<directory>/~~<random>/<package>-<suffix>`.
    pub const APPLICATION_DIRECTORIES: [&'static str; 1] = ["/data/app"];
    /// The mount points of storage devices adopted as internal storage, one per volume.
    pub const ADOPTED_STORAGE: &'static str = "/mnt/expand";

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

    pub fn mount_table() -> Result<MountTable, DropletError> {
        MountTable::current().map_err(|error| DropletError::io("read mountinfo", error))
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
            while kind.shows_generated(&Engine::mount_table()?) {
                Mount::detach(&kind.mount_point())?;
                detached += 1;
            }
        }
        Ok(detached)
    }

    /// Reads the original system documents: what the system shows with the generated
    /// documents removed. When some are mounted, they are removed inside a private copy of
    /// the mount namespace, which affects no other process.
    pub fn originals(&mut self) -> Result<Vec<SystemDocument>, DropletError> {
        let global = MountNamespace::current().map_err(|error| DropletError::io("open the current mount namespace", error))?;
        let table = Engine::mount_table()?;
        let covered = DocumentKind::ALL.iter().any(|kind| kind.shows_generated(&table));
        if covered {
            MountNamespace::isolate()?;
        }
        let originals = Engine::detach_generated().map(|_| ()).and_then(|()| Engine::read_originals());
        if covered {
            global.enter().map_err(|error| DropletError::io("return to the mount namespace of init", error))?;
        }
        originals
    }

    /// Reads every system document as the current mount namespace shows it.
    pub fn read_originals() -> Result<Vec<SystemDocument>, DropletError> {
        let mut originals = Vec::new();
        for kind in DocumentKind::ALL {
            originals.extend(SystemDocument::read(kind).map_err(|error| DropletError::io_at("read", kind.path(), error))?);
        }
        Ok(originals)
    }

    /// Lists the families of the original system documents.
    pub fn families(&mut self) -> Result<Vec<FamilyInfo>, DropletError> {
        let originals = if Engine::uid() == 0 {
            Engine::enter_init_namespace()?;
            self.originals()?
        } else {
            DocumentKind::ALL.into_iter().filter_map(|kind| SystemDocument::read(kind).ok().flatten()).collect()
        };
        let mut families = Vec::new();
        for original in originals {
            let document = Document::parse(original.text).map_err(|source| DropletError::Document { path: original.kind.path().to_path_buf(), source })?;
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
                    kind: original.kind,
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

    /// Applies a configuration: installs its fonts, generates the documents, mounts them,
    /// starts the guard and, while the framework runs, reloads the font service, disables the
    /// components and restarts the configured processes. The configuration is saved for the
    /// boot hook, except when the boot hook applies it.
    pub fn apply(&mut self, config: &Config, mode: ApplyMode) -> Result<ApplyReport, DropletError> {
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
        if mode.respects_tripped_guard()
            && let GuardState::Tripped { reason, .. } = &state.guard
        {
            let message = format!("not applied after the boot, because the guard tripped earlier: {reason}. Apply manually to try again.");
            self.log.warning(message.clone());
            state.save(&self.store.state_path())?;
            return Err(DropletError::Refused(message));
        }
        Guard::stop(&state, &self.store);
        state.guard = GuardState::Idle;
        // Saved at once: if a later step fails, the stopped guard must not stay recorded as
        // watching, which the next boot would take for a crash.
        state.save(&self.store.state_path())?;
        self.log.info(format!("applying a configuration with {} roles and {} rules ({mode:?})", config.roles.len(), config.rules.len()));

        Engine::enter_init_namespace()?;
        let originals = self.originals()?;
        let installed = self.install_fonts(config, fresh_boot)?;
        let generation = state.generation + 1;
        let generated = self.generate(config, &originals, &installed, generation)?;
        self.add_fonts(&generated, &installed, &mut state)?;
        let server = Guard::system_server().map(|server| server.pid);
        self.mount(&generated, generation, config, &mut state)?;
        let guard = match self.start_guard(config.guard_seconds, mode, server, &mut state) {
            Ok(guard) => guard,
            Err(error) => {
                // Without its guard, a configuration that breaks the system could not be undone.
                self.log.error(format!("the guard did not start: {error}; restoring the original documents"));
                Engine::detach_documents(&mut self.log);
                state.applied = false;
                state.documents.clear();
                state.save(&self.store.state_path())?;
                return Err(error);
            }
        };
        let reload = if mode.framework_is_running() { self.reload(&generated, server, &mut state)? } else { ReloadOutcome::AtStart };
        let mut restarted = Vec::new();
        if mode.framework_is_running() {
            state.components = Components::apply(&config.components, &state.components, &mut self.log);
            state.save(&self.store.state_path())?;
            self.store.save_config(config)?;
            restarted = self.restart_processes(&config.restart);
        }
        self.store.collect_documents(generation, &mut self.log);
        Ok(ApplyReport {
            generation,
            documents: generated.into_iter().map(|document| (document.kind, document.report)).collect(),
            fonts: installed.into_values().collect(),
            reload,
            restarted,
            guard,
        })
    }

    /// Installs the fonts the configuration uses, by font id. After a boot, before anything
    /// of the store is mounted again, installed fonts the configuration no longer uses are removed.
    pub fn install_fonts(&mut self, config: &Config, fresh_boot: bool) -> Result<HashMap<String, InstalledFont>, DropletError> {
        let mut installed = HashMap::new();
        for entry in config.used_fonts() {
            installed.insert(entry.id.clone(), self.store.install(entry, &mut self.log)?);
        }
        if fresh_boot {
            let keep: HashSet<String> = installed.values().map(|font| font.file_name.clone()).collect();
            self.store.collect_fonts(&keep, &mut self.log);
        }
        Ok(installed)
    }

    /// Generates and checks every document before anything of the system is touched.
    pub fn generate(
        &mut self,
        config: &Config,
        originals: &[SystemDocument],
        installed: &HashMap<String, InstalledFont>,
        generation: u64,
    ) -> Result<Vec<GeneratedDocument>, DropletError> {
        let api = Engine::api();
        let directory = self.store.generation_directory(generation);
        fs::create_dir_all(&directory).map_err(|error| DropletError::io_at("create", &directory, error))?;
        let mut generated = Vec::new();
        for original in originals {
            let kind = original.kind;
            let document = Document::parse(original.text.as_str()).map_err(|source| DropletError::Document { path: kind.path().to_path_buf(), source })?;
            let mut generator = Generator::new(kind, api, config, installed);
            let output = generator.generate(&document).map_err(|reason| DropletError::Generation { path: kind.path().to_path_buf(), reason })?;
            let path = directory.join(kind.file_name());
            if let Err(error) = self.store.write(&path, output.as_bytes(), original.label.as_deref()) {
                // A label the root domain may not set; the label of the store is readable as well.
                self.log.warning(format!("cannot give {} the label of the original ({error}); using the label of the store", kind.file_name()));
                self.store.write(&path, output.as_bytes(), None)?;
            }
            for (family, role) in &generator.report.replaced {
                self.log.info(format!("{}: {family} -> {role}", kind.file_name()));
            }
            for (language, roles) in &generator.report.inserted {
                self.log.info(format!("{}: inserted {} before lang={language}", kind.file_name(), roles.join("+")));
            }
            generated.push(GeneratedDocument { kind, path, report: generator.report });
        }
        Ok(generated)
    }

    /// Adds the installed fonts to the font directories the documents refer to.
    pub fn add_fonts(&mut self, generated: &[GeneratedDocument], installed: &HashMap<String, InstalledFont>, state: &mut State) -> Result<(), DropletError> {
        for document in generated {
            if document.report.files.is_empty() {
                continue;
            }
            let directory = FontDirectory::new(document.kind.font_directory());
            if !directory.path.is_dir() {
                return Err(DropletError::Refused(format!("{} refers to {}, which does not exist", document.kind.path().display(), directory.path.display())));
            }
            if !directory.is_prepared(&Engine::mount_table()?) {
                let staging = PathBuf::from(format!("{}{}", Engine::STAGING_PREFIX, std::process::id()));
                directory.prepare(&staging, &mut self.log)?;
                state.directories.push(directory.path.clone());
                state.save(&self.store.state_path())?;
            }
            for name in &document.report.files {
                let Some(font) = installed.values().find(|font| &font.file_name == name) else { continue };
                if directory.add(&font.path, name)? {
                    self.log.info(format!("added {name} to {}", directory.path.display()));
                    if !state.fonts.contains(name) {
                        state.fonts.push(name.clone());
                    }
                }
            }
        }
        Ok(())
    }

    /// Replaces the documents of an earlier generation with the generated ones.
    pub fn mount(&mut self, generated: &[GeneratedDocument], generation: u64, config: &Config, state: &mut State) -> Result<(), DropletError> {
        Engine::detach_documents(&mut self.log);
        state.documents.clear();
        state.applied = false;
        state.generation = generation;
        state.restart = config.restart.clone();
        for document in generated {
            // Recorded before mounting, so that a failure in between leaves a state that
            // still names every document that may be mounted.
            state.documents.push(MountedDocument { kind: document.kind, file: document.path.clone() });
            state.save(&self.store.state_path())?;
            Mount::bind(&document.path, &document.kind.mount_point())?;
            Mount::remount_bind_readonly(&document.kind.mount_point())?;
        }
        state.applied = true;
        state.save(&self.store.state_path())?;
        self.log.info(format!("mounted generation {generation}"));
        Ok(())
    }

    /// Starts the guard. While the framework runs, it watches `server`, the system_server that
    /// parses the documents when the font service reloads. Before the framework starts, it
    /// watches the first system_server of the boot, and it always runs then.
    pub fn start_guard(&mut self, seconds: u32, mode: ApplyMode, server: Option<u32>, state: &mut State) -> Result<Option<u32>, DropletError> {
        let (seconds, target) = match (mode, server) {
            (ApplyMode::EarlyBoot, _) => (seconds.max(Guard::MINIMUM_BOOT_SECONDS), GuardTarget::Boot),
            (_, _) if seconds == 0 => return Ok(None),
            (_, Some(pid)) => (seconds, GuardTarget::Running(pid)),
            (_, None) => {
                self.log.warning("cannot find system_server; the guard does not run");
                return Ok(None);
            }
        };
        let guard = Guard::spawn(&self.store, seconds, target)?;
        state.guard = GuardState::Watching { guard, until: Log::now() + Guard::longest(seconds, target) };
        state.save(&self.store.state_path())?;
        self.log.info(format!("guard {guard} watches system_server ({target:?}) for {seconds} seconds"));
        Ok(Some(guard))
    }

    /// Reloads the font service and checks that it serves the new fonts. If the reload fails,
    /// the original documents are mounted again before anything else reads the generated ones.
    pub fn reload(&mut self, generated: &[GeneratedDocument], server: Option<u32>, state: &mut State) -> Result<ReloadOutcome, DropletError> {
        let expected: BTreeSet<&String> = generated.iter().flat_map(|document| document.report.files.iter()).collect();
        let reload = FontService::restart().and_then(|()| {
            if expected.is_empty() {
                return Ok(ReloadOutcome::Unchanged);
            }
            let dump = FontService::dump()?;
            let served = FontService::paths(&dump).iter().any(|path| expected.iter().any(|name| path.ends_with(name.as_str())));
            Ok(if served { ReloadOutcome::Served } else { ReloadOutcome::Ignored })
        });
        match reload {
            Ok(outcome) => {
                match outcome {
                    ReloadOutcome::Served => self.log.info("the font service serves the new configuration"),
                    ReloadOutcome::Ignored => self.log.warning("the font service did not reload the configuration; a soft restart is needed"),
                    ReloadOutcome::Unchanged | ReloadOutcome::AtStart => self.log.info("no rule matched; the documents are unchanged"),
                }
                Ok(outcome)
            }
            Err(error) => {
                self.log.error(format!("the font service failed to reload: {error}; restoring the original documents"));
                Guard::stop(state, &self.store);
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
                Err(error.into())
            }
        }
    }

    /// Applies the saved configuration before the framework starts; run by the boot hook.
    /// `package` is the application that saved it: if it is no longer installed, nothing is
    /// applied, and a cleanup process removes the engine's traces once the system has started.
    pub fn boot(&mut self, package: &str) -> Result<BootReport, DropletError> {
        Engine::require_root()?;
        if !Engine::is_package_present(package) {
            self.log.warning(format!("{package} seems to be uninstalled; nothing is applied"));
            let arguments = [Engine::CLEANUP_COMMAND, "--package", package, "--store", &self.store.root.to_string_lossy()].map(str::to_string);
            return Ok(BootReport::Uninstalled { cleanup: Daemon::spawn(&arguments)? });
        }
        let Some(config) = self.store.load_config()? else { return Ok(BootReport::NothingSaved) };
        let mut log = Log::new(None);
        let state = State::load(&self.store.state_path(), &mut log)?;
        Engine::enter_init_namespace()?;
        let table = Engine::mount_table()?;
        if state.is_current_boot() && state.applied && state.documents.iter().all(|document| document.kind.shows_generated(&table)) {
            return Ok(BootReport::AlreadyApplied);
        }
        Ok(BootReport::Applied { report: self.apply(&config, ApplyMode::EarlyBoot)? })
    }

    /// Whether `package` is installed, as far as the application directories show before the
    /// framework starts.
    pub fn is_package_present(package: &str) -> bool {
        Engine::is_package_present_in(&Engine::application_directories().iter().map(PathBuf::as_path).collect::<Vec<_>>(), package)
    }

    /// The directories applications are installed into: `/data/app`, and `app` of every
    /// adopted storage device under `/mnt/expand`. Before the framework starts, adopted storage
    /// is not mounted yet; its directories are then unreadable, which counts as present.
    pub fn application_directories() -> Vec<PathBuf> {
        let mut directories: Vec<PathBuf> = Engine::APPLICATION_DIRECTORIES.iter().map(PathBuf::from).collect();
        if let Ok(volumes) = fs::read_dir(Engine::ADOPTED_STORAGE) {
            directories.extend(volumes.flatten().map(|volume| volume.path().join("app")));
        }
        directories
    }

    /// Whether one of `directories` holds an installation of `package`: `<package>-<suffix>`,
    /// directly or inside a `~~<random>` directory. Unreadable directories count as holding
    /// it, so that the engine never gives up on a configuration only because it cannot look.
    pub fn is_package_present_in(directories: &[&Path], package: &str) -> bool {
        let prefix = format!("{package}-");
        directories.iter().any(|directory| match fs::read_dir(directory) {
            Err(_) => true,
            Ok(entries) => entries.flatten().any(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                name.starts_with(&prefix)
                    || (name.starts_with("~~")
                        && fs::read_dir(entry.path()).is_ok_and(|inner| inner.flatten().any(|child| child.file_name().to_string_lossy().starts_with(&prefix))))
            }),
        })
    }

    /// Removes every trace of the engine once `package` is uninstalled: waits until the system
    /// has started, asks the package manager whether `package` is installed, and if it is not,
    /// restores the components, removes the documents, the boot hook and the store.
    /// Returns whether it cleaned up.
    pub fn cleanup(&mut self, package: &str) -> Result<bool, DropletError> {
        Engine::require_root()?;
        let started = Instant::now();
        while !SystemProperty::is_boot_completed() {
            if started.elapsed() > Engine::CLEANUP_TIMEOUT {
                return Err(DropletError::Refused("the system did not finish starting".to_string()));
            }
            thread::sleep(Guard::POLL_INTERVAL * 20);
        }
        if PackageManager::is_installed(package)? {
            self.log.info(format!("{package} is installed; nothing to clean up"));
            return Ok(false);
        }
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.components = Components::apply(&[], &state.components, &mut self.log);
        if !state.components.is_empty() {
            state.save(&self.store.state_path())?;
            return Err(DropletError::Refused("some components could not be restored; the store is kept".to_string()));
        }
        Engine::detach_documents(&mut self.log);
        BootHook::remove()?;
        fs::remove_dir_all(&self.store.root).map_err(|error| DropletError::io_at("remove", &self.store.root, error))?;
        Ok(true)
    }

    /// Removes the generated documents and the saved configuration, reloads the font service,
    /// restores the components and restarts the configured processes. The font directory
    /// skeletons stay until the next boot, so that running processes never lose a font file
    /// they may still load. Every step runs even when an earlier one fails; the first failure
    /// is returned. A tripped guard stays tripped: only a manual application clears it.
    pub fn revert(&mut self) -> Result<Vec<String>, DropletError> {
        Engine::require_root()?;
        self.store.prepare()?;
        let _lock = self.store.lock()?;
        self.log = Log::new(Some(self.store.log_path()));
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.adopt_boot();
        Guard::stop(&state, &self.store);
        if let GuardState::Watching { .. } = state.guard {
            state.guard = GuardState::Idle;
        }
        let saved = self.store.remove_config();
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
        saved?;
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
        Guard::stop(&state, &self.store);
        if let GuardState::Watching { .. } = state.guard {
            state.guard = GuardState::Idle;
        }
        let previous = Guard::system_server().map(|server| server.pid);
        self.log.info("restarting zygote");
        let restart = SystemProperty::set("ctl.restart", "zygote").map_err(|error| DropletError::io("restart zygote", error));
        // Only a restart that was accepted gets a guard; the guard waits for the new system_server.
        let guard = match (&restart, previous) {
            (Ok(()), Some(previous)) if guard_seconds > 0 && state.applied => {
                let target = GuardTarget::After(previous);
                let guard = Guard::spawn(&self.store, guard_seconds, target)?;
                state.guard = GuardState::Watching { guard, until: Log::now() + Guard::longest(guard_seconds, target) };
                Some(guard)
            }
            _ => None,
        };
        state.save(&self.store.state_path())?;
        restart?;
        Ok(guard)
    }

    /// Restarts the processes of the given targets: package names, `@home` (the default home
    /// application) and `@background` (every process the activity manager may kill safely).
    /// Processes are killed as the low-memory killer does; the system restarts persistent and
    /// visible ones.
    pub fn restart_processes(&mut self, targets: &[String]) -> Vec<String> {
        let mut restarted = Vec::new();
        for target in targets {
            if target == Config::BACKGROUND {
                match ActivityManager::kill_background() {
                    Ok(()) => {
                        self.log.info("restarted the background applications");
                        restarted.push(target.clone());
                    }
                    Err(error) => self.log.warning(format!("cannot restart the background applications: {error}")),
                }
                continue;
            }
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

    /// Installs the boot hook for `package`, with a fresh copy of the engine.
    pub fn install_hook(&mut self, package: &str) -> Result<BootHook, DropletError> {
        Engine::require_root()?;
        self.store.prepare()?;
        let _lock = self.store.lock()?;
        let hook = BootHook::install(&self.store, package)?;
        if hook.scripts.is_empty() {
            self.log.info("no root manager runs boot scripts; the boot hook is not installed");
        } else {
            self.log.info(format!("installed the boot hook: {}", hook.scripts.iter().map(|path| path.display().to_string()).collect::<Vec<_>>().join(", ")));
        }
        Ok(hook)
    }

    pub fn remove_hook(&mut self) -> Result<BootHook, DropletError> {
        Engine::require_root()?;
        let hook = BootHook::remove()?;
        self.log.info("removed the boot hook");
        Ok(hook)
    }

    /// The current state, checked against the live mounts and the font service.
    pub fn status(&mut self) -> Result<StatusReport, DropletError> {
        let mut state = State::load(&self.store.state_path(), &mut self.log)?;
        state.adopt_boot();
        let uid = Engine::uid();
        let mut mounted = Vec::new();
        let mut served = Vec::new();
        if uid == 0 {
            Engine::enter_init_namespace()?;
            let table = Engine::mount_table()?;
            mounted = DocumentKind::ALL.into_iter().filter(|kind| kind.shows_generated(&table)).collect();
            served = FontService::dump()
                .map(|dump| {
                    let names: BTreeSet<String> = FontService::paths(&dump)
                        .iter()
                        .filter_map(|path| Path::new(path).file_name()?.to_str())
                        .filter(|name| name.starts_with(FontStore::FILE_PREFIX))
                        .map(str::to_string)
                        .collect();
                    names.into_iter().collect()
                })
                .unwrap_or_default();
        }
        Ok(StatusReport {
            api: SystemProperty::sdk(),
            uid,
            applied: !mounted.is_empty(),
            saved: self.store.config_path().is_file(),
            generation: state.generation,
            mounted,
            directories: state.directories,
            served,
            guard: state.guard,
            components: state.components,
            documents: DocumentKind::ALL.into_iter().filter(|kind| kind.path().exists()).collect(),
            hook: BootHook::current(),
        })
    }
}
