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
