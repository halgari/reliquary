;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.steam.local
  "What version the bytes on disk actually ARE, decided by hashing them.

   `reliquary.steam.installs` asks Steam which version is installed, by reading
   the manifest ids in appmanifest_<appid>.acf. That answer is cheap and usually
   right, and it is still only Steam's bookkeeping: it goes stale when a user
   swaps files by hand, it says nothing after a half-applied switch, and it is
   exactly what this app is about to make untrue. So the authority is the
   content. Hash the files, compare against the manifests, and let the bytes say.

   ## The executable is the version

   Identification is weighted almost entirely on executables, for a reason worth
   stating plainly: a real Skyrim install does not match any manifest file for
   file. Mods replace .esm and .bsa files and add plugins that were never in a
   depot -- and even a completely unmodded install here carries Debug.log, Mods/
   and Creations/, none of which Steam shipped. A scheme that scored every file
   equally would look at a heavily modded 1.5.97 and report no known version,
   which is the one answer that is useless.

   An executable, by contrast, is the thing a downgrade is FOR. It changes
   between builds, mod managers leave it alone, and there are two of them rather
   than forty thousand -- so identification reads a few tens of megabytes rather
   than sixteen gigabytes.

   ## Chunks, not files

   Identification is one thing; working out what a switch must MOVE is another,
   and it has to happen at chunk granularity. Measured between Skyrim SE's
   `public` and `1.6.1130` manifests, against the install on this machine:

     whole files:  1 of 46 files reusable   ->  fetch 14.99 GB
     chunks:       15853 of 16074 reusable  ->  fetch  0.21 GB

   Seventy times the difference, and it is not subtle: this game ships a handful
   of enormous .bsa archives, so almost every file differs between builds while
   almost every 1 MB chunk inside them does not. A file-level delta is therefore
   worthless here -- it costs the same full hashing pass and then downloads the
   game anyway. That is why this namespace stops at identification: the switch
   plan needs a local CHUNK index, which is a larger piece of work and the reason
   the design has a progress bar on its hashing step.

   ## Why this makes failure cheap

   Because the current state is derived by hashing rather than tracked in a
   progress file, an interrupted switch needs no repair and no backup. Whatever
   is on disk hashes to something; that something is compared against the target;
   the difference is what gets fetched. Run it again after a crash, a cancel, or
   a power cut and it converges on the target from wherever it actually is. There
   is nothing to roll back because nothing was recorded that could be wrong."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File InputStream)
           (java.security MessageDigest)))

;; ---------------------------------------------------------------------------
;; hashing

(def ^:private ^:const buffer-bytes (* 256 1024))

(defn- hex [^bytes digest]
  (let [sb (StringBuilder. (* 2 (alength digest)))]
    (dotimes [i (alength digest)]
      (.append sb (format "%02x" (bit-and 0xFF (long (aget digest i))))))
    (.toString sb)))

(defn sha1-file
  "Lowercase hex SHA-1 of a file's contents, or nil if it is not a readable file.

   Streams. `reliquary.steam.chunk/sha1-hex` takes a byte array, which is right
   for a 1 MB chunk and wrong here: depot files run to several GB and hashing one
   by slurping it would put the whole thing in memory.

   nil rather than a throw for a missing file, because the manifest names files
   the install may legitimately not have -- a half-applied switch, or one a user
   deleted."
  ^String [f]
  (let [^File file (io/as-file f)]
    (when (and file (.isFile file))
      (try
        (let [md (MessageDigest/getInstance "SHA-1")
              buf (byte-array buffer-bytes)]
          (with-open [^InputStream in (io/input-stream file)]
            (loop []
              (let [n (.read in buf)]
                (when (pos? n)
                  (.update md buf 0 n)
                  (recur)))))
          (hex (.digest md)))
        ;; unreadable mid-read: a permission change, or Steam moving it under us
        (catch Exception _ nil)))))

