use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use mount::SELinuxContext;
use serde::Serialize;
use sfnt::{FontFile, FontFormat, FontRebuilder, NameTable, Tag};
use sha2::{Digest, Sha256};

use crate::{Config, DropletError, FaceInfo, FontEntry, FontInfo, FontSource, Log, SourceReader};

/// One installed copy of a font: the font id, and the family name written into the copy
/// (`None` for an unchanged copy of the source).
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FontKey {
    pub id: String,
    pub family: Option<String>,
}

impl FontKey {
    pub fn new(id: impl Into<String>, family: Option<String>) -> FontKey {
        FontKey { id: id.into(), family }
    }

    /// The entry of the font with the family name of this copy.
    pub fn entry(&self, entry: &FontEntry) -> FontEntry {
        FontEntry { rename: self.family.clone(), ..entry.clone() }
    }
}

/// A font file installed in the store, ready to be mounted.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledFont {
    pub id: String,
    /// The family name written into this copy, if any.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub family: Option<String>,
    pub file_name: String,
    pub path: PathBuf,
    pub index: u32,
    pub face: FaceInfo,
}

impl InstalledFont {
    pub fn key(&self) -> FontKey {
        FontKey::new(self.id.clone(), self.family.clone())
    }
}

/// The directory the engine owns: installed fonts, generated documents, state and log.
///
/// Installed fonts are named after their source, face and family name, so the same input
/// always maps to the same file and a font is processed only once.
pub struct FontStore {
    pub root: PathBuf,
    /// The SELinux context given to installed fonts and generated documents, or `None` to
    /// leave labels alone (on systems without SELinux).
    pub context: Option<String>,
}

impl FontStore {
    pub const DEFAULT_ROOT: &'static str = "/data/adb/typeface-droplet";
    pub const FILE_PREFIX: &'static str = "TypeFaceDroplet-";
    pub const FILE_MODE: u32 = 0o644;
    pub const DIRECTORY_MODE: u32 = 0o700;
    /// The version of the way installed fonts are written. It is part of their file names, so
    /// that a change of the writing never reuses a font written the old way.
    pub const FORMAT: u32 = 1;
    pub const EXECUTABLE: &'static str = "droplet";
    pub const EXECUTABLE_MODE: u32 = 0o700;

    pub fn new(root: impl Into<PathBuf>, context: Option<String>) -> FontStore {
        FontStore { root: root.into(), context }
    }

    /// The store at its default location, labeling files for the Android system partition.
    pub fn system() -> FontStore {
        FontStore::new(FontStore::DEFAULT_ROOT, Some(SELinuxContext::SYSTEM_FILE.to_string()))
    }

    pub fn fonts_directory(&self) -> PathBuf {
        self.root.join("fonts")
    }

    pub fn documents_directory(&self) -> PathBuf {
        self.root.join("documents")
    }

    pub fn generation_directory(&self, generation: u64) -> PathBuf {
        self.documents_directory().join(generation.to_string())
    }

    pub fn state_path(&self) -> PathBuf {
        self.root.join("state.json")
    }

    pub fn log_path(&self) -> PathBuf {
        self.root.join("droplet.log")
    }

    pub fn lock_path(&self) -> PathBuf {
        self.root.join("lock")
    }

    /// The configuration applied last, which the boot hook applies again at every boot.
    pub fn config_path(&self) -> PathBuf {
        self.root.join("config.json")
    }

    /// The copy of the engine that the boot hook runs: the application's own copy moves with
    /// every update of the application.
    pub fn executable_path(&self) -> PathBuf {
        self.root.join("bin").join(FontStore::EXECUTABLE)
    }

    /// Takes the exclusive lock of the store, waiting for other engine processes to release it.
    /// The lock is released when the returned file is dropped, or when the process ends.
    pub fn lock(&self) -> Result<File, DropletError> {
        let path = self.lock_path();
        let file =
            OpenOptions::new().create(true).truncate(false).write(true).mode(0o600).open(&path).map_err(|error| DropletError::io_at("open", &path, error))?;
        file.lock().map_err(|error| DropletError::io_at("lock", &path, error))?;
        Ok(file)
    }

    /// Creates the directories of the store, accessible to root only.
    pub fn prepare(&self) -> Result<(), DropletError> {
        for directory in [self.root.clone(), self.fonts_directory(), self.documents_directory()] {
            fs::create_dir_all(&directory).map_err(|error| DropletError::io_at("create", &directory, error))?;
            fs::set_permissions(&directory, fs::Permissions::from_mode(FontStore::DIRECTORY_MODE))
                .map_err(|error| DropletError::io_at("set the mode of", &directory, error))?;
        }
        Ok(())
    }

