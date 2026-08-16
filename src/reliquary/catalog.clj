;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.catalog
  "What games exist, what versions they have, and what to say while they
   download.

   Steam's PICS names only the CURRENT manifest on each branch, so the versions
   Reliquary offers cannot come from live metadata. They come from here: a JSON
   document bundled with the binary and refreshed from a URL at startup.

   The document is EDN, not JSON: it is Clojure data consumed by Clojure, so
   it is stored as Clojure data -- read with `clojure.edn/read-string`, no
   parser dependency, keywords rather than stringly-typed keys.

   `edn/read-string` is used rather than `read`: it does not eval, so a hostile
   document fetched from the network cannot execute anything. Reader tags are
   rejected by default for the same reason.

   Three sources, newest `generated` wins: the bundled copy (always present),
   the last good fetch (cached on disk), and today's fetch. Every failure mode
   degrades to an older catalog rather than to an error -- an app that will not
   start because a GitHub URL was slow is a worse app."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [reliquary.config :as config])
  (:import (java.io File InputStream)
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
    (let [c (edn/read-string s)]
      (when (and (map? c) (= schema-version (:schema-version c)))
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

(defn bundled [] (some-> (io/resource "catalog.edn") slurp parse))
(defn- cache-file ^File [] (io/file (config/data-dir) "catalog.edn"))
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

(def ^:const max-catalog-bytes
  "A hard ceiling on the refresh response body. The catalog URL is the only
   host this application contacts that is neither Steam nor a Steam CDN, so an
   unbounded read here is an unbounded memory sink handed to an untrusted
   server. The bundled catalog is under 2 KB; a few MB is generous headroom
   for the catalog to grow for years without anyone touching this constant."
  (* 4 1024 1024))

(defn- read-capped
  "Read `stream` into a string, or nil if it exceeds `max-catalog-bytes`.
   Always closes `stream`. Reads at most one byte past the cap, so memory use
   stays bounded even against a server that never stops sending."
  [^InputStream stream]
  (with-open [in stream]
    (let [buf   (byte-array (inc max-catalog-bytes))
          total (loop [off 0]
                  (let [n (.read in buf off (- (alength buf) off))]
                    (cond
                      (neg? n)                       off
                      (>= (+ off n) (alength buf))    (+ off n)
                      :else                           (recur (+ off n)))))]
      (when (<= total max-catalog-bytes)
        (String. buf 0 total "UTF-8")))))

(defn refresh!
  "Fetch `url` on a background thread and call `on-done` with the parsed
   catalog if it is valid and newer than what we have. Returns immediately.

   Silent on every failure -- including a body over `max-catalog-bytes`. The
   UI shows which catalog is live in its status line; a failed refresh simply
   means that line keeps saying what it said."
  [^String url on-done]
  (let [^Thread t (Thread.
                   (fn []
                     (try
                       (let [client (-> (HttpClient/newBuilder)
                                        (.connectTimeout (Duration/ofSeconds 10))
                                        (.build))
                             resp   (.send client
                                           (-> (HttpRequest/newBuilder (URI/create url))
                                               (.timeout (Duration/ofSeconds 20))
                                               (.build))
                                           (HttpResponse$BodyHandlers/ofInputStream))]
                         (when (<= 200 (.statusCode resp) 299)
                           (when-let [body (read-capped (.body resp))]
                             (when-let [fresh (parse body)]
                               (when (= fresh (newest (load!) fresh))
                                 (io/make-parents (cache-file))
                                 (spit (cache-file) body)
                                 (on-done fresh))))))
                       (catch Exception _ nil)))
                   "reliquary-catalog-refresh")]
    ;; a catalog refresh is strictly best-effort: nothing is lost by the JVM
    ;; exiting mid-fetch, but a non-daemon thread would hold the app open for
    ;; up to ~30s (connect + request timeout) after the window closes.
    (.setDaemon t true)
    (.start t)))

(defn games [catalog] (:games catalog))
(defn game [catalog appid] (first (filter #(= (long appid) (:appid %)) (:games catalog))))
(defn version [game version-id] (first (filter #(= version-id (:id %)) (:versions game))))
