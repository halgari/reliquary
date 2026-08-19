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
   game anyway. `chunk-index` and `plan-chunks` are the useful unit instead.

   Measured end to end on a real 15 GB install: the whole tree hashes in about
   16 seconds, and a public -> 1.6.1130 switch comes out at 1.69 GB against
   14.99 GB for a full download. That is 89% of chunks reused rather than the
   98.6% the two manifests share on paper, and the gap is the point of the
   verification: this install has been played and modded, so some of its content
   no longer matches the manifest it came from, and those chunks are correctly
   refused rather than trusted.

   ## Why this makes failure cheap

   Because the current state is derived by hashing rather than tracked in a
   progress file, an interrupted switch needs no repair and no backup. Whatever
   is on disk hashes to something; that something is compared against the target;
   the difference is what gets fetched. Run it again after a crash, a cancel, or
   a power cut and it converges on the target from wherever it actually is. There
   is nothing to roll back because nothing was recorded that could be wrong."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.error :as error])
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

(defn sha1-range
  "Lowercase hex SHA-1 of `len` bytes at `offset`, or nil if the file is absent
   or too short to supply them.

   nil for a short file is not a detail: a truncated download, or a file a user
   replaced with a smaller one, must read as `we do not hold that chunk` rather
   than as a hash of whatever happened to be there."
  ^String [f ^long offset ^long len]
  (let [^File file (io/as-file f)]
    (when (and file (.isFile file) (>= (.length file) (+ offset len)))
      (try
        (with-open [raf (java.io.RandomAccessFile. file "r")]
          (.seek raf offset)
          (let [md (MessageDigest/getInstance "SHA-1")
                buf (byte-array (min len buffer-bytes))]
            (loop [remaining len]
              (if (zero? remaining)
                (hex (.digest md))
                (let [n (.read raf buf 0 (int (min remaining (alength buf))))]
                  (if (pos? n)
                    (do (.update md buf 0 n) (recur (- remaining n)))
                    ;; short read where the length said otherwise
                    nil))))))
        (catch Exception _ nil)))))

