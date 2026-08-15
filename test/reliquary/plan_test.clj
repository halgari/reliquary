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

;; --- review round 1 fixes -----------------------------------------------

(deftest a-non-numeric-uint64-field-is-a-categorized-error
  (testing "Long/parseLong must not be allowed to escape as a raw NumberFormatException"
    (let [ex (try (plan/build (manifest (entry "a" "not-a-number" "s" [["c" 0 10]])))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :incorrect (:reliquary/error (ex-data ex))))
      (is (= "not-a-number" (:value (ex-data ex)))))))

(deftest an-out-of-range-uint64-field-is-a-categorized-error
  (testing "a legal uint64 >= 2^63 overflows Long/parseLong; a hostile or unusual manifest can carry one"
    (let [huge "99999999999999999999"
          ex   (try (plan/build (manifest (entry "a" 10 "s" [["c" huge 10]])))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :incorrect (:reliquary/error (ex-data ex))))
      (is (= huge (:value (ex-data ex)))))))

(deftest chunks-are-ordered-by-offset-not-by-manifest-order
  (testing ":index is what the resume file records, so it must follow offset order, not list order"
    (let [p  (plan/build (manifest (entry "a.bsa" 30 "sha-a"
                                          [["c2" 20 10] ["c0" 0 10] ["c1" 10 10]])))
          cs (-> p :files first :chunks)]
      (is (= [0 10 20] (mapv :offset cs)))
      (is (= [0 1 2] (mapv :index cs)))
      (is (= ["c0" "c1" "c2"] (mapv :id cs))))))

(deftest an-empty-or-dot-path-is-rejected
  (testing "both resolve to the destination directory itself, not to a real file"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "" 10 "s" [["c" 0 10]])))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "." 10 "s" [["c" 0 10]])))))))

(deftest a-nul-byte-in-the-path-is-rejected
  (testing "a NUL byte would crash the writer uncategorized rather than error cleanly"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry (str "a" (char 0) "b") 10 "s" [["c" 0 10]])))))))

(deftest an-empty-sha-content-never-matches-another-empty-sha-content
  (testing "an empty digest must behave like a missing one, or unrelated files collapse into copies"
    (let [p (plan/build (manifest (entry "a.bsa" 10 "" [["c0" 0 10]])
                                  (entry "b.bsa" 10 "" [["c1" 0 10]])))]
      (is (= ["a.bsa" "b.bsa"] (mapv :path (:files p))) "both remain real files")
      (is (= [] (:copies p))))))

