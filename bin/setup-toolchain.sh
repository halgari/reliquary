#!/usr/bin/env bash
# Fetch the JavaFX jmods jlink needs into ~/.local/share/reliquary-toolchain.
# Prints the jmods directory to use. Idempotent.
#
# FX_OS selects the platform bundle (default linux-x64; windows-x64 and
# osx-aarch64 also exist). The Windows build does its own fetch in
# bin/package-windows.ps1 -- this script stays the POSIX path.
#
# Maven publishes JavaFX as plain jars; jlink cannot link those. The jmods
# come from Gluon and are the only reason this script exists.
set -euo pipefail

FX_VERSION="${FX_VERSION:-26.0.2}"

# Gluon publishes a separate jmods bundle per platform, and the platform is
# part of the cache identity -- NOT just the URL. Keying DEST on FX_VERSION
# alone would let an already-downloaded linux-x64 tree satisfy `verified` for a
# windows-x64 request, and jlink would happily build a runtime carrying the
# wrong natives: an app image that links cleanly and cannot start.
FX_OS="${FX_OS:-linux-x64}"
ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/reliquary-toolchain"
DEST="$ROOT/javafx-jmods-$FX_VERSION-$FX_OS"

# A complete tree has the three modules we link against. The fast path must
# only fire on a tree that passes this, so an interrupted extraction can
# never masquerade as an installed toolchain.
verified() {
  [ -f "$1/javafx.base.jmod" ] && [ -f "$1/javafx.graphics.jmod" ] && [ -f "$1/javafx.controls.jmod" ]
}

if verified "$DEST"; then echo "$DEST"; exit 0; fi

mkdir -p "$ROOT"
tmp=$(mktemp -d)
# Stage inside ROOT so the promoting mv is a same-filesystem rename, i.e. atomic.
stage=$(mktemp -d "$ROOT/.staging-XXXXXX")
trap 'rm -rf "$tmp" "$stage"' EXIT

url="https://download2.gluonhq.com/openjfx/${FX_VERSION}/openjfx-${FX_VERSION}_${FX_OS}_bin-jmods.zip"
curl -fsSL "$url" -o "$tmp/fx.zip"
unzip -q "$tmp/fx.zip" -d "$tmp/x"

# The zip nests the jmods one directory down; find it rather than assume.
found=$(find "$tmp/x" -name 'javafx.base.jmod' -print -quit)
[ -n "$found" ] || { echo "no javafx.base.jmod in $url" >&2; exit 1; }
mv "$(dirname "$found")"/*.jmod "$stage/"

# Verify the staged tree before it is visible at DEST -- a half-extracted
# tree must never be promoted.
verified "$stage" || { echo "staged jmods incomplete" >&2; exit 1; }
rm -rf "$DEST"
mv "$stage" "$DEST"
echo "$DEST"
