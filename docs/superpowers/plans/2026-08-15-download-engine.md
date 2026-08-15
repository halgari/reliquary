# Download Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Download a chosen version of a Steam game to a folder, headlessly, with
working resume and cancel — driven by a CLI, so the engine is provable before any
UI exists.

**Architecture:** `plan.clj` (already built, amended here) turns manifests into a
chunk work list. `progress.clj` records which chunks landed. `download.clj` runs a
fixed thread pool over the work list, writing chunks at their declared offsets and
flushing progress atomically. `cli.clj` gives it a face: `login`, `download`,
`resume`. Nothing in this plan knows JavaFX exists.

**Tech Stack:** Clojure 1.12.5, JDK 26, `java.util.concurrent` (no core.async),
`FileChannel` positional writes, the copied `reliquary.steam.*` client.

**Spec:** `docs/superpowers/specs/2026-08-15-reliquary-design.md` — §4 was amended
on 2026-08-15 with the foundation review's findings and is authoritative over any
older sketch of the plan map.

## Global Constraints

- **Secrets never surface.** Refresh tokens, passwords and depot keys are never
  logged, rendered, printed, or placed in an ex-info data map. Depot keys must
  not appear in the plan map, the progress file, or any error snapshot.
- **Error categories**: `:unauthenticated`, `:unavailable`, `:io`, `:incorrect`,
  under `:reliquary/error`.
- **No pulsar, no RocksDB, no core.async, no FUSE.** Persistent state is one EDN
  config plus one progress file per download.
- **No separate verification pass.** A chunk's id *is* the SHA-1 of its decoded
  plaintext and `chunk/fetch-decoded` already enforces it. Do not re-hash files.
- **JDK 26, jlink + jpackage.** No GraalVM.
- License GPL-3.0-or-later; every new file carries the GPL header used across
  `src/reliquary/`.
- The suite is currently **206 tests / 492 assertions / 0 failures**. It must be
  green at every commit.

## File Structure

| Path | Responsibility |
|---|---|
| `src/reliquary/plan.clj` | *(exists)* amended: tiling validation, structural classification, no keys, retained compressed sizes |
| `src/reliquary/progress.clj` | **new** — the resume file: format, atomic write, load |
| `src/reliquary/download.clj` | **new** — resolve a version, execute the plan, report progress, cancel |
| `src/reliquary/cli.clj` | **new** — `login`, `download`, `resume` |
| `test/reliquary/progress_test.clj` | **new** |
| `test/reliquary/download_test.clj` | **new** — against a local HTTP fixture server |

## Prerequisites this plan does not resolve

**RESOLVED 2026-08-15.** The account is logged in (`clojure -M:cli status` reports
online, 548 licenses) and owns 11 of the catalog's 13 games. Everything in this
plan is now unblocked.

**The live gate target is Stardew Valley (appid 413150).** It is the smallest
entry at 0.5 GB *and* the only small owned game with non-public branches
(`legacy_1.5.6`, `legacy_1.6.8`, `previous_version`, `compatibility`), so it
exercises historical branch resolution — which Morrowind, at one public version,
cannot test at all. Morrowind (22320, 3.1 GB) remains the fallback if Stardew's
tiny depots turn out to hide a bug that only appears at scale.

---

### Task 1: Amend `plan.clj` with the foundation review's carried findings

Four findings were deliberately deferred from the foundation branch as
"opening work on the engine brief." This is that work. Each is minutes now and a
redesign once the engine consumes the plan.

**Files:**
- Modify: `src/reliquary/plan.clj`
- Modify: `test/reliquary/plan_test.clj`

**Interfaces:**
- Consumes: `reliquary.error/raise`
- Produces: `(build depot-manifests)` → the amended plan map (no `:key-hex`,
  with `:cb-compressed`), raising `:incorrect` on a non-tiling manifest;
  `(keys-by-depot depot-manifests)` → `{depot-id key-hex}`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest chunks-that-do-not-tile-are-rejected
  (testing "a gap leaves a hole in the file that nothing downstream would catch"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "a" 100 "sha-a" [["c0" 0 10] ["c1" 90 10]]))))))
  (testing "an overlap writes one region twice and loses the other"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "a" 20 "sha-a" [["c0" 0 15] ["c1" 10 10]]))))))
  (testing "a chunk running past the declared size breaks preallocation"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "a" 5 "sha-a" [["c0" 0 100]])))))))