(deftest same-sha-content-at-two-different-sizes-is-planned-as-two-files
  (testing "identical content cannot have two lengths, so a size conflict means this is NOT a copy -- a
            remote-controlled sentinel/all-zero digest at differing sizes must not abort the whole
            download; each side is simply fetched on its own"
    (let [p (plan/build (manifest (entry "a.bsa" 10 "same" [["c0" 0 10]])
                                  (entry "b.bsa" 20 "same" [["c1" 0 20]])))]
      (is (= #{"a.bsa" "b.bsa"} (set (mapv :path (:files p)))) "both are real files")
      (is (= [] (:copies p)) "neither is a copy -- their content is not proven identical"))))

(deftest a-duplicate-path-never-produces-a-self-copy
  (testing "the same path listed twice under the same sha and size would otherwise plan a copy of a
            path onto itself; Files/copy with REPLACE_EXISTING would truncate the source before
            reading it, so this is rejected as a malformed manifest instead"
    (is (thrown? clojure.lang.ExceptionInfo
                 (plan/build (manifest (entry "a.bsa" 10 "same" [["c0" 0 10]])
                                       (entry "a.bsa" 10 "same" [["c0" 0 10]])))))
    (let [ex (try (plan/build (manifest (entry "a.bsa" 10 "same" [["c0" 0 10]])
                                        (entry "a.bsa" 10 "same" [["c0" 0 10]])))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :incorrect (:reliquary/error (ex-data ex))))
      (is (= "a.bsa" (:path (ex-data ex)))))))

;; --- properties ---------------------------------------------------------

;; Names are generated distinctly (gen/vector-distinct) across every file in
;; one manifest. Two independently-random gen/such-that names CAN collide at
;; small sizes (single alphanumeric characters aren't rare), and a same-name
;; collision producing the same "sha-<n>" for two files this generator
;; otherwise gives different random sizes would trip the same-path-twice
;; rejection -- an unrelated, valid rejection that would make these
;; properties spuriously ERROR rather than exercise the tiling logic they
;; exist to check.
(defn- gen-file-named [n]
  (gen/let [sizes (gen/vector (gen/choose 1 5000) 1 8)]
    (let [offsets (reductions + 0 sizes)
          total   (reduce + sizes)]
      (entry n total (str "sha-" n)
             (mapv (fn [i off len] [(str "c" n i) off len])
                   (range) offsets sizes)))))

(defn- gen-names [lo hi]
  (gen/vector-distinct (gen/such-that seq gen/string-alphanumeric)
                       {:min-elements lo :max-elements hi}))

(defn- gen-files [lo hi]
  (gen/bind (gen-names lo hi)
            (fn [names] (apply gen/tuple (map gen-file-named names)))))

(defspec chunks-tile-every-file-exactly 80
  (prop/for-all [files (gen-files 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (every? (fn [f]
                (let [cs (:chunks f)]
                  (and (= 0 (:offset (first cs)))
                       (= (:size f) (+ (:offset (last cs)) (:cb-original (last cs))))
                       (every? (fn [[a b]] (= (+ (:offset a) (:cb-original a)) (:offset b)))
                               (partition 2 1 cs)))))
              (:files p)))))

(defspec download-bytes-equals-the-sum-of-fetched-chunks 80
  (prop/for-all [files (gen-files 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (= (:download-bytes p)
         (reduce + 0 (for [f (:files p) c (:chunks f)] (:cb-original c)))))))

(defspec total-chunks-matches-the-fetch-list 80
  (prop/for-all [files (gen-files 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files files}])]
      (= (:total-chunks p) (plan/chunk-count p)
         (count (for [f (:files p) c (:chunks f)] c))))))

;; gen-file-named always hands `build` chunks already in offset order
;; (offsets are built via `reductions` over the same seq the chunks are
;; constructed from), and none of the three properties above ever compares a
;; chunk's returned :offset back to what the *manifest* declared for that
;; specific :id -- they only check that the returned chunks are
;; self-consistently contiguous, and that cb-original sums match. Both of
;; those hold trivially for ANY cumulative-sum construction, in any order,
;; over any set of lengths -- so an implementation that ignores :offset
;; entirely and just cumulative-sums :cb-original in list order passes all
;; three above. This generator shuffles the chunk list independently of
;; declared offset, and the property below checks each id's offset against
;; what was actually declared, so an implementation that ignores :offset
;; cannot pass it.
(defn- gen-file-shuffled-named [n]
  (gen/let [sizes (gen/vector (gen/choose 1 5000) 1 8)]
    (let [offsets  (reductions + 0 sizes)
          total    (reduce + sizes)
          declared (mapv (fn [i off len] [(str "c" n i) off len])
                         (range) offsets sizes)]
      (gen/fmap (fn [shuffled]
                  {:file     (entry n total (str "sha-" n) shuffled)
                   :expected (into {} (map (fn [[id off _]] [id off])) declared)})
                (gen/shuffle declared)))))

(defn- gen-files-shuffled [lo hi]
  (gen/bind (gen-names lo hi)
            (fn [names] (apply gen/tuple (map gen-file-shuffled-named names)))))

(defspec chunk-offsets-match-what-the-manifest-declared 80
  (prop/for-all [specs (gen-files-shuffled 1 6)]
    (let [p (plan/build [{:depot-id 1 :key-hex "k" :files (mapv :file specs)}])]
      (every? true?
              (map (fn [{:keys [expected]} f]
                     (every? (fn [c] (= (:offset c) (get expected (:id c)))) (:chunks f)))
                   specs (:files p))))))
