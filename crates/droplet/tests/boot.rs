//! Tests of the parts of booting that do not need a running Android system: the boot hook
//! script, the saved configuration and the detection of the installed application.

use std::fs;
use std::path::{Path, PathBuf};

use droplet::{BootHook, Config, Engine, FontStore, Preset, RootManager};

fn temporary(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("droplet-boot-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).unwrap();
    path
}

#[test]
fn hook_scripts_run_the_store_copy_of_the_engine_with_quoted_arguments() {
    let store = FontStore::new("/data/adb/it's here", None);
    let script = BootHook::script(&store, "dev.nercone.typefacedroplet").unwrap();
    let lines: Vec<&str> = script.lines().collect();
    assert_eq!(lines[0], "#!/system/bin/sh");
    assert!(lines[1..lines.len() - 1].iter().all(|line| line.starts_with('#')), "only comments precede the command");
    // POSIX shell quoting: a single quote is written as '\'' inside single quotes.
    assert_eq!(
        *lines.last().unwrap(),
        "'/data/adb/it'\\''s here/bin/droplet' boot --package 'dev.nercone.typefacedroplet' --store '/data/adb/it'\\''s here' >/dev/null 2>&1"
    );
    assert!(script.ends_with('\n'));
}

#[test]
fn hook_scripts_refuse_anything_but_package_names() {
    let store = FontStore::new("/data/adb/typeface-droplet", None);
    for package in ["", "single", "a;reboot", "a.b c", "1a.b", "a..b", "'a.b'"] {
        assert!(BootHook::script(&store, package).is_err(), "{package:?}");
    }
}

#[test]
fn hooks_run_before_zygote_and_after_the_modules_of_overlay_root_managers() {
    assert_eq!(RootManager::Magisk.hook_directory(), Path::new("/data/adb/post-fs-data.d"));
    assert_eq!(RootManager::KernelSU.hook_directory(), Path::new("/data/adb/post-mount.d"));
    assert_eq!(RootManager::APatch.hook_directory(), Path::new("/data/adb/post-mount.d"));
    let candidates = BootHook::candidates();
    assert_eq!(candidates, [PathBuf::from("/data/adb/post-fs-data.d/typeface-droplet.sh"), PathBuf::from("/data/adb/post-mount.d/typeface-droplet.sh")]);
}

#[test]
fn saved_configurations_round_trip_and_can_be_removed() {
    let directory = temporary("config");
    let store = FontStore::new(directory.join("store"), None);
    store.prepare().unwrap();
    assert_eq!(store.load_config().unwrap(), None, "nothing is saved at first");
    let config = Preset::default().config();
    store.save_config(&config).unwrap();
    assert_eq!(store.load_config().unwrap(), Some(config));
    store.remove_config().unwrap();
    assert_eq!(store.load_config().unwrap(), None);
    store.remove_config().unwrap();
    fs::write(store.config_path(), "{\"version\": 99}").unwrap();
    assert!(store.load_config().is_err(), "an unknown version is an error, not an empty configuration");
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn the_engine_copy_is_executable_by_root_only() {
    use std::os::unix::fs::PermissionsExt;
    let directory = temporary("executable");
    let store = FontStore::new(directory.join("store"), None);
    store.prepare().unwrap();
    let path = store.install_executable().unwrap();
    assert_eq!(path, store.executable_path());
    assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o700);
    assert_eq!(fs::read(&path).unwrap(), fs::read(std::env::current_exe().unwrap()).unwrap());
    store.install_executable().unwrap();
    assert!(!path.with_extension("tmp").exists());
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn installed_applications_are_found_in_both_directory_layouts() {
    let directory = temporary("applications");
    let old = directory.join("old");
    let new = directory.join("new");
    fs::create_dir_all(old.join("com.example.app-1")).unwrap();
    fs::create_dir_all(new.join("~~random==").join("com.example.other-AbC==")).unwrap();
    fs::create_dir_all(new.join("com.example.prefix")).unwrap();
    let directories = [old.as_path(), new.as_path()];
    assert!(Engine::is_package_present_in(&directories, "com.example.app"));
    assert!(Engine::is_package_present_in(&directories, "com.example.other"));
    assert!(!Engine::is_package_present_in(&directories, "com.example"), "a package is not a prefix of another");
    assert!(!Engine::is_package_present_in(&directories, "com.example.prefix"), "the suffix separator is required");
    assert!(Engine::is_package_present_in(&[directory.join("missing").as_path()], "com.example.missing"), "unreadable directories count as present");
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn restart_targets_are_packages_home_or_background() {
    for target in ["com.android.systemui", Config::HOME, Config::BACKGROUND] {
        assert!(Config::is_restart_target(target), "{target}");
    }
    for target in ["@other", "background", "systemui", ""] {
        assert!(!Config::is_restart_target(target), "{target}");
    }
}

#[test]
fn reports_name_root_managers_and_reload_outcomes_in_camel_case() {
    use droplet::ReloadOutcome;
    let names: Vec<String> = RootManager::ALL.iter().map(|manager| serde_json::to_string(manager).unwrap()).collect();
    assert_eq!(names, ["\"magisk\"", "\"kernelSU\"", "\"aPatch\""]);
    let outcomes: Vec<String> = [ReloadOutcome::Served, ReloadOutcome::Ignored, ReloadOutcome::Unchanged, ReloadOutcome::AtStart]
        .iter()
        .map(|outcome| serde_json::to_string(outcome).unwrap())
        .collect();
    assert_eq!(outcomes, ["\"served\"", "\"ignored\"", "\"unchanged\"", "\"atStart\""]);
    let hook = BootHook { managers: vec![RootManager::Magisk], scripts: vec![PathBuf::from("/data/adb/post-fs-data.d/typeface-droplet.sh")] };
    assert_eq!(
        serde_json::to_value(&hook).unwrap(),
        serde_json::json!({ "managers": ["magisk"], "scripts": ["/data/adb/post-fs-data.d/typeface-droplet.sh"] })
    );
}

#[test]
fn only_rescue_party_reboots_count_as_failures() {
    use droplet::Watch;
    for reason in ["reboot,RescueParty", "reboot,rescueparty", "shutdown,rescue"] {
        assert!(Watch::is_rescue(reason), "{reason}");
    }
    for reason in ["reboot,userrequested", "reboot,shell", "shutdown,", "reboot,adb", "0reboot"] {
        assert!(!Watch::is_rescue(reason), "{reason}");
    }
}

#[test]
fn guards_are_recognized_by_their_command_and_store_whichever_engine_runs_them() {
    use droplet::Guard;
    let arguments = |text: &str| text.split(' ').map(str::to_string).collect::<Vec<_>>();
    let root = "/data/adb/typeface-droplet";
    assert!(Guard::is_guard_command(&arguments("guard --store /data/adb/typeface-droplet --seconds 60 --boot"), root));
    assert!(Guard::is_guard_command(&arguments("guard --seconds 60 --pid 600 --store /data/adb/typeface-droplet"), root));
    assert!(!Guard::is_guard_command(&arguments("guard --store /data/adb/other --seconds 60 --boot"), root), "a guard of another store");
    assert!(!Guard::is_guard_command(&arguments("boot --store /data/adb/typeface-droplet"), root), "another command of the engine");
    assert!(!Guard::is_guard_command(&arguments("--store /data/adb/typeface-droplet guard"), root));
    assert!(!Guard::is_guard_command(&[], root));
}
