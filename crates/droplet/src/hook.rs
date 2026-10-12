use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::{Config, DropletError, FontStore};

/// A root manager that runs boot scripts from `/data/adb` before the Android framework starts.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum RootManager {
    Magisk,
    KernelSU,
    APatch,
}

impl RootManager {
    pub const ALL: [RootManager; 3] = [RootManager::Magisk, RootManager::KernelSU, RootManager::APatch];

    /// A path that exists while the root manager is installed.
    pub fn marker(&self) -> &'static Path {
        Path::new(match self {
            RootManager::Magisk => "/data/adb/magisk",
            RootManager::KernelSU => "/data/adb/ksud",
            RootManager::APatch => "/data/adb/apd",
        })
    }

    /// The directory of general scripts that run before zygote starts.
    ///
    /// Magisk runs `post-fs-data.d` before it mounts its modules. KernelSU and APatch also run
    /// `post-mount.d`, after they mount their modules; that stage is used there, because the
    /// overlay file systems of their modules would otherwise cover the generated documents.
    pub fn hook_directory(&self) -> &'static Path {
        Path::new(match self {
            RootManager::Magisk => "/data/adb/post-fs-data.d",
            RootManager::KernelSU | RootManager::APatch => "/data/adb/post-mount.d",
        })
    }

    /// The root managers installed on the device. Leftovers of an earlier root manager may
    /// make several appear; a hook is installed for each, and the engine applies at most once
    /// per boot unless the earlier application was covered.
    pub fn installed() -> Vec<RootManager> {
        RootManager::ALL.into_iter().filter(|manager| fs::symlink_metadata(manager.marker()).is_ok()).collect()
    }
}

/// The boot scripts that apply the saved configuration at every boot, before zygote starts,
/// so that every process reads the generated documents from its start.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BootHook {
    pub managers: Vec<RootManager>,
    /// The installed scripts.
    pub scripts: Vec<PathBuf>,
}

impl BootHook {
    pub const FILE_NAME: &'static str = "typeface-droplet.sh";
    pub const SCRIPT_MODE: u32 = 0o755;
    pub const DIRECTORY_MODE: u32 = 0o755;

    /// The hook as it is installed now.
    pub fn current() -> BootHook {
        let managers = RootManager::installed();
        let scripts = BootHook::candidates().into_iter().filter(|path| path.is_file()).collect();
        BootHook { managers, scripts }
    }

    /// Every path a script of this engine may have been installed at.
    pub fn candidates() -> Vec<PathBuf> {
        let mut paths: Vec<PathBuf> = RootManager::ALL.iter().map(|manager| manager.hook_directory().join(BootHook::FILE_NAME)).collect();
        paths.sort();
        paths.dedup();
        paths
    }

    /// The script: it runs the engine copy of the store with the `boot` command.
    pub fn script(store: &FontStore, package: &str) -> Result<String, DropletError> {
        if !Config::is_package_name(package) {
            return Err(DropletError::Refused(format!("\"{package}\" is not a package name")));
        }
        let quote = |text: &str| format!("'{}'", text.replace('\'', "'\\''"));
        let executable = store.executable_path();
        let store = store.root.to_string_lossy();
        Ok(format!(
            "#!/system/bin/sh\n\
             # TypeFace Droplet applies the saved font configuration here, before the Android framework starts.\n\
             # It is removed when TypeFace Droplet is uninstalled; deleting this file disables it.\n\
             {} boot --package {} --store {} >/dev/null 2>&1\n",
            quote(&executable.to_string_lossy()),
            quote(package),
            quote(&store),
        ))
    }

    /// Installs the engine copy and a script for every installed root manager. Without a root
    /// manager that runs boot scripts, nothing is installed: the returned hook has no scripts.
    pub fn install(store: &FontStore, package: &str) -> Result<BootHook, DropletError> {
        let managers = RootManager::installed();
        let script = BootHook::script(store, package)?;
        if managers.is_empty() {
            return Ok(BootHook::current());
        }
        store.install_executable()?;
        for manager in &managers {
            let directory = manager.hook_directory();
            if !directory.is_dir() {
                fs::create_dir_all(directory).map_err(|error| DropletError::io_at("create", directory, error))?;
                fs::set_permissions(directory, fs::Permissions::from_mode(BootHook::DIRECTORY_MODE))
                    .map_err(|error| DropletError::io_at("set the mode of", directory, error))?;
            }
            let path = directory.join(BootHook::FILE_NAME);
            let temporary = path.with_extension("tmp");
            fs::write(&temporary, &script).map_err(|error| DropletError::io_at("write", &temporary, error))?;
            fs::set_permissions(&temporary, fs::Permissions::from_mode(BootHook::SCRIPT_MODE))
                .map_err(|error| DropletError::io_at("set the mode of", &temporary, error))?;
            fs::rename(&temporary, &path).map_err(|error| DropletError::io_at("rename", &temporary, error))?;
        }
        Ok(BootHook::current())
    }

    /// Removes every script of this engine.
    pub fn remove() -> Result<BootHook, DropletError> {
        for path in BootHook::candidates() {
            match fs::remove_file(&path) {
                Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(DropletError::io_at("remove", &path, error)),
                _ => {}
            }
        }
        Ok(BootHook::current())
    }
}
