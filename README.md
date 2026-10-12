# TypeFace Droplet

The successor of TypeFace Magic: an Android application that replaces the system fonts of
Android 13 to 17 (API 33 to 37) without a reboot, from a Material 3 Expressive user interface.

- **Fonts**: choose one font for each kind of text (sans-serif, serif, monospace), upright and
  italic. The Nercone Sans, Serif and Mono CJK NF fonts are bundled; any TTF, OTF or TTC file can
  be imported. Families of the system that use these kinds (Roboto, Google Sans, Noto Serif, Droid
  Sans Mono and so on) follow the choice; the recommended rules reproduce TypeFace Magic.
- **Customization**: a role is a set of faces (fonts with a style, an optional fixed weight and
  fixed variation axes). Ordered rules map system font families to roles, by family name, by font
  file name, or by language (to insert fallback families). Any single system family can be
  assigned a role or a font directly. Once roles or rules are edited by hand, choosing fonts only
  changes the faces of the roles `sans`, `serif` and `mono`.
- **At boot**: with Magisk, KernelSU or APatch, the root manager applies the fonts at every boot
  before zygote starts, so every process uses them from its start and nothing has to be
  restarted. Otherwise, the application applies them once the system has started.
- **Privileges**: su (Magisk, KernelSU, APatch and others), Sui, or Shizuku started with root.
  Shizuku started through ADB runs as the shell user, which cannot change system mounts or the
  components of other applications, so it cannot apply fonts; the application explains this.
  Root must keep its capabilities: Magisk's "Restrict" Superuser policy drops them, and the engine
  then refuses to run, naming the missing capabilities; the application explains how to grant
  full root ("Grant" instead of "Restrict").

## How it works

The engine (`crates/droplet`, a Rust executable packaged as `libdroplet.so`) runs as root and:

1. installs the fonts into `/data/adb/typeface-droplet/fonts`, writing the configured family
   name into their `name` table when asked;
2. reads the original `/system/etc/fonts.xml`, `/system/etc/font_fallback.xml` and
   `/product/etc/fonts_customization.xml` (inside a private mount namespace, so that earlier
   generated documents are not seen), and generates new documents from the rules, with the
   SELinux labels of the originals;
3. in the mount namespace of init, covers the font directory with a tmpfs skeleton of bind
   mounts and adds the installed fonts to it, then bind-mounts the generated documents over the
   system documents;
4. runs `cmd font restart`, so that the font service reads the documents again and serves the
   new fonts to every process started afterwards, and checks with `cmd font dump` that it does;
5. disables the configured components (by default the downloadable font provider of Google Play
   services) and restarts the configured processes (by default SystemUI, the home application
   and the applications in the background);
6. saves the configuration in the store, for the boot hook;
7. starts a guard that watches system_server for a while (60 seconds by default). If
   system_server dies, or SystemUI keeps crashing, the guard removes the generated documents at
   once, and the configuration is not applied at boot until it is applied by hand. A shutdown or
   restart of the device during the watch only ends the watch.

Mounts do not survive a reboot. The boot hook (`droplet hook install`) puts a script into
`/data/adb/post-fs-data.d` (Magisk) or `/data/adb/post-mount.d` (KernelSU, APatch) that runs a
copy of the engine with `droplet boot` before zygote starts: it applies the saved configuration
without reloading anything, and its guard watches the boot until it completes and then at least
30 seconds more. After the boot, the application checks that the fonts are active and applies
them itself when they are not. Installed fonts stay reachable until the next boot even after
restoring the original fonts, because running processes load fonts lazily by path.

When the application is uninstalled, the next `droplet boot` applies nothing and starts a cleanup
that waits until the system has started, asks the package manager to confirm, re-enables the
components and removes the boot hook and the store.

On systems without fs-verity the font service cannot reload by itself; the application then
offers a soft restart (a restart of zygote, without rebooting the device). The boot hook makes
this unnecessary after the next boot.

## Building

Requirements: JDK 17 or later, the Android SDK with NDK 30.0.16248370, and Rust with the targets
`aarch64-linux-android`, `armv7-linux-androideabi` and `x86_64-linux-android`. The bundled fonts
are stored with Git LFS (`git lfs pull`); without them the application is built without bundled
fonts.

```bash
./gradlew :app:assembleRelease
```

Gradle builds the engine with Cargo for every ABI and links it with the LLVM toolchain of the NDK.
Dependencies are checked against `gradle/verification-metadata.xml`.

## Tests

```bash
cargo test --workspace
```

```bash
./gradlew :app:testDebugUnitTest
```

The Rust tests check the OpenType structures against the specification, the generated documents
against the grammar of the Android font parsers (on the documents of Android 13, 15 and 17), the
configuration format, the boot hook scripts and the JSON the application reads. The Kotlin tests
check the exchange format of the engine, the role and rule operations and the font selection.

## Layout

| Path | Contents |
| --- | --- |
| `crates/sfnt` | OpenType/TrueType/TTC reading and table rewriting |
| `crates/fontxml` | Lossless reading and editing of the Android font configuration XML |
| `crates/mount` | Mount namespaces, bind and tmpfs mounts, mountinfo, SELinux labels |
| `crates/android` | System properties, `cmd font`, `cmd package`, `cmd activity`, processes |
| `crates/droplet` | The engine, its boot hook and its command line |
| `app` | The Android application (Kotlin, Jetpack Compose, Material 3 Expressive) |
| `fonts` | The bundled fonts and their license (SIL Open Font License 1.1) |
