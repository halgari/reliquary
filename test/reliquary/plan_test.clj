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
