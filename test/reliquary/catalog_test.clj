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