(defn- read-range
  "`len` bytes at `offset`, or nil if the file cannot supply them."
  ^bytes [f ^long offset ^long len]
  (let [^File file (io/as-file f)]
    (when (and file (.isFile file) (>= (.length file) (+ offset len)))
      (try
        (with-open [raf (java.io.RandomAccessFile. file "r")]
          (.seek raf offset)
          (let [buf (byte-array len)]
            (.readFully raf buf)
            buf))
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

(defn- ->long
  "Manifest numerics arrive in whichever type survived protobuf: a chunk's
   :offset is a uint64 and comes back a STRING, while its :cb-original is a
   uint32 and comes back an Integer. Coercing at the point of use rather than
   assuming either -- assuming Number cost a ClassCastException against live
   Steam that fixtures full of tidy numbers had no way to catch."
  ^long [v]
  (cond (number? v) (long v)
        (string? v) (try (Long/parseLong v) (catch Exception _ 0))
        :else 0))

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
                  [name {:size (->long size)
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

(defn catalog-candidates
  "Catalog versions as `identify` candidates, from the executable hashes the
   catalog carries.

   This is what makes identification free. A manifest is immutable, so a
   version's executable hash never changes; the catalog tool resolves it once
   with a session and every installation afterwards settles which version it has
   by hashing two files and looking them up -- no session, no manifest fetch, no
   network at all, and none of it dependent on Steam's own record of what it
   installed.

   A version with no executable hashes is not a candidate. A catalog generated
   before the field existed would otherwise match everything by matching
   nothing."
  [versions]
  (into []
        (keep (fn [v]
                (when (seq (:executables v))
                  {:version v
                   :files (into {} (map (fn [[path sha]] [path {:sha sha}]))
                                (:executables v))})))
        versions))

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

;; ---------------------------------------------------------------------------
;; the local chunk index

(defn chunk-index
  "Every chunk of this install that is verifiably the content it claims, as
   `{chunk-id {:path p :offset o :size n}}`.

   `files` is the INSTALLED version's manifest entries. Steam's chunk boundaries
   are defined by a manifest and cannot be recomputed from local bytes -- they
   are content-defined and variable-length -- so the installed manifest is the
   boundary map, and identifying the installed version is a prerequisite rather
   than a nicety.

   Every chunk is hashed and compared against its declared id, so this is a
   verification pass and not a bookkeeping read. A chunk a mod overwrote fails
   that comparison and is left out: claiming to hold content we do not would
   corrupt whatever we later copied it into, which is the one failure mode this
   whole approach must not have.

   `opts`:
     :on-progress  {:done bytes-hashed :total bytes-to-hash :path p}. BYTES, not
                   files: depot files are wildly uneven, and a per-file bar would
                   sit at 2% and then jump to 98% on one .bsa.
     :abort?       0-arg predicate, checked per chunk. This reads the whole
                   install -- fifteen gigabytes for Skyrim SE -- and a user who
                   closes the panel must not leave it grinding.

   An aborted pass returns what it had. That is safe because the index is only
   ever a set of chunks we can PROVE we hold: a smaller one costs downloads, not
   correctness."
  [root files {:keys [on-progress abort?]}]
  (let [total (reduce + 0 (for [f files ch (:chunks f)] (->long (:cb-original ch))))]
    (loop [entries (seq (for [f files ch (:chunks f)] [(:name f) ch]))
           done 0
           acc {}]
      (let [[[path ch] & more] entries]
        (if (or (nil? path) (and abort? (abort?)))
          acc
          (let [len (->long (:cb-original ch))
                off (->long (:offset ch))
                sha (sha1-range (->file root path) off len)
                done (+ done len)]
            (when on-progress (on-progress {:done done :total total :path path}))
            (recur more done
                   (if (and sha (= sha (:id ch)))
                     ;; every position, not just the first. A chunk genuinely
                     ;; can sit in several places, and keeping only one turns an
                     ;; in-place chunk into a needless copy whenever the one we
                     ;; kept was the other occurrence -- which on a real switch
                     ;; is a gigabyte moved for nothing.
                     (update acc (:id ch) (fnil conj []) {:path path :offset off :size len})
                     acc))))))))

(defn plan-switch
  "What changing this install to `target` requires, split by what each chunk
   actually needs doing.

   `index` is `chunk-index`'s output; `target` is the target version's manifest
   entries. Every chunk the target wants falls into exactly one of three:

     :in-place  already that content at that offset in that file. Nothing to do
                at all -- not a read, not a write.
     :copy      that content is on disk, but somewhere else. It has to be
                preserved BEFORE the writes start, because its source is very
                often a region the writes are about to overwrite.
     :fetch     not on disk. From Steam.

   Measured on the real public -> 1.6.1130 switch of Skyrim SE:

     in-place  14910 chunks  13.81 GB
     copy       1013 chunks   0.97 GB
     fetch       221 chunks   0.21 GB

   That split is what makes a staging area affordable. Staging the whole tree
   would need fifteen gigabytes; staging only what moves needs about one.

   `:sizes` is the length each target file must end up, because a file that
   shrinks has to be truncated -- otherwise the tail of the old version survives
   past the end of the new one and the file is quietly corrupt."
  [index target]
  (let [wanted (for [f target ch (:chunks f)]
                 (let [id  (:id ch)
                       to  {:path (:name f) :offset (->long (:offset ch))}
                       locs (get index id)]
                   {:id id
                    :to to
                    :size (->long (:cb-original ch))
                    :kind (cond (some #(and (= (:path %) (:path to))
                                            (= (:offset %) (:offset to)))
                                      locs)          :in-place
                                (seq locs)           :copy
                                :else                :fetch)
                    :from (first (remove nil? locs))}))
        by (group-by :kind wanted)]
    {:in-place (vec (:in-place by))
     :copy     (vec (:copy by))
     :fetch    (vec (:fetch by))
     :fetch-bytes (reduce + 0 (map :size (:fetch by)))
     :copy-bytes  (reduce + 0 (map :size (:copy by)))
     :sizes (into {} (for [f target]
                       [(:name f)
                        (reduce (fn [n ch] (max n (+ (->long (:offset ch))
                                                     (->long (:cb-original ch)))))
                                0 (:chunks f))]))}))

;; ---------------------------------------------------------------------------
;; applying a switch
;;
;; Two phases, and the order between them is the point of the whole design.

(defn staging-dir
  "Where moving chunks are parked while the install is rewritten.

   Inside the install, not in a temp directory: it must be on the same filesystem
   as the files it came from and is going into, and it must be somewhere the user
   would find it if a crash left it behind. The leading dot keeps it out of the
   way of a game that scans its own directory."
  ^File [root]
  (io/file root ".reliquary-staging"))

(defn stage!
  "Copy every MOVING chunk out to the staging area, and return {id file}.

   This runs before a single byte of the install is written, and that ordering is
   not a nicety: 89% of a switch's reuse comes from the install being rewritten,
   so a moving chunk's source is very often a region the writes are about to
   overwrite. Reading it afterwards would read whatever replaced it.

   Only the chunks that MOVE are staged. Chunks already at the right offset are
   never read or written, and chunks that are not on disk are fetched -- so this
   costs about a gigabyte on a fifteen gigabyte switch rather than a second copy
   of the game.

   Verified content: each chunk is hashed against the id it is filed under. The
   source was verified when the index was built, but the index may be minutes old
   by now and a game the user launched in between could have rewritten it."
  [root plan {:keys [on-progress abort?]}]
  (let [dir (doto (staging-dir root) .mkdirs)
        total (reduce + 0 (map :size (:copy plan)))]
    (loop [[{:keys [id from size]} & more] (seq (:copy plan)) done 0 acc {}]
      (if (or (nil? id) (and abort? (abort?)))
        acc
        (let [out (io/file dir id)
              bytes (when from (read-range (->file root (:path from)) (:offset from) size))
              ok?  (and bytes (= id (hex (.digest (doto (MessageDigest/getInstance "SHA-1")
                                                    (.update ^bytes bytes))))))]
          (when ok?
            (with-open [os (io/output-stream out)] (.write os ^bytes bytes)))
          (let [done (+ done size)]
            (when on-progress (on-progress {:done done :total total :id id}))
            (recur more done (cond-> acc ok? (assoc id out)))))))))

(defn clear-staging!
  "Remove the staging area. Derived data: leaving a gigabyte of it behind after
   every switch is exactly the disk waste this design exists to avoid."
  [root]
  (let [dir (staging-dir root)]
    (when (.isDirectory dir)
      (run! #(.delete ^File %) (reverse (file-seq dir))))
    nil))

(defn apply!
  "Write the target version into the install, in place.

   `staged` is `stage!`'s output; `opts` takes:
     :fetch        {:id :size :to ...} -> byte[], for chunks not on disk
     :on-progress  {:done bytes-written :total :path}
     :abort?       0-arg predicate

   In-place chunks are never touched -- not read, not written -- which is what
   keeps a 15 GB switch to about 1 GB of I/O. Everything else is written at its
   declared offset, then each file is truncated to the length the target says, or
   the tail of the old version survives past the end of the new one and the file
   is quietly corrupt rather than obviously wrong.

   Every chunk is verified against its id before it is written. A chunk id IS the
   sha1 of its content, so a corrupt transfer is detectable, and writing it anyway
   would put silent corruption into a game the user still has to launch.

   Destructive and deliberately not transactional. An interrupted apply leaves a
   half-switched install, which is simply another state to hash and diff from --
   re-running converges from wherever it actually is. That is why there is no
   backup and no rollback: nothing is recorded that could be wrong."
  [root plan staged {:keys [fetch on-progress abort?]}]
  (let [work  (concat (:copy plan) (:fetch plan))
        total (reduce + 0 (map :size work))]
    (loop [[{:keys [id to size] :as chunk} & more] (seq work) done 0]
      (when-not (or (nil? id) (and abort? (abort?)))
        (let [bytes (if-let [f (get staged id)]
                      (read-range f 0 size)
                      ;; the whole plan entry, not a bare id: a caller needs the
                      ;; size to fetch a chunk (chunk/fetch-decoded verifies the
                      ;; decoded length) and the path for any error it raises
                      (when fetch (fetch chunk)))]
          (when-not bytes
            (error/raise :unavailable (str "no source for chunk " id)
                         {:chunk-id id :path (:path to)}))
          (let [actual (hex (.digest (doto (MessageDigest/getInstance "SHA-1")
                                       (.update ^bytes bytes))))]
            (when-not (= actual id)
              (error/raise :incorrect
                           (str "chunk " id " arrived as different content")
                           {:chunk-id id :path (:path to)})))
          (let [dest (->file root (:path to))]
            (io/make-parents dest)
            (with-open [raf (java.io.RandomAccessFile. dest "rw")]
              (.seek raf (:offset to))
              (.write raf ^bytes bytes)))
          (let [done (+ done size)]
            (when on-progress (on-progress {:done done :total total :path (:path to)}))
            (recur more done)))))
    ;; sizes last: a file only shrinks once everything that belongs in it is
    ;; written, or truncation would cut off content still to come
    (doseq [[path size] (:sizes plan)]
      (let [f (->file root path)]
        (when (.isFile f)
          (with-open [raf (java.io.RandomAccessFile. f "rw")]
            (when (> (.length raf) (long size))
              (.setLength raf (long size)))))))
    nil))
