use std::fs;
use std::path::PathBuf;
use std::process::ExitCode;

use droplet::{Config, DropletError, Engine, FontInfo, FontSource, FontStore, Guard, GuardTarget, LogLine, Preset, SourceReader};
use serde::Serialize;
use serde_json::{Value, json};

/// A subcommand of the command line.
enum Command {
    Inspect(FontSource),
    Hash(FontSource),
    Preset(PathBuf),
    Validate(PathBuf),
    Families,
    Apply { config: PathBuf, boot: bool },
    Revert,
    Status,
    SoftRestart { seconds: u32 },
    Guard { seconds: u32, target: GuardTarget },
    Version,
}

/// The parsed command line: `droplet <command> [arguments] [--store DIR]`.
///
/// Every command prints one JSON object to standard output:
/// `{"ok": true, "result": ..., "log": [...]}` or
/// `{"ok": false, "error": {"kind": ..., "message": ...}, "log": [...]}`,
/// and exits with status 0 or 1 accordingly. A command line that cannot be parsed prints the
/// usage to standard error and the error object to standard output, and exits with status 2.
struct CommandLine {
    command: Command,
    store: PathBuf,
}

impl CommandLine {
    const USAGE: &'static str = "usage: droplet <inspect|hash> <file> [--offset N] [--length N]
       droplet preset <preset.json>
       droplet validate <config.json>
       droplet apply <config.json> [--boot]
       droplet families | revert | status | version
       droplet soft-restart [--seconds N]
       droplet guard --seconds N (--pid PID | --after PID)
options: --store DIR   the store directory, an absolute path (default /data/adb/typeface-droplet)";

    fn parse() -> Result<CommandLine, lexopt::Error> {
        use lexopt::prelude::*;
        let mut parser = lexopt::Parser::from_env();
        let mut name: Option<String> = None;
        let mut positional: Vec<String> = Vec::new();
        let mut store = PathBuf::from(FontStore::DEFAULT_ROOT);
        let mut offset = 0u64;
        let mut length: Option<u64> = None;
        let mut boot = false;
        let mut seconds = 60u32;
        let mut after: Option<u32> = None;
        let mut pid: Option<u32> = None;
        while let Some(argument) = parser.next()? {
            match argument {
                Long("store") => store = parser.value()?.parse()?,
                Long("offset") => offset = parser.value()?.parse()?,
                Long("length") => length = Some(parser.value()?.parse()?),
                Long("boot") => boot = true,
                Long("seconds") => seconds = parser.value()?.parse()?,
                Long("after") => after = Some(parser.value()?.parse()?),
                Long("pid") => pid = Some(parser.value()?.parse()?),
                Value(value) if name.is_none() => name = Some(value.string()?),
                Value(value) => positional.push(value.string()?),
                _ => return Err(argument.unexpected()),
            }
        }
        let first = |positional: &[String]| -> Result<String, lexopt::Error> {
            positional.first().cloned().ok_or_else(|| lexopt::Error::from("missing file argument"))
        };
        let source =
            |positional: &[String]| -> Result<FontSource, lexopt::Error> { Ok(FontSource { path: PathBuf::from(first(positional)?), offset, length }) };
        let command = match name.as_deref() {
            Some("inspect") => Command::Inspect(source(&positional)?),
            Some("hash") => Command::Hash(source(&positional)?),
            Some("preset") => Command::Preset(first(&positional)?.into()),
            Some("validate") => Command::Validate(first(&positional)?.into()),
            Some("families") => Command::Families,
            Some("apply") => Command::Apply { config: first(&positional)?.into(), boot },
            Some("revert") => Command::Revert,
            Some("status") => Command::Status,
            Some("soft-restart") => Command::SoftRestart { seconds },
            Some("guard") => Command::Guard {
                seconds,
                target: match (pid, after) {
                    (Some(pid), None) => GuardTarget::Running(pid),
                    (None, Some(previous)) => GuardTarget::After(previous),
                    _ => return Err(lexopt::Error::from("guard needs exactly one of --pid and --after")),
                },
            },
            Some("version") => Command::Version,
            Some(other) => return Err(lexopt::Error::from(format!("unknown command {other}"))),
            None => return Err(lexopt::Error::from("missing command")),
        };
        if !store.is_absolute() {
            // The engine changes its mount namespace, which also changes its working directory.
            return Err(lexopt::Error::from("--store must be an absolute path"));
        }
        if seconds > Config::MAX_GUARD_SECONDS {
            return Err(lexopt::Error::from(format!("--seconds must be at most {}", Config::MAX_GUARD_SECONDS)));
        }
        Ok(CommandLine { command, store })
    }

