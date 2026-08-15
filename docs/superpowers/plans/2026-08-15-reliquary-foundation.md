# Reliquary Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Reliquary repository with a working, tested Steam content
core and a native-image toolchain proven (or disproven) against both halves of
the app.

**Architecture:** The Steam layer is copied wholesale from `mauvi-mod-manager`
under `reliquary.*` namespaces, stripped of its pulsar/RocksDB coupling and
backed instead by a plain EDN config file. Two spikes then compile a cljfx
window and the Steam core to native binaries, because the whole small-binary
premise rests on both surviving GraalVM. Four new pure-ish namespaces —
`config`, `session`, `catalog`, `plan` — complete the foundation the download
engine will sit on.

**Tech Stack:** Clojure 1.12.5, cljfx 1.10.10, JavaFX 25.0.4, protobuf-java
4.35.1 (`DynamicMessage`, no generated classes), zxing 3.5.4, data.json 2.5.2,
XZ 1.12, graal-build-time 1.0.6, GraalVM CE for JDK 25.

**Spec:** `docs/superpowers/specs/2026-08-15-reliquary-design.md`

## Global Constraints

- **License**: GPL-3.0-or-later. Every new source file gets the standard GPL
  header comment naming `Reliquary` and `Copyright (C) 2026 Timothy Baldridge`.
- **JDK 25, not 26.** GraalVM CE and Liberica NIK both top out at JDK 25. The
  JDK 26 installed on this machine **cannot** produce a native image. Dev and
  native builds both use JDK 25 so class-file versions never drift.
- **No pulsar, no RocksDB, no core.async, no FUSE.** A JNI native library in
  the image defeats the entire point. Persistent state is one EDN file plus one
  progress file per download.
- **Copied code is copied, not depended on.** No `:local/root` pointing at any
  `mauvi*` directory, ever. After Task 2 the `mauvi` repos are reference
  material only.
- **Secrets never surface.** Refresh tokens, passwords and depot keys are never
  logged, never placed in an `ex-info` data map, never rendered, never printed.
  This rule is inherited from the copied code and must survive every edit.
- **Error categories**: `:unauthenticated`, `:unavailable`, `:io`,
  `:incorrect`, carried under the key `:reliquary/error`.
- **Catalog schema version is `1`.** A document declaring anything else is
  ignored, not an error.
- **Legal footer, verbatim, on every screen**: `Not associated with or endorsed
  by Valve Corporation or Steam.`

## File Structure

| Path | Responsibility |
|---|---|
| `deps.edn` | pinned deps and the `:test` / `:dev` / `:native` aliases |
| `bin/setup-toolchain.sh` | fetch and unpack GraalVM CE for JDK 25 |
| `bin/native.sh` | build a native image from an AOT'd classpath |
| `src/reliquary/error.clj` | copied — one categorized `raise` |
| `src/reliquary/steam/**` | copied — the Steam content client |
| `src/reliquary/config.clj` | XDG paths, EDN config, 0600 token storage |
| `src/reliquary/session.clj` | refresh token → live CM session |
| `src/reliquary/catalog.clj` | catalog parse, validate, three-source merge |
| `src/reliquary/plan.clj` | pure: manifests → chunk work plan |
| `resources/steam/steam.desc` | copied — protobuf descriptor set |
| `test/reliquary/steam/**` | copied — 20 offline fixture test namespaces |
| `test/resources/steam/**` | copied — captured Steam fixtures (272 KB) |
| `spike/` | the two native-image spikes, kept as living gates |
| `docs/superpowers/spikes/` | recorded spike findings |

## Prerequisites the plan does not resolve

Three items from the spec are still open. None block Tasks 1–8, but note where
they land:

1. **GPL linking exception** — the spec assumes it is included. Task 1 writes
   the notice; strike the clause there if the decision changes.
2. **Catalog GitHub URL** — needed before the *next* plan's phase 3. Task 7
   builds the loader with the fetch behind a supplied URL argument, so nothing
   here is blocked.
3. **A small owned game** — needed for the *next* plan's live login and
   download work. Nothing here needs Steam credentials.

---

### Task 1: Repository scaffolding and the JDK 25 toolchain

**Files:**
- Create: `deps.edn`
- Create: `bin/setup-toolchain.sh`
- Create: `README.md`
- Modify: `LICENSE` (append the linking exception note)

**Interfaces:**
- Consumes: nothing
- Produces: the `:test` alias (`clojure -M:test`) every later task's
  verification step runs; `$RELIQUARY_JAVA_HOME` pointing at a GraalVM CE
  JDK 25 with `native-image` on its `bin/`.

- [ ] **Step 1: Write `deps.edn`**

The openjfx artifacts are platform-classified. tools.deps does not evaluate the
Maven profile activation that normally picks a classifier, so they are pinned
explicitly, using the `lib$classifier` coordinate syntax. The older
`{:mvn/version … :classifier "linux"}` map key was REMOVED from tools.deps and
is rejected outright by Clojure CLI 1.12.5 with "`:classifier` in Maven
coordinates is no longer supported". **This is the first thing that will break
on another platform** — the Windows build swaps `$linux` for `$win`.

```clojure
{:paths ["src" "resources"]

 :deps {org.clojure/clojure                  {:mvn/version "1.12.5"}

        ;; UI
        cljfx/cljfx                          {:mvn/version "1.10.10"}
        org.openjfx/javafx-base$linux          {:mvn/version "25.0.4"}
        org.openjfx/javafx-graphics$linux      {:mvn/version "25.0.4"}
        org.openjfx/javafx-controls$linux      {:mvn/version "25.0.4"}

        ;; Steam
        com.google.protobuf/protobuf-java    {:mvn/version "4.35.1"}
        com.google.zxing/core                {:mvn/version "3.5.4"}
        org.tukaani/xz                       {:mvn/version "1.12"}

        ;; catalog
        org.clojure/data.json                {:mvn/version "2.5.2"}

        ;; native-image support
        com.github.clj-easy/graal-build-time {:mvn/version "1.0.6"}}

 :aliases
 {:test
  {:extra-paths ["test" "test/resources"]
   :extra-deps  {io.github.cognitect-labs/test-runner
                 {:git/tag "v0.5.1" :git/sha "dfb30dd"}
                 org.clojure/test.check {:mvn/version "1.1.3"}}
   :jvm-opts    ["--enable-native-access=ALL-UNNAMED"]
   :main-opts   ["-m" "cognitect.test-runner"]
   :exec-fn     cognitect.test-runner.api/test}

  :dev
  {:extra-paths ["test" "test/resources" "spike"]
   :jvm-opts    ["--enable-native-access=ALL-UNNAMED"]}

  ;; AOT compile every namespace into classes/ for native-image
  :aot
  {:extra-paths ["spike"]
   :jvm-opts    ["-Dclojure.compiler.direct-linking=true"]
   :exec-fn     clojure.core/compile}}}
```

- [ ] **Step 2: Write `bin/setup-toolchain.sh`**

Queries the GitHub release rather than hardcoding an asset name, so a version
bump is a one-line edit.

```bash
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
```

- [ ] **Step 3: Run the toolchain script and confirm native-image exists**

Run: `chmod +x bin/setup-toolchain.sh && ./bin/setup-toolchain.sh`
Expected: prints a path; `native-image --version` on stderr reports a GraalVM
for JDK 25 build. Export it for later tasks:
`export RELIQUARY_JAVA_HOME=$(./bin/setup-toolchain.sh)`

- [ ] **Step 4: Verify the JavaFX classifier pin actually resolves**

This is the step that catches the openjfx platform problem before it costs a
spike.

Run:
```
clojure -M:dev -e "(import 'javafx.scene.paint.Color) (println (Color/web \"#C2A35F\"))"
```
Expected: prints a `Color` — **not** `ClassNotFoundException`. If it throws,
the classifier is wrong for this platform; correct it in `deps.edn` and rerun.

