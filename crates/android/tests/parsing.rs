//! Tests of the parsers of Android system program output, using output captured from
//! Android 17 (API 37) and the formats documented by `cmd package help`.

use android::{ComponentName, ComponentState, FontService, PackageManager, ProcessInfo};

#[test]
fn component_names_expand_relative_classes() {
    let component = ComponentName::parse("com.google.android.gms/.fonts.provider.FontsProvider").unwrap();
    assert_eq!(component.package, "com.google.android.gms");
    assert_eq!(component.class_name(), "com.google.android.gms.fonts.provider.FontsProvider");
    assert_eq!(component.to_string(), "com.google.android.gms/.fonts.provider.FontsProvider");
    let absolute = ComponentName::parse("a.b/c.d.Service").unwrap();
    assert_eq!(absolute.class_name(), "c.d.Service");
    for invalid in ["nopackage", "/x", "a.b/", "a.b/.", "a b/c"] {
        assert!(ComponentName::parse(invalid).is_none(), "{invalid}");
    }
}

#[test]
fn users_are_read_from_user_info_entries() {
    let output = "Users:\n\tUserInfo{0:Owner:4c13} running\n\tUserInfo{10:Work profile:1030}\n\tUserInfo{150:Private:1090} running\n";
    assert_eq!(PackageManager::parse_users(output), vec![0, 10, 150]);
    assert!(PackageManager::parse_users("Error: no users").is_empty());
}

const DUMP: &str = "Activity Resolver Table:
  Non-Data Actions:
    User 0:
      disabledComponents:
        com.google.android.gms.fonts.update.UpdateSchedulerService
Packages:
  Package [com.google.android.gms] (41cd270):
    User 0: ceDataInode=1 installed=true hidden=false
      gids=[3003]
      disabledComponents:
        com.google.android.gms.pay.deeplink.AliasProcessImageResourceActivity
        com.google.android.gms.fonts.provider.FontsProvider
      enabledComponents:
        com.google.android.gms.fonts.update.UpdateSchedulerService
      lastDisabledCaller: shell
    User 1: ceDataInode=2 installed=true
      enabledComponents:
        com.google.android.gms.fonts.provider.FontsProvider
    User 10: ceDataInode=3 installed=true
      disabledComponents:
        com.google.android.gms.fonts.update.UpdateSchedulerService

Hidden system packages:
  Package [com.google.android.gms] (1a2b3c):
    User 1: ceDataInode=0 installed=true
      disabledComponents:
        com.google.android.gms.fonts.update.UpdateSchedulerService
Queries:
  User 2:
      disabledComponents:
        com.google.android.gms.fonts.provider.FontsProvider
";

#[test]
fn component_states_are_read_per_user() {
    let provider = "com.google.android.gms.fonts.provider.FontsProvider";
    let scheduler = "com.google.android.gms.fonts.update.UpdateSchedulerService";
    assert_eq!(PackageManager::parse_component_state(DUMP, 0, provider), ComponentState::Disabled);
    assert_eq!(PackageManager::parse_component_state(DUMP, 0, scheduler), ComponentState::Enabled);
    assert_eq!(PackageManager::parse_component_state(DUMP, 1, provider), ComponentState::Enabled);
    assert_eq!(PackageManager::parse_component_state(DUMP, 1, scheduler), ComponentState::Default);
    assert_eq!(PackageManager::parse_component_state(DUMP, 10, scheduler), ComponentState::Disabled);
    // "User 1:" must not match the section of user 10, and lines after a list end it.
    assert_eq!(PackageManager::parse_component_state(DUMP, 10, provider), ComponentState::Default);
    assert_eq!(PackageManager::parse_component_state(DUMP, 0, "lastDisabledCaller: shell"), ComponentState::Default);
    // Sections other than Packages: describe other settings and are ignored.
    assert_eq!(PackageManager::parse_component_state(DUMP, 2, provider), ComponentState::Default);
    assert_eq!(PackageManager::parse_component_state(DUMP, 1, scheduler), ComponentState::Default);
    for state in [ComponentState::Default, ComponentState::Enabled, ComponentState::Disabled] {
        assert_eq!(ComponentState::from_name(state.name()), Some(state));
    }
}

#[test]
fn font_dump_paths_are_extracted() {
    let dump = "Named Family List
  Named Family (sans-serif)
    Family
      style = FontStyle { weight=400, slant=0}, path = /system/fonts/Roboto-Regular.ttf, axes = 'wdth' 100.0, rev=3.015
Dump Fallback Families
  Fallback Family [3]: lang=\"und-Ethi\"
    style = FontStyle { weight=400, slant=0}, path = /system/fonts/NotoSerifEthiopic-VF.ttf, fallback = serif, rev=2.102
    style = FontStyle { weight=400, slant=0}, path = /system/fonts/Last.ttf
";
    assert_eq!(FontService::paths(dump), ["/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSerifEthiopic-VF.ttf", "/system/fonts/Last.ttf"]);
}

#[test]
fn process_names_are_the_first_argument() {
    assert_eq!(ProcessInfo::name_of(b"system_server\0\0\0\0"), "system_server");
    assert_eq!(ProcessInfo::name_of(b"/system/bin/sh\0-c\0x\0"), "/system/bin/sh");
    let process = ProcessInfo { pid: 1, name: "com.android.systemui:screenshot".to_string() };
    assert!(process.belongs_to("com.android.systemui"));
    assert!(!process.belongs_to("com.android.system"));
    assert!(ProcessInfo { pid: 1, name: "com.android.systemui".to_string() }.belongs_to("com.android.systemui"));
}
