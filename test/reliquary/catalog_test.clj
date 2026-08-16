(ns reliquary.catalog-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.catalog :as catalog]
            [reliquary.config :as config])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
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
  ;; the real ~/.local/share/reliquary/catalog.json is the
  ;; reliquary.data-dir JVM property the :test alias sets, consulted
  ;; directly by config/data-dir with no thread-locality involved at all.
  (let [real-data-dir (io/file (System/getProperty "user.home") ".local" "share" "reliquary")
        fresh-json    (str/replace minimal "2026-01-01T00:00:00Z" "2099-06-01T00:00:00Z")
        cache-file    (io/file (config/data-dir) "catalog.json")]
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
      (is (not (.exists (io/file real-data-dir "catalog.json")))
          "the real ~/.local/share/reliquary/catalog.json must remain untouched")
      ;; this test's whole point is proving the sysprop path is reachable and
      ;; writable -- but that makes target/test-state/data a shared,
      ;; process-wide location any other test using the DEFAULT (unbound)
      ;; data-dir also reads from (e.g. cli-test's use of catalog/load!).
      ;; Leaving a fabricated, artificially-future-dated catalog sitting
      ;; there would silently shadow the bundled catalog for every test that
      ;; runs after this one in the same JVM. Clean up regardless of outcome.
      (finally (io/delete-file cache-file true)))))
