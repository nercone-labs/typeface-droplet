//! The engine of TypeFace Droplet: replaces the system fonts of Android 13 and later at
//! runtime, without a reboot.
//!
//! - `Config` describes fonts, roles (sets of faces), ordered rules that map system font
//!   families to roles, components to disable and processes to restart.
//! - `FontStore` installs fonts (optionally writing a new family name into them).
//! - `Generator` rewrites the system font configuration documents.
//! - `FontDirectory` adds the installed fonts to the read-only system font directories with
//!   a tmpfs skeleton, and `Engine` mounts everything in the mount namespace of init and
//!   reloads the font service.
//! - `Guard` removes the generated documents if system_server dies right after a reload or
//!   during a boot.
//! - `BootHook` installs a boot script of the root manager (Magisk, KernelSU, APatch) that
//!   applies the saved configuration at every boot, before zygote starts.

mod components;
mod config;
mod daemon;
mod document;
mod engine;
mod error;
mod font_info;
mod generator;
mod guard;
mod hook;
mod log;
mod pattern;
mod preset;
mod skeleton;
mod state;
mod store;

pub use components::Components;
pub use config::{Action, Config, Face, FaceStyle, FallbackEntry, FontEntry, FontSource, Matcher, Role, Rule};
pub use daemon::Daemon;
pub use document::{DocumentKind, SystemDocument};
pub use engine::{ApplyMode, ApplyReport, BootReport, Engine, FamilyInfo, GeneratedDocument, ReloadOutcome, StatusReport};
pub use error::DropletError;
pub use font_info::{AxisInfo, FaceInfo, FontInfo, SourceReader};
pub use generator::{GenerationReport, Generator};
pub use guard::{Ending, Guard, GuardTarget, Watch};
pub use hook::{BootHook, RootManager};
pub use log::{Log, LogLevel, LogLine};
pub use pattern::Pattern;
pub use preset::Preset;
pub use skeleton::FontDirectory;
pub use state::{ComponentRecord, GuardState, MountedDocument, State};
pub use store::{FontKey, FontStore, InstalledFont};
