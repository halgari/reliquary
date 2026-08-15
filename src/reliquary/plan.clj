;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.plan
  "Parsed depot manifests -> the work list the download engine executes.

   Pure, and deliberately so. The offset arithmetic here is the layer where a
   mistake produces plausible bytes at the wrong place rather than an error:
   the only thing downstream that would catch it is a chunk SHA-1 failure, and
   that failure would blame the depot key. Hence the property tests."
  (:require [clojure.string :as str]
            [reliquary.error :as error]))

;; EDepotFileFlag
(def ^:const flag-directory 64)

(defn- ->long
  "Coerce a manifest uint64 field (a number, or a string because the
   protobuf bridge deliberately decodes uint64 as a string) to a long.
   `field` names the offending field in the error, so a bad size and a bad
   offset don't read the same."
  (^long [v] (->long v "field"))
  (^long [v field]
   (cond
     (number? v) (long v)
     (string? v) (try
                   (Long/parseLong v)
                   (catch NumberFormatException _
                     (error/raise :incorrect
                                  (str "manifest " (name field)
                                       " is not a valid 64-bit integer: " (pr-str v))
                                  {:field field :value v})))
     :else (error/raise :incorrect
                        (str "manifest " (name field) " is neither a number nor a string")
                        {:field field :value v}))))

