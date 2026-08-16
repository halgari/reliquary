;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.art
  "Capsule and screenshot art for catalog games: fetch once, cache to disk
   forever, hand the UI a JavaFX Image or nil.

   Every catalog URL is third-party (Steam's CDN, not Steam's authenticated
   API), so this namespace treats every fetch the way `reliquary.catalog`
   treats its refresh URL: bounded size, best-effort, and silent on failure.
   Unlike the catalog, art is fetched lazily and on a background thread by
   design -- `capsule`/`screenshot` are called from render code, which may
   run on the JavaFX Application Thread, and network I/O there would freeze
   the window.

   The public functions never throw. A missing art URL, a network failure,
   an oversized body, a non-2xx response, or a corrupt image all collapse to
   the same outcome: nil. The caller renders the mockup's placeholder;
   nothing downstream needs to know why the image isn't there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.config :as config])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.time Duration)
           (javafx.scene.image Image)))

(def ^:const max-art-bytes
  "A hard ceiling on one fetched image body. 8 MB is generous for a capsule
   or a screenshot; an unbounded read against a third-party CDN is an
   unbounded memory sink handed to a server this app does not control."
  (* 8 1024 1024))

(def ^:private http
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))

(defn- sha1-hex ^String [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- extension
  "The URL's file extension, lower-cased, sans any query/fragment -- or
   \"jpg\" when none is present. Only used to name the cache file; every
   catalog URL observed so far ends in .jpg, but this must not throw or
   misbehave on one that doesn't."
  ^String [^String url]
  (let [path (first (str/split url #"[?#]" 2))
        m    (re-find #"\.([A-Za-z0-9]{1,5})$" (or path ""))]
    (if m (str/lower-case (second m)) "jpg")))

(defn- cache-file
  "Where `url` is cached on disk: (config/data-dir)/art/<sha1-of-url>.<ext>.
   Always goes through `config/data-dir`, which is redirected by JVM property
   under test -- never build this from user.home or System/getenv."
  ^File [^String url]
  (io/file (config/data-dir) "art" (str (sha1-hex url) "." (extension url))))

(defn- read-capped
  "Reads `stream` fully into a byte array, or nil if it exceeds
   `max-art-bytes`. Always closes `stream`. Reads at most one byte array's
   worth past the cap, so memory use stays bounded even against a server
   that never stops sending."
  ^bytes [^java.io.InputStream stream]
  (with-open [in stream]
    (let [buf   (byte-array (inc max-art-bytes))
          total (loop [off 0]
                  (let [n (.read in buf off (- (alength buf) off))]
                    (cond
                      (neg? n)                    off
                      (>= (+ off n) (alength buf)) (+ off n)
                      :else                        (recur (+ off n)))))]
      (when (<= total max-art-bytes)
        (java.util.Arrays/copyOf buf total)))))

(defn- fetch-bytes
  "GETs `url` and returns the body as bytes, or nil on any failure: a
   non-2xx status, a transport exception, or a body over `max-art-bytes`.
   Never throws -- every catalog art URL is third-party and unauthenticated,
   so nothing about this request is trusted to behave."
  [^String url]
  (try
    (let [resp (.send ^HttpClient @http
                       (-> (HttpRequest/newBuilder (URI/create url))
                           (.timeout (Duration/ofSeconds 20))
                           (.build))
                       (HttpResponse$BodyHandlers/ofInputStream))]
      (when (<= 200 (.statusCode resp) 299)
        (read-capped (.body resp))))
    (catch Exception _ nil)))

(defn- write-cache!
  "Writes `bytes` to `f` atomically: a temp file in the same directory, then
   a rename. Same discipline as `config/write-config!` -- a half-written
   cache file would be worse than no cache file, since a later reader would
   see a corrupt image instead of retrying the fetch."
  [^File f ^bytes bytes]
  (let [dir (doto (.getParentFile f) .mkdirs)
        tmp (Files/createTempFile (.toPath dir) ".art" ".tmp" (make-array FileAttribute 0))]
    (Files/write tmp bytes (make-array java.nio.file.OpenOption 0))
    (Files/move tmp (.toPath f)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/ATOMIC_MOVE]))))

;; URLs currently being fetched by a background thread, so a burst of render
;; calls for the same not-yet-cached image starts one fetch, not one per
;; call.
(defonce ^:private in-flight (atom #{}))

(defn- ensure-fetching!
  "If `url` is already cached on disk, returns the cache File immediately --
   a local read, never a network call. Otherwise starts a background daemon
   fetch (unless one for this exact URL is already running) and returns nil:
   the caller renders a placeholder for this pass, and a later pass -- once
   the fetch lands -- will find the file cached.

   Never fetches on the calling thread, so this is safe to call from the
   JavaFX Application Thread."
  ^File [^String url]
  (let [f (cache-file url)]
    (if (.isFile f)
      f
      (do
        (when (empty? (filter #{url} @in-flight))
          (swap! in-flight conj url)
          (let [^Thread t (Thread.
                           (fn []
                             (try
                               (when-let [bytes (fetch-bytes url)]
                                 (write-cache! f bytes))
                               (catch Exception _ nil)
                               (finally (swap! in-flight disj url))))
                           "reliquary-art-fetch")]
            ;; art is strictly best-effort: nothing is lost by the JVM
            ;; exiting mid-fetch, but a non-daemon thread would hold the app
            ;; open after the window closes.
            (.setDaemon t true)
            (.start t)))
        nil))))

(defn- load-image
  "Builds a JavaFX Image from a local file, or nil if the file is missing,
   empty, or not a decodable image. Loading from a `file:` URI is
   synchronous and does not require the JavaFX toolkit to be started."
  ^Image [^File f]
  (when (and f (.isFile f) (pos? (.length f)))
    (try
      (let [img (Image. (.toString (.toURI f)))]
        (when-not (.isError img) img))
      (catch Exception _ nil))))

(defn capsule
  "`game`'s capsule art as a JavaFX Image, or nil when there is no capsule
   URL, it has not been fetched yet, or the fetch/decode failed. Never
   throws."
  [game]
  (try
    (when-let [url (-> game :art :capsule)]
      (load-image (ensure-fetching! url)))
    (catch Exception _ nil)))

(defn screenshot
  "The `n`th screenshot for `game` as a JavaFX Image, or nil when there is no
   such screenshot, it has not been fetched yet, or the fetch/decode failed.
   Never throws."
  [game n]
  (try
    (when-let [url (nth (-> game :art :screenshots) n nil)]
      (load-image (ensure-fetching! url)))
    (catch Exception _ nil)))

(defn prefetch!
  "Kicks off background fetches for `game`'s capsule and every screenshot
   that isn't already cached, and returns immediately. Callers use this to
   warm the cache ahead of a render -- e.g. for the whole visible library
   grid -- rather than relying solely on the fetch-on-miss inside
   `capsule`/`screenshot`."
  [game]
  (doseq [url (cons (-> game :art :capsule) (-> game :art :screenshots))
          :when (seq url)]
    (try (ensure-fetching! url) (catch Exception _ nil)))
  nil)
