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

(defn- ->long ^long [v]
  (cond (number? v) (long v)
        (string? v) (Long/parseLong v)
        :else       (error/raise :incorrect "manifest field is neither a number nor a string")))

(defn- safe-path
  "A manifest is remote input. A name that is absolute, or that climbs above
   the destination, would let a manifest write anywhere on the disk -- so it is
   rejected outright rather than sanitized into something the user did not ask
   for."
  ^String [name]
  (when-not (string? name)
    (error/raise :incorrect
                 "manifest entry has no filename -- the depot key was missing or wrong"))
  (let [p (str/replace name "\\" "/")]
    (when (or (str/starts-with? p "/")
              (re-find #"^[A-Za-z]:" p)
              (some #{".."} (str/split p #"/")))
      (error/raise :incorrect (str "manifest entry escapes the install folder: " p)
                   {:path p}))
    p))

(defn- directory? [e] (pos? (bit-and (long (or (:flags e) 0)) flag-directory)))

(defn- norm-chunks [chunks]
  (into []
        (map-indexed (fn [i c]
                       {:index       i
                        :id          (:id c)
                        :offset      (->long (:offset c))
                        :cb-original (->long (:cb-original c))}))
        (sort-by #(->long (:offset %)) chunks)))

(defn build
  "Depot manifests -> a work plan.

   `depot-manifests` is a vector of {:depot-id long :key-hex string :files
   [entry]}, where each entry is what reliquary.steam.manifest/parse produced.
   The depot key travels with each file because the chunk fetcher needs it and
   the engine should not have to look it up again.

   Files sharing a content SHA-1 are fetched once; the rest become :copies.
   Steam depots do repeat content, and a copy is free next to a download."
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
           seen      {}                       ; sha-content -> path already planned
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
        (let [e    (first remaining)
              path (safe-path (:name e))
              size (->long (:size e))
              sha  (:sha-content e)
              src  (get seen sha)]
          (if (and sha src)
            (recur (next remaining) seen files
                   (conj copies {:path path :source src :size size})
                   dl-bytes (+ disk size) chunks)
            (let [cs (norm-chunks (:chunks e))]
              (recur (next remaining)
                     (if sha (assoc seen sha path) seen)
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
