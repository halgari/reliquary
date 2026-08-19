(ns reliquary.catalog-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.catalog :as catalog]
            [reliquary.config :as config])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io File)
           (java.net InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- local-http-server
  "A JDK HttpServer bound to an OS-assigned port, serving `body` (bytes) at
   `path` with `status` after an optional `delay-ms`. Caller must (.stop server 0)."
  ([path status body] (local-http-server path status body 0))
  ([path status ^bytes body delay-ms]
   (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
     (.createContext server path
       (reify HttpHandler
         (handle [_ ex]
           (when (pos? delay-ms) (Thread/sleep ^long delay-ms))
           (.sendResponseHeaders ^HttpExchange ex status (alength body))
           (with-open [os (.getResponseBody ^HttpExchange ex)]
             (.write os body)))))
     (.start server)
     server)))

(defn- server-url [server path]
  (str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer server)) path))

(def ^:private minimal
  "The catalog is EDN, so the fixtures are EDN. Kept as a STRING rather than a
   literal map because `parse` takes the raw document text -- that is what
   arrives off disk and off the network, and parsing is what is under test."
  (pr-str {:schema-version 1
           :generated "2026-01-01T00:00:00Z"
           :games [{:appid 220 :title "HL2" :studio "Valve"
                    :versions [{:id "public" :label "L" :branch "public"
                                :build "1" :date "2026-01-01" :bytes 10
                                :depots [{:depot-id 221 :manifest-gid "77"}]}]}]}))

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
  (is (nil? (catalog/parse (str/replace minimal ":schema-version 1" ":schema-version 99")))
      "an old binary must keep working when the catalog moves ahead"))

(deftest malformed-json-is-nil
  (is (nil? (catalog/parse "{:unbalanced")))
  (is (nil? (catalog/parse ""))))

(deftest a-game-missing-required-fields-is-rejected
  (is (nil? (catalog/parse (pr-str {:schema-version 1 :generated "2026-01-01T00:00:00Z"
                       :games [{:title "no appid"}]})))))

(deftest a-version-with-no-depots-is-rejected
  (is (nil? (catalog/parse (str/replace minimal ":depots [{:depot-id 221, :manifest-gid \"77\"}]" ":depots []")))
      "a version we cannot fetch is worse than a version we do not offer"))

(deftest newest-wins-and-skips-nils
  (let [old (catalog/parse minimal)
        new (catalog/parse (str/replace minimal "2026-01-01T00:00:00Z" "2026-06-01T00:00:00Z"))]
    (is (= "2026-06-01T00:00:00Z" (:generated (catalog/newest old new nil))))
    (is (= "2026-06-01T00:00:00Z" (:generated (catalog/newest nil new old))))
    (is (nil? (catalog/newest nil nil)))))

(deftest the-bundled-catalog-is-valid
  (let [c (catalog/bundled)]
    (is (some? c) "resources/catalog.edn must parse — it is the offline fallback")
    (is (= 1 (:schema-version c)))
    (is (seq (catalog/games c)))
    (is (every? (fn [g] (and (:appid g) (:title g) (seq (:versions g))))
                (catalog/games c)))))

