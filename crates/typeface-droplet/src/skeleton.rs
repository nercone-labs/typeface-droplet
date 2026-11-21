use std::fs::{self, File};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};

use mount::{Mount, MountTable, SELinuxContext};

use crate::{DropletError, Log};

/// A font directory of the system (such as `/system/fonts`) that new files can be added to.
///
/// The read-only directory is covered with a tmpfs "skeleton": an empty placeholder for every
/// original entry, each with the original bind-mounted onto it. A new file is added the same
/// way: a placeholder in the tmpfs and the file bind-mounted onto it. Only bind and tmpfs
/// mounts are used, so the store may live on any file system, including an encrypted /data.
///
/// Files are only ever added: processes load fonts lazily by path, so a file that was ever
/// referenced must stay reachable until the next boot.
pub struct FontDirectory {
    pub path: PathBuf,
}

impl FontDirectory {
    /// The name of the skeleton tmpfs, shown as the mount source in mountinfo.
    pub const SOURCE: &'static str = "typeface-droplet";
    /// The size limit of the skeleton tmpfs. It holds only empty placeholders.
    pub const TMPFS_SIZE: &'static str = "1m";

    /// The directory at `path`, with symbolic links resolved so that it matches mountinfo.
    pub fn new(path: impl Into<PathBuf>) -> FontDirectory {
        let path = path.into();
        FontDirectory { path: fs::canonicalize(&path).unwrap_or(path) }
    }

    /// Whether the directory is already covered by a skeleton.
    pub fn is_prepared(&self, table: &MountTable) -> bool {
        table.top(&self.path).is_some_and(|entry| entry.file_system == "tmpfs" && entry.source == FontDirectory::SOURCE)
    }

    /// Covers the directory with a skeleton that shows exactly the original entries.
    /// `staging` must be a directory on a writable file system that is not under `path`.
    pub fn prepare(&self, staging: &Path, log: &mut Log) -> Result<(), DropletError> {
        let metadata = fs::metadata(&self.path).map_err(|error| DropletError::io_at("read", &self.path, error))?;
        // The skeleton must carry the label of the original directory, or processes could not
        // look up fonts in it; system_file is the label of system font directories.
        let context = SELinuxContext::get(&self.path).ok();
        fs::create_dir_all(staging).map_err(|error| DropletError::io_at("create", staging, error))?;
        Mount::tmpfs(FontDirectory::SOURCE, staging, &format!("mode={:o},size={}", metadata.mode() & 0o7777, FontDirectory::TMPFS_SIZE))?;
        let result = self.populate(staging, &metadata, context.as_deref()).and_then(|()| Ok(Mount::bind_recursive(staging, &self.path)?));
        // The staging mount is only a construction site; the skeleton lives on at `path`.
        // The recursive bind made the skeleton a peer of the staging mount, so unmounting
        // the staging mount as it is would unmount the bind mounts of the skeleton as well.
        // As a slave, the staging mount no longer propagates its unmounting to its peers.
        let result = result.and_then(|()| {
            Mount::make_slave_recursive(staging).map_err(|error| {
                let _ = Mount::detach(&self.path);
                DropletError::from(error)
            })
        });
        let _ = Mount::detach(staging);
        let _ = fs::remove_dir(staging);
        result?;
        log.info(format!("covered {} with a skeleton", self.path.display()));
        Ok(())
    }

    pub fn populate(&self, staging: &Path, metadata: &fs::Metadata, context: Option<&str>) -> Result<(), DropletError> {
        std::os::unix::fs::chown(staging, Some(metadata.uid()), Some(metadata.gid()))
            .map_err(|error| DropletError::io_at("set the owner of", staging, error))?;
        match context {
            Some(context) => SELinuxContext::set(staging, context).map_err(|error| DropletError::io_at("label", staging, error))?,
            // Without a readable label (SELinux disabled), labeling is attempted but not required.
            None => {
                let _ = SELinuxContext::set(staging, SELinuxContext::SYSTEM_FILE);
            }
        }
        let entries = fs::read_dir(&self.path).map_err(|error| DropletError::io_at("list", &self.path, error))?;
        for entry in entries {
            let entry = entry.map_err(|error| DropletError::io_at("list", &self.path, error))?;
            let original = entry.path();
            let placeholder = staging.join(entry.file_name());
            let kind = entry.file_type().map_err(|error| DropletError::io_at("read", &original, error))?;
            if kind.is_symlink() {
                let target = fs::read_link(&original).map_err(|error| DropletError::io_at("read", &original, error))?;
                std::os::unix::fs::symlink(&target, &placeholder).map_err(|error| DropletError::io_at("create", &placeholder, error))?;
                if let Ok(context) = SELinuxContext::get(&original) {
                    let _ = SELinuxContext::set(&placeholder, &context);
                }
                continue;
            }
            if kind.is_dir() {
                fs::create_dir(&placeholder).map_err(|error| DropletError::io_at("create", &placeholder, error))?;
            } else if kind.is_file() {
                File::create(&placeholder).map_err(|error| DropletError::io_at("create", &placeholder, error))?;
                fs::set_permissions(&placeholder, fs::Permissions::from_mode(0o644))
                    .map_err(|error| DropletError::io_at("set the mode of", &placeholder, error))?;
            } else {
                continue;
            }
            // Recursive, so that mounts inside an original subdirectory stay visible.
            Mount::bind_recursive(&original, &placeholder)?;
        }
        Ok(())
    }

    /// Adds `file` to the directory under `name`, unless an entry of that name exists.
    /// Returns whether it was added.
    pub fn add(&self, file: &Path, name: &str) -> Result<bool, DropletError> {
        let target = self.path.join(name);
        if fs::symlink_metadata(&target).is_ok() {
            return Ok(false);
        }
        File::create(&target).map_err(|error| DropletError::io_at("create", &target, error))?;
        fs::set_permissions(&target, fs::Permissions::from_mode(0o644)).map_err(|error| DropletError::io_at("set the mode of", &target, error))?;
        if let Err(error) = Mount::bind(file, &target) {
            let _ = fs::remove_file(&target);
            return Err(error.into());
        }
        Mount::remount_bind_readonly(&target)?;
        Ok(true)
    }
}