- [ ] **Step 5: Write `README.md`**

```markdown
# Reliquary

Download a chosen *version* of a Steam game to a folder of your choosing.
Steam's client gives you one build: whatever is current. Reliquary gives you
the archive.

Not associated with or endorsed by Valve Corporation or Steam.

## Building

    ./bin/setup-toolchain.sh          # GraalVM CE for JDK 25
    clojure -M:test                   # tests

## License

GPL-3.0-or-later. See LICENSE.
```

- [ ] **Step 6: Append the linking exception to `LICENSE`**

Append at the end of the file:

```
                    ADDITIONAL PERMISSION UNDER GNU GPL VERSION 3 SECTION 7

  As a special exception, you may link this program with the Clojure runtime
  and distribute the result, without those libraries falling under the terms
  of the GNU General Public License.
```

- [ ] **Step 7: Confirm the test alias runs with no tests**

Run: `clojure -M:test`
Expected: exits 0, reporting `0 tests`. A dependency resolution failure here
fails the task.

- [ ] **Step 8: Commit**

```bash
git add deps.edn bin/setup-toolchain.sh README.md LICENSE
git commit -m "Pin the toolchain at JDK 25, which is where native-image ends"
```

---

### Task 2: Copy the Steam layer

Mechanical but large. It is deliberately early: every fallback in the spec's
risk ladder still needs this code, so it is not gated on the spikes.

**Files:**
- Create: `src/reliquary/error.clj` (from `mauvi/error.clj`)
- Create: `src/reliquary/steam/{api,apps,auth,auth_api,cdn,chunk,crypto,depots,kv,manifest,proto,qr,vzip}.clj`
- Create: `src/reliquary/steam/cm/{client,connection,content,discovery,envelope,multi}.clj`
- Create: `resources/steam/steam.desc`, `resources/steam/protos/*.proto`
- Create: `test/reliquary/steam/**` (20 namespaces)
- Create: `test/resources/steam/**` (fixtures)

**Interfaces:**
- Consumes: Task 1's `:test` alias
- Produces: the whole Steam surface later tasks call —
  `reliquary.steam.auth/login-qr!`, `login-credentials!`;
  `reliquary.steam.cm.client/logon!` → `{:conn :steamid :heartbeat}` and
  `licenses`; `reliquary.steam.cm.connection/close!`;
  `reliquary.steam.cm.content/depot-key` → hex string,
  `manifest-request-code` → string, `cdn-servers` → vector of host strings;
  `reliquary.steam.manifest/fetch` → `byte[]`, `parse` →
  `{:depot-id :files [{:name :size :flags :sha-content :chunks}]}`;
  `reliquary.steam.chunk/fetch-decoded` → `byte[]`;
  `reliquary.steam.crypto/jwt-claims` → `{:sub :exp}`;
  `reliquary.steam.qr/module-matrix`, `dark-at?`, `module-span`;
  `reliquary.error/raise`.

**Do NOT copy** — these three test namespaces depend on `mauvi.system`,
`mauvi.model.*` or `pulsar.api` and have no meaning here:
`session_test.clj`, `live_test.clj`, `cdn_e2e_test.clj`.
**Do NOT copy** these two source files: `slice.clj` (FUSE block math) and
`session.clj` (rewritten in Task 5). `slice_test.clj` goes with `slice.clj`.

- [ ] **Step 1: Copy the source tree, renaming as you go**

```bash
SRC=/home/tbaldrid/oss/mauvi-mod-manager
mkdir -p src/reliquary/steam/cm resources test/reliquary/steam/cm test/resources

cp "$SRC/src/mauvi/error.clj" src/reliquary/error.clj
for f in api apps auth auth_api cdn chunk crypto depots kv manifest proto qr vzip; do
  cp "$SRC/src/mauvi/steam/$f.clj" "src/reliquary/steam/$f.clj"
done
for f in client connection content discovery envelope multi; do
  cp "$SRC/src/mauvi/steam/cm/$f.clj" "src/reliquary/steam/cm/$f.clj"
done

cp -r "$SRC/resources/steam" resources/steam
cp -r "$SRC/test/resources/steam" test/resources/steam

for f in api apps auth_api auth cdn chunk crypto depots descriptor_set kv manifest proto qr vzip; do
  cp "$SRC/test/mauvi/steam/${f}_test.clj" "test/reliquary/steam/${f}_test.clj"
done
for f in client connection content discovery envelope multi; do
  cp "$SRC/test/mauvi/steam/cm/${f}_test.clj" "test/reliquary/steam/cm/${f}_test.clj"
done
```

- [ ] **Step 2: Rewrite the namespaces**

Three substitutions, in this order. The `:mauvi/error` keyword must be handled
separately from the namespace symbols — a single blanket `mauvi` → `reliquary`
would also rewrite prose in docstrings that legitimately refers to the mauvi
project, and those references are worth keeping for provenance.

```bash
files=$(find src/reliquary test/reliquary -name '*.clj')
sed -i 's/\bmauvi\.steam\./reliquary.steam./g'  $files
sed -i 's/\bmauvi\.error\b/reliquary.error/g'   $files
sed -i 's/:mauvi\/error/:reliquary\/error/g'    $files
```

- [ ] **Step 3: Verify no `mauvi` code references survive**

Run:
```
grep -rnE '\bmauvi\.(steam|error)\b|:mauvi/error' src/ test/ || echo CLEAN
```
Expected: `CLEAN`. Remaining bare `mauvi` mentions inside prose docstrings are
expected and correct.

- [ ] **Step 4: Add the GPL header and provenance note to every copied file**

Insert above the `(ns …)` form of each copied file:

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
```

- [ ] **Step 5: Run the copied tests**

Run: `clojure -M:test`
Expected: PASS, 20 namespaces. Failures at this point are namespace-rename
mistakes or a missing fixture, not logic — the code is unchanged and was green
in mauvi.

- [ ] **Step 6: Make the manifest request code branch-aware**

The copied `cm/content.clj` hardcodes `:app-branch "public"`. The catalog names
a branch per version, so it becomes an argument. First, the failing test —
append to `test/reliquary/steam/cm/content_test.clj`:

```clojure
(deftest manifest-request-code-passes-the-branch
  (let [sent (atom nil)]
    (with-redefs [conn/send-service! (fn [_c _m _rt body _resp]
                                       (reset! sent body)
                                       (doto (java.util.concurrent.CompletableFuture.)
                                         (.complete {:manifest-request-code "99"})))
                  conn/join          (fn [f] (.get ^java.util.concurrent.CompletableFuture f))]
      (is (= "99" (content/manifest-request-code nil 1 2 "3" "beta")))
      (is (= "beta" (:app-branch @sent))))))
```

- [ ] **Step 7: Run it and watch it fail**

Run: `clojure -M:test -n reliquary.steam.cm.content-test`
Expected: FAIL — arity error, `manifest-request-code` takes 4 args not 5.

- [ ] **Step 8: Add the branch parameter**

In `src/reliquary/steam/cm/content.clj`, change the signature and the body's
`:app-branch` value. Keep a 4-arity that defaults to `"public"` so no copied
caller breaks:

```clojure
(defn manifest-request-code
  ^String
  ([c app-id depot-id ^String manifest-gid]
   (manifest-request-code c app-id depot-id manifest-gid "public"))
  ([c app-id depot-id ^String manifest-gid ^String branch]
   (let [r    (conn/join
               (conn/send-service! c "ContentServerDirectory.GetManifestRequestCode#1"
                                   "CContentServerDirectory_GetManifestRequestCode_Request"
                                   {:app-id app-id :depot-id depot-id
                                    :manifest-id manifest-gid :app-branch branch}
                                   "CContentServerDirectory_GetManifestRequestCode_Response"))
         code (:manifest-request-code r)]
     (when (nil? code)
       (error/raise :incorrect
                    (str "steam granted no manifest request code for depot " depot-id)
                    {:depot-id depot-id}))
     code)))
