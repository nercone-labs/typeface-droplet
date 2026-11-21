# TypeFace Droplet — developer notes

These notes record why the application and the engine are shaped the way they are, and the
options that were considered and set aside. Read them with the crate documentation; every crate's
`lib.rs` states its own contract.

## The user interface

The screens were laid out again after the settings had grown into one long page, and after
failures were told differently on each screen. The layout follows that of Replay Integrity, a
sibling application built the same way, so that both behave alike.

- **Four destinations**: Home, Fonts, Customize and Settings, in a bar at the bottom of a narrow
  window, or in a rail at its side from 600 dp (`DropletApp.RAIL_WIDTH`). The content is centred
  at a readable width (`Widgets.CONTENT_WIDTH`). When the content has room for 840 dp (`Widgets.TWO_COLUMNS`), the screen is centred at a wider width (`Widgets.Screen`'s `columnsWidth`), and the home
  screen puts the preview beside the verdict, and the fonts screen puts the three categories side
  by side. The window size is read from `LocalWindowInfo`; the adaptive library was not added,
  because every dependency must be verified (`gradle/verification-metadata.xml`) and two widths
  do not need it.
- **Settings is a list of pages** (`SettingsScreen.Page`): Privilege, At boot, Applying,
  Components, Advanced and About, each listed with a summary of its values. The guard, the
  restart targets and the components used to share one page with the privilege and the boot,
  where a stray scroll changed them. Navigation is plain state in `DropletApp` (a destination and
  an optional page, saved across configuration changes) with `BackHandler`: Back leaves a page,
  then returns to Home. `navigation-compose` was not added for the same reason as above.
- **The details on the home screen open the page they are about**: the privilege opens Privilege,
  the boot opens At boot, the guard opens Applying, the disabled components open Components.
- **A choice of several values is a list item that opens a dialog** (`Widgets.ChoiceItem`), with
  the meaning of each option under it. The guard duration was a slider from 0 to 300 seconds in
  steps of 10, which was hard to set precisely and left no room to say what "off" means. It is now
  a choice of off, 30, 60, 120 and 300 seconds; a value set otherwise (by an earlier version, or in
  `config.json` by hand, up to 3600) is offered as well, so it is never changed by opening the
  dialog. Lists of packages and components are edited in a dialog (`Widgets.StringListItem`).
  The editors of roles and rules keep segmented buttons for their choices of two or three short
  values, which fit.
- **The notices are the same on every screen** (`Notices`): the outcome of the last operation
  (with its log), and **Changes not applied yet** with **Apply now**. Before, the outcome was shown
  on the home screen only, a failure elsewhere was a snackbar that went away, and a success was
  not shown at all until the user went home.
- **"Changes not applied yet"** compares `Config.digest` of the configuration with the digest
  recorded when it was last applied (`Settings.appliedDigest`). The digest covers what applying
  changes: the fonts the roles use (by id, face index and family name), the roles, the rules and
  the options. It leaves out where a font file is, because an update of the application moves the
  bundled fonts inside its new APK without changing them, and fonts no role uses, which the engine
  does not install; importing a font must not ask to apply again. The notice is shown only while
  the system shows applied fonts: after a restoration, or when the guard removed them, there is
  nothing applied to differ from. The digest recorded is that of the configuration as stored, not
  of the copy handed to the engine, which may lack a font the library lost and would then differ
  for ever. An installation that was applied by an earlier version has no
  digest, and shows no notice until it is applied again. Comparing with the engine's saved
  `config.json` was considered; it needs root and a reading for every edit, and it holds the
  sources, which change with every update.
- **The engine log belongs to the frame**: any notice can open it, and it shows the time of
  each line.
- **Importing a font tells its outcome**, including that the library had the font already (the
  library recognises a font by the SHA-256 of its bytes, so the same file is never added twice).
- **The notification permission is asked for once**, after the first application
  (`Settings.notificationsAsked`), instead of after every application while it is not granted.

## Robustness of the application

- **A status that cannot be read is a state of its own** (`HomeScreen.Condition.UNREADABLE`).
  Before, a failed `status` left the status empty, which the home screen took for "still
  checking": it showed a spinner and "Checking the system" for ever. The failure is kept apart
  (`UiState.statusError`); the verdict says the system could not be read and offers **Check
  again**, the engine log of the failed reading becomes the log, and the outcome of the operation
  before stays. A reading refused for a restricted root also becomes the outcome, so that the
  frame's dialog explains how to grant full root.
- **A failed preparation does not leave "Getting ready" for ever.** If the library or the
  configuration cannot be loaded, the failure is the outcome, the screens leave the preparation,
  and **Check again** (in the verdict while the configuration is missing) tries to load them again (`Droplet.initialize` does nothing once it has
  succeeded, and tries again after a failure).
- **After a failed operation the system is read again.** An operation can fail half-way; the
  screens showed the state from before it until the next check. Checking and importing change
  nothing on the system and are not followed by a reading.
- **The families of the system load apart from the operations** (`AppViewModel.loadFamilies`).
  They were loaded as an operation, which is refused while another runs: opening the System tab
  while the application was still checking the system left a spinner for ever, and so did a
  failure, as nothing tried again. They now load whenever the tab needs them and root is
  available; a failure is shown with **Try again**. Reading them changes nothing and does not
  conflict with an operation.
- **No input or output on the main thread**: the name of a chosen font file is asked of its
  provider (which may be a slow cloud provider) off the main thread.
- **A privilege that fails while it is asked** (a Shizuku binder that dies in between) is told as
  a reading that failed, with **Check again**, instead of leaving "Checking" for ever.
- **Lists are edited by the item added or removed**, not by sending a whole new list built from
  the one shown, which may be older than the stored one while an edit is being saved.
- **One privilege probe per check.** A check selected the privilege, then `Droplet.status`
  selected it again, running `su -c id -u` (or a Shizuku query) twice for every reading.
- **One instance of the settings per process.** `BootReceiver` made a second `Settings`, which
  could miss a write of the first. `Settings.update` is synchronized, because the boot worker
  writes from another thread than the user interface; two updates could otherwise start from the
  same values and lose one change.
- **An unreadable `config.json` is moved aside**, to `config.json.<time>.unreadable`, instead of
  being overwritten with the recommended configuration, so that rules written by hand are not
  lost when a newer version wrote a configuration this one cannot read; the user is told. Only a
  file that cannot be decoded is moved: a failure to read the file at all may be passing, and
  fails the preparation instead (with **Check again**), as does a move that fails, so that
  nothing ever replaces a configuration that was not kept.
- **The files handed to the engine are each a file of their own** (`Engine.withTemporary`), and
  are deleted afterwards. `preset` and `validate` wrote to fixed names, which two concurrent calls
  would have shared. The configuration to apply is written to a temporary file that replaces the
  old one, so that the engine never reads a half-written file.
- **A command that timed out does not hang the application.** After the timeout the process is
  killed, but a child of it (the engine started by `su`) may keep the output open; the output is
  now read for at most `ProcessRunner.DRAIN_MILLIS` more.
- **A cancelled operation is not reported as a failure.** Cancellation (the view model ending,
  the boot worker being replaced by the work of a later boot) passes through instead of becoming
  a failure notice or a notification.
- **A failed import leaves nothing behind**: its temporary copy is deleted whatever happens.
- **The engine's own messages** say what `su` printed when the engine printed no result, and an
  unexpected result is an engine error with its log rather than a crash of the application.

## Robustness of the engine

- **The guard and the cleanup daemon get a session of their own.** `Daemon::spawn` made the child
  the leader of a process group (`process_group(0)`), and `setsid`, which the guard called, refuses
  a process-group leader, so the guard stayed in the session of the `su`, Shizuku service or boot
  script that started it. The child now calls `setsid` itself, before `exec`, while it is still a
  member of its parent's group; a failure of `setsid` is a failure to start it.
- **A mount that fails half-way is undone.** When binding one document failed after another was
  already mounted, `apply` returned the error with the first document still in place, no guard
  running and the state saying nothing was applied. The documents are now detached again
  (`Engine::roll_back`), as they already were when the guard failed to start; the rollback also
  stops a guard that was started but could not be recorded, and logs a failure to save the state
  instead of reporting it in place of the failure that led there.
- **A soft restart records the stopped guard at once.** `soft-restart` stopped the guard and asked
  zygote to restart before saving the state; the restart ends the framework of the caller, and a
  failure to start the new guard returned early. Either left the stopped guard recorded as
  watching, which the next boot takes for a crash, so it refused to apply the fonts. The state is
  now saved before zygote restarts, and a guard that does not start leaves the restart unwatched
  (with a warning in the log) instead of failing the command after zygote was already told to
  restart.

### Known and not changed

These were found in a review and are plausible, but need a device with root to reproduce and a
larger change to fix safely:

- `apply` may report success when system_server crashes while it is still disabling components
  or restarting processes: the guard removes the documents and records the trip only after
  `apply` released the lock. The next reading of the status shows the true state.
- If saving the state or the configuration fails after the font service reloaded, `apply`
  reports a failure while the new documents stay mounted and guarded, and the boot hook keeps
  the configuration applied before.
- The boot hook runs in Magisk's `post-fs-data` stage, which pauses the boot for at most 40
  seconds, with its scripts capped at 35 (Magisk's developer guide, "Boot Scripts"). A first application at boot that must copy large fonts to slow storage may then
  race zygote. Fonts are installed when they are applied by hand, so this only concerns a
  configuration whose fonts were removed from the store.
- If the engine dies between creating a placeholder in the skeleton and binding the font onto it,
  the empty placeholder counts as installed until the next boot.

## Code shared with Replay Integrity

TypeFace Droplet and Replay Integrity are built the same way, and parts of them were written once and copied.
The copies had drifted apart, each with fixes the other lacked, so they were brought together:
for every part, the better of the two versions was taken, and both repositories now hold the
same code. The parts are:

- **`crates/android`**, the whole crate, byte for byte. It holds every Android interface either
  engine uses: the font service (`FontService`), the keystore (`DeviceKeystore`), watches of
  files (`FileWatch`) and `/data/system/packages.list` (`PackageList`) as well as the package
  manager, the activity manager, processes, capabilities and properties. Each engine uses only
  some of them; the unused ones are left out of the binary by the linker, and one crate
  for both is simpler to keep equal than two crates that differ by a few modules.
- **`crates/droplet/src/log.rs`**, byte for byte.
- **The `privilege` package** of the application (`Privilege`, `PrivilegeSelector`,
  `RootPrivilege`, `ShizukuPrivilege`, `CommandService`, `ProcessRunner` and
  `ICommandService.aidl`), which differs only in its `package` line.
- **`ui/Widgets.kt`**, which differs only in its `package` line and its import of `R`. Parts that
  belong to one application stay out of it (the editor of variation axes is
  `CustomizeScreen.Axes` in TypeFace Droplet).
- The shared labels at the top of `ui/Labels` (`privilege`, `privilegeChoice`, `describe`,
  `rootManager`, `rootManagers`), and the shape of the frame (`Navigator`, `Route`) and of the
  notice of changes not applied yet (`Notices.Pending`).
- The build files outside the application's own steps: `build.gradle.kts`, `gradle.properties`,
  `gradle/libs.versions.toml`, `rustfmt.toml`, and `app/proguard-rules.pro` apart from its package names.

A change to one of these parts is made in both repositories, in the same words. The copies are
checked with `diff -r crates/android ../replay-integrity/crates/android`, and with `diff` after
replacing the package names for the Kotlin files.

Sharing them through a third repository was considered: a Git submodule, or a Cargo Git
dependency and a published Gradle library. It was set aside for now. Each repository builds on
its own today, from one checkout, with every Gradle dependency verified
(`gradle/verification-metadata.xml`); a submodule makes every checkout and the CI depend on a
second repository, and a published library must be published and verified on every change.
