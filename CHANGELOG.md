# Changelog

Notable changes per release. Newest first.

The top section is published as the GitHub release notes and the Nexus file
description. It is written for someone deciding whether to update.

## 1.0.0 - 2026-08-21

First release out of beta. Downloading a chosen version and switching an
existing install have both been in use through the 0.1.x series; the version
numbering says so.

### Fixed

- **Downloads failed on Steam's newest builds.** Steam has started serving some
  chunks compressed with Zstandard (`VSZa`), and Reliquary only understood the
  older formats, so any download or switch that touched one of those chunks died
  with "malformed deflate stream". Skyrim SE's current build is affected. If you
  are on 0.1.3, this is the reason to update.

### Added

- **Real version numbers.** The current build of a game used to be called
  "Latest" because Steam publishes no version number for it. Reliquary now reads
  the number out of the game's own executable, so Skyrim SE reads 1.7.99,
  Fallout 4 reads 1.11.240, and so on. The current build is shown as
  `1.7.99 (Latest)`, so the number says which build it is and the marker says it
  is the one Steam ships today.
- **Switch a copy of a game that Steam did not install.** A backup, a second
  install kept at a known-good build, or a folder moved by hand can now be
  pointed at directly: `Change…` beside the install path re-points a detected
  install, and `Already have a copy? Choose its folder…` under the download
  button handles a game Steam has never installed. The version is worked out by
  hashing the executables, which needs no Steam bookkeeping.
- **Force a switch on an unrecognised build.** A hand-downgraded install matches
  no build in the catalog, and used to be offered nothing. It can now be switched
  anyway: every file is checked against the target and whatever does not already
  match is fetched. More than an ordinary switch downloads, far less than a
  reinstall.

### Changed

- The library's Owned tab lists games you already have on disk first, and marks
  them with the build they are on.
- The Installed/Owned switch and the search box moved into the title bar.
- Skyrim Special Edition and Fallout 4 picked up the builds Steam shipped on
  20 and 18 August. Fallout 4's previous build was recorded before it rolled, so
  it is still available to downgrade to.

### Notes

- A switch never deletes files it does not recognise. Mods, ini edits, script
  extenders and anything else in the game folder are left alone, and files a
  build adds are created. This was always the intent; there are now tests that
  fail if it stops being true.
- The other side of that: a file that only the newer build has survives a
  downgrade. Steam would remove it. For Bethesda games an unreferenced archive
  is not loaded, so this is usually harmless.

## 0.1.3 and earlier

Released before this file existed. See the commit history.