```

- [ ] **Step 9: Run the full suite**

Run: `clojure -M:test`
Expected: PASS, including the new branch test.

- [ ] **Step 10: Commit**

```bash
git add src/ test/ resources/
git commit -m "Copy mauvi's Steam client in, and make its branch an argument"
```

---

### Task 3: Spike — a cljfx window as a native binary

The spec's premise rests on this. If it fails, the UI approach changes and the
next plan is written differently, so it runs before any UI code exists.

**Files:**
- Create: `spike/reliquary/spike/fx_window.clj`
- Create: `bin/native.sh`
- Create: `docs/superpowers/spikes/2026-08-15-cljfx-native.md`

**Interfaces:**
- Consumes: Task 1's toolchain and deps
- Produces: `bin/native.sh <main-ns> <output-name>`, reused by Task 4 and by
  the packaging phase.

- [ ] **Step 1: Write the spike window**

Deliberately exercises the things most likely to break: cljfx's lifecycle,
a custom font, a `Canvas` with 2D drawing (what the QR renderer needs), and
`Platform/runLater` from a non-FX thread.

```clojure
(ns reliquary.spike.fx-window
  "A cljfx window that exists only to be compiled to a native binary.

   :gen-class is load-bearing -- bin/native.sh hands native-image a MAIN CLASS,
   and without it `compile` emits no such class and the build fails late with a
   confusing 'main entry point not found'."
  (:gen-class)
  (:require [cljfx.api :as fx])
  (:import (javafx.application Platform)
           (javafx.scene.canvas Canvas)
           (javafx.scene.paint Color)))

(defn- draw! [^Canvas canvas]
  (let [g (.getGraphicsContext2D canvas)]
    (.setFill g (Color/web "#0C0C0C"))
    (.fillRect g 0 0 120 120)
    (.setFill g (Color/web "#C2A35F"))
    (doseq [x (range 0 120 20) y (range 0 120 20)
            :when (even? (+ (quot x 20) (quot y 20)))]
      (.fillRect g x y 20 20))))

(defn view [{:keys [ticks]}]
  {:fx/type :stage
   :showing true
   :title   "Reliquary spike"
   :width   420 :height 320
   :scene {:fx/type :scene
           :fill    (Color/web "#0C0C0C")
           :root {:fx/type  :v-box
                  :spacing  16
                  :padding  24
                  :children [{:fx/type :label
                              :text    "RELIQUARY"
                              :style   {:-fx-text-fill "#F2F0EE"
                                        :-fx-font-size 18}}
                             ;; ext-on-instance-lifecycle is how cljfx hands
                             ;; you the real Node. :canvas has no :on-created
                             ;; prop -- drawing needs the instance itself.
                             {:fx/type    fx/ext-on-instance-lifecycle
                              :on-created draw!
                              :desc       {:fx/type :canvas
                                           :width 120 :height 120}}
                             {:fx/type :label
                              :text    (str "ticks " ticks)
                              :style   {:-fx-text-fill "#9A9A9A"}}]}}})

(defn -main [& _]
  (let [state    (atom {:ticks 0})
        renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
    (fx/mount-renderer state renderer)
    ;; prove Platform/runLater works from a plain thread, which every
    ;; background download update will depend on
    (.start (Thread. (fn []
                       (dotimes [i 5]
                         (Thread/sleep 400)
                         (Platform/runLater #(swap! state assoc :ticks (inc i))))
                       (Thread/sleep 600)
                       (println "SPIKE-OK ticks=5")
                       (Platform/exit)
                       (System/exit 0))))
    renderer))
```

- [ ] **Step 2: Write `bin/native.sh`**

```bash
#!/usr/bin/env bash
# bin/native.sh <main-namespace> <output-binary-name>
# AOT-compiles the namespace and builds a native image from it.
set -euo pipefail

MAIN_NS="$1"
OUT="$2"
MAIN_CLASS="${MAIN_NS//-/_}"

JAVA_HOME="${RELIQUARY_JAVA_HOME:-$(./bin/setup-toolchain.sh)}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"; export PATH

rm -rf classes target
mkdir -p classes target

clojure -M:dev -e "(binding [*compile-path* \"classes\"] (compile '$MAIN_NS))"

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
  "$MAIN_CLASS"

ls -lh "target/$OUT"
```

- [ ] **Step 3: Collect reflection metadata with the tracing agent**

JavaFX will not survive without this. Run the spike on the JVM under the agent
first:

```bash
mkdir -p native/config
JAVA_HOME=$RELIQUARY_JAVA_HOME \
xvfb-run -a $RELIQUARY_JAVA_HOME/bin/java \
  -agentlib:native-image-agent=config-output-dir=native/config \
  --enable-native-access=ALL-UNNAMED \
  -cp "$(clojure -Spath -A:dev)" clojure.main \
  -m reliquary.spike.fx-window
```
Expected: a window renders (headless under xvfb), `SPIKE-OK ticks=5` prints,
and `native/config/reflect-config.json` is non-empty.

- [ ] **Step 4: Build the native binary**

Run: `chmod +x bin/native.sh && ./bin/native.sh reliquary.spike.fx-window reliquary-spike-fx`
Expected: a binary at `target/reliquary-spike-fx`. Record its size — this is
the number the whole premise turns on. **A build failure here is a finding, not
a blocker: record the exact error and stop.**

- [ ] **Step 5: Run the native binary and confirm it renders**

Run: `xvfb-run -a ./target/reliquary-spike-fx`
Expected: prints `SPIKE-OK ticks=5` and exits 0. Anything else — a crash, a
missing native library, a blank window — is the finding.

- [ ] **Step 6: Record the findings**

Write `docs/superpowers/spikes/2026-08-15-cljfx-native.md` covering: whether it
built, whether it ran, the binary size in MB, the wall-clock build time, every
error encountered and what fixed it, and the total size of `native/config`.
State plainly whether the spec's 40–80 MB expectation held.

**If the binary does not build or does not run**, the finding is the
deliverable. Do not begin repairing cljfx. Stop and report — the spec's
fallback ladder (raw JavaFX interop, then jlink+jpackage) is a decision for the
user, not this task.

- [ ] **Step 7: Commit**

```bash
git add spike/ bin/native.sh native/config docs/superpowers/spikes/
git commit -m "Spike: does cljfx survive native-image, and at what size"
```

---

### Task 4: Spike — the Steam core as a native binary

Separate from Task 3 because it fails for entirely different reasons:
protobuf's descriptor reflection, TLS, and resource loading from inside the
image.

**Files:**
- Create: `spike/reliquary/spike/steam_native.clj`
- Create: `docs/superpowers/spikes/2026-08-15-steam-native.md`

**Interfaces:**
- Consumes: Task 2's Steam namespaces, Task 3's `bin/native.sh`
- Produces: a recorded verdict on protobuf + TLS under native-image

- [ ] **Step 1: Write the spike**

Needs no Steam account. It exercises the three things a native image breaks:
loading `steam.desc` as a resource, `DynamicMessage` descriptor reflection, and
an outbound TLS connection.

```clojure
(ns reliquary.spike.steam-native
  "The Steam core with no UI in it, so a native-image failure here is
   unambiguously protobuf, LZMA or TLS -- never JavaFX. :gen-class for the same
   reason as the fx spike: native-image needs a main class to exist."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [reliquary.steam.cm.discovery :as discovery]
            [reliquary.steam.manifest :as manifest]
            [reliquary.steam.proto :as proto]
            [reliquary.steam.vzip :as vzip]))

(defn -main [& _]
  ;; 1. resource loading + protobuf descriptor reflection
  (let [encoded (proto/encode "CMsgClientLogon" {:protocol-version 65580})
        back    (proto/decode "CMsgClientLogon" encoded)]
    (assert (= 65580 (:protocol-version back)) "protobuf round trip")
    (println "PROTO-OK bytes=" (alength ^bytes encoded)))

  ;; 2. the XZ/LZMA decoder and the manifest parser, off a committed fixture.
  ;;    chunk-table, not parse: the committed manifest has encrypted filenames
  ;;    (every current Steam depot does) and parse refuses that without a key.
  ;;    chunk-table needs no key by design -- only `filename` is ciphertext.
  (let [blob  (vzip/decompress (with-open [in (io/input-stream (io/resource "steam/manifest.zip"))]
                                 (.readAllBytes in)))
        table (manifest/chunk-table blob)]
    (assert (seq table) "manifest chunk table came back empty")
    (println "MANIFEST-OK files=" (count table)))

  ;; 3. outbound TLS through java.net.http
  (let [servers (discovery/cm-servers)]
    (assert (seq servers) "CM server list came back empty")
    (println "TLS-OK servers=" (count servers)))

  (println "SPIKE-OK")
  (System/exit 0))
```

Note: `manifest/parse` with a `nil` key raises when filenames are encrypted.
If the committed fixture is an encrypted manifest, use `manifest/chunk-table`
instead — it needs no key by design — and assert on the chunk count. Check
`test/reliquary/steam/manifest_test.clj` for which the fixture is and match it.

- [ ] **Step 2: Verify it passes on the JVM first**

Run: `clojure -M:dev -m reliquary.spike.steam-native`
Expected: four `-OK` lines. A failure here is a copy problem from Task 2, not
a native-image problem — fix it before building.

- [ ] **Step 3: Build the native binary**

Run: `./bin/native.sh reliquary.spike.steam-native reliquary-spike-steam`
Expected: a binary at `target/reliquary-spike-steam`. Record its size — this
one has no JavaFX in it, so the difference against Task 3's binary tells you
what JavaFX actually costs.

- [ ] **Step 4: Run it**

Run: `./target/reliquary-spike-steam`
Expected: the same four `-OK` lines. The likely failures, each worth recording
precisely: `steam.desc` not found (needs `-H:IncludeResources`), a protobuf
reflection error (needs `reflect-config.json` entries), or a TLS handshake
failure (needs `--enable-url-protocols` and possibly `-H:EnableSecurityServices`).

- [ ] **Step 5: Record the findings**

Write `docs/superpowers/spikes/2026-08-15-steam-native.md`: built or not, ran or
not, binary size, every flag that had to be added, and the size delta against
Task 3's binary.

- [ ] **Step 6: Commit**

```bash
git add spike/ native/ docs/superpowers/spikes/
git commit -m "Spike: protobuf, LZMA and TLS inside a native image"
```

---

### Task 5: `config.clj` — XDG paths and token storage

**Files:**
- Create: `src/reliquary/config.clj`
- Create: `test/reliquary/config_test.clj`

**Interfaces:**
- Consumes: nothing. Note that this namespace deliberately never raises —
  a corrupt config reads as `{}` so a file the user never edited cannot
  prevent the app from starting.
- Produces:
  - `(config-dir)` → `java.io.File`, `$XDG_CONFIG_HOME/reliquary` or `~/.config/reliquary`
  - `(data-dir)` → `java.io.File`, `$XDG_DATA_HOME/reliquary` or `~/.local/share/reliquary`
  - `(read-config)` → map, `{}` when absent or unreadable
  - `(write-config! m)` → the map; writes atomically at mode 0600
  - `(token)` → `{:refresh-token s :account s}` or `nil`
  - `(save-token! {:refresh-token :account})` → the map
  - `(forget-token!)` → `nil`
- The dirs are overridable by binding `*config-dir*` / `*data-dir*`, which is
  how the tests avoid touching the real home directory.

- [ ] **Step 1: Write the failing tests**

```clojure
(ns reliquary.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)))

