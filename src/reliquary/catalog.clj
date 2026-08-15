;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.catalog
  "What games exist, what versions they have, and what to say while they
   download.

   Steam's PICS names only the CURRENT manifest on each branch, so the versions
   Reliquary offers cannot come from live metadata. They come from here: a JSON
   document bundled with the binary and refreshed from a URL at startup.

   Three sources, newest `generated` wins: the bundled copy (always present),
   the last good fetch (cached on disk), and today's fetch. Every failure mode
   degrades to an older catalog rather than to an error -- an app that will not
   start because a GitHub URL was slow is a worse app."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [reliquary.config :as config])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration Instant)))

(def ^:const schema-version 1)

(defn- ->long [v]
  (cond (number? v) (long v)
        (string? v) (try (Long/parseLong v) (catch Exception _ nil))
        :else       nil))

(defn- norm-depot [d]
  (let [id  (->long (:depot-id d))
        gid (:manifest-gid d)]
    ;; a manifest gid is a uint64: it stays a STRING, exactly as the manifest
    ;; layer expects. Coercing it to a long here would round the large ones.
    (when (and id (string? gid) (seq gid))
      {:depot-id id :manifest-gid gid})))

(defn- norm-version [v]
  (let [depots (into [] (keep norm-depot) (:depots v))]
    (when (and (seq (:id v)) (seq (:label v)) (seq (:branch v)) (seq depots))
      {:id     (:id v)
       :label  (:label v)
       :branch (:branch v)
       :build  (str (:build v))
       :date   (:date v)
       :bytes  (or (->long (:bytes v)) 0)
       :depots depots})))

(defn- norm-game [g]
  (let [appid    (->long (:appid g))
        versions (into [] (keep norm-version) (:versions g))]
    (when (and appid (seq (:title g)) (seq versions))
      {:appid  appid
       :title  (:title g)
       :studio (:studio g)
       :art    {:capsule     (-> g :art :capsule)
                :screenshots (into [] (-> g :art :screenshots))}
       :quotes (into [] (keep (fn [q] (when (seq (:text q))
                                        {:text (:text q) :attrib (:attrib q)}))
                              (:quotes g)))
       :versions versions})))

(defn parse
  "A catalog JSON string -> a normalized catalog map, or nil.

   nil covers every rejection: malformed JSON, a schema version this build does
   not know, an unparseable timestamp, and a document whose games all fail
   validation. A caller that gets nil falls back to an older source; there is
   nothing actionable to report."
  [^String s]
  (try
    (let [c (json/read-str s :key-fn keyword)]
      (when (= schema-version (:schema-version c))
        (Instant/parse (:generated c))            ; throws if unparseable
        (let [games (into [] (keep norm-game) (:games c))]
          ;; a document with no usable game is not a catalog
          (when (and (seq games) (= (count games) (count (:games c))))
            {:schema-version schema-version
             :generated      (:generated c)
             :games          games}))))
    (catch Exception _ nil)))

(defn- read-catalog [^File f]
  (when (and f (.isFile f)) (parse (slurp f))))

(defn bundled [] (some-> (io/resource "catalog.json") slurp parse))
(defn- cache-file ^File [] (io/file (config/data-dir) "catalog.json"))
(defn cached [] (read-catalog (cache-file)))

(defn newest
  "The catalog with the latest `generated`. nils are skipped; ties keep the
   first argument, so callers order their sources by preference."
  [& catalogs]
  (reduce (fn [best c]
            (cond (nil? c)    best
                  (nil? best) c
                  (.isAfter (Instant/parse (:generated c))
                            (Instant/parse (:generated best))) c
                  :else best))
          nil
          catalogs))

(defn load!
  "The best catalog available without touching the network. Synchronous and
   fast enough to call before the window opens."
  []
  (newest (bundled) (cached)))

(defn refresh!
  "Fetch `url` on a background thread and call `on-done` with the parsed
   catalog if it is valid and newer than what we have. Returns immediately.

   Silent on every failure. The UI shows which catalog is live in its status
   line; a failed refresh simply means that line keeps saying what it said."
  [^String url on-done]
  (.start
   (Thread.
    (fn []
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 10))
                         (.build))
              resp   (.send client
                            (-> (HttpRequest/newBuilder (URI/create url))
                                (.timeout (Duration/ofSeconds 20))
                                (.build))
                            (HttpResponse$BodyHandlers/ofString))]
          (when (<= 200 (.statusCode resp) 299)
            (when-let [fresh (parse (.body resp))]
              (when (= fresh (newest (load!) fresh))
                (io/make-parents (cache-file))
                (spit (cache-file) (.body resp))
                (on-done fresh)))))
        (catch Exception _ nil)))
    "reliquary-catalog-refresh")))

(defn games [catalog] (:games catalog))
(defn game [catalog appid] (first (filter #(= (long appid) (:appid %)) (:games catalog))))
(defn version [game version-id] (first (filter #(= version-id (:id %)) (:versions game))))
