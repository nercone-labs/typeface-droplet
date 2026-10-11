use std::fmt;
use std::io;
use std::path::{Path, PathBuf};

use android::CommandError;
use fontxml::FontXMLError;
use mount::MountError;
use sfnt::SFNTError;

/// An error of the engine. Every variant names what it was working on.
#[derive(Debug)]
pub enum DropletError {
    /// The configuration is invalid.
    Config(Vec<String>),
    /// A font file cannot be read or written.
    Font {
        path: PathBuf,
        source: SFNTError,
    },
    /// A font configuration document cannot be read, or the generated one is invalid.
    Document {
        path: PathBuf,
        source: FontXMLError,
    },
    /// A generated document failed its checks.
    Generation {
        path: PathBuf,
        reason: String,
    },
    /// A file operation failed.
    IO {
        context: String,
        source: io::Error,
    },
    Mount(MountError),
    Command(CommandError),
    /// The engine refuses to act in the current situation.
    Refused(String),
}

impl DropletError {
    pub fn io(context: impl Into<String>, source: io::Error) -> DropletError {
        DropletError::IO { context: context.into(), source }
    }

    pub fn io_at(action: &str, path: &Path, source: io::Error) -> DropletError {
        DropletError::IO { context: format!("{action} {}", path.display()), source }
    }

    /// A short machine-readable name of the variant, for user interfaces.
    pub fn kind(&self) -> &'static str {
        match self {
            DropletError::Config(_) => "config",
            DropletError::Font { .. } => "font",
            DropletError::Document { .. } => "document",
            DropletError::Generation { .. } => "generation",
            DropletError::IO { .. } => "io",
            DropletError::Mount(_) => "mount",
            DropletError::Command(_) => "command",
            DropletError::Refused(_) => "refused",
        }
    }
}

impl fmt::Display for DropletError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DropletError::Config(problems) => write!(formatter, "invalid configuration: {}", problems.join("; ")),
            DropletError::Font { path, source } => write!(formatter, "{}: {source}", path.display()),
            DropletError::Document { path, source } => write!(formatter, "{}: {source}", path.display()),
            DropletError::Generation { path, reason } => write!(formatter, "generated {} is invalid: {reason}", path.display()),
            DropletError::IO { context, source } => write!(formatter, "cannot {context}: {source}"),
            DropletError::Mount(error) => write!(formatter, "cannot {error}"),
            DropletError::Command(error) => write!(formatter, "{error}"),
            DropletError::Refused(reason) => formatter.write_str(reason),
        }
    }
}

impl std::error::Error for DropletError {}

impl From<MountError> for DropletError {
    fn from(error: MountError) -> DropletError {
        DropletError::Mount(error)
    }
}

impl From<CommandError> for DropletError {
    fn from(error: CommandError) -> DropletError {
        DropletError::Command(error)
    }
}