(defn- safe-path
  "A manifest is remote input. A name that is absolute, that climbs above the
   destination, that is empty/no-op, or that carries a NUL byte, would let a
   manifest write anywhere on disk or crash the writer uncategorized -- so all
   of these are rejected outright rather than sanitized into something the
   user did not ask for."
  ^String [name]
  (when-not (string? name)
    (error/raise :incorrect
                 "manifest entry has no filename -- the depot key was missing or wrong"))
  (when (str/index-of name (char 0))
    (error/raise :incorrect "manifest entry filename contains a NUL byte" {:name name}))
  (let [p (str/replace name "\\" "/")]
    (when (or (str/starts-with? p "/")
              (re-find #"^[A-Za-z]:" p)
              (some #{".."} (str/split p #"/")))
      (error/raise :incorrect (str "manifest entry escapes the install folder: " p)
                   {:path p}))
    (when (or (empty? p) (= p "."))
      (error/raise :incorrect (str "manifest entry has an empty or no-op path: " (pr-str p))
                   {:path p}))
    p))

(defn- directory? [e] (pos? (bit-and (long (or (:flags e) 0)) flag-directory)))

(defn- regular-file?
  "Structural classification, NOT flags: manifest.clj deliberately refuses to
   define EDepotFileFlag constants beyond flag-directory (see its docstring),
   so a symlink's bits cannot be pinned. An entry with chunks, or a content
   SHA, is content the engine has to fetch or copy -- a file. An entry with
   neither is nothing the engine can serve; flag-directory only decides
   whether that entry becomes a directory or is silently skipped."
  [e]
  (boolean (or (seq (:chunks e)) (seq (:sha-content e)))))

(defn- norm-chunks [chunks]
  (into []
        (map-indexed (fn [i c]
                       {:index         i
                        :id            (:id c)
                        :offset        (->long (:offset c) :offset)
                        :cb-original   (->long (:cb-original c) :cb-original)
                        :cb-compressed (->long (:cb-compressed c) :cb-compressed)}))
        (sort-by #(->long (:offset %) :offset) chunks)))

(defn- validate-tiling!
  "Chunks (already normalized: sorted by offset, offset/cb-original as longs)
   must tile `size` exactly -- start at 0, each chunk's end must be the next
   chunk's start, and the last chunk must end exactly at size. A gap leaves a
   hole in the preallocated file that nothing downstream would catch; an
   overlap writes one region twice and loses another; a chunk running past
   size breaks preallocation entirely. A file with no chunks and size 0 is a
   legitimate empty file."
  [path size chunks]
  (if (empty? chunks)
    (when-not (zero? size)
      (error/raise :incorrect
                   (str "file " path " declares size " size " but has no chunks to fill it")
                   {:path path :offset 0}))
    (do
      (when-not (zero? (:offset (first chunks)))
        (error/raise :incorrect
                     (str "file " path " chunks do not start at offset 0, at offset "
                          (:offset (first chunks)))
                     {:path path :offset (:offset (first chunks))}))
      (doseq [[a b] (partition 2 1 chunks)]
        (let [end-a (+ (:offset a) (:cb-original a))]
          (when (not= end-a (:offset b))
            (error/raise :incorrect
                         (str "file " path " chunks do not tile at offset " (:offset b)
                              " -- the previous chunk ends at " end-a)
                         {:path path :offset (:offset b)}))))
      (let [last-c (last chunks)
            end    (+ (:offset last-c) (:cb-original last-c))]
        (when (not= end size)
          (error/raise :incorrect
                       (str "file " path " chunks end at offset " end
                            " but the manifest declares size " size)
                       {:path path :offset end}))))))

(defn build
  "Depot manifests -> a work plan.

   `depot-manifests` is a vector of {:depot-id long :key-hex string :files
   [entry]}, where each entry is what reliquary.steam.manifest/parse produced.
   The depot key is deliberately NOT threaded into the returned plan -- see
   `keys-by-depot`.

   Files sharing a content SHA-1 are fetched once; the rest become :copies.
   Steam depots do repeat content, and a copy is free next to a download. An
   empty sha-content is treated as absent -- never as a match -- so a
   manifest missing the field doesn't collapse unrelated files together. Two
   entries sharing a sha-content but declaring different sizes cannot be the
   same content, so a size conflict means this is NOT a copy: the entry is
   planned as its own file rather than aborting the whole download over a
   remote-controlled input like a sentinel/all-zero digest. A manifest that
   lists the same destination path twice -- whether under the same sha, a
   different sha, or no sha at all -- is rejected outright: read naively it
   would produce a copy of a path onto itself, and the engine's Files/copy
   with REPLACE_EXISTING would truncate that path before reading it.

   Every planned file's chunks must tile its declared size exactly (see
   `validate-tiling!`) -- a gap or overlap here produces plausible bytes at
   the wrong place, and the only thing downstream that would catch it is a
   chunk SHA-1 failure blaming the depot key instead of the manifest.

   An entry with no chunks and no content SHA is not a regular file (a
   symlink, most likely, since manifest.clj refuses to pin the flag bit that
   would say so for certain) -- flag-directory decides whether it becomes a
   directory or is silently skipped and counted in :skipped."
  [depot-manifests]
  (let [entries (for [{:keys [depot-id files]} depot-manifests
                      e files]
                  (assoc e :depot-id depot-id))
        {file-es true not-file-es false} (group-by regular-file? entries)
        dirs    (into [] (comp (filter directory?)
                               (map #(safe-path (:name %)))
                               (distinct))
                      not-file-es)
        skipped (- (count not-file-es) (count (filter directory? not-file-es)))]
    (loop [remaining (seq file-es)
           seen      {}                       ; sha-content -> {:path :size} already planned
           paths     #{}                      ; every destination path already claimed
           files     []
           copies    []
           dl-bytes  0
           disk      0
           chunks    0]
      (if-not remaining
        {:download-bytes dl-bytes
         :disk-bytes     disk
         :total-chunks   chunks
         :dirs           (vec (sort dirs))
         :files          files
         :copies         copies
         :skipped        skipped}
        (let [e    (first remaining)
              path (safe-path (:name e))
              _    (when (contains? paths path)
                     (error/raise :incorrect
                                  (str "manifest lists the same path twice: " path)
                                  {:path path}))
              size     (->long (:size e) :size)
              sha      (:sha-content e)
              has-sha? (seq sha)
              src      (when has-sha? (get seen sha))
              as-file  (fn [seen']
                         (let [cs (norm-chunks (:chunks e))]
                           (validate-tiling! path size cs)
                           [(next remaining)
                            seen'
                            (conj paths path)
                            (conj files {:path        path
                                         :size        size
                                         :depot-id    (:depot-id e)
                                         :sha-content sha
                                         :chunks      cs})
                            copies
                            (+ dl-bytes (reduce + 0 (map :cb-original cs)))
                            (+ disk size)
                            (+ chunks (count cs))]))]
          (cond
            (and has-sha? src (not= (:size src) size))
            (let [[rem seen' paths' files' copies' dl' disk' chunks'] (as-file seen)]
              (recur rem seen' paths' files' copies' dl' disk' chunks'))

            (and has-sha? src)
            (recur (next remaining) seen (conj paths path) files
                   (conj copies {:path path :source (:path src) :size (:size src)})
                   dl-bytes (+ disk size) chunks)

            :else
            (let [[rem seen' paths' files' copies' dl' disk' chunks']
                  (as-file (if has-sha? (assoc seen sha {:path path :size size}) seen))]
              (recur rem seen' paths' files' copies' dl' disk' chunks'))))))))

(defn keys-by-depot
  "{depot-id -> key-hex} for the manifests in this plan.

   Kept OUT of the plan map deliberately: the plan is what the engine serializes
   into progress files and error snapshots, and a depot key is a secret under the
   spec's §9 rule. The engine threads this map alongside the plan instead."
  [depot-manifests]
  (into {} (map (juxt :depot-id :key-hex)) depot-manifests))

(defn chunk-count ^long [plan]
  (reduce + 0 (map (comp count :chunks) (:files plan))))
