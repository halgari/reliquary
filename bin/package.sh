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

# Sanity-check the derived list before building a runtime out of it.
#
# jdeps has a failure mode that produces a legal-looking but wrong answer: if
# any dependency jar contributes a module-info.class, jdeps reads it as the
# whole uberjar's module descriptor and reports only what THAT module needs.
# org.tukaani:xz did exactly this via META-INF/versions/9/module-info.class,
# and the answer came back "java.base" -- a runtime built from which is
# missing java.sql and, transitively, java.logging, so the app dies at
# startup with ClassNotFoundException: java.util.logging.LogManager.
#
# java.desktop is the cheapest tell: anything that draws a window needs it,
# and it is present in every correct list this project can produce. A list
# without it means jdeps analysed something other than our code.
case ",$MODS," in
  *,java.desktop,*) ;;
  *)
    echo "error: jdeps returned an implausible module list: $MODS" >&2
    echo "       expected java.desktop among them. A stray module-info.class in" >&2
    echo "       a dependency jar is the usual cause -- see build.clj's" >&2
    echo "       :exclude, and docs/superpowers/spikes/2026-08-15-jlink-jpackage.md." >&2
    exit 1
    ;;
esac

"$JDK/bin/jlink" \
  --module-path "$JDK/jmods:$FX_JMODS" \
  --add-modules "$MODS,javafx.base,javafx.graphics,javafx.controls" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output target/runtime

# --enable-native-access silences the four restricted-method warnings JavaFX
# draws on every launch. It is cosmetic today, but the warning is a promise:
# a future JDK blocks the restricted System::load and the app stops starting.
"$JDK/bin/jpackage" \
  --type app-image \
  --name Reliquary \
  --input target/lib \
  --main-jar reliquary.jar \
  --runtime-image target/runtime \
  --dest target/app \
  --java-options=--enable-native-access=javafx.graphics

du -sh target/runtime target/app
