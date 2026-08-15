#!/usr/bin/env bash
# bin/native.sh <main-namespace> <output-binary-name>
# AOT-compiles the namespace and builds a native image from it.
set -euo pipefail

# Extra native-image flags may be passed via EXTRA_NATIVE_IMAGE_ARGS, e.g.
#   EXTRA_NATIVE_IMAGE_ARGS="--initialize-at-run-time=cljfx" ./bin/native.sh ...
# See docs/superpowers/spikes/2026-08-15-cljfx-native.md for why cljfx needs it.

MAIN_NS="$1"
OUT="$2"
MAIN_CLASS="${MAIN_NS//-/_}"

JAVA_HOME="${RELIQUARY_JAVA_HOME:-$(./bin/setup-toolchain.sh)}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"; export PATH

rm -rf classes target
mkdir -p classes target

# NOTE: loading cljfx.api starts a non-daemon JavaFX Application Thread, so this
# JVM will never exit on its own after `compile` returns. The explicit
# (System/exit 0) is required or the build hangs forever here. Headless build
# machines also need a display for the toolkit to start, hence xvfb-run.
xvfb-run -a clojure -M:dev -e "(binding [*compile-path* \"classes\"] (compile '$MAIN_NS)) (System/exit 0)"

CP="$(clojure -Spath -A:dev):classes"

native-image \
  -cp "$CP" \
  -o "target/$OUT" \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --enable-url-protocols=http,https \
  --report-unsupported-elements-at-runtime \
  -H:IncludeResources='steam/.*|catalog\.json|fonts/.*' \
  -H:+UnlockExperimentalVMOptions \
  -H:ConfigurationFileDirectories=native/config \
  -J-Dclojure.compiler.direct-linking=true \
  ${EXTRA_NATIVE_IMAGE_ARGS:-} \
  "$MAIN_CLASS"

ls -lh "target/$OUT"
