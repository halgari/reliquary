;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.progress-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.progress :as progress])
  (:import (java.nio.file Files)))

(def ^:private mf
  "The manifest set every save/load below is recorded against, unless a test is
   deliberately changing it."
  {1 "gid-one" 2 "gid-two"})

(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f d)
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest a-missing-progress-file-reads-as-empty
  (with-tmp (fn [d] (is (= {} (progress/load d 220 "public" mf))))))

(deftest progress-round-trips
  (with-tmp (fn [d]
              (progress/save! d 220 "public" mf {"a.bsa" #{0 1 3}})
              (is (= {"a.bsa" #{0 1 3}} (progress/load d 220 "public" mf))))))

(deftest a-corrupt-progress-file-reads-as-empty-rather-than-throwing
  (testing "a bad progress file costs a re-download; a crash costs the install"
    (with-tmp (fn [d]
                (let [f (progress/progress-file d 220 "public")]
                  (.mkdirs (.getParentFile f))
                  (spit f "{:unbalanced "))
                (is (= {} (progress/load d 220 "public" mf)))))))

(deftest save-is-atomic
  (with-tmp (fn [d]
              (progress/save! d 220 "public" mf {"a" #{0}})
              (progress/save! d 220 "public" mf {"a" #{0 1}})
              (is (= 1 (count (filter #(.isFile %) (.listFiles (.getParentFile (progress/progress-file d 220 "public")))))))
              (is (= {"a" #{0 1}} (progress/load d 220 "public" mf))))))

(deftest versions-do-not-share-a-progress-file
  (with-tmp (fn [d]
              (progress/save! d 220 "public" mf {"a" #{0}})
              (progress/save! d 220 "1_5_97" mf {"a" #{7}})
              (is (= {"a" #{0}} (progress/load d 220 "public" mf))))))

;; ---- the manifest binding (C1) ---------------------------------------------

(deftest progress-recorded-against-other-manifests-is-discarded
  (testing "a chunk index means nothing except against the manifest that
            produced it. appid+version-id do not pin that manifest -- `public`
            moves, and catalog/refresh! can swap any version's manifest-gid
            between two runs -- so honouring these indices against a different
            build mixes two builds into one install that afterwards looks
            perfectly clean: there is no verification pass, and the per-chunk
            SHA-1 never sees a chunk that was skipped as already done."
    (with-tmp
      (fn [d]
        (progress/save! d 220 "public" {1 "gid-one" 2 "gid-two"} {"a" #{0 1 2}})
        (is (= {"a" #{0 1 2}} (progress/load d 220 "public" {1 "gid-one" 2 "gid-two"}))
            "the same manifests must resume, or every resume is dead")
        (is (= {} (progress/load d 220 "public" {1 "gid-one" 2 "gid-CHANGED"}))
            "one depot re-pointed is a different build")
        (is (= {} (progress/load d 220 "public" {1 "gid-one"}))
            "a depot dropped from the version is a different build")
        (is (= {} (progress/load d 220 "public" {1 "gid-one" 2 "gid-two" 3 "gid-new"}))
            "a depot added to the version is a different build")
        (is (= {} (progress/load d 220 "public" {}))
            "no identity at all cannot be assumed to match")))))

(deftest a-progress-file-without-a-manifest-stamp-is-discarded
  (testing "the pre-C1 file format, and any hand-written one: nothing in it
            identifies the build those bytes came from, which is precisely the
            case that must not resume"
    (with-tmp
      (fn [d]
        (let [f (progress/progress-file d 220 "public")]
          (.mkdirs (.getParentFile f))
          (spit f (pr-str {"a" #{0 1 2}})))
        (is (= {} (progress/load d 220 "public" mf)))))))

(deftest the-manifest-fingerprint-does-not-care-how-the-numbers-were-typed
  (testing "the CM and the catalog both hand out uint64s as strings sometimes
            and numbers others; a resume must not be discarded over that"
    (is (= (progress/manifest-fingerprint {1 "77" 2 "88"})
           (progress/manifest-fingerprint {(long 1) 77 (long 2) 88})))
    (with-tmp
      (fn [d]
        (progress/save! d 220 "public" {1 12345678901234567} {"a" #{0}})
        (is (= {"a" #{0}} (progress/load d 220 "public" {1 "12345678901234567"})))))))

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
