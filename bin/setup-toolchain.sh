#!/usr/bin/env bash
# Fetch GraalVM CE for JDK 25 into ~/.local/share/reliquary-toolchain.
# Prints the JAVA_HOME to use. Idempotent.
set -euo pipefail

GRAAL_TAG="${GRAAL_TAG:-jdk-25.0.2}"
ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/reliquary-toolchain"
DEST="$ROOT/$GRAAL_TAG"

if [ -x "$DEST/bin/native-image" ]; then
  echo "$DEST"
  exit 0
fi

mkdir -p "$ROOT"
url=$(curl -fsSL "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases/tags/$GRAAL_TAG" \
      | grep -oP '"browser_download_url": "\K[^"]*linux-x64_bin\.tar\.gz(?=")' | head -1)

if [ -z "$url" ]; then
  echo "no linux-x64 asset on release $GRAAL_TAG" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "$url" -o "$tmp/graal.tar.gz"

# Extract into a staging dir alongside DEST (same filesystem as ROOT, so the
# final move is atomic) and only verify + promote it to DEST once extraction
# and the sanity check both succeed. A half-extracted tree must never be
# visible at DEST, and the fast path above must only ever see a tree that
# passed this check.
stage=$(mktemp -d "$ROOT/.staging-XXXXXX")
trap 'rm -rf "$tmp" "$stage"' EXIT
tar -xzf "$tmp/graal.tar.gz" -C "$stage" --strip-components=1

"$stage/bin/native-image" --version >&2

rm -rf "$DEST"
mv "$stage" "$DEST"

echo "$DEST"
