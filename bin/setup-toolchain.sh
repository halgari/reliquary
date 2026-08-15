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
mkdir -p "$DEST"
tar -xzf "$tmp/graal.tar.gz" -C "$DEST" --strip-components=1

"$DEST/bin/native-image" --version >&2
echo "$DEST"