(defn- ->file
  "The File for a manifest-relative path under `root`.

   Manifest paths always use forward slashes -- reliquary.steam.manifest
   normalises Steam's backslashes on the way in -- and io/file splits on them
   correctly on both platforms."
  ^File [root path]
  (apply io/file root (str/split path #"/")))

(defn hash-paths
  "SHA-1 every one of `paths` under `root`, as {path sha}.

   `opts`:
     :on-progress  called with {:done n :total n :path p} after each file, for
                   the UI's hashing bar
     :abort?       0-arg predicate, checked before each file. Hashing a 16 GB
                   install takes minutes, and a user who closes the panel must
                   not leave a thread grinding through the rest of it.

   A path with no file contributes nothing rather than a nil hash: absent and
   'hashed to nil' are different facts, and only one of them belongs in a map
   that gets compared against a manifest."
  [root paths {:keys [on-progress abort?]}]
  (let [total (count paths)]
    (loop [[p & more] (seq paths) done 0 acc {}]
      (if (or (nil? p) (and abort? (abort?)))
        acc
        (let [sha (sha1-file (->file root p))
              done (inc done)]
          (when on-progress (on-progress {:done done :total total :path p}))
          (recur more done (cond-> acc sha (assoc p sha))))))))

;; ---------------------------------------------------------------------------
;; the file index

(def ^:private zero-sha
  "Steam's all-zero sentinel, which directory entries carry in place of a
   digest. Same value and same reason as reliquary.plan/zero-sha."
  "0000000000000000000000000000000000000000")

(defn index-files
  "A parsed manifest's `:files` as `{path {:size long :sha hex}}`, ready to
   compare against hashed local files. Entries from several depots can be
   concatenated first: one version spans them all -- Skyrim SE's executable sits
   alone in depot 489833 while its archives are in 489831 and 489832.

   The filename key is `:name`, NOT `:path`. Reading `:path` returns nil for
   every entry and does not throw: it silently reports that a 16 GB game contains
   no executables at all. That cost a debugging cycle against live Steam, which
   is why the conversion is here, once, instead of at each call site.

   Entries with no real content hash are dropped -- a directory carries the
   all-zero sentinel rather than a digest, and treating that as a hash would
   collapse every directory into one entry that can never match."
  [files]
  (into {}
        (keep (fn [{:keys [name size sha-content]}]
                (when (and (seq name) (seq sha-content) (not= sha-content zero-sha))
                  [name {:size (try (Long/parseLong (str size)) (catch Exception _ 0))
                         :sha  sha-content}])))
        files))

;; ---------------------------------------------------------------------------
;; identification

(defn- exe? [^String path]
  (str/ends-with? (str/lower-case path) ".exe"))

(defn exe-paths
  "The executables in a manifest's file index.

   Taken from the MANIFEST rather than by scanning the directory, deliberately:
   a mod or a tool may have dropped its own .exe into the install, and that file
   says nothing about which build of the game is there."
  [file-index]
  (into [] (filter exe?) (keys file-index)))

(defn- score
  "How well `local-hashes` matches one candidate's file index.

   Only files present in BOTH are judged. A file the local install does not have
   is not evidence against a version -- it is a file the switch will fetch."
  [local-hashes file-index]
  (reduce (fn [acc [path {:keys [sha]}]]
            (if-let [have (get local-hashes path)]
              (let [hit? (= have sha)
                    k (if (exe? path)
                        (if hit? :exe-matched :exe-mismatched)
                        (if hit? :matched :mismatched))]
                (update acc k inc))
              acc))
          {:exe-matched 0 :exe-mismatched 0 :matched 0 :mismatched 0}
          file-index))

(defn identify
  "Which candidate version the local files are, with the evidence for it.

   `local-hashes` is {manifest-path sha}; `candidates` is a seq of
   `{:version v :files {path {:size s :sha h}}}`.

   Returns `{:version v-or-nil :confidence :exact|:likely|:unknown :evidence m}`:

     :exact    every file compared matched, executables included
     :likely   every executable compared matched, other files did not. This is
               the normal state of a modded install and is not a lesser answer
               about the VERSION -- the executables are what a version is.
     :unknown  no executable matched any candidate. nil version, deliberately:
               a user whose game Steam has just updated to a build the catalog
               does not carry is in exactly this state, and naming the nearest
               version would tell them they have something they do not.

   Ranked on executable matches first and everything else only as a tiebreak,
   which is what decides a half-applied switch: the file that is the version wins
   over the forty thousand that merely came along with it."
  [local-hashes candidates]
  (let [scored (map (fn [c] (assoc c :score (score local-hashes (:files c)))) candidates)
        best   (->> scored
                    (filter #(pos? (:exe-matched (:score %))))
                    (sort-by (juxt #(- (:exe-matched (:score %)))
                                   #(- (:matched (:score %)))
                                   #(:exe-mismatched (:score %))))
                    (first))]
    (if-not best
      {:version nil :confidence :unknown
       :evidence (or (:score (first scored)) {})}
      (let [{:keys [exe-mismatched mismatched] :as s} (:score best)]
        {:version    (:version best)
         :confidence (if (and (zero? exe-mismatched) (zero? mismatched)) :exact :likely)
         :evidence   s}))))
