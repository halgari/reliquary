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

(defn- norm-chunks [chunks]
  (into []
        (map-indexed (fn [i c]
                       {:index       i
                        :id          (:id c)
                        :offset      (->long (:offset c) :offset)
                        :cb-original (->long (:cb-original c) :cb-original)}))
        (sort-by #(->long (:offset %) :offset) chunks)))

(defn build
  "Depot manifests -> a work plan.

   `depot-manifests` is a vector of {:depot-id long :key-hex string :files
   [entry]}, where each entry is what reliquary.steam.manifest/parse produced.
   The depot key travels with each file because the chunk fetcher needs it and
   the engine should not have to look it up again.

   Files sharing a content SHA-1 are fetched once; the rest become :copies.
   Steam depots do repeat content, and a copy is free next to a download.
   An empty sha-content is treated as absent -- never as a match -- so a
   manifest missing the field doesn't collapse unrelated files together. Two
   entries sharing a sha-content but declaring different sizes is a
   self-contradictory manifest (identical content cannot have two lengths)
   and is rejected rather than guessed at."
  [depot-manifests]
  (let [entries (for [{:keys [depot-id key-hex files]} depot-manifests
                      e files]
                  (assoc e :depot-id depot-id :key-hex key-hex))
        dirs    (into [] (comp (filter directory?)
                               (map #(safe-path (:name %)))
                               (distinct))
                      entries)
        plain   (remove directory? entries)]
    (loop [remaining (seq plain)
           seen      {}                       ; sha-content -> {:path :size} already planned
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
         :copies         copies}
        (let [e        (first remaining)
              path     (safe-path (:name e))
              size     (->long (:size e) :size)
              sha      (:sha-content e)
              has-sha? (seq sha)
              src      (when has-sha? (get seen sha))]
          (cond
            (and has-sha? src (not= (:size src) size))
            (error/raise :incorrect
                         (str "manifest declares sha-content " sha
                              " at two different sizes")
                         {:sha sha :first-path (:path src) :first-size (:size src)
                          :second-path path :second-size size})

            (and has-sha? src)
            (recur (next remaining) seen files
                   (conj copies {:path path :source (:path src) :size (:size src)})
                   dl-bytes (+ disk size) chunks)

            :else
            (let [cs (norm-chunks (:chunks e))]
              (recur (next remaining)
                     (if has-sha? (assoc seen sha {:path path :size size}) seen)
                     (conj files {:path        path
                                  :size        size
                                  :depot-id    (:depot-id e)
                                  :key-hex     (:key-hex e)
                                  :sha-content sha
                                  :chunks      cs})
                     copies
                     (+ dl-bytes (reduce + 0 (map :cb-original cs)))
                     (+ disk size)
                     (+ chunks (count cs))))))))))

(defn chunk-count ^long [plan]
  (reduce + 0 (map (comp count :chunks) (:files plan))))