(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (binding [config/*config-dir* d config/*data-dir* d] (f d))
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest missing-config-reads-as-empty
  (with-tmp (fn [_] (is (= {} (config/read-config))))))

(deftest config-round-trips
  (with-tmp (fn [_]
              (config/write-config! {:folder "/games" :workers 8})
              (is (= {:folder "/games" :workers 8} (config/read-config))))))

(deftest config-file-is-owner-only
  (with-tmp (fn [d]
              (config/write-config! {:a 1})
              (let [p (.toPath (io/file d "config.edn"))
                    perms (PosixFilePermissions/toString (Files/getPosixFilePermissions p (make-array java.nio.file.LinkOption 0)))]
                (is (= "rw-------" perms)
                    "the refresh token lives here; group and other must not read it")))))

(deftest corrupt-config-reads-as-empty-rather-than-throwing
  (with-tmp (fn [d]
              (spit (io/file d "config.edn") "{:unbalanced ")
              (is (= {} (config/read-config))
                  "a corrupt config must not brick startup"))))

(deftest token-round-trips-and-forgets
  (with-tmp (fn [_]
              (is (nil? (config/token)))
              (config/save-token! {:refresh-token "jwt.abc.def" :account "someone"})
              (is (= {:refresh-token "jwt.abc.def" :account "someone"} (config/token)))
              (config/write-config! (assoc (config/read-config) :folder "/games"))
              (config/forget-token!)
              (is (nil? (config/token)))
              (is (= "/games" (:folder (config/read-config)))
                  "forgetting the token must not discard unrelated settings"))))

(deftest write-is-atomic
  (with-tmp (fn [d]
              (config/write-config! {:a 1})
              (config/write-config! {:a 2})
              (is (= 1 (count (filter #(.isFile %) (.listFiles d))))
                  "no temp file left behind"))))
```

- [ ] **Step 2: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.config-test`
Expected: FAIL — `reliquary.config` does not exist.

- [ ] **Step 3: Implement `config.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.config
  "Where Reliquary keeps its small amount of durable state: one EDN file.

   There is no database. The whole persistent surface is this file plus a
   progress file per in-flight download, and that is deliberate -- a JNI native
   library inside the native image would cost more than every feature it could
   buy.

   The file holds a Steam refresh token, so it is written at mode 0600 and its
   contents are never logged, never rendered, and never placed in an error map."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file CopyOption Files LinkOption StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- xdg ^File [env-var fallback]
  (io/file (or (not-empty (System/getenv env-var))
               (str (System/getProperty "user.home") fallback))
           "reliquary"))

(def ^:dynamic *config-dir* nil)
(def ^:dynamic *data-dir* nil)

(defn config-dir ^File [] (or *config-dir* (xdg "XDG_CONFIG_HOME" "/.config")))
(defn data-dir   ^File [] (or *data-dir*   (xdg "XDG_DATA_HOME"   "/.local/share")))

(defn- config-file ^File [] (io/file (config-dir) "config.edn"))

(defn- posix? []
  (.. (java.nio.file.FileSystems/getDefault) supportedFileAttributeViews (contains "posix")))

(defn read-config
  "The config map, or {} when the file is absent or unparseable.

   A corrupt config reads as empty rather than throwing: the alternative is an
   app that cannot start and gives the user no way back, over a file they
   never edited by hand."
  []
  (let [f (config-file)]
    (if (.isFile f)
      (try (or (edn/read-string (slurp f)) {})
           (catch Exception _ {}))
      {})))

(defn write-config!
  "Write `m` atomically at mode 0600, creating the directory if needed.

   Atomic because a half-written config that loses a refresh token costs the
   user a re-login for no reason. Temp file in the SAME directory, so the
   rename cannot cross a filesystem boundary and silently degrade to a copy."
  [m]
  (let [dir (doto (config-dir) .mkdirs)
        tmp (Files/createTempFile (.toPath dir) ".config" ".tmp"
                                  (make-array FileAttribute 0))]
    (spit (.toFile tmp) (pr-str m))
    (when (posix?)
      (Files/setPosixFilePermissions tmp (PosixFilePermissions/fromString "rw-------")))
    (Files/move tmp (.toPath (config-file))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/ATOMIC_MOVE]))
    m))

(defn token
  "The stored Steam credentials, or nil when nobody has logged in."
  []
  (let [{:keys [refresh-token account]} (read-config)]
    (when (seq refresh-token)
      {:refresh-token refresh-token :account account})))

(defn save-token! [{:keys [refresh-token account]}]
  (write-config! (assoc (read-config) :refresh-token refresh-token :account account))
  {:refresh-token refresh-token :account account})

(defn forget-token!
  "Drop the credentials, keeping every other setting."
  []
  (write-config! (dissoc (read-config) :refresh-token :account))
  nil)
```

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n reliquary.config-test`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/reliquary/config.clj test/reliquary/config_test.clj
git commit -m "Store the refresh token in one 0600 EDN file, atomically"
```

---

### Task 6: `session.clj` — token to live CM session

Replaces the copied `mauvi.steam.session`, whose entire body was a pulsar read.

**Files:**
- Create: `src/reliquary/session.clj`
- Create: `test/reliquary/session_test.clj`

**Interfaces:**
- Consumes: `reliquary.config/token`, `reliquary.steam.cm.client/logon!` and
  `licenses`, `reliquary.steam.cm.connection/close!`,
  `reliquary.steam.crypto/jwt-claims`, `reliquary.error/raise`
- Produces:
  - `(expired? refresh-token now-secs)` → boolean
  - `(open!)` → `{:conn c :steamid s :account a :heartbeat t}`; raises
    `:unauthenticated` when there is no usable token
  - `(close! session)` → nil
  - `(status session)` → `{:status :online :steamid :account :licenses n}`
  - `(owned-appids session)` → set of longs

- [ ] **Step 1: Write the failing tests**

Everything network-shaped is redefined; these are unit tests, not a live gate.

```clojure
(ns reliquary.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config]
            [reliquary.session :as session]
            [reliquary.steam.cm.client :as client]
            [reliquary.steam.crypto :as crypto]))

(deftest expired-reads-the-jwt-expiry
  (with-redefs [crypto/jwt-claims (fn [_] {:sub "76561198000000000" :exp 1000})]
    (is (session/expired? "jwt" 1001))
    (is (not (session/expired? "jwt" 999)))))

(deftest an-unreadable-token-counts-as-expired
  (with-redefs [crypto/jwt-claims (fn [_] (throw (ex-info "bad jwt" {})))]
    (is (session/expired? "garbage" 0)
        "a token we cannot read is a token we cannot use")))

(deftest open-without-a-token-is-unauthenticated
  (with-redefs [config/token (constantly nil)]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (session/open!)))]
      (is (= :unauthenticated (:reliquary/error (ex-data e)))))))

(deftest open-with-an-expired-token-is-unauthenticated
  (with-redefs [config/token  (constantly {:refresh-token "jwt" :account "a"})
                crypto/jwt-claims (fn [_] {:exp 0})]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (session/open!)))]
      (is (= :unauthenticated (:reliquary/error (ex-data e)))))))

(deftest open-logs-on-with-the-stored-token
  (let [seen (atom nil)]
    (with-redefs [config/token      (constantly {:refresh-token "jwt" :account "someone"})
                  crypto/jwt-claims (fn [_] {:exp 99999999999})
                  client/logon!     (fn [t a] (reset! seen [t a])
                                      {:conn :a-conn :steamid "765" :heartbeat nil})]
      (let [s (session/open!)]
        (is (= ["jwt" "someone"] @seen))
        (is (= "someone" (:account s)))
        (is (= :a-conn (:conn s)))))))

(deftest an-error-never-quotes-the-token
  (with-redefs [config/token      (constantly {:refresh-token "SECRET-JWT" :account "a"})
                crypto/jwt-claims (fn [_] {:exp 0})]
    (try (session/open!)
         (catch clojure.lang.ExceptionInfo e
           (is (not (re-find #"SECRET-JWT" (str (ex-message e) (pr-str (ex-data e))))))))))
```

- [ ] **Step 2: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.session-test`
Expected: FAIL — `reliquary.session` does not exist.

- [ ] **Step 3: Implement `session.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.session
  "A logged-on Steam CM session, opened from the stored refresh token.

   mauvi's version of this namespace read the token out of a RocksDB-backed
   store. Reliquary has no store, so this reads reliquary.config instead and is
   otherwise the same shape: the caller owns the session's lifetime."
  (:require [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.steam.apps :as apps]
            [reliquary.steam.cm.client :as client]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.crypto :as crypto]))

(defn expired?
  "Is this refresh token past its expiry at `now-secs`?

   A token we cannot parse counts as expired. The alternative is presenting an
   unreadable credential to Steam and reporting its refusal as a network fault."
  [refresh-token now-secs]
  (try
    (let [{:keys [exp]} (crypto/jwt-claims refresh-token)]
      (or (nil? exp) (<= (long exp) (long now-secs))))
    (catch Exception _ true)))

(defn open!
  "Log on with the stored token. Raises :unauthenticated when there is no
   usable one -- the UI turns that into the login screen.

   The raised message names neither the token nor any part of it."
  []
  (let [{:keys [refresh-token account]} (config/token)]
    (when-not refresh-token
      (error/raise :unauthenticated "not signed in to steam"))
    (when (expired? refresh-token (quot (System/currentTimeMillis) 1000))
      (error/raise :unauthenticated "the stored steam session has expired"))
    (let [{:keys [conn steamid heartbeat]} (client/logon! refresh-token account)]
      {:conn conn :steamid steamid :account account :heartbeat heartbeat})))

(defn close!
  "Stop the heartbeat and drop the connection. The heartbeat is a daemon thread
   and cannot hold the process open, but a long-lived app must not accumulate
   one per session."
  [session]
  (when-let [^Thread hb (:heartbeat session)] (.interrupt hb))
  (when-let [c (:conn session)] (conn/close! c))
  nil)

(defn status [session]
  {:status   :online
   :steamid  (:steamid session)
   :account  (:account session)
   :licenses (count (client/licenses (:conn session)))})

(defn owned-appids
  "The set of appids this account licenses, for the library's ownership
   marking. A courtesy only -- the authority is Steam's answer to the
   depot-key request, which the download engine still handles."
  [session]
  (let [c (:conn session)]
    (into #{} (map :appid) (:apps (apps/owned-apps c (client/licenses c))))))
```

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n reliquary.session-test`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the whole suite to confirm nothing regressed**

Run: `clojure -M:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/reliquary/session.clj test/reliquary/session_test.clj
git commit -m "Open a CM session from the config file, not a store"
```

---

### Task 7: `catalog.clj` — parse, validate, and pick a source

**Files:**
- Create: `src/reliquary/catalog.clj`
- Create: `resources/catalog.json`
- Create: `test/reliquary/catalog_test.clj`

**Interfaces:**
- Consumes: `reliquary.config/data-dir`
- Produces:
  - `schema-version` → `1`
  - `(parse json-string)` → normalized catalog map, or `nil` if invalid
  - `(bundled)` → catalog map from `resources/catalog.json`
  - `(cached)` → catalog map from `<data-dir>/catalog.json`, or `nil`
  - `(newest & catalogs)` → the one with the latest `:generated`, nils skipped
  - `(load!)` → `(newest (bundled) (cached))`; synchronous, no network
  - `(refresh! url on-done)` → returns immediately; calls `on-done` with a
    catalog map on success, or is silent on any failure
  - `(games catalog)` → vector of game maps
  - `(game catalog appid)` → one game map or nil
  - `(version game version-id)` → one version map or nil
- Normalized game shape: `{:appid long :title s :studio s :art {:capsule s
  :screenshots [s]} :quotes [{:text s :attrib s}] :versions [{:id s :label s
  :branch s :build s :date s :bytes long :depots [{:depot-id long
  :manifest-gid s}]}]}`

- [ ] **Step 1: Write `resources/catalog.json`**

A hand-written two-game catalog, real appids so it is testable later. This is
the bundled fallback and the fixture both.

```json
{
  "schema-version": 1,
  "generated": "2026-08-15T00:00:00Z",
  "games": [
    {
      "appid": 220,
      "title": "Half-Life 2",
      "studio": "Valve",
      "art": {
        "capsule": "https://cdn.cloudflare.steamstatic.com/steam/apps/220/library_600x900.jpg",
        "screenshots": []
      },
      "quotes": [],
      "versions": [
        {
          "id": "public",
          "label": "Latest — public",
          "branch": "public",
          "build": "0",
          "date": "2026-08-15",
          "bytes": 0,
          "depots": [{"depot-id": 221, "manifest-gid": "0"}]
        }
      ]
    },
    {
      "appid": 400,
      "title": "Portal",
      "studio": "Valve",
      "art": {
        "capsule": "https://cdn.cloudflare.steamstatic.com/steam/apps/400/library_600x900.jpg",
        "screenshots": []
      },
      "quotes": [],
      "versions": [
        {
          "id": "public",
          "label": "Latest — public",
          "branch": "public",
          "build": "0",
          "date": "2026-08-15",
          "bytes": 0,
          "depots": [{"depot-id": 401, "manifest-gid": "0"}]
        }
      ]
    }
  ]
}
```

The `"0"` manifest GIDs are placeholders in *data*, not in the plan: the
bundled catalog is a shape reference until the generator (a later phase) emits
a real one. Tests assert on shape, never on these values.

- [ ] **Step 2: Write the failing tests**

```clojure
(ns reliquary.catalog-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.catalog :as catalog]
            [reliquary.config :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private minimal
  "{\"schema-version\":1,\"generated\":\"2026-01-01T00:00:00Z\",
    \"games\":[{\"appid\":220,\"title\":\"HL2\",\"studio\":\"Valve\",
                \"versions\":[{\"id\":\"public\",\"label\":\"L\",\"branch\":\"public\",
                               \"build\":\"1\",\"date\":\"2026-01-01\",\"bytes\":10,
                               \"depots\":[{\"depot-id\":221,\"manifest-gid\":\"77\"}]}]}]}")

(deftest parses-a-minimal-catalog
  (let [c (catalog/parse minimal)
        g (first (catalog/games c))]
    (is (= 1 (:schema-version c)))
    (is (= 220 (:appid g)))
    (is (= "Valve" (:studio g)))
    (is (= 221 (-> g :versions first :depots first :depot-id)))
    (is (= "77" (-> g :versions first :depots first :manifest-gid))
        "a manifest gid is a uint64 and must stay a string")))

(deftest absent-optional-fields-normalize-to-empty
  (let [g (first (catalog/games (catalog/parse minimal)))]
    (is (= [] (:quotes g)))
    (is (= [] (-> g :art :screenshots)))
    (is (nil? (-> g :art :capsule)))))

(deftest a-future-schema-version-is-ignored-not-an-error
  (is (nil? (catalog/parse (str/replace minimal "\"schema-version\":1" "\"schema-version\":99")))
      "an old binary must keep working when the catalog moves ahead"))

(deftest malformed-json-is-nil
  (is (nil? (catalog/parse "{not json")))
  (is (nil? (catalog/parse ""))))

(deftest a-game-missing-required-fields-is-rejected
  (is (nil? (catalog/parse "{\"schema-version\":1,\"generated\":\"2026-01-01T00:00:00Z\",\"games\":[{\"title\":\"no appid\"}]}"))))

(deftest a-version-with-no-depots-is-rejected
  (is (nil? (catalog/parse (str/replace minimal "\"depots\":[{\"depot-id\":221,\"manifest-gid\":\"77\"}]" "\"depots\":[]")))
      "a version we cannot fetch is worse than a version we do not offer"))

(deftest newest-wins-and-skips-nils
  (let [old (catalog/parse minimal)
        new (catalog/parse (str/replace minimal "2026-01-01T00:00:00Z" "2026-06-01T00:00:00Z"))]
    (is (= "2026-06-01T00:00:00Z" (:generated (catalog/newest old new nil))))
    (is (= "2026-06-01T00:00:00Z" (:generated (catalog/newest nil new old))))
    (is (nil? (catalog/newest nil nil)))))

(deftest the-bundled-catalog-is-valid
  (let [c (catalog/bundled)]
    (is (some? c) "resources/catalog.json must parse — it is the offline fallback")
    (is (= 1 (:schema-version c)))
    (is (seq (catalog/games c)))
    (is (every? (fn [g] (and (:appid g) (:title g) (seq (:versions g))))
                (catalog/games c)))))

(deftest lookups-work
  (let [c (catalog/parse minimal)
        g (catalog/game c 220)]
    (is (= "HL2" (:title g)))
    (is (nil? (catalog/game c 999)))
    (is (= "L" (:label (catalog/version g "public"))))
    (is (nil? (catalog/version g "nope")))))

(deftest load-prefers-the-cache-when-it-is-newer
  (let [d (.toFile (Files/createTempDirectory "reliquary-cat" (make-array FileAttribute 0)))]
    (try
      (binding [config/*data-dir* d]
        (is (= (:generated (catalog/bundled)) (:generated (catalog/load!)))
            "with no cache, the bundled copy is what loads")
        (spit (io/file d "catalog.json")
              (str/replace minimal "2026-01-01T00:00:00Z" "2099-01-01T00:00:00Z"))
        (is (= "2099-01-01T00:00:00Z" (:generated (catalog/load!)))))
      (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest a-corrupt-cache-falls-back-to-bundled
  (let [d (.toFile (Files/createTempDirectory "reliquary-cat" (make-array FileAttribute 0)))]
    (try
      (binding [config/*data-dir* d]
        (spit (io/file d "catalog.json") "{ broken")
        (is (= (:generated (catalog/bundled)) (:generated (catalog/load!)))))
      (finally (run! io/delete-file (reverse (file-seq d)))))))
```

- [ ] **Step 3: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.catalog-test`
Expected: FAIL — `reliquary.catalog` does not exist.

- [ ] **Step 4: Implement `catalog.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.catalog
  "What games exist, what versions they have, and what to say while they
   download.

   Steam's PICS names only the CURRENT manifest on each branch, so the versions
   Reliquary offers cannot come from live metadata. They come from here: a JSON
   document bundled with the binary and refreshed from a URL at startup.

   Three sources, newest `generated` wins: the bundled copy (always present),
   the last good fetch (cached on disk), and today's fetch. Every failure mode
   degrades to an older catalog rather than to an error -- an app that will not
   start because a GitHub URL was slow is a worse app."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [reliquary.config :as config])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration Instant)))

(def ^:const schema-version 1)

(defn- ->long [v]
  (cond (number? v) (long v)
        (string? v) (try (Long/parseLong v) (catch Exception _ nil))
        :else       nil))

(defn- norm-depot [d]
  (let [id  (->long (:depot-id d))
        gid (:manifest-gid d)]
    ;; a manifest gid is a uint64: it stays a STRING, exactly as the manifest
    ;; layer expects. Coercing it to a long here would round the large ones.
    (when (and id (string? gid) (seq gid))
      {:depot-id id :manifest-gid gid})))

(defn- norm-version [v]
  (let [depots (into [] (keep norm-depot) (:depots v))]
    (when (and (seq (:id v)) (seq (:label v)) (seq (:branch v)) (seq depots))
      {:id     (:id v)
       :label  (:label v)
       :branch (:branch v)
       :build  (str (:build v))
       :date   (:date v)
       :bytes  (or (->long (:bytes v)) 0)
       :depots depots})))

(defn- norm-game [g]
  (let [appid    (->long (:appid g))
        versions (into [] (keep norm-version) (:versions g))]
    (when (and appid (seq (:title g)) (seq versions))
      {:appid  appid
       :title  (:title g)
       :studio (:studio g)
       :art    {:capsule     (-> g :art :capsule)
                :screenshots (into [] (-> g :art :screenshots))}
       :quotes (into [] (keep (fn [q] (when (seq (:text q))
                                        {:text (:text q) :attrib (:attrib q)}))
                              (:quotes g)))
       :versions versions})))

(defn parse
  "A catalog JSON string -> a normalized catalog map, or nil.

   nil covers every rejection: malformed JSON, a schema version this build does
   not know, an unparseable timestamp, and a document whose games all fail
   validation. A caller that gets nil falls back to an older source; there is
   nothing actionable to report."
  [^String s]
  (try
    (let [c (json/read-str s :key-fn keyword)]
      (when (= schema-version (:schema-version c))
        (Instant/parse (:generated c))            ; throws if unparseable
        (let [games (into [] (keep norm-game) (:games c))]
          ;; a document with no usable game is not a catalog
          (when (and (seq games) (= (count games) (count (:games c))))
            {:schema-version schema-version
             :generated      (:generated c)
             :games          games}))))
    (catch Exception _ nil)))

(defn- read-catalog [^File f]
  (when (and f (.isFile f)) (parse (slurp f))))

(defn bundled [] (some-> (io/resource "catalog.json") slurp parse))
(defn- cache-file ^File [] (io/file (config/data-dir) "catalog.json"))
(defn cached [] (read-catalog (cache-file)))

(defn newest
  "The catalog with the latest `generated`. nils are skipped; ties keep the
   first argument, so callers order their sources by preference."
  [& catalogs]
  (reduce (fn [best c]
            (cond (nil? c)    best
                  (nil? best) c
                  (.isAfter (Instant/parse (:generated c))
                            (Instant/parse (:generated best))) c
                  :else best))
          nil
          catalogs))

(defn load!
  "The best catalog available without touching the network. Synchronous and
   fast enough to call before the window opens."
  []
  (newest (bundled) (cached)))

(defn refresh!
  "Fetch `url` on a background thread and call `on-done` with the parsed
   catalog if it is valid and newer than what we have. Returns immediately.

   Silent on every failure. The UI shows which catalog is live in its status
   line; a failed refresh simply means that line keeps saying what it said."
  [^String url on-done]
  (.start
   (Thread.
    (fn []
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 10))
                         (.build))
              resp   (.send client
                            (-> (HttpRequest/newBuilder (URI/create url))
                                (.timeout (Duration/ofSeconds 20))
                                (.build))
                            (HttpResponse$BodyHandlers/ofString))]
          (when (<= 200 (.statusCode resp) 299)
            (when-let [fresh (parse (.body resp))]
              (when (= fresh (newest (load!) fresh))
                (io/make-parents (cache-file))
                (spit (cache-file) (.body resp))
                (on-done fresh)))))
        (catch Exception _ nil)))
    "reliquary-catalog-refresh")))

(defn games [catalog] (:games catalog))
(defn game [catalog appid] (first (filter #(= (long appid) (:appid %)) (:games catalog))))
(defn version [game version-id] (first (filter #(= version-id (:id %)) (:versions game))))
```

- [ ] **Step 5: Run the tests**

Run: `clojure -M:test -n reliquary.catalog-test`
Expected: PASS, 11 tests.

- [ ] **Step 6: Commit**

```bash
git add src/reliquary/catalog.clj resources/catalog.json test/reliquary/catalog_test.clj
git commit -m "Load the catalog from bundled, cache and network, newest wins"
```

---

### Task 8: `plan.clj` — manifests to a chunk work plan

The last pure layer before the download engine. Everything here is a function
of its arguments, which is why it carries the property tests: a mistake in this
arithmetic produces plausible bytes at the wrong offset, and the only thing
that catches it downstream is a chunk SHA-1 failure with a confusing message.

**Files:**
- Create: `src/reliquary/plan.clj`
- Create: `test/reliquary/plan_test.clj`

**Interfaces:**
- Consumes: nothing but its arguments
- Produces:
  - `flag-directory` → `64`
  - `(build depot-manifests)` → a work plan. Input is a vector of
    `{:depot-id long :key-hex s :files [<manifest/parse entry>]}`.
  - Plan shape:
    ```clojure
    {:download-bytes 8000    ; unique content actually fetched
     :disk-bytes     9000    ; everything written, duplicates included
     :total-chunks   12
     :dirs  ["Data" "Data/textures"]
     :files [{:path "Data/a.bsa" :size 4096 :depot-id 221 :key-hex "ab…"
              :sha-content "3f…"
              :chunks [{:index 0 :id "aa…" :offset 0 :cb-original 4096}]}]
     :copies [{:path "Data/b.bsa" :source "Data/a.bsa" :size 4096}]}
    ```
  - `(chunk-count plan)` → long, the number of chunks to fetch

- [ ] **Step 1: Write the failing tests**

```clojure
(ns reliquary.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [reliquary.plan :as plan]))

(defn- entry
  "A manifest entry as reliquary.steam.manifest/parse produces one: uint64
   fields arrive as STRINGS."
  [name size sha chunks & {:keys [flags] :or {flags 0}}]
  {:name name :size (str size) :flags flags :sha-content sha
   :chunks (mapv (fn [[id off len]]
                   {:id id :offset (str off) :cb-original len :cb-compressed len})
                 chunks)})

(defn- manifest [& entries]
  [{:depot-id 221 :key-hex "deadbeef" :files (vec entries)}])

(deftest builds-a-single-file
  (let [p (plan/build (manifest (entry "a.bsa" 4096 "sha-a" [["c0" 0 2048] ["c1" 2048 2048]])))
        f (first (:files p))]
    (is (= 4096 (:download-bytes p)))
    (is (= 4096 (:disk-bytes p)))
    (is (= 2 (:total-chunks p)))
    (is (= "a.bsa" (:path f)))
    (is (= 4096 (:size f)))
    (is (= 221 (:depot-id f)))
    (is (= "deadbeef" (:key-hex f)) "the chunk fetcher needs the key with the chunk")
    (is (= [0 1] (mapv :index (:chunks f))) "the index is what the resume file records")
    (is (= [0 2048] (mapv :offset (:chunks f))) "offsets are longs, not strings")))

(deftest directories-become-dirs-not-files
  (let [p (plan/build (manifest (entry "Data" 0 nil [] :flags plan/flag-directory)
                                (entry "Data/a.bsa" 10 "sha-a" [["c0" 0 10]])))]
    (is (= ["Data"] (:dirs p)))
    (is (= ["Data/a.bsa"] (mapv :path (:files p))))))

(deftest an-empty-file-is-created-not-skipped
  (let [p (plan/build (manifest (entry "empty.txt" 0 nil [])))]
    (is (= ["empty.txt"] (mapv :path (:files p))))
    (is (= [] (-> p :files first :chunks)))
    (is (= 0 (:total-chunks p)))))

(deftest identical-content-is-fetched-once-and-copied
  (let [p (plan/build (manifest (entry "a.bsa" 4096 "same" [["c0" 0 4096]])
                                (entry "b.bsa" 4096 "same" [["c0" 0 4096]])))]
    (is (= ["a.bsa"] (mapv :path (:files p))) "only the first is downloaded")
    (is (= [{:path "b.bsa" :source "a.bsa" :size 4096}] (:copies p)))
    (is (= 4096 (:download-bytes p)) "the duplicate costs no bandwidth")
    (is (= 8192 (:disk-bytes p))     "but it does cost disk")
    (is (= 1 (:total-chunks p)))))

(deftest depots-merge-and-carry-their-own-keys
  (let [p (plan/build [{:depot-id 221 :key-hex "k1" :files [(entry "a" 10 "sa" [["c0" 0 10]])]}
                       {:depot-id 222 :key-hex "k2" :files [(entry "b" 20 "sb" [["c1" 0 20]])]}])]
    (is (= #{"a" "b"} (set (mapv :path (:files p)))))
    (is (= #{"k1" "k2"} (set (mapv :key-hex (:files p)))))
    (is (= 30 (:download-bytes p)))))

(deftest paths-are-relative-and-never-escape-the-destination
  (testing "a manifest is remote input; a path that climbs out of the install dir is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "../../etc/passwd" 10 "s" [["c" 0 10]])))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "/etc/passwd" 10 "s" [["c" 0 10]])))))))

(deftest a-nil-name-is-rejected
  (testing "manifest/parse yields a nil name when filenames are encrypted and no key was given"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry nil 10 "s" [["c" 0 10]])))))))

;; --- properties ---------------------------------------------------------

(def ^:private gen-file
  (gen/let [n      (gen/such-that seq gen/string-alphanumeric)
            sizes  (gen/vector (gen/choose 1 5000) 1 8)]
    (let [offsets (reductions + 0 sizes)
          total   (reduce + sizes)]
      (entry n total (str "sha-" n)
             (mapv (fn [i off len] [(str "c" n i) off len])
                   (range) offsets sizes)))))

(defspec chunks-tile-every-file-exactly 80
  (prop/for-all [files (gen/vector gen-file 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (every? (fn [f]
                (let [cs (:chunks f)]
                  (and (= 0 (:offset (first cs)))
                       (= (:size f) (+ (:offset (last cs)) (:cb-original (last cs))))
                       (every? (fn [[a b]] (= (+ (:offset a) (:cb-original a)) (:offset b)))
                               (partition 2 1 cs)))))
              (:files p)))))

(defspec download-bytes-equals-the-sum-of-fetched-chunks 80
  (prop/for-all [files (gen/vector gen-file 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (= (:download-bytes p)
         (reduce + 0 (for [f (:files p) c (:chunks f)] (:cb-original c)))))))

(defspec total-chunks-matches-the-fetch-list 80
  (prop/for-all [files (gen/vector gen-file 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (= (:total-chunks p) (plan/chunk-count p)
         (count (for [f (:files p) c (:chunks f)] c))))))
```

- [ ] **Step 2: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.plan-test`
Expected: FAIL — `reliquary.plan` does not exist.

- [ ] **Step 3: Implement `plan.clj`**

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.plan
  "Parsed depot manifests -> the work list the download engine executes.

   Pure, and deliberately so. The offset arithmetic here is the layer where a
   mistake produces plausible bytes at the wrong place rather than an error:
   the only thing downstream that would catch it is a chunk SHA-1 failure, and
   that failure would blame the depot key. Hence the property tests."
  (:require [clojure.string :as str]
            [reliquary.error :as error]))

;; EDepotFileFlag
(def ^:const flag-directory 64)

(defn- ->long ^long [v]
  (cond (number? v) (long v)
        (string? v) (Long/parseLong v)
        :else       (error/raise :incorrect "manifest field is neither a number nor a string")))

(defn- safe-path
  "A manifest is remote input. A name that is absolute, or that climbs above
   the destination, would let a manifest write anywhere on the disk -- so it is
   rejected outright rather than sanitized into something the user did not ask
   for."
  ^String [name]
  (when-not (string? name)
    (error/raise :incorrect
                 "manifest entry has no filename -- the depot key was missing or wrong"))
  (let [p (str/replace name "\\" "/")]
    (when (or (str/starts-with? p "/")
              (re-find #"^[A-Za-z]:" p)
              (some #{".."} (str/split p #"/")))
      (error/raise :incorrect (str "manifest entry escapes the install folder: " p)
                   {:path p}))
    p))

(defn- directory? [e] (pos? (bit-and (long (or (:flags e) 0)) flag-directory)))

(defn- norm-chunks [chunks]
  (into []
        (map-indexed (fn [i c]
                       {:index       i
                        :id          (:id c)
                        :offset      (->long (:offset c))
                        :cb-original (->long (:cb-original c))}))
        (sort-by #(->long (:offset %)) chunks)))

(defn build
  "Depot manifests -> a work plan.

   `depot-manifests` is a vector of {:depot-id long :key-hex string :files
   [entry]}, where each entry is what reliquary.steam.manifest/parse produced.
   The depot key travels with each file because the chunk fetcher needs it and
   the engine should not have to look it up again.

   Files sharing a content SHA-1 are fetched once; the rest become :copies.
   Steam depots do repeat content, and a copy is free next to a download."
  [depot-manifests]
  (let [entries (for [{:keys [depot-id key-hex files]} depot-manifests
                      e files]
                  (assoc e :depot-id depot-id :key-hex key-hex))
        dirs    (into [] (comp (filter directory?)
                               (map #(safe-path (:name %)))
                               (distinct))
                      entries)
        plain   (remove directory? entries)]
    (loop [remaining (seq plain)
           seen      {}                       ; sha-content -> path already planned
           files     []
           copies    []
           dl-bytes  0
           disk      0
           chunks    0]
      (if-not remaining
        {:download-bytes dl-bytes
         :disk-bytes     disk
         :total-chunks   chunks
         :dirs           (vec (sort dirs))
         :files          files
         :copies         copies}
        (let [e    (first remaining)
              path (safe-path (:name e))
              size (->long (:size e))
              sha  (:sha-content e)
              src  (get seen sha)]
          (if (and sha src)
            (recur (next remaining) seen files
                   (conj copies {:path path :source src :size size})
                   dl-bytes (+ disk size) chunks)
            (let [cs (norm-chunks (:chunks e))]
              (recur (next remaining)
                     (if sha (assoc seen sha path) seen)
                     (conj files {:path        path
                                  :size        size
                                  :depot-id    (:depot-id e)
                                  :key-hex     (:key-hex e)
                                  :sha-content sha
                                  :chunks      cs})
                     copies
                     (+ dl-bytes (reduce + 0 (map :cb-original cs)))
                     (+ disk size)
                     (+ chunks (count cs))))))))))

(defn chunk-count ^long [plan]
  (reduce + 0 (map (comp count :chunks) (:files plan))))
```

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n reliquary.plan-test`
Expected: PASS — 7 example tests and 3 properties.

- [ ] **Step 5: Run the whole suite**

Run: `clojure -M:test`
Expected: PASS across every namespace.

- [ ] **Step 6: Commit**

```bash
git add src/reliquary/plan.clj test/reliquary/plan_test.clj
git commit -m "Turn manifests into a chunk work list, with the tiling pinned"
```

---

## What this plan does not build

Stated so the next plan's scope is unambiguous. None of these are started here:

- `download.clj` — the executor, resume file, progress sampling, cancel
- `art.clj` — capsule and screenshot fetching and caching
- `ui/**` — every screen, the theme, the QR canvas, the guard-code field
- `main.clj` — the application entry point
- `tool/catalog` — the catalog generator
- Windows packaging

The next plan is written **after** Tasks 3 and 4 report, because their findings
decide whether the UI is cljfx, raw interop, or jlink+jpackage.