    fn json<T: Serialize>(value: &T) -> Result<Value, DropletError> {
        serde_json::to_value(value).map_err(|error| DropletError::io("serialize the result", std::io::Error::other(error)))
    }

    fn read(path: &PathBuf) -> Result<String, DropletError> {
        fs::read_to_string(path).map_err(|error| DropletError::io_at("read", path, error))
    }

    fn run(self) -> (Result<Value, DropletError>, Vec<LogLine>) {
        let store = FontStore::new(self.store, Some(mount::SELinuxContext::SYSTEM_FILE.to_string()));
        let mut engine = Engine::new(store);
        let result = match self.command {
            Command::Inspect(source) => FontInfo::inspect(&source)
                .map_err(|error| DropletError::Font { path: source.path.clone(), source: error })
                .and_then(|info| CommandLine::json(&info)),
            Command::Hash(source) => SourceReader::open(&source)
                .and_then(|mut reader| reader.sha256())
                .map(|sha256| json!({ "sha256": sha256 }))
                .map_err(|error| DropletError::io_at("read", &source.path, error)),
            Command::Preset(path) => CommandLine::read(&path).and_then(|text| {
                let preset: Preset = serde_json::from_str(&text).map_err(|error| DropletError::Config(vec![format!("invalid preset: {error}")]))?;
                CommandLine::json(&preset.config())
            }),
            Command::Validate(path) => CommandLine::read(&path).map(|text| match Config::parse(&text) {
                Ok(config) => json!({ "problems": config.validate() }),
                Err(problem) => json!({ "problems": [problem] }),
            }),
            Command::Families => engine.families().and_then(|families| CommandLine::json(&families)),
            // The configuration lives in the private directory of the application, which only
            // an unrestricted root can read; checking first names the actual problem.
            Command::Apply { config, boot } => Engine::require_root()
                .and_then(|()| CommandLine::read(&config))
                .and_then(|text| Config::parse(&text).map_err(|problem| DropletError::Config(vec![problem])))
                .and_then(|config| engine.apply(&config, boot))
                .and_then(|report| CommandLine::json(&report)),
            Command::Revert => engine.revert().map(|restarted| json!({ "restarted": restarted })),
            Command::Status => engine.status().and_then(|status| CommandLine::json(&status)),
            Command::SoftRestart { seconds } => engine.soft_restart(seconds).map(|guard| json!({ "guard": guard })),
            Command::Guard { seconds, target } => {
                let mut log = engine.log;
                let outcome = Guard::run(&engine.store, seconds, target, &mut log).and_then(|outcome| CommandLine::json(&outcome));
                return (outcome, log.lines);
            }
            Command::Version => Ok(json!({ "version": env!("CARGO_PKG_VERSION"), "configVersion": Config::VERSION })),
        };
        (result, engine.log.lines)
    }
}

fn main() -> ExitCode {
    let command_line = match CommandLine::parse() {
        Ok(command_line) => command_line,
        Err(error) => {
            eprintln!("droplet: {error}\n{}", CommandLine::USAGE);
            let output = json!({ "ok": false, "error": { "kind": "usage", "message": error.to_string() }, "log": [] });
            println!("{output}");
            return ExitCode::from(2);
        }
    };
    let (result, log) = command_line.run();
    let (output, status) = match result {
        Ok(result) => (json!({ "ok": true, "result": result, "log": log }), ExitCode::SUCCESS),
        Err(error) => (json!({ "ok": false, "error": { "kind": error.kind(), "message": error.to_string() }, "log": log }), ExitCode::FAILURE),
    };
    println!("{output}");
    status
}
