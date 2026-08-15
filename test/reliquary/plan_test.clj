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
    (is (nil? (:key-hex f)) "the key is a secret; it travels via keys-by-depot, not the plan")
    (is (= [0 1] (mapv :index (:chunks f))) "the index is what the resume file records")
    (is (= [0 2048] (mapv :offset (:chunks f))) "offsets are longs, not strings")))

(deftest directories-become-dirs-not-files
  (let [p (plan/build (manifest (entry "Data" 0 nil [] :flags plan/flag-directory)
                                (entry "Data/a.bsa" 10 "sha-a" [["c0" 0 10]])))]
    (is (= ["Data"] (:dirs p)))
    (is (= ["Data/a.bsa"] (mapv :path (:files p))))))

(deftest an-empty-file-is-created-not-skipped
  (testing "a real Steam manifest entry for an empty file still carries a content sha;
            a no-chunk no-sha entry is what a symlink/junk entry looks like structurally,
            and must NOT be conflated with a genuine empty file (see the symlink test below)"
    (let [p (plan/build (manifest (entry "empty.txt" 0 "sha-empty" [])))]
      (is (= ["empty.txt"] (mapv :path (:files p))))
      (is (= [] (-> p :files first :chunks)))
      (is (= 0 (:total-chunks p))))))

(deftest identical-content-is-fetched-once-and-copied
  (let [p (plan/build (manifest (entry "a.bsa" 4096 "same" [["c0" 0 4096]])
                                (entry "b.bsa" 4096 "same" [["c0" 0 4096]])))]
    (is (= ["a.bsa"] (mapv :path (:files p))) "only the first is downloaded")
    (is (= [{:path "b.bsa" :source "a.bsa" :size 4096}] (:copies p)))
    (is (= 4096 (:download-bytes p)) "the duplicate costs no bandwidth")
    (is (= 8192 (:disk-bytes p))     "but it does cost disk")
    (is (= 1 (:total-chunks p)))))

(deftest depots-merge-and-carry-their-own-keys
  (let [manifests [{:depot-id 221 :key-hex "k1" :files [(entry "a" 10 "sa" [["c0" 0 10]])]}
                   {:depot-id 222 :key-hex "k2" :files [(entry "b" 20 "sb" [["c1" 0 20]])]}]
        p (plan/build manifests)]
    (is (= #{"a" "b"} (set (mapv :path (:files p)))))
    (is (every? nil? (mapv :key-hex (:files p))) "keys never ride in the plan")
    (is (= {221 "k1" 222 "k2"} (plan/keys-by-depot manifests)) "keys travel via keys-by-depot instead")
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

;; --- carried findings from the foundation review -------------------------

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
          "a no-chunk no-sha entry is not a zero-byte regular file")
      (is (not (contains? (set (:dirs p)) "link"))
          "a symlink misclassified as a directory is just as wrong as one misclassified
           as an empty file -- the brief's :files assertion alone would pass either way"))))

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

;; --- live-gate findings ---------------------------------------------------

(deftest an-all-zero-sha-is-a-directory-sentinel-not-a-content-hash
  (testing "the literal value from a real Stardew Valley (413150) manifest: Steam's
            directory entry Content/Characters carries this exact sha, size 0, no chunks
            -- classifying it as a regular file plans a zero-byte FILE where the engine's
            own children later need a DIRECTORY, and the real download crashed exactly
            this way (\"cannot create Content/Characters (FileNotFoundException)\")"
    (let [zero-sha "0000000000000000000000000000000000000000"
          p (plan/build (manifest (entry "Content/Characters" 0 zero-sha []
                                         :flags plan/flag-directory)
                                  (entry "Content/Characters/Haley.xnb" 10 "sha-h"
                                         [["c0" 0 10]])))]
      (is (= ["Content/Characters"] (:dirs p))
          "the all-zero-sha entry must land in :dirs, not be treated as content")
      (is (not (contains? (set (mapv :path (:files p))) "Content/Characters"))
          "and must never appear in :files")
      (is (= ["Content/Characters/Haley.xnb"] (mapv :path (:files p)))))))

(deftest an-all-zero-sha-entry-without-the-directory-flag-is-skipped-not-planned
  (testing "even if Steam ever sends the sentinel without flag-directory set, it must
            still never become a zero-byte file -- absent is absent"
    (let [zero-sha "0000000000000000000000000000000000000000"
          p (plan/build (manifest (entry "Content/Characters" 0 zero-sha [])
                                  (entry "real.bsa" 10 "sha-r" [["c0" 0 10]])))]
      (is (= ["real.bsa"] (mapv :path (:files p))))
      (is (not (contains? (set (:dirs p)) "Content/Characters")))
      (is (= 1 (:skipped p))))))

(deftest two-all-zero-sha-entries-never-become-copies-of-one-another
  (testing "the exact hazard the finding named: a sentinel must never make two
            directory placeholders (or any two hash-less entries) copies of each other"
    (let [zero-sha "0000000000000000000000000000000000000000"
          p (plan/build (manifest (entry "Content/Characters" 0 zero-sha []
                                         :flags plan/flag-directory)
                                  (entry "Content/Maps" 0 zero-sha []
                                         :flags plan/flag-directory)))]
      (is (= ["Content/Characters" "Content/Maps"] (:dirs p)))
      (is (= [] (:files p)))
      (is (= [] (:copies p))
          "an all-zero sha must never be treated as a match, even against itself"))))

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
