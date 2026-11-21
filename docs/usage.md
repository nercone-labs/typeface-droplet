# Using TypeFace Droplet

TypeFace Droplet replaces the fonts of the system while it runs, without a reboot. It needs root:
su of Magisk, KernelSU or APatch, Sui, or Shizuku started with root.

## Finding your way

The app has four places: **Home**, **Fonts**, **Customize** and **Settings**, in a bar at the
bottom of the screen, or in a rail at its side when the window is wide (a tablet, a foldable
opened, landscape).

Every screen starts with the same notices when they apply: the outcome of the last operation
(with **Show log** and **Dismiss**), and **Changes not applied yet** with **Apply now** when the
fonts, their names, the rules or the options changed since the fonts were applied. Importing a
font into the library does not count as a change: only the fonts the rules use are installed.

## Home

- The **verdict**: your fonts are active, the original fonts are active, the guard restored the
  original fonts, root is needed or restricted, or the system could not be read (with **Check
  again**). **Apply** and **Restore** are in it.
- **Details**: the privilege in use, what happens at boot, the guard, the disabled components and
  the engine log. Tapping one opens the page of the settings it is about.
- **Preview**: each kind of text in the chosen fonts. Tapping it opens Fonts. In a wide window
  the preview stands beside the verdict.

## Fonts

Choose a font for sans-serif, serif and monospace text, each upright and italic, from the
library: the bundled Nercone fonts and the fonts you **Import** (TTF, OTF or TTC). Tap a font of
the library to see it, to set the family name written into it, or to remove an imported font that
no role uses.

## Customize

Roles (sets of fonts) and the rules that decide which families of the system each role replaces,
for those who want more than the three kinds of text. The **System** tab lists the families of
the system and lets you assign a role or a single font to one of them. Editing a role or a rule
switches from the recommended rules to rules edited by hand; **Reset** returns to the recommended
ones.

## Settings

Settings is a list of pages, each with a summary of what it is set to; **Back** leaves a page,
then returns to Home.

- **Privilege**: automatic (Sui or Shizuku when it runs as root, su otherwise), Shizuku or Sui
  only, or su only.
- **At boot**: whether the root manager applies the fonts while the system starts (before any
  application), and whether this app applies them after the boot when they are not active.
- **Applying**: what is restarted after applying and restoring (the system interface, the home
  screen, the background applications, and packages you add), and how long the **guard** watches
  the system after a change. If the system crashes in that time, the guard restores the original
  fonts at once, and they are not applied at boot until you apply them again.
- **Components**: the downloadable fonts of Google Play services, and other components, disabled
  while the fonts are applied.
- **Advanced**: reset to the recommended configuration, and whether to accept font configuration
  files of versions newer than this one knows.
- **About**: the version, where the engine keeps its files, the license of the bundled fonts, and
  what to do before uninstalling.
