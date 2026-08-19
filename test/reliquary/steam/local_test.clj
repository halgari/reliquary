;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.steam.local-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.local :as local])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir ^File []
  (.toFile (Files/createTempDirectory "reliquary-local" (make-array FileAttribute 0))))

(defn- rm-rf [^File d]
  (when (.exists d) (run! io/delete-file (reverse (file-seq d)))))

;; Two versions of one game, differing in the executable and one archive. Paths
;; use forward slashes because reliquary.steam.manifest already normalises the
;; backslashes Steam ships (see its `entries`).

(def ^:private v-latest
  {:version {:id "public" :label "Latest"}
   :files {"SkyrimSE.exe"        {:size 37157144 :sha "aaaa1111"}
           "SkyrimSELauncher.exe" {:size 4713472 :sha "bbbb2222"}
           "Data/Skyrim.esm"     {:size 100 :sha "cccc3333"}
           "Skyrim_Default.ini"  {:size 10 :sha "dddd4444"}}})

(def ^:private v-1-5-97
  {:version {:id "1_5_97" :label "1.5.97"}
   :files {"SkyrimSE.exe"        {:size 29000000 :sha "eeee5555"}
           "SkyrimSELauncher.exe" {:size 4713472 :sha "bbbb2222"}
           "Data/Skyrim.esm"     {:size 100 :sha "ffff6666"}
           "Skyrim_Default.ini"  {:size 10 :sha "dddd4444"}}})

(def ^:private candidates [v-latest v-1-5-97])

;; ---------------------------------------------------------------------------
;; identifying what is on disk

(deftest a-stock-install-is-identified-exactly
  (let [local {"SkyrimSE.exe" "eeee5555"
               "SkyrimSELauncher.exe" "bbbb2222"
               "Data/Skyrim.esm" "ffff6666"
               "Skyrim_Default.ini" "dddd4444"}
        r (local/identify local candidates)]
    (is (= "1_5_97" (-> r :version :id)))
    (is (= :exact (:confidence r)) "every hashed file matched, executables included")))

(deftest a-modded-install-is-still-identified-from-its-executable
  (testing "This is the case that decides the whole design. A modded Skyrim has
            changed .esm/.bsa files and files Steam never shipped -- and even an
            UNMODDED one does: this machine's install carries Debug.log, Mods/ and
            Creations/. Scoring every file equally would call a heavily modded
            1.5.97 'no known version'. The executable is the version, and is
            weighted to say so."
    (let [local {"SkyrimSE.exe" "eeee5555"
                 "SkyrimSELauncher.exe" "bbbb2222"
                 ;; a mod replaced the master and added its own plugin
                 "Data/Skyrim.esm" "MODIFIED-BY-A-MOD"
                 "Data/SomeMod.esp" "NOT-IN-ANY-MANIFEST"}
          r (local/identify local candidates)]
      (is (= "1_5_97" (-> r :version :id)))
      (is (= :likely (:confidence r))
          "the executables agree, other files do not -- likely, not exact")
      (is (pos? (:mismatched (:evidence r)))))))

(deftest the-closest-version-wins-when-none-matches-completely
  (testing "a half-applied switch: the launcher and ini already came from Latest,
            the main executable has not been replaced yet"
    (let [local {"SkyrimSE.exe" "aaaa1111"      ; Latest
                 "SkyrimSELauncher.exe" "bbbb2222" ; both
                 "Data/Skyrim.esm" "ffff6666"}  ; still 1.5.97
          r (local/identify local candidates)]
      (is (= "public" (-> r :version :id))
          "the main executable decides; it is the file that is the version"))))

(deftest no-executable-match-is-not-a-guess
  (testing "an executable from a build the catalog does not carry. Naming the
            closest version anyway would tell the user they have something they
            do not -- and this is the state a user is in right after Steam
            updates the game."
    (let [local {"SkyrimSE.exe" "UNKNOWN-BUILD"
                 "SkyrimSELauncher.exe" "ALSO-UNKNOWN"
                 ;; non-executables agree with 1.5.97, and must not carry the day
                 "Data/Skyrim.esm" "ffff6666"
                 "Skyrim_Default.ini" "dddd4444"}
          r (local/identify local candidates)]
      (is (nil? (:version r)))
      (is (= :unknown (:confidence r))))))

