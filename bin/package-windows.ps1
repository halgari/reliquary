#!/usr/bin/env pwsh
# Reliquary — Copyright (C) 2026 Timothy Baldridge
# Licensed under the GNU General Public License v3 or later. See LICENSE.
#
# Build a self-contained Windows application image with jlink + jpackage, the
# Windows counterpart of bin/package.sh.
#
# A separate script rather than a conditionalised package.sh, for three reasons
# that are not stylistic: the module-path separator is ';' on Windows and ':'
# everywhere else, the tools carry a .exe suffix, and jpackage lays a Windows
# app-image out as Reliquary\Reliquary.exe rather than Reliquary/bin/Reliquary.
# Every one of those is a silent-wrong-answer if guessed, so they are spelled
# out here instead of hidden behind shell conditionals.
#
# --type app-image deliberately: it needs no WiX toolchain, and the release
# ships a portable ZIP. An .msi would need WiX installed on the runner and an
# upgrade UUID managed across versions.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$FxVersion = if ($env:FX_VERSION) { $env:FX_VERSION } else { '26.0.2' }
$FxOs      = 'windows-x64'
$Jar       = 'target/lib/reliquary.jar'

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set' }
$Jdk = $env:JAVA_HOME
foreach ($tool in 'jdeps', 'jlink', 'jpackage') {
    if (-not (Test-Path (Join-Path $Jdk "bin/$tool.exe"))) {
        throw "no $tool.exe under JAVA_HOME ($Jdk) -- a JDK is required, not a JRE"
    }
}
if (-not (Test-Path $Jar)) {
    throw "no $Jar -- run: clojure -T:build uber :omit-javafx true"
}

# The jar must carry no JavaFX: the jlink runtime below supplies it as real
# modules, which win at class-resolution time, so classpath copies are inert
# weight. More to the point for THIS script, a jar built without
# :omit-javafx carries Linux .so files, and shipping those inside a Windows
# image is how you get an app that looks built and cannot start.
$fxEntries = & "$Jdk/bin/jar.exe" --list --file $Jar 2>$null |
             Where-Object { $_ -like 'javafx/*' -or $_ -match '\.(so|dylib)$' }
if ($fxEntries) {
    throw ("$Jar bundles JavaFX or foreign natives ({0} entries). " -f @($fxEntries).Count +
           'Rebuild with: clojure -T:build uber :omit-javafx true')
}

# ---------------------------------------------------------------------------
# JavaFX jmods. Maven publishes JavaFX as plain jars and jlink cannot link
# those; the jmods come from Gluon and are the only reason this section exists.

$toolchainRoot = Join-Path $env:LOCALAPPDATA 'reliquary-toolchain'
$jmods = Join-Path $toolchainRoot "javafx-jmods-$FxVersion-$FxOs"

function Test-Jmods([string] $dir) {
    # A complete tree has the three modules we link against. The fast path must
    # only fire on a tree that passes this, so an interrupted extraction can
    # never masquerade as an installed toolchain. The directory name carries
    # the platform (see bin/setup-toolchain.sh) so a linux tree can never
    # satisfy a windows request.
    foreach ($m in 'javafx.base', 'javafx.graphics', 'javafx.controls') {
        if (-not (Test-Path (Join-Path $dir "$m.jmod"))) { return $false }
    }
    return $true
}