(deftest a-symlink-entry-is-not-planned-as-an-empty-file
  (testing "manifest.clj refuses to define flag constants; classify structurally"
    (let [p (plan/build (manifest (entry "link" 0 nil [] :flags 512)
                                  (entry "real.bsa" 10 "sha-r" [["c0" 0 10]])))]
      (is (= ["real.bsa"] (mapv :path (:files p)))
          "a no-chunk no-sha entry is not a zero-byte regular file"))))

(deftest an-empty-regular-file-is-still-planned
  (let [p (plan/build (manifest (entry "empty.txt" 0 "sha-e" [])))]
    (is (= ["empty.txt"] (mapv :path (:files p))))))

(deftest the-plan-carries-no-depot-keys
  (let [p (plan/build (manifest (entry "a" 10 "sha-a" [["c0" 0 10]])))]
    (is (not (re-find #"deadbeef" (pr-str p)))
        "the plan is serialized into progress files; a depot key must not ride along")
    (is (nil? (:key-hex (first (:files p)))))))

(deftest keys-travel-separately
  (is (= {221 "deadbeef"}
         (plan/keys-by-depot (manifest (entry "a" 10 "sha-a" [["c0" 0 10]]))))))

(deftest compressed-sizes-are-retained
  (testing "the fetcher reports wire bytes; without this the progress bar cannot close"
    (let [p (plan/build (manifest (entry "a" 10 "sha-a" [["c0" 0 10]])))]
      (is (= 10 (:cb-compressed (first (:chunks (first (:files p))))))))))

(deftest two-entries-may-not-claim-one-path
  (testing "different shas, same destination -- Fix 3 keyed off sha and missed this"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "a" 10 "sha-1" [["c0" 0 10]])
                                       (entry "a" 20 "sha-2" [["c1" 0 20]])))))))
```

- [ ] **Step 2: Run them and watch them fail**

Run: `clojure -M:test -n reliquary.plan-test`
Expected: FAIL — `keys-by-depot` undefined; the tiling, symlink, key-absence and
path-collision tests all fail against the current implementation.

- [ ] **Step 3: Implement**

In `src/reliquary/plan.clj`:

- Add `validate-tiling!`, called from `build` per file: chunks sorted by offset
  must start at 0, each chunk's `offset + cb-original` must equal the next
  chunk's offset, and the last must end exactly at `:size`. Raise `:incorrect`
  naming the path and the offending offset. A file with no chunks and size 0 is
  valid.
- Replace the `flag-directory` test with structural classification:
  - has chunks, or has a content SHA → a **file**
  - no chunks, no content SHA, size 0 → **not a regular file** (directory or
    symlink); directories go to `:dirs`, everything else is skipped and counted
  - keep `flag-directory` only as a hint for which of those two it is
- Drop `:key-hex` from the file maps. Add:

```clojure
(defn keys-by-depot
  "{depot-id -> key-hex} for the manifests in this plan.

   Kept OUT of the plan map deliberately: the plan is what the engine serializes
   into progress files and error snapshots, and a depot key is a secret under the
   spec's §9 rule. The engine threads this map alongside the plan instead."
  [depot-manifests]
  (into {} (map (juxt :depot-id :key-hex)) depot-manifests))
```

- Retain `:cb-compressed` in `norm-chunks`.
- Track destination paths in a set and raise `:incorrect` on a repeat,
  independent of `:sha-content`.

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n reliquary.plan-test`
Expected: PASS, including the existing properties.

- [ ] **Step 5: Full suite, then commit**

Run: `clojure -M:test` → 206+ tests, 0 failures.

```bash
git add src/reliquary/plan.clj test/reliquary/plan_test.clj
git commit -m "Make the plan enforce its own invariants and stop carrying keys"
```

---

### Task 2: `progress.clj` — the resume file

**Files:**
- Create: `src/reliquary/progress.clj`
- Create: `test/reliquary/progress_test.clj`

**Interfaces:**
- Produces:
  - `(progress-file dest appid version-id)` → `java.io.File` at
    `<dest>/.reliquary/<appid>-<version-id>.progress`
  - `(load dest appid version-id)` → `{path #{chunk-index …}}`, `{}` when absent
    or unreadable
  - `(save! dest appid version-id done)` → writes atomically
  - `(remaining plan done)` → the plan with completed chunks removed and
    `:download-bytes` / `:total-chunks` recomputed
  - `(done-bytes plan done)` → bytes already on disk, for the resumed progress bar

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest a-missing-progress-file-reads-as-empty
  (with-tmp (fn [d] (is (= {} (progress/load d 220 "public"))))))

