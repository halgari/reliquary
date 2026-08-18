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

**The test suite needs a display.** Several tests render real JavaFX scenes and
measure the resulting pixels, so on a headless machine they abort at
`Unable to open DISPLAY` before any assertion runs:

    xvfb-run -a clojure -M:test

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