(deftest identifying-nothing-yields-nothing
  (is (= :unknown (:confidence (local/identify {} candidates))))
  (is (nil? (:version (local/identify {} [])))))

;; ---------------------------------------------------------------------------
;; which executables to hash

(deftest the-executables-are-what-identification-hashes
  (testing "identification must not read 16GB to answer a question two files
            settle. `exe-paths` is the shortlist, taken from the target
            manifest's own file list rather than from the directory, so a stray
            .exe a mod dropped in is never mistaken for the game."
    (is (= ["SkyrimSE.exe" "SkyrimSELauncher.exe"]
           (sort (local/exe-paths (:files v-latest)))))))

(deftest executable-matching-is-case-insensitive
  (testing "depot paths are Windows paths; a manifest may say .EXE"
    (is (= ["Game.EXE"] (local/exe-paths {"Game.EXE" {:sha "x"} "data.bsa" {:sha "y"}})))))

;; ---------------------------------------------------------------------------
;; hashing real files

(deftest a-file-hashes-to-its-sha1
  (let [d (tmp-dir)]
    (try
      (let [f (io/file d "hello.txt")]
        (spit f "abc")
        ;; the known SHA-1 of "abc"
        (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (local/sha1-file f))))
      (finally (rm-rf d)))))

(deftest hashing-streams-rather-than-loading-the-file
  (testing "a depot file can be several GB; slurping one to hash it would put the
            whole thing in memory. This only proves the streaming path handles
            more than one buffer's worth, which is what would break if someone
            'simplified' it back to a single read."
    (let [d (tmp-dir)]
      (try
        (let [f (io/file d "big.bin")
              chunk (byte-array 64000)]
          (with-open [out (io/output-stream f)]
            (dotimes [_ 40] (.write out chunk)))   ; ~2.5 MB of zeros
          (is (= 2560000 (.length f)))
          (is (= 40 (count (local/sha1-file f)))
              "a SHA-1 is 40 hex characters however many buffers it took"))
        (finally (rm-rf d))))))

(deftest a-missing-file-hashes-to-nil-not-an-exception
  (testing "the manifest names files the install may simply not have -- a
            half-applied switch, or a file a user deleted"
    (is (nil? (local/sha1-file (io/file "/nonexistent/nope.exe"))))))