if (-not (Test-Jmods $jmods)) {
    New-Item -ItemType Directory -Force -Path $toolchainRoot | Out-Null
    $tmp   = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
    # Stage inside the toolchain root so the promoting move is a same-volume
    # rename rather than a copy that can be interrupted half-done.
    $stage = Join-Path $toolchainRoot (".staging-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
    try {
        New-Item -ItemType Directory -Force -Path $tmp, $stage | Out-Null
        $url = "https://download2.gluonhq.com/openjfx/$FxVersion/openjfx-${FxVersion}_${FxOs}_bin-jmods.zip"
        $zip = Join-Path $tmp 'fx.zip'
        Write-Host "fetching $url"
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath (Join-Path $tmp 'x') -Force

        # The zip nests the jmods one directory down; find them rather than
        # assume the layout.
        $found = Get-ChildItem -Path (Join-Path $tmp 'x') -Filter 'javafx.base.jmod' -Recurse |
                 Select-Object -First 1
        if (-not $found) { throw "no javafx.base.jmod in $url" }
        Copy-Item -Path (Join-Path $found.DirectoryName '*.jmod') -Destination $stage

        # Verify the staged tree BEFORE it is visible at its final name.
        if (-not (Test-Jmods $stage)) { throw 'staged jmods incomplete' }
        if (Test-Path $jmods) { Remove-Item -Recurse -Force $jmods }
        Move-Item -Path $stage -Destination $jmods
    }
    finally {
        foreach ($d in $tmp, $stage) {
            if (Test-Path $d) { Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue }
        }
    }
}
Write-Host "javafx jmods: $jmods"

# ---------------------------------------------------------------------------
# Derive the JDK modules the jar actually needs rather than guessing.
# Clojure reflects, so --ignore-missing-deps is required.

$mods = (& "$Jdk/bin/jdeps.exe" --ignore-missing-deps --print-module-deps --multi-release 26 $Jar) -join ''
$mods = $mods.Trim()
Write-Host "jdeps resolved: $mods"

# Sanity-check the derived list before building a runtime out of it.
#
# jdeps has a failure mode that produces a legal-looking but wrong answer: if
# any dependency jar contributes a module-info.class, jdeps reads it as the
# whole uberjar's module descriptor and reports only what THAT module needs.
# org.tukaani:xz did exactly this via META-INF/versions/9/module-info.class,
# and the answer came back "java.base" -- a runtime built from which is missing
# java.sql and, transitively, java.logging, so the app dies at startup with
# ClassNotFoundException: java.util.logging.LogManager.
#
# java.desktop is the cheapest tell: anything that draws a window needs it, and
# it is present in every correct list this project can produce. A list without
# it means jdeps analysed something other than our code. Same check, same
# reason, as bin/package.sh.
if ($mods -split ',' -notcontains 'java.desktop') {
    throw ("jdeps returned an implausible module list: $mods`n" +
           "       expected java.desktop among them. A stray module-info.class in a`n" +
           "       dependency jar is the usual cause -- see build.clj's :exclude and`n" +
           '       docs/superpowers/spikes/2026-08-15-jlink-jpackage.md.')
}

# ---------------------------------------------------------------------------
# link and package

foreach ($d in 'target/runtime', 'target/app') {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
}

# ';' is the module-path separator on Windows. A ':' here would be read as part
# of a path (and "C:" makes that ambiguity worse), so jlink would report a
# module it cannot find rather than anything about separators.
& "$Jdk/bin/jlink.exe" `
    --module-path "$Jdk/jmods;$jmods" `
    --add-modules "$mods,javafx.base,javafx.graphics,javafx.controls" `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
    --output target/runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed ($LASTEXITCODE)" }

# --enable-native-access silences the four restricted-method warnings JavaFX
# draws on every launch. Cosmetic today, but the warning is a promise: a future
# JDK blocks the restricted System::load and the app stops starting.
$jpackageArgs = @(
    '--type', 'app-image',
    '--name', 'Reliquary',
    '--input', 'target/lib',
    '--main-jar', 'reliquary.jar',
    '--runtime-image', 'target/runtime',
    '--dest', 'target/app',
    '--java-options=--enable-native-access=javafx.graphics'
)
# jpackage rejects anything that is not dotted numerics here, and a tag like
# "v0.1.0" is exactly that shape once the v is dropped -- so it is only passed
# when it will be accepted, rather than failing the build over a label.
if ($env:APP_VERSION -and $env:APP_VERSION -match '^[0-9]+(\.[0-9]+)*$') {
    $jpackageArgs += @('--app-version', $env:APP_VERSION)
    Write-Host "app-version: $($env:APP_VERSION)"
}
& "$Jdk/bin/jpackage.exe" @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }

$exe = 'target/app/Reliquary/Reliquary.exe'
if (-not (Test-Path $exe)) {
    throw "jpackage reported success but produced no $exe -- the app-image layout changed"
}

$size = [math]::Round((Get-ChildItem -Recurse 'target/app' | Measure-Object -Property Length -Sum).Sum / 1MB)
Write-Host "built $exe (app image ~$size MB)"
