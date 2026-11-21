use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::{DocumentKind, DropletError, Log};

/// A component the engine disabled, with the state to restore it to.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentRecord {
    pub user: u32,
    pub component: String,
    /// `default` or `enabled`.
    pub state: String,
}

/// A generated document mounted over a system document.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MountedDocument {
    pub kind: DocumentKind,
    pub file: PathBuf,
}

/// The state of the guard that watches system_server after the fonts are reloaded.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "state")]
pub enum GuardState {
    #[default]
    Idle,
    /// A guard process is watching system_server until `until` (seconds since the epoch).
    Watching { guard: u32, until: u64 },
    /// system_server stayed up for the whole watch.
    Passed,
    /// The guard removed the generated documents. Automatic application at boot stays
    /// disabled until a configuration is applied manually.
    Tripped { reason: String, time: u64 },
}

/// The persistent state of the engine.
///
/// Mounts disappear at every boot, so the fields about them are only meaningful while
/// `boot_id` matches the running system. Component records and the guard outcome are kept
/// across boots.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct State {
    pub boot_id: String,
    pub applied: bool,
    pub generation: u64,
    pub documents: Vec<MountedDocument>,
    /// Font directories replaced by a skeleton during this boot.
    pub directories: Vec<PathBuf>,
    /// File names of fonts mounted into the skeletons during this boot.
    pub fonts: Vec<String>,
    pub restart: Vec<String>,
    pub components: Vec<ComponentRecord>,
    pub guard: GuardState,
}

impl State {
    pub const BOOT_ID: &'static str = "/proc/sys/kernel/random/boot_id";

    pub fn current_boot_id() -> String {
        fs::read_to_string(State::BOOT_ID).map(|id| id.trim().to_string()).unwrap_or_default()
    }

    /// Loads the state. A missing file is an empty state; a corrupt file is set aside as
    /// `<file>.corrupt` and replaced by an empty state, with a warning.
    pub fn load(path: &Path, log: &mut Log) -> Result<State, DropletError> {
        match fs::read_to_string(path) {
            Ok(text) => match serde_json::from_str(&text) {
                Ok(state) => Ok(state),
                Err(error) => {
                    log.warning(format!("the state file is corrupt ({error}); it was moved to state.json.corrupt"));
                    let _ = fs::rename(path, path.with_extension("json.corrupt"));
                    Ok(State::default())
                }
            },
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(State::default()),
            Err(error) => Err(DropletError::io_at("read", path, error)),
        }
    }

    pub fn save(&self, path: &Path) -> Result<(), DropletError> {
        let temporary = path.with_extension("json.tmp");
        let text = serde_json::to_string_pretty(self).map_err(|error| DropletError::io("serialize the state", io::Error::other(error)))?;
        // Written durably: the component records must survive a power loss.
        let mut file = fs::File::create(&temporary).map_err(|error| DropletError::io_at("create", &temporary, error))?;
        file.write_all(text.as_bytes()).and_then(|_| file.sync_all()).map_err(|error| DropletError::io_at("write", &temporary, error))?;
        fs::rename(&temporary, path).map_err(|error| DropletError::io_at("rename", &temporary, error))?;
        if let Some(directory) = path.parent() {
            let _ = fs::File::open(directory).and_then(|directory| directory.sync_all());
        }
        Ok(())
    }

    /// Whether the boot-scoped fields describe the running system.
    pub fn is_current_boot(&self) -> bool {
        !self.boot_id.is_empty() && self.boot_id == State::current_boot_id()
    }

    /// Adopts the running boot. When the state comes from an earlier boot, its mounts are
    /// gone; a guard that was still watching then is treated as tripped, because the device
    /// restarted before system_server was known to be stable.
    pub fn adopt_boot(&mut self) {
        if self.is_current_boot() {
            return;
        }
        if let GuardState::Watching { .. } = self.guard {
            self.guard = GuardState::Tripped { reason: "the device restarted while the guard was watching system_server".to_string(), time: Log::now() };
        }
        self.boot_id = State::current_boot_id();
        self.applied = false;
        self.documents.clear();
        self.directories.clear();
        self.fonts.clear();
    }
}
