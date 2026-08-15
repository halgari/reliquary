;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.progress-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.progress :as progress])
  (:import (java.nio.file Files)))

(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f d)
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest a-missing-progress-file-reads-as-empty
  (with-tmp (fn [d] (is (= {} (progress/load d 220 "public"))))))

(deftest progress-round-trips
  (with-tmp (fn [d]
              (progress/save! d 220 "public" {"a.bsa" #{0 1 3}})
              (is (= {"a.bsa" #{0 1 3}} (progress/load d 220 "public"))))))

(deftest a-corrupt-progress-file-reads-as-empty-rather-than-throwing
  (testing "a bad progress file costs a re-download; a crash costs the install"
    (with-tmp (fn [d]
                (let [f (progress/progress-file d 220 "public")]
                  (.mkdirs (.getParentFile f))
                  (spit f "{:unbalanced "))
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