    /// The installed file name of a font: derived from its id, face index and family name.
    pub fn file_name(entry: &FontEntry, extension: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(FontStore::FORMAT.to_be_bytes());
        hasher.update(entry.id.as_bytes());
        hasher.update([0]);
        hasher.update(entry.index.to_string().as_bytes());
        hasher.update([0]);
        if let Some(name) = &entry.rename {
            hasher.update(name.as_bytes());
        }
        let digest: String = hasher.finalize().iter().take(10).map(|byte| format!("{byte:02x}")).collect();
        format!("{}{digest}.{extension}", FontStore::FILE_PREFIX)
    }

    /// Gives a file the mode of a system file and the SELinux context `context`, or the
    /// context of the store when `context` is `None`.
    pub fn label(&self, path: &Path, context: Option<&str>) -> Result<(), DropletError> {
        fs::set_permissions(path, fs::Permissions::from_mode(FontStore::FILE_MODE)).map_err(|error| DropletError::io_at("set the mode of", path, error))?;
        if let Some(context) = context.or(self.context.as_deref()) {
            SELinuxContext::set(path, context).map_err(|error| DropletError::io_at("label", path, error))?;
        }
        Ok(())
    }

    /// Writes `data` to `path` atomically (through a temporary file) and labels it with
    /// `context`, or with the context of the store when `context` is `None`.
    pub fn write(&self, path: &Path, data: &[u8], context: Option<&str>) -> Result<(), DropletError> {
        let temporary = path.with_extension("tmp");
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(FontStore::FILE_MODE)
            .open(&temporary)
            .map_err(|error| DropletError::io_at("create", &temporary, error))?;
        file.write_all(data).and_then(|_| file.sync_all()).map_err(|error| DropletError::io_at("write", &temporary, error))?;
        self.label(&temporary, context)?;
        fs::rename(&temporary, path).map_err(|error| DropletError::io_at("rename", &temporary, error))
    }

    /// Saves the configuration that the boot hook applies.
    pub fn save_config(&self, config: &Config) -> Result<(), DropletError> {
        let text = serde_json::to_string_pretty(config).map_err(|error| DropletError::io("serialize the configuration", io::Error::other(error)))?;
        self.write(&self.config_path(), text.as_bytes(), None)
    }

    /// The saved configuration, or `None` if no configuration is saved.
    pub fn load_config(&self) -> Result<Option<Config>, DropletError> {
        let path = self.config_path();
        match fs::read_to_string(&path) {
            Ok(text) => Config::parse(&text).map(Some).map_err(|problem| DropletError::Config(vec![problem])),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(DropletError::io_at("read", &path, error)),
        }
    }

    /// Removes the saved configuration, so that the boot hook applies nothing.
    pub fn remove_config(&self) -> Result<(), DropletError> {
        let path = self.config_path();
        match fs::remove_file(&path) {
            Err(error) if error.kind() != io::ErrorKind::NotFound => Err(DropletError::io_at("remove", &path, error)),
            _ => Ok(()),
        }
    }

    /// Copies the running engine to `executable_path`, replacing an earlier copy atomically.
    pub fn install_executable(&self) -> Result<PathBuf, DropletError> {
        let source = std::env::current_exe().map_err(|error| DropletError::io("find the engine executable", error))?;
        let target = self.executable_path();
        if source == target {
            return Ok(target);
        }
        let directory = target.parent().unwrap_or(&self.root);
        fs::create_dir_all(directory).map_err(|error| DropletError::io_at("create", directory, error))?;
        let temporary = target.with_extension("tmp");
        fs::copy(&source, &temporary).map_err(|error| DropletError::io_at("copy the engine to", &temporary, error))?;
        fs::set_permissions(&temporary, fs::Permissions::from_mode(FontStore::EXECUTABLE_MODE))
            .map_err(|error| DropletError::io_at("set the mode of", &temporary, error))?;
        if let Some(context) = &self.context {
            SELinuxContext::set(&temporary, context).map_err(|error| DropletError::io_at("label", &temporary, error))?;
        }
        File::open(&temporary).and_then(|file| file.sync_all()).map_err(|error| DropletError::io_at("write", &temporary, error))?;
        fs::rename(&temporary, &target).map_err(|error| DropletError::io_at("rename", &temporary, error))?;
        Ok(target)
    }

    /// The installed file of `entry`, if it is installed. The extension depends on the format
    /// of the source, so every format is tried; the source itself is not needed.
    pub fn installed(&self, entry: &FontEntry) -> Option<PathBuf> {
        [FontFormat::TTF, FontFormat::OTF, FontFormat::TTC]
            .iter()
            .map(|format| self.fonts_directory().join(FontStore::file_name(entry, format.extension())))
            .find(|path| path.is_file())
    }