(deftest every-shipped-version-carries-a-game-executable
  (testing "not a redistributable installer and not a launcher: the binary the
            game actually runs.

            This is a depot-selection guard, not a nice-to-have. Steam marks
            Skyrim's 72852 `optional` -- Valve packages CEG-wrapped executables
            in their own depot -- and the catalog tool dropped everything marked
            optional, so the shipped entry described a 6 GB install of Skyrim
            with no TESV.exe anywhere in it. Every version listed its depots,
            every size looked right, and the download would have produced a game
            that could not start.

            The executables map is resolved from the very depots a version will
            download, so `no game executable` means exactly `these depots do not
            add up to a runnable game`."
    (let [redist?  (fn [path]
                     (some #(str/includes? (str/lower-case path) %)
                           ["dxsetup" "vcredist" "dotnetfx" "directx" "redist"
                            "setup.exe" "uninst" "crashreporter" "7za"]))
          launcher? (fn [path] (str/includes? (str/lower-case path) "launcher"))
          game-exes (fn [v] (remove #(or (redist? %) (launcher? %))
                                    (keys (:executables v))))]
      (doseq [g (catalog/games (catalog/bundled))
              v (:versions g)
              ;; Baldur's Gate 3 ships with none at all: Steam refuses this
              ;; account a key for depot 1086941, so the tool could not read the
              ;; manifest to resolve any. That is a coverage gap, not a broken
              ;; depot list, and it is visible as an empty map rather than a
              ;; wrong one.
              :when (seq (:executables v))]
        (is (seq (game-exes v))
            (str (:title g) " " (:id v) " lists only support binaries: "
                 (pr-str (keys (:executables v)))))))))

