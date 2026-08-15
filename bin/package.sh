#!/usr/bin/env bash
# Build a self-contained application image with jlink + jpackage.
set -euo pipefail

JDK="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}"
FX_JMODS="$(./bin/setup-toolchain.sh)"
JAR=target/lib/reliquary.jar

[ -f "$JAR" ] || { echo "no $JAR -- run: clojure -T:build uber" >&2; exit 1; }

# A jar built without :omit-javafx carries ~49 MB of JavaFX classes and
# natives that the jlink runtime below supplies as real modules. The modules
# win at class-resolution time, so the copies are inert -- they just make the
# app image ~48 MB larger for nothing. Warn rather than fail: the jar is
# still correct, only fat.
if unzip -l "$JAR" | grep -q 'libjfxwebkit\.so\|javafx/application/Platform\.class'; then
  echo "warning: $JAR bundles JavaFX; the app image will be ~48 MB larger than it needs to be." >&2
  echo "         rebuild with: clojure -T:build uber :omit-javafx true" >&2
fi

rm -rf target/runtime target/app

# Derive the JDK modules the jar actually needs rather than guessing.
# Clojure reflects, so --ignore-missing-deps is required.
MODS=$("$JDK/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 26 "$JAR")
echo "jdeps resolved: $MODS" >&2

"$JDK/bin/jlink" \
  --module-path "$JDK/jmods:$FX_JMODS" \
  --add-modules "$MODS,javafx.base,javafx.graphics,javafx.controls" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output target/runtime

"$JDK/bin/jpackage" \
  --type app-image \
  --name Reliquary \
  --input target/lib \
  --main-jar reliquary.jar \
  --runtime-image target/runtime \
  --dest target/app

du -sh target/runtime target/app
