use std::fmt;
use std::io;

/// An error raised while reading or writing an SFNT font file.
#[derive(Debug)]
pub enum SFNTError {
    /// The underlying reader or writer failed.
    IO(io::Error),
    /// The data violates the OpenType specification.
    Malformed(String),
    /// The data is valid, but uses a version or feature this crate does not implement.
    Unsupported(String),
}

impl SFNTError {
    pub fn malformed(reason: impl Into<String>) -> SFNTError {
        SFNTError::Malformed(reason.into())
    }

    pub fn unsupported(reason: impl Into<String>) -> SFNTError {
        SFNTError::Unsupported(reason.into())
    }
}

impl fmt::Display for SFNTError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SFNTError::IO(error) => write!(formatter, "I/O error: {error}"),
            SFNTError::Malformed(reason) => write!(formatter, "malformed font: {reason}"),
            SFNTError::Unsupported(reason) => write!(formatter, "unsupported font: {reason}"),
        }
    }
}

impl std::error::Error for SFNTError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            SFNTError::IO(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for SFNTError {
    fn from(error: io::Error) -> SFNTError {
        SFNTError::IO(error)
    }
}