(deftest skyrims-executable-depot-is-not-dropped-as-optional
  (testing "the specific depot the `optional` rule got wrong, pinned by id so a
            future change to the filter cannot quietly lose it again"
    (let [sk (first (filter #(= 72850 (:appid %)) (catalog/games (catalog/bundled))))
          v  (first (:versions sk))]
      (is (some? sk) "Skyrim is in the shipped catalog")
      (is (contains? (set (map :depot-id (:depots v))) 72852))
      (is (contains? (:executables v) "TESV.exe")))))

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
        (spit (io/file d "catalog.edn")
              (str/replace minimal "2026-01-01T00:00:00Z" "2099-01-01T00:00:00Z"))
        (is (= "2099-01-01T00:00:00Z" (:generated (catalog/load!)))))
      (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest a-corrupt-cache-falls-back-to-bundled
  (let [d (.toFile (Files/createTempDirectory "reliquary-cat" (make-array FileAttribute 0)))]
    (try
      (binding [config/*data-dir* d]
        (spit (io/file d "catalog.edn") "{ broken")
        (is (= (:generated (catalog/bundled)) (:generated (catalog/load!)))))
      (finally (run! io/delete-file (reverse (file-seq d)))))))

(defn- with-clean-sysprop-cache
  "Run `f` with the sysprop-path cache file removed before and after.

   refresh! writes through `config/data-dir`, which reads the reliquary.data-dir
   JVM property and NOT any dynamic binding -- so `with-tmp-data-dir` cannot
   redirect it, and every refresh test writes to the same shared
   target/test-state/data. A fabricated, future-dated catalog left there
   silently shadows the bundled one for every test that runs afterwards in the
   same JVM; cli-test's `list` assertions are the ones that notice, by listing
   a two-line fixture instead of the real catalog.

   Deleting BEFORE as well as after matters: it stops a file left by an earlier
   run from defeating the freshness comparison inside refresh! itself."
  [f]
  (let [cache (io/file (config/data-dir) "catalog.edn")]
    (io/delete-file cache true)
    (try (f) (finally (io/delete-file cache true)))))

(defn- real-cache-state
  "Whether the real user-level catalog cache exists and when it last changed.

   Compared before and after rather than asserted absent: the app's own startup
   refresh writes this file in normal use, so its mere existence says nothing
   about whether a test misbehaved. Its mtime changing does."
  [^File dir]
  (let [f (io/file dir "catalog.edn")]
    {:exists (.exists f) :modified (.lastModified f)}))

(defn- with-tmp-data-dir
  "Binds *data-dir* to a throwaway temp directory for `f`'s dynamic extent.

   Hygiene only where `catalog/refresh!` is concerned: refresh! writes its
   cache from a raw Thread, and a `binding` never crosses that -- what
   actually keeps refresh!'s write off real user state is the
   reliquary.data-dir JVM property the :test alias sets (consulted by
   config/data-dir itself, not by anything thread-local). This binding still
   matters for any call made directly on the calling thread, e.g. `load!`,
   so every test in this namespace stays hermetic the same way regardless of
   which path a given assertion happens to exercise."
  [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-cat" (make-array FileAttribute 0)))]
    (try (binding [config/*data-dir* d] (f))
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest refresh-runs-on-a-daemon-thread
  ;; the handler sleeps before responding, so the refresh thread is still
  ;; alive when we inspect it; the body is invalid JSON, so the fetch stops at
  ;; `parse` once it does respond and never reaches the cache-write step.
  (with-tmp-data-dir
    (fn []
      (let [server (local-http-server "/slow" 200 (.getBytes "not json" "UTF-8") 2000)]
        (try
          (catalog/refresh! (server-url server "/slow") (fn [_] nil))
          (Thread/sleep 300) ;; give the thread time to start and register itself
          (let [t (first (filter #(= "reliquary-catalog-refresh" (.getName ^Thread %))
                                  (keys (Thread/getAllStackTraces))))]
            (is (some? t) "the refresh thread should be observable while the fetch is in flight")
            (is (true? (.isDaemon ^Thread t))
                "a non-daemon thread would hold the JVM open for up to ~30s after the window closes"))
          (finally (.stop ^HttpServer server 0)))))))

(deftest an-oversized-response-is-discarded-without-calling-on-done
  (with-tmp-data-dir
    (fn []
      (let [big    (byte-array (inc catalog/max-catalog-bytes) (byte 65))
            server (local-http-server "/big" 200 big)]
        (try
          (let [called (atom false)]
            (catalog/refresh! (server-url server "/big") (fn [_] (reset! called true)))
            (Thread/sleep 1500)
            (is (false? @called)
                "a body over max-catalog-bytes must be discarded like any other refresh failure"))
          (finally (.stop ^HttpServer server 0)))))))

(deftest a-successful-refresh-writes-under-the-test-property-not-real-home
  ;; THE test finding 1 exists to prove: this is the one where refresh!
  ;; actually succeeds -- valid JSON, newer than what's loaded, under the
  ;; size cap -- so it reaches the cache-write step the two tests above
  ;; dodge entirely (one via invalid JSON, one via an oversized body). And
  ;; unlike every other test in this namespace, it deliberately binds
  ;; NEITHER *config-dir* nor *data-dir* -- because refresh! writes from a
  ;; raw Thread, a binding here would not even reach it. What has to save
  ;; the real ~/.local/share/reliquary/catalog.edn is the
  ;; reliquary.data-dir JVM property the :test alias sets, consulted
  ;; directly by config/data-dir with no thread-locality involved at all.
  (let [real-data-dir (io/file (System/getProperty "user.home") ".local" "share" "reliquary")
        real-before   (real-cache-state real-data-dir)
        fresh-json    (str/replace minimal "2026-01-01T00:00:00Z" "2099-06-01T00:00:00Z")
        cache-file    (io/file (config/data-dir) "catalog.edn")]
    (try
      ;; a previous run of this exact test is idempotent content, but delete
      ;; first anyway so the freshness comparison inside refresh! can never
      ;; be defeated by a stale file left over from a different run.
      (io/delete-file cache-file true)
      (let [server (local-http-server "/fresh" 200 (.getBytes fresh-json "UTF-8"))]
        (try
          (let [received (promise)]
            (catalog/refresh! (server-url server "/fresh") (fn [c] (deliver received c)))
            (let [c (deref received 5000 :timeout)]
              (is (not= :timeout c) "refresh! should have called on-done with the fresh catalog")
              (is (= "2099-06-01T00:00:00Z" (:generated c)))))
          (finally (.stop ^HttpServer server 0))))
      (is (.exists cache-file)
          "the cache file must exist where config/data-dir says it does")
      (let [cache-path (.getCanonicalPath cache-file)
            under-test (.getCanonicalPath (io/file "target" "test-state"))]
        (is (str/starts-with? cache-path under-test)
            (str "with no dynamic binding in scope, refresh!'s write must still land under "
                 "target/test-state via the JVM property, not wherever XDG_DATA_HOME/$HOME "
                 "would otherwise point -- got: " cache-path)))
      ;; Asserting this file does not EXIST was wrong, and only looked right
      ;; while nothing in the app ever called refresh!. Startup refreshes the
      ;; catalog now, so a developer who has simply run the app has a perfectly
      ;; legitimate copy here -- and the assertion failed for that alone, which
      ;; is a test reporting on the machine rather than on the code. What it
      ;; actually needs to prove is that THIS TEST did not touch it: same
      ;; existence, same mtime, before and after.
      (is (= real-before (real-cache-state real-data-dir))
          "the real ~/.local/share/reliquary/catalog.edn must be exactly as this
           test found it -- refresh!'s write must have gone to the sysprop path")
      ;; this test's whole point is proving the sysprop path is reachable and
      ;; writable -- but that makes target/test-state/data a shared,
      ;; process-wide location any other test using the DEFAULT (unbound)
      ;; data-dir also reads from (e.g. cli-test's use of catalog/load!).
      ;; Leaving a fabricated, artificially-future-dated catalog sitting
      ;; there would silently shadow the bundled catalog for every test that
      ;; runs after this one in the same JVM. Clean up regardless of outcome.
      (finally (io/delete-file cache-file true)))))

(deftest versions-are-newest-release-first
  ;; Document order is not display order: the current build comes from PICS
  ;; and older ones from hand-curated files, so they arrive concatenated.
  ;; Skyrim Special Edition listed Latest (2024), 1.5.97 (2019), then climbed
  ;; back through 2021 and 2022 -- no order at all, as far as a reader can tell.
  (let [g {:appid 1 :versions [{:id "public" :date "2024-01-17"}
                               {:id "old"    :date "2019-11-20"}
                               {:id "mid"    :date "2021-11-11"}
                               {:id "newer"  :date "2023-12-05"}]}]
    (is (= ["public" "newer" "mid" "old"] (mapv :id (catalog/versions g)))))
  (testing "ties keep document order -- Stardew ships public and compatibility
            the same day, and the current build belongs above the 32-bit one"
    (let [g {:appid 2 :versions [{:id "public" :date "2024-12-22"}
                                 {:id "compatibility" :date "2024-12-22"}
                                 {:id "older" :date "2024-11-04"}]}]
      (is (= ["public" "compatibility" "older"] (mapv :id (catalog/versions g))))))
  (testing "a missing date sorts last instead of throwing"
    (let [g {:appid 3 :versions [{:id "undated"} {:id "dated" :date "2020-01-01"}]}]
      (is (= ["dated" "undated"] (mapv :id (catalog/versions g)))))))

;; ---------------------------------------------------------------------------
;; where the refresh points
;;
;; refresh! has been able to fetch a catalog since it was written; nothing ever
;; told it where from. The repo is public now, so the raw endpoint on the default
;; branch IS the distribution channel -- regenerating resources/catalog.edn and
;; pushing it updates every installed copy, with no release required.

(deftest the-catalog-url-points-at-the-repos-raw-endpoint
  (let [url catalog/catalog-url]
    (is (str/starts-with? url "https://")
        "plain http would let anyone on the path swap the catalog")
    (is (str/includes? url "raw.githubusercontent.com")
        "the raw endpoint serves the file itself; the repo page serves HTML")
    (is (str/includes? url "/halgari/reliquary/"))
    (is (str/ends-with? url "/resources/catalog.edn")
        "and it must name the catalog, not a directory listing")))

(deftest the-catalog-url-tracks-a-branch-not-a-tag
  (testing "a tag would freeze the catalog at whatever shipped with that
            release, which defeats the point: the catalog is meant to move
            without anyone cutting a build"
    (is (str/includes? catalog/catalog-url "/main/"))))

(deftest a-catalog-identical-to-what-we-have-is-not-re-applied
  (testing "The freshness guard asked `is this the newest?`, and a fetch equal to
            what we already have passes that: `newest` returns its first argument
            on a tie, and the fetched value is EQUAL to it, so the comparison held
            and the catalog was re-cached and re-applied.

            That was invisible while nothing called refresh!. Now that startup
            does, it meant a 42KB disk write and a full library re-render on every
            single launch, for a document that had not changed. The guard has to
            ask the question it meant: is this DIFFERENT, and newer?"
    (with-clean-sysprop-cache
     (fn []
      (with-tmp-data-dir
       (fn []
        ;; serve exactly the catalog that is already bundled, which is the real
        ;; situation the moment the repo's copy and the shipped copy agree
        (let [body (slurp (io/resource "catalog.edn"))
              server (local-http-server "/same" 200 (.getBytes body "UTF-8"))
              fired (promise)]
          (try
            (catalog/refresh! (server-url server "/same") (fn [c] (deliver fired c)))
            (is (= :not-fired (deref fired 4000 :not-fired))
                "on-done must not fire for a catalog we already have")
            (finally (.stop server 0))))))))))

(deftest a-genuinely-newer-catalog-is-still-applied
  (testing "the tightened guard must not break the case it exists for"
    (with-clean-sysprop-cache
     (fn []
      (with-tmp-data-dir
       (fn []
        (let [newer (str/replace minimal "2026-01-01T00:00:00Z" "2099-06-01T00:00:00Z")
              server (local-http-server "/newer" 200 (.getBytes newer "UTF-8"))
              fired (promise)]
          (try
            (catalog/refresh! (server-url server "/newer") (fn [c] (deliver fired c)))
            (let [c (deref fired 6000 :timeout)]
              (is (not= :timeout c) "a newer catalog must still reach on-done")
              (is (= "2099-06-01T00:00:00Z" (:generated c))))
            (finally (.stop server 0))))))))))

;; ---------------------------------------------------------------------------
;; executable hashes
;;
;; Which version an install IS gets decided by hashing its executable, so the
;; catalog carries the hash each version's executable should have. A manifest is
;; immutable, so this is resolved once by the catalog tool and never fetched
;; again -- the app identifies an install offline, in milliseconds, before the
;; user has even signed in.

(deftest a-version-carries-its-executable-hashes
  (let [c (catalog/parse
           (pr-str {:schema-version 1 :generated "2026-01-01T00:00:00Z"
                    :games [{:appid 1 :title "G"
                             :versions [{:id "v" :label "V" :branch "public"
                                         :depots [{:depot-id 1 :manifest-gid "9"}]
                                         :executables {"Game.exe" "aabb"}}]}]}))]
    (is (= {"Game.exe" "aabb"}
           (-> c :games first :versions first :executables)))))

(deftest a-version-without-executable-hashes-still-parses
  (testing "the catalog predates this field, and an app that refused to load one
            without it would refuse to load the copy it ships with"
    (let [c (catalog/parse
             (pr-str {:schema-version 1 :generated "2026-01-01T00:00:00Z"
                      :games [{:appid 1 :title "G"
                               :versions [{:id "v" :label "V" :branch "public"
                                           :depots [{:depot-id 1 :manifest-gid "9"}]}]}]}))]
      (is (some? c))
      (is (empty? (-> c :games first :versions first :executables))))))

(deftest executable-entries-that-are-not-hashes-are-dropped
  (testing "this map decides which version a user is told they have, and it
            arrives over the network"
    (let [c (catalog/parse
             (pr-str {:schema-version 1 :generated "2026-01-01T00:00:00Z"
                      :games [{:appid 1 :title "G"
                               :versions [{:id "v" :label "V" :branch "public"
                                           :depots [{:depot-id 1 :manifest-gid "9"}]
                                           :executables {"Game.exe" "aabb"
                                                         "" "cc"
                                                         "Bad.exe" ""
                                                         "Worse.exe" 42}}]}]}))]
      (is (= {"Game.exe" "aabb"}
             (-> c :games first :versions first :executables))))))