(deftest hashing-reports-progress-and-can-be-stopped
  (let [d (tmp-dir)]
    (try
      (doseq [n ["a.bin" "b.bin" "c.bin"]] (spit (io/file d n) n))
      (let [seen (atom [])
            r (local/hash-paths d ["a.bin" "b.bin" "c.bin"]
                                {:on-progress (fn [p] (swap! seen conj p))})]
        (is (= 3 (count r)) "every path hashed")
        (is (= 3 (count @seen)) "and every one reported")
        (is (= [1 2 3] (map :done @seen)))
        (is (every? #(= 3 (:total %)) @seen)))
      (testing "and an abort predicate stops it part way, so a user closing the
                panel does not leave a thread hashing a 16GB install"
        (let [n (atom 0)
              r (local/hash-paths d ["a.bin" "b.bin" "c.bin"]
                                  {:abort? (fn [] (>= @n 1))
                                   :on-progress (fn [_] (swap! n inc))})]
          (is (< (count r) 3))))
      (finally (rm-rf d)))))

(deftest hashing-skips-paths-that-are-not-there
  (let [d (tmp-dir)]
    (try
      (spit (io/file d "here.bin") "x")
      (let [r (local/hash-paths d ["here.bin" "gone.bin"] {})]
        (is (= ["here.bin"] (keys r))
            "a path with no file contributes nothing rather than a nil hash"))
      (finally (rm-rf d)))))

(deftest relative-paths-use-forward-slashes
  (testing "so they compare directly against manifest paths, which
            reliquary.steam.manifest has already normalised from Steam's
            backslashes"
    (let [d (tmp-dir)]
      (try
        (.mkdirs (io/file d "Data" "Sub"))
        (spit (io/file d "Data" "Sub" "f.esp") "x")
        (let [r (local/hash-paths d ["Data/Sub/f.esp"] {})]
          (is (= ["Data/Sub/f.esp"] (keys r)))
          (is (not-any? #(str/includes? % "\\") (keys r))))
        (finally (rm-rf d))))))

;; ---------------------------------------------------------------------------
;; building the index from a parsed manifest

(deftest a-manifest-becomes-a-file-index
  (testing "reliquary.steam.manifest calls the filename :name, not :path. Reading
            :path instead returns nil for every entry, which does not throw -- it
            silently reports that a 16GB game contains no executables. That cost a
            debugging cycle against live Steam, so the conversion lives here once,
            with a test, rather than at each call site."
    (let [files [{:name "SkyrimSE.exe" :size "37157144" :sha-content "aaaa"}
                 {:name "Data/Skyrim.esm" :size "100" :sha-content "bbbb"}]
          idx (local/index-files files)]
      (is (= #{"SkyrimSE.exe" "Data/Skyrim.esm"} (set (keys idx))))
      (is (= {:size 37157144 :sha "aaaa"} (get idx "SkyrimSE.exe"))
          "size becomes a long, because the switch estimate adds them up")
      (is (= ["SkyrimSE.exe"] (local/exe-paths idx))))))

(deftest index-entries-with-no-real-content-hash-are-skipped
  (testing "a directory entry carries Steam's all-zero sentinel rather than a
            digest -- see reliquary.plan/zero-sha. Treating that as a hash would
            collapse every directory into one 'file' that always mismatches."
    (let [idx (local/index-files
               [{:name "Data" :size "0" :sha-content "0000000000000000000000000000000000000000"}
                {:name "empty" :size "0" :sha-content ""}
                {:name "nohash" :size "5"}
                {:name "real.exe" :size "5" :sha-content "abcd"}])]
      (is (= ["real.exe"] (keys idx))))))

(deftest indexes-from-several-depots-merge
  (testing "a version spans depots -- Skyrim SE's executable is alone in depot
            489833 while its archives are in 489831 and 489832 -- so identifying
            a version means one index across all of them"
    (let [idx (local/index-files (concat [{:name "a.bsa" :size "1" :sha-content "aa"}]
                                         [{:name "SkyrimSE.exe" :size "2" :sha-content "bb"}]))]
      (is (= 2 (count idx))))))

;; ---------------------------------------------------------------------------
;; the local chunk index
;;
;; Measured between Skyrim SE's public and 1.6.1130 manifests: 1 of 46 files is
;; reusable, and 15853 of 16074 chunks are -- 14.99 GB against 0.21 GB. So the
;; unit that matters is the chunk, and the index is what makes a chunk already on
;; disk findable.
;;
;; Steam's chunk boundaries come from a MANIFEST; they cannot be recomputed from
;; local bytes. So the index is built with the INSTALLED version's manifest as
;; the boundary map, which is why identification has to happen first.

(defn- write-bytes! [f ^bytes b]
  (io/make-parents f)
  (with-open [out (io/output-stream f)] (.write out b)))

(defn- bytes-of [^String s] (.getBytes s "UTF-8"))

(deftest a-byte-range-hashes-independently-of-the-rest-of-the-file
  (let [d (tmp-dir)]
    (try
      (let [f (io/file d "f.bin")]
        (write-bytes! f (bytes-of "XXXabcYYY"))
        ;; the known SHA-1 of "abc", read from the middle of a larger file
        (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (local/sha1-range f 3 3))))
      (finally (rm-rf d)))))

(deftest a-range-past-the-end-of-the-file-is-nil
  (testing "a local file shorter than the manifest says -- a truncated download,
            or a file a user replaced with a smaller one"
    (let [d (tmp-dir)]
      (try
        (let [f (io/file d "short.bin")]
          (write-bytes! f (bytes-of "abc"))
          (is (nil? (local/sha1-range f 0 100))))
        (finally (rm-rf d))))))

(def ^:private sha-abc "a9993e364706816aba3e25717850c26c9cd0d89d")
(def ^:private sha-xyz "66b27417d37e024c46526c2f6d358a754fc552f3")

;; Chunk offsets are STRINGS in a real manifest -- a uint64 that survived
;; protobuf -- while cb-original is an Integer. The fixtures below carry those
;; types deliberately: an earlier version used tidy numbers throughout, passed
;; every test, and then threw ClassCastException on the first real manifest.

(deftest the-index-records-chunks-whose-content-matches-their-id
  (let [d (tmp-dir)]
    (try
      (write-bytes! (io/file d "a.bin") (bytes-of "abcxyz"))
      (let [files [{:name "a.bin"
                    :chunks [{:id sha-abc :offset "0" :cb-original 3}
                             {:id sha-xyz :offset "3" :cb-original 3}]}]
            idx (local/chunk-index d files {})]
        (is (= #{sha-abc sha-xyz} (set (keys idx))))
        (is (= {:path "a.bin" :offset 0 :size 3} (get idx sha-abc))))
      (finally (rm-rf d)))))

(deftest a-chunk-whose-content-does-not-match-is-not-indexed
  (testing "this is the verification pass, not a bookkeeping read: a file a mod
            overwrote hashes to something else, and claiming we hold that chunk
            would corrupt whatever we copied it into"
    (let [d (tmp-dir)]
      (try
        (write-bytes! (io/file d "a.bin") (bytes-of "MODDED"))
        (let [idx (local/chunk-index d [{:name "a.bin"
                                         :chunks [{:id sha-abc :offset "0" :cb-original 3}]}]
                                     {})]
          (is (empty? idx)))
        (finally (rm-rf d))))))

(deftest a-missing-file-contributes-no-chunks
  (let [d (tmp-dir)]
    (try
      (let [idx (local/chunk-index d [{:name "gone.bin"
                                       :chunks [{:id sha-abc :offset "0" :cb-original 3}]}]
                                   {})]
        (is (empty? idx)))
      (finally (rm-rf d)))))

(deftest indexing-reports-progress-in-bytes-and-can-be-stopped
  (testing "the design's hashing bar is a percentage, and files are wildly uneven
            in size -- counting files would jump from 2% to 98% on one .bsa"
    (let [d (tmp-dir)]
      (try
        (write-bytes! (io/file d "a.bin") (bytes-of "abcxyz"))
        (let [seen (atom [])]
          (local/chunk-index d [{:name "a.bin"
                                 :chunks [{:id sha-abc :offset "0" :cb-original 3}
                                          {:id sha-xyz :offset "3" :cb-original 3}]}]
                             {:on-progress (fn [p] (swap! seen conj p))})
          (is (= [3 6] (map :done @seen)) "progress counts BYTES hashed")
          (is (every? #(= 6 (:total %)) @seen)))
        (testing "and aborting leaves a partial index rather than running on"
          (let [idx (local/chunk-index d [{:name "a.bin"
                                           :chunks [{:id sha-abc :offset "0" :cb-original 3}
                                                    {:id sha-xyz :offset "3" :cb-original 3}]}]
                                       {:abort? (constantly true)})]
            (is (empty? idx))))
        (finally (rm-rf d))))))

(deftest a-chunk-is-reusable-from-anywhere-on-disk
  (testing "content addressing is the whole point. 98.6% reuse comes from chunks
            found wherever they happen to sit -- a chunk the target wants in
            Data/big.bsa may already be on disk inside a completely different
            file, and copying it locally beats fetching it."
    (let [d (tmp-dir)]
      (try
        (write-bytes! (io/file d "somewhere-else.bin") (bytes-of "PADabc"))
        (let [idx (local/chunk-index d [{:name "somewhere-else.bin"
                                         :chunks [{:id sha-abc :offset "3" :cb-original 3}]}]
                                     {})
              target [{:name "Data/wanted.bsa"
                       :chunks [{:id sha-abc :offset "0" :cb-original 3}
                                {:id "deadbeef" :offset "3" :cb-original 7}]}]
              p (local/plan-chunks idx target)]
          (is (= 1 (count (:have p))))
          (is (= "somewhere-else.bin" (-> p :have first :from :path))
              "and the plan says where to copy it from")
          (is (= ["deadbeef"] (map :id (:fetch p))))
          (is (= 7 (:bytes p)) "only the chunks actually fetched are counted"))
        (finally (rm-rf d))))))

(deftest a-switch-with-nothing-to-fetch-is-a-no-op
  (testing "re-running a completed switch, which is what makes an interrupted one
            safe to simply run again"
    (let [idx {sha-abc {:path "a.bin" :offset 0 :size 3}}
          p (local/plan-chunks idx [{:name "a.bin" :chunks [{:id sha-abc :offset "0" :cb-original 3}]}])]
      (is (empty? (:fetch p)))
      (is (zero? (:bytes p))))))