(deftest progress-round-trips
  (with-tmp (fn [d]
              (progress/save! d 220 "public" {"a.bsa" #{0 1 3}})
              (is (= {"a.bsa" #{0 1 3}} (progress/load d 220 "public"))))))

(deftest a-corrupt-progress-file-reads-as-empty-rather-than-throwing
  (testing "a bad progress file costs a re-download; a crash costs the install"
    (with-tmp (fn [d]
                (spit (progress/progress-file d 220 "public") "{:unbalanced ")
                (is (= {} (progress/load d 220 "public")))))))

(deftest save-is-atomic
  (with-tmp (fn [d]
              (progress/save! d 220 "public" {"a" #{0}})
              (progress/save! d 220 "public" {"a" #{0 1}})
              (is (= 1 (count (filter #(.isFile %) (.listFiles (.getParentFile (progress/progress-file d 220 "public")))))))
              (is (= {"a" #{0 1}} (progress/load d 220 "public"))))))

(deftest versions-do-not-share-a-progress-file
  (with-tmp (fn [d]
              (progress/save! d 220 "public" {"a" #{0}})
              (progress/save! d 220 "1_5_97" {"a" #{7}})
              (is (= {"a" #{0}} (progress/load d 220 "public"))))))

(deftest remaining-drops-completed-chunks-and-recounts
  (let [p {:download-bytes 30 :disk-bytes 30 :total-chunks 3 :dirs [] :copies []
           :files [{:path "a" :size 30 :depot-id 1 :sha-content "s"
                    :chunks [{:index 0 :offset 0 :cb-original 10 :cb-compressed 5 :id "c0"}
                             {:index 1 :offset 10 :cb-original 10 :cb-compressed 5 :id "c1"}
                             {:index 2 :offset 20 :cb-original 10 :cb-compressed 5 :id "c2"}]}]}
        r (progress/remaining p {"a" #{0 2}})]
    (is (= [1] (mapv :index (:chunks (first (:files r))))))
    (is (= 10 (:download-bytes r)))
    (is (= 1 (:total-chunks r)))
    (is (= 20 (progress/done-bytes p {"a" #{0 2}})))))

(deftest a-fully-complete-file-keeps-its-entry-with-no-chunks
  (testing "the file must still be preallocated and its copies still made"
    (let [p {:download-bytes 10 :disk-bytes 10 :total-chunks 1 :dirs [] :copies []
             :files [{:path "a" :size 10 :depot-id 1 :sha-content "s"
                      :chunks [{:index 0 :offset 0 :cb-original 10 :cb-compressed 5 :id "c0"}]}]}
          r (progress/remaining p {"a" #{0}})]
      (is (= 1 (count (:files r))))
      (is (= [] (:chunks (first (:files r)))))
      (is (= 0 (:total-chunks r))))))
```

- [ ] **Step 2: Run and watch them fail**

Run: `clojure -M:test -n reliquary.progress-test` → FAIL, namespace missing.

- [ ] **Step 3: Implement `progress.clj`**

Mirror `config.clj`'s atomic-write discipline: temp file in the same directory,
`ATOMIC_MOVE`. EDN, because sets serialize natively and the file is small.

The docstring must record why the write ordering matters:

```clojure
;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.progress
  "Which chunks already landed, so a resumed download re-fetches nothing.

   The interrupted screen tells the user that nothing needs to be re-fetched.
   That claim is only honest if this file is written BEFORE the bytes it
   describes are acknowledged as done -- never after. A progress file that runs
   ahead of the disk silently skips chunks that were never written.

   A corrupt or missing file reads as {} -- an empty progress map costs a
   re-download, while a raise costs the user the whole install."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))
```

Mirror `config.clj` exactly for the write: `Files/createTempFile` in the SAME
directory as the target, then `Files/move` with `REPLACE_EXISTING` and
`ATOMIC_MOVE`. A temp file in a different directory silently degrades the move to
copy-and-delete and loses atomicity.

- [ ] **Step 4: Run the tests**

Run: `clojure -M:test -n reliquary.progress-test` → PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/reliquary/progress.clj test/reliquary/progress_test.clj
git commit -m "Record landed chunks so a resume re-fetches nothing"
```

---

### Task 3: `download.clj` — resolve and execute

The engine proper. Split into two halves that are tested differently: resolution
(network, redefined in tests) and execution (a local HTTP fixture server).

**Files:**
- Create: `src/reliquary/download.clj`
- Create: `test/reliquary/download_test.clj`

**Interfaces:**
- Consumes: `session/open!`, `cm.content/depot-key`, `cm.content/manifest-request-code`,
  `cm.content/cdn-servers`, `manifest/fetch`, `manifest/parse`, `chunk/fetch-decoded`,
  `plan/build`, `plan/keys-by-depot`, `progress/*`
- Produces:
  - `(resolve-version session game version)` → `{:plan … :keys {depot-id key} :hosts […]}`
  - `(execute! ctx opts)` → runs to completion; returns the final progress snapshot
  - `(snapshot ctx)` → `{:bytes-done :bytes-total :chunks-done :chunks-total :stage :error :samples}`
  - `(cancel! ctx)` → sets the flag; in-flight chunks finish

- [ ] **Step 1: Write the failing tests**

Resolution tests redefine every network call. Execution tests run against a real
`com.sun.net.httpserver.HttpServer` on an OS-assigned port serving chunk fixtures,
so the concurrency and the file writes are genuinely exercised.

```clojure
(deftest a-denied-depot-fails-the-whole-version
  (testing "the catalog named exactly these depots; a denial means this account
            cannot have this build, and a partial install is worse than an error"
    (with-redefs [content/depot-key (fn [_ _ d] (if (= d 2) (error/raise :incorrect "denied") "ab"))
                  content/manifest-request-code (constantly "1")
                  content/cdn-servers (constantly ["h"])
                  manifest/fetch (constantly (byte-array 0))
                  manifest/parse (constantly {:depot-id 1 :files []})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (download/resolve-version {} {:appid 7}
                                             {:branch "public"
                                              :depots [{:depot-id 1 :manifest-gid "a"}
                                                       {:depot-id 2 :manifest-gid "b"}]}))))))

(deftest the-branch-reaches-the-request-code-call
  (let [seen (atom nil)]
    (with-redefs [content/depot-key (constantly "ab")
                  content/manifest-request-code (fn [_ _ _ _ br] (reset! seen br) "1")
                  content/cdn-servers (constantly ["h"])
                  manifest/fetch (constantly (byte-array 0))
                  manifest/parse (constantly {:depot-id 1 :files []})]
      (download/resolve-version {} {:appid 7}
                                {:branch "1_63_legacy_patch"
                                 :depots [{:depot-id 1 :manifest-gid "a"}]})
      (is (= "1_63_legacy_patch" @seen)
          "a historical version on a named branch must not ask for public"))))

(def ^:private plaintext
  "Three 10-byte chunks whose assembled content is known, so the only thing the
   test trusts is the bytes on disk."
  {"c0" (.getBytes "AAAAAAAAAA") "c1" (.getBytes "BBBBBBBBBB") "c2" (.getBytes "CCCCCCCCCC")})

(defn- with-fixture-server
  "Serve `plaintext` at the chunk paths chunk/fetch-decoded requests, counting
   hits per chunk id. Port 0 so parallel test runs cannot collide."
  [f]
  (let [hits (atom {})
        srv  (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [id (last (str/split (.getPath (.getRequestURI ex)) #"/"))]
            (swap! hits update id (fnil inc 0))
            (if-let [b (plaintext id)]
              (do (.sendResponseHeaders ex 200 (alength b))
                  (doto (.getResponseBody ex) (.write b) (.close)))
              (.sendResponseHeaders ex 404 -1))))))
    (.start srv)
    (try (f (str "127.0.0.1:" (.getPort (.getAddress srv))) hits)
         (finally (.stop srv 0)))))

(deftest chunks-land-at-their-declared-offsets
  (testing "byte-exactness is the only assertion that catches an off-by-one in
            the positional write -- a wrong offset still produces a full file"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            ;; chunks deliberately queued out of offset order
            (download/execute! (ctx-for host dest
                                        [{:index 2 :id "c2" :offset 20 :cb-original 10}
                                         {:index 0 :id "c0" :offset 0  :cb-original 10}
                                         {:index 1 :id "c1" :offset 10 :cb-original 10}])
                               {:workers 3})
            (is (= "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC"
                   (slurp (io/file dest "a.bin"))))))))))

(deftest a-killed-download-resumes-without-refetching
  (with-tmp
    (fn [dest]
      (with-fixture-server
        (fn [host hits]
          ;; first run: stop after one chunk
          (download/execute! (ctx-for host dest all-chunks) {:workers 1 :chunk-budget 1})
          (let [after-first @hits]
            (is (= 1 (reduce + 0 (vals after-first))))
            ;; second run resumes from the progress file
            (download/execute! (ctx-for host dest all-chunks) {:workers 3})
            (is (= "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC" (slurp (io/file dest "a.bin"))))
            (is (every? #(= 1 %) (vals @hits))
                "the interrupted screen promises nothing is re-fetched; prove it")))))))

(deftest cancel-lets-in-flight-chunks-finish
  (testing "a progress file that runs ahead of the disk silently skips chunks"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            (let [c (ctx-for host dest all-chunks)]
              (future (Thread/sleep 20) (download/cancel! c))
              (download/execute! c {:workers 1})
              (let [done (progress/load dest 7 "public")
                    ch   (java.io.RandomAccessFile. (io/file dest "a.bin") "r")]
                (doseq [i (get done "a.bin")]
                  (.seek ch (* 10 i))
                  (let [buf (byte-array 10)]
                    (.readFully ch buf)
                    (is (not= (seq (byte-array 10)) (seq buf))
                        (str "chunk " i " is recorded done but its region is empty"))))
                (.close ch)))))))))

(deftest preallocation-fails-before-any-chunk-is-fetched
  (testing "a full disk must fail immediately, not at 94%"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host hits]
            ;; a path the OS will refuse: dest/a.bin exists as a DIRECTORY
            (.mkdirs (io/file dest "a.bin"))
            (is (thrown? Exception
                         (download/execute! (ctx-for host dest all-chunks) {:workers 3})))
            (is (empty? @hits)
                "not one chunk should be requested if the files cannot be created")))))))
```

`ctx-for` is a small test helper the implementer writes: it assembles a plan for
a single file `a.bin` of size 30 from the given chunk vector, a `{depot-id key}`
map, and the fixture host list, then hands back what `execute!` consumes. Keep it
in the test namespace — it exists so each test reads as its assertion rather than
as setup.

- [ ] **Step 2: Run and watch them fail**

Run: `clojure -M:test -n reliquary.download-test` → FAIL, namespace missing.

- [ ] **Step 3: Implement resolution**

For each depot in the version: `depot-key` → `manifest-request-code` with the
version's **branch** → `manifest/fetch` → `manifest/parse` with the key. Collect
into `plan/build` input, and `plan/keys-by-depot` alongside. A denial at either
step raises — do not skip and count.

- [ ] **Step 4: Implement execution**

- Create `:dirs`, then create and `setLength` every file **before** any fetch.
- A fixed `ExecutorService` (default 8, from config) over a
  `LinkedBlockingQueue` of chunk jobs. Each worker: check the cancel flag,
  `chunk/fetch-decoded`, then `FileChannel/write` at `:offset` — positional
  writes are thread-safe without locking, so keep one open channel per file.
- Record the landed chunk in an atom; flush `progress/save!` every ~3 seconds
  and on exit. **Write progress only after the channel write returns.**
- Apply `:copies` after their sources complete.
- A worker exception sets `:error`, drains the queue, leaves the disk intact.

- [ ] **Step 5: Implement progress sampling**

A `ScheduledExecutorService` at 250ms computes instantaneous MB/s and maintains
a 48-sample ring. `snapshot` returns a plain map — the UI reads snapshots and
never touches engine internals.

Reconcile the units the foundation review flagged: `:bytes-done` counts
**decompressed** bytes so it closes against `:download-bytes`; wire throughput is
computed from `:cb-compressed` and reported separately as `:wire-bytes`.

- [ ] **Step 6: Run the tests, then the full suite**

Run: `clojure -M:test -n reliquary.download-test`, then `clojure -M:test`.

- [ ] **Step 7: Commit**

```bash
git add src/reliquary/download.clj test/reliquary/download_test.clj
git commit -m "Run the plan: preallocate, fetch in parallel, write at offset, resume"
```

---

### Task 4: `cli.clj` — the `download` command

**Partially built ahead of schedule.** `login`, `status`, `list` and `logout`
already exist and are committed (6 tests), because logging in needed a human and
blocked everything else. What remains here is the `download` command and its
resume path.

**Files:**
- Modify: `src/reliquary/cli.clj` *(exists — add `download`)*
- Modify: `test/reliquary/cli_test.clj` *(exists — 6 tests passing)*
- `deps.edn`'s `:cli` alias already exists

**Interfaces:**
- Produces: `reliquary login`, `reliquary download <appid> <version-id> <dest>`,
  `reliquary list`

- [ ] **Step 1: Write the failing tests**

Argument parsing and exit-code mapping are pure and testable; the commands
themselves are covered by Task 5's live gate.

```clojure
(deftest unknown-command-is-incorrect
  (is (= 1 (cli/exit-code-for (ex-info "x" {:reliquary/error :incorrect})))))

(deftest not-logged-in-maps-to-4
  (is (= 4 (cli/exit-code-for (ex-info "x" {:reliquary/error :unauthenticated})))))

(deftest download-requires-a-known-appid-and-version
  (testing "a typo must not reach Steam"
    (is (thrown? clojure.lang.ExceptionInfo (cli/parse ["download" "999999" "public" "/tmp/x"])))))

(deftest list-renders-every-catalog-version
  (let [out (with-out-str (cli/run ["list"]))]
    (is (re-find #"Cyberpunk 2077" out))
    (is (re-find #"1.63 Legacy" out))))
```

- [ ] **Step 2: Run and watch them fail** → namespace missing.

- [ ] **Step 3: Implement**

- `login` — `auth/login-qr!`, rendering the challenge with `qr/terminal-string`,
  then `config/save-token!`. Never print the token.
- `list` — read the catalog, print appid / title / versions.
- `download` — resolve the game and version from the catalog (fail on an unknown
  pair before opening a session), `session/open!`, `download/resolve-version`,
  `download/execute!`, printing a progress line.
- Exit codes: `:incorrect` 1, `:unavailable` 2, `:io` 3, `:unauthenticated` 4.
- A catalog version with `bytes` 0 or `build` "" prints **unknown**, never
  "0.0 GB" — those fields are genuinely absent for community-sourced versions.

Add to `deps.edn`:

```clojure
  :cli {:main-opts ["-m" "reliquary.cli"]}
```

- [ ] **Step 4: Run the tests and the full suite** → green.

- [ ] **Step 5: Commit**

```bash
git add src/reliquary/cli.clj test/reliquary/cli_test.clj deps.edn
git commit -m "Give the engine a CLI so it can be driven before the UI exists"
```

---

### Task 5: The live gate — real bytes from real Steam

**This task requires a real Steam account and a small owned game.** Everything
before it is offline. Morrowind (appid 22320, 3.1 GB, one public version) is the
smallest entry in the catalog and the intended target.

**Files:**
- Create: `docs/superpowers/notes/2026-08-15-engine-live-results.md`

**Interfaces:** consumes everything above.

- [ ] **Step 1: Log in**

Run: `clojure -M:cli login`
Expected: a scannable QR in the terminal; after approval, a token in
`~/.config/reliquary/config.edn` at mode 0600. **Confirm the token is not echoed
anywhere in the terminal output.**

- [ ] **Step 2: List the catalog**

Run: `clojure -M:cli list`
Expected: 13 games; Skyrim SE shows 11 versions; community-sourced versions show
their size as **unknown** rather than 0.0 GB.

- [ ] **Step 3: Download a real game**

Run: `clojure -M:cli download 22320 public /tmp/reliquary-live`
Expected: real bytes on disk. Record wall time, average throughput, peak
throughput, and the final byte count against the catalog's declared size —
they will differ, because the catalog sums all language depots and the engine
selects one. **That difference is the number to record.**

- [ ] **Step 4: Kill it and resume**

Kill the process at roughly 30%. Note the progress file's contents. Re-run the
same command.
Expected: it resumes, re-fetches nothing already recorded, and the finished
install is byte-identical to an uninterrupted run. Verify by running a second
clean download to a different directory and diffing the trees.

- [ ] **Step 5: Verify the game actually runs**

The strongest possible assertion: point a Steam-free launcher (or Proton) at the
downloaded directory and confirm the game starts. A tree that diffs clean but
will not launch means something structural is wrong.

- [ ] **Step 6: Record the results**

Write `docs/superpowers/notes/2026-08-15-engine-live-results.md` with every
number, every failure encountered, and — if the resumed and clean trees differed
in any way — exactly how.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/notes/
git commit -m "Record the first real download: <game>, <size>, resumed clean"
```

---

## What this plan does not build

- Any UI — no cljfx, no screens, no `main.clj`
- `art.clj` — capsule and screenshot fetching
- The catalog's GitHub refresh URL (still undecided)
- Windows packaging
- Language-depot selection beyond "pick one" — a proper picker is UI work
