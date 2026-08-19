# Reliquary

[![test](https://github.com/halgari/reliquary/actions/workflows/test.yml/badge.svg)](https://github.com/halgari/reliquary/actions/workflows/test.yml)

Download a chosen *version* of a Steam game to a folder of your choosing.
Steam's client gives you one build: whatever is current. Reliquary gives you
the archive.

Not associated with or endorsed by Valve Corporation or Steam.

## Building

Needs JDK 26 (`JAVA_HOME`, or `/usr/lib/jvm/java-26-openjdk`) and the Clojure CLI.

    clojure -M:test                          # tests
    clojure -M:app                           # run the desktop app
    clojure -M:cli login                     # sign in from a terminal

    ./bin/setup-toolchain.sh                 # JavaFX jmods, for jlink
    clojure -T:build uber :omit-javafx true  # target/lib/reliquary.jar
    ./bin/package.sh                         # target/app/Reliquary/bin/Reliquary

`clojure -T:build uber` without `:omit-javafx` produces a fatter jar that runs
under a plain `java -jar`. `bin/package.sh` does not need that, because its
jlink runtime supplies JavaFX as modules.

**The tests and the build both need a display.** Several tests render real
JavaFX scenes and measure the resulting pixels, and the build AOT-compiles —
which loads `cljfx.api`, and *that* initialises the JavaFX toolkit at load time.
On a headless machine both abort at `Unable to open DISPLAY` before doing any
work, which reads like a code fault and is not one:

    xvfb-run -a clojure -M:test
    xvfb-run -a clojure -T:build uber :omit-javafx true

### Windows

The packaging jar carries no JavaFX and no native libraries at all — `drop-javafx`
in `build.clj` strips every `org.openjfx` artifact, and the jlink runtime supplies
JavaFX as real modules instead. So the jar is identical on every OS and only the
runtime is platform-specific, which is why a Windows build needs no change to
`deps.edn`: the Windows natives come from Gluon's jmods by way of jlink, never
from Maven.

On a Windows machine with JDK 26 and the jar already built:

    pwsh ./bin/package-windows.ps1           # target/app/Reliquary/Reliquary.exe

Running the *tests* on Windows is a separate matter, and does not work yet:
`deps.edn` pins the `$linux` JavaFX classifiers, so the UI tests would load Linux
natives. CI runs them on Linux.

### Icons

The application icon is derived from the header logo, not drawn separately, so
the two cannot drift apart:

    python3 bin/make-icons.py     # needs Pillow

`resources/reliquary-logo.png` is a 330x78 wordmark; the script measures the
emblem's bounds out of its alpha channel and writes two committed artifacts —
`reliquary-icon.png` (what the running window shows in the taskbar and alt-tab)
and `reliquary.ico` (what `jpackage --icon` stamps on the `.exe`). Both are
committed so neither CI nor a packaging run needs Pillow. Re-run it after any
change to the logo.

### Window chrome

The window is `StageStyle/UNDECORATED`, because the app draws its own title bar —
with the OS bar as well, Windows showed two stacked bars. That means the OS
supplies no close button, no grab handle and **no resize edges**: the title bar's
own close button and drag handlers replace the first two, and the window is
currently a fixed 1100x720 with no minimise.

## Releases

Tagging is the whole trigger:

    git tag v0.1.0 && git push origin v0.1.0

`.github/workflows/release.yml` then builds the jar on Linux, packages and signs
a Windows app image, and opens a **draft** release carrying
`Reliquary-<version>-win-x64.zip` and `SHA256SUMS`. It stays a draft on purpose —
publishing is a human decision.

Linux releases are not wired up yet. Authenticode does not apply to an ELF
binary, so that needs checksums plus a detached GPG or minisign signature rather
than a copy of the Windows job.

### Signing

Windows binaries are signed with [SSL.com eSigner][esigner] via their
`esigner-codesign` action. Four repository secrets drive it:

| secret | required | what it is |
| --- | --- | --- |
| `CODE_SIGN_USER` | yes | eSigner account username |
| `CODE_SIGN_PASS` | yes | eSigner account password |
| `CODE_SIGN_TOTP_SECRET` | yes | the TOTP secret for automated signing |
| `CODE_SIGN_CREDENTIAL_ID` | only with >1 cert | selects which certificate to use |

A tag push with no signing secrets **fails** rather than quietly producing an
unsigned binary. A manual `workflow_dispatch` run is allowed through unsigned, so
the pipeline can be exercised in a fork without the certificate; it warns and
never reaches the release step.

[esigner]: https://www.ssl.com/guide/esigner-codesigntool-command-guide/

## License

GPL-3.0-or-later. See LICENSE.