    /// Installs a font: checks that the source still has the content its id names, writes
    /// the family name if requested, and labels the result. An installed font is reused
    /// without opening its source, which may have moved (an APK is moved by every update).
    pub fn install(&self, entry: &FontEntry, log: &mut Log) -> Result<InstalledFont, DropletError> {
        let path = match self.installed(entry) {
            Some(path) => path,
            None => self.create(entry, log)?,
        };
        let file_name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
        let index = entry.index as usize;
        let info = FontInfo::inspect(&FontSource::file(&path)).map_err(|source| DropletError::Font { path: path.clone(), source })?;
        let face = info.faces.into_iter().nth(index).ok_or_else(|| DropletError::Refused(format!("{file_name} has no face {index}")))?;
        Ok(InstalledFont { id: entry.id.clone(), family: entry.rename.clone(), file_name, path, index: entry.index, face })
    }

    /// Creates the installed file of `entry` from its source and returns its path.
    pub fn create(&self, entry: &FontEntry, log: &mut Log) -> Result<PathBuf, DropletError> {
        let font_error = |source| DropletError::Font { path: entry.source.path.clone(), source };
        let mut reader = SourceReader::open(&entry.source).map_err(|error| DropletError::io_at("open", &entry.source.path, error))?;
        let file = FontFile::read(&mut reader).map_err(font_error)?;
        if entry.index as usize >= file.faces().len() {
            return Err(DropletError::Refused(format!("{} has no face {}", entry.source.path.display(), entry.index)));
        }
        let hash = reader.sha256().map_err(|error| DropletError::io_at("read", &entry.source.path, error))?;
        if hash != entry.id {
            return Err(DropletError::Refused(format!("{} no longer has the content of font {}", entry.source.path.display(), entry.id)));
        }
        let file_name = FontStore::file_name(entry, file.format().extension());
        let path = self.fonts_directory().join(&file_name);
        let temporary = self.fonts_directory().join(format!(".{file_name}.tmp"));
        self.write_font(entry, &file, &mut reader, &temporary).inspect_err(|_| {
            let _ = fs::remove_file(&temporary);
        })?;
        self.label(&temporary, None)?;
        fs::rename(&temporary, &path).map_err(|error| DropletError::io_at("rename", &temporary, error))?;
        log.info(format!("installed {} as {file_name}", entry.source.path.display()));
        Ok(path)
    }

    pub fn write_font(&self, entry: &FontEntry, file: &FontFile, reader: &mut SourceReader, target: &Path) -> Result<(), DropletError> {
        let output = File::create(target).map_err(|error| DropletError::io_at("create", target, error))?;
        let mut writer = BufWriter::with_capacity(256 * 1024, output);
        match &entry.rename {
            Some(family) => {
                let index = entry.index as usize;
                let directory = &file.faces()[index];
                let data = directory
                    .read_table(reader, Tag::NAME)
                    .map_err(|source| DropletError::Font { path: entry.source.path.clone(), source })?
                    .ok_or_else(|| DropletError::Refused(format!("{} has no name table to rename", entry.source.path.display())))?;
                let mut name = NameTable::parse(&data).map_err(|source| DropletError::Font { path: entry.source.path.clone(), source })?;
                name.set_family(family);
                let data = name.to_bytes().map_err(|source| DropletError::Font { path: entry.source.path.clone(), source })?;
                let mut rebuilder = FontRebuilder::new(file);
                rebuilder.replace(index, Tag::NAME, data);
                if directory.find(Tag::DSIG).is_some() {
                    // The signature no longer matches the modified font.
                    rebuilder.remove(index, Tag::DSIG);
                }
                rebuilder.write(reader, &mut writer).map_err(|source| DropletError::Font { path: target.to_path_buf(), source })?;
            }
            None => {
                io::Seek::seek(reader, io::SeekFrom::Start(0)).map_err(|error| DropletError::io_at("read", &entry.source.path, error))?;
                io::copy(reader, &mut writer).map_err(|error| DropletError::io_at("copy to", target, error))?;
            }
        }
        let output = writer.into_inner().map_err(|error| DropletError::io_at("write", target, error.into_error()))?;
        output.sync_all().map_err(|error| DropletError::io_at("write", target, error))
    }

    /// Removes installed fonts whose file name is not in `keep`. Only safe while no font of
    /// the store is mounted, that is, before the first deployment after a boot.
    pub fn collect_fonts(&self, keep: &HashSet<String>, log: &mut Log) {
        let Ok(entries) = fs::read_dir(self.fonts_directory()) else { return };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !keep.contains(&name) && fs::remove_file(entry.path()).is_ok() {
                log.info(format!("removed unused font {name}"));
            }
        }
    }

    /// Removes generated documents of every generation other than `keep`.
    pub fn collect_documents(&self, keep: u64, log: &mut Log) {
        let Ok(entries) = fs::read_dir(self.documents_directory()) else { return };
        for entry in entries.flatten() {
            if entry.file_name().to_string_lossy() != keep.to_string() && fs::remove_dir_all(entry.path()).is_ok() {
                log.info(format!("removed documents of generation {}", entry.file_name().to_string_lossy()));
            }
        }
    }
}
