;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.art-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config]
            [reliquary.ui.art :as art])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (javax.imageio ImageIO)))

;; Same pattern as catalog_test.clj: a JDK HttpServer on an OS-assigned port,
;; so the suite stays offline while still exercising a real fetch.
(defn- local-http-server
  ([path status body] (local-http-server path status body 0))
  ([path status ^bytes body delay-ms]
   (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
     (.createContext server path
       (reify HttpHandler
         (handle [_ ex]
           (when (pos? delay-ms) (Thread/sleep ^long delay-ms))
           (.sendResponseHeaders ^HttpExchange ex status (alength body))
           (with-open [os (.getResponseBody ^HttpExchange ex)]
             (.write os body)))))
     (.start server)
     server)))

(defn- server-url [server path]
  (str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer server)) path))

(defn- tiny-jpg-bytes
  "A real, decodable 4x4 JPEG, built in memory -- ImageIO/write does not need
   a display, so this works under a headless test JVM."
  ^bytes []
  (let [img (BufferedImage. 4 4 BufferedImage/TYPE_INT_RGB)
        out (ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" out)
    (.toByteArray out)))

(defn- game-with [url]
  {:appid 220 :title "HL2" :art {:capsule url :screenshots [url (str url "-second")]}})

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(deftest fetch-bytes-returns-nil-not-a-throw-on-a-failed-fetch
  (let [server (local-http-server "/broken" 500 (.getBytes "nope" "UTF-8"))]
    (try
      (is (nil? (#'art/fetch-bytes (server-url server "/broken"))))
      (finally (.stop ^HttpServer server 0)))))

(deftest fetch-bytes-returns-nil-on-an-unreachable-host
  ;; nothing listens on this port -- a transport-level failure, not an HTTP
  ;; error status.
  (is (nil? (#'art/fetch-bytes "http://127.0.0.1:1"))))

(deftest fetch-bytes-returns-nil-for-an-oversized-body
  (let [big    (byte-array (inc art/max-art-bytes) (byte 65))
        server (local-http-server "/big" 200 big)]
    (try
      (is (nil? (#'art/fetch-bytes (server-url server "/big"))))
      (finally (.stop ^HttpServer server 0)))))

(deftest fetch-bytes-returns-the-body-on-success
  (let [bytes  (tiny-jpg-bytes)
        server (local-http-server "/ok.jpg" 200 bytes)]
    (try
      (is (= (seq bytes) (seq (#'art/fetch-bytes (server-url server "/ok.jpg")))))
      (finally (.stop ^HttpServer server 0)))))

(deftest a-cached-file-is-reused-without-a-fetch
  ;; No server is started at all -- if `capsule` tried to fetch, it would
  ;; fail (connection refused) and the pre-written cache file would never be
  ;; read. Returning a real Image here proves the disk cache was used.
  (let [d   (io/file (System/getProperty "reliquary.data-dir") "art-cache-reuse-test")
        url "http://127.0.0.1:1/never-contacted.jpg"]
    (binding [config/*data-dir* d]
      (try
        (let [f (#'art/cache-file url)]
          (io/make-parents f)
          (io/copy (tiny-jpg-bytes) f))
        (let [img (art/capsule (game-with url))]
          (is (some? img) "a pre-cached file must be returned without any network call")
          (is (= 4.0 (.getWidth img))))
        (finally (when (.exists d) (run! #(io/delete-file % true) (reverse (file-seq d)))))))))

(deftest capsule-is-nil-when-the-game-has-no-art
  (is (nil? (art/capsule {:appid 1 :title "no art"})))
  (is (nil? (art/capsule {:appid 1 :title "no art" :art {}}))))

(deftest screenshot-is-nil-for-an-out-of-range-index
  (is (nil? (art/screenshot (game-with "http://127.0.0.1:1/x.jpg") 7))))

(deftest a-failed-background-fetch-resolves-to-nil-never-a-throw
  (let [d      (io/file (System/getProperty "reliquary.data-dir") "art-failed-fetch-test")
        server (local-http-server "/broken.jpg" 500 (.getBytes "nope" "UTF-8"))
        game   (game-with (server-url server "/broken.jpg"))]
    (binding [config/*data-dir* d]
      (try
        (is (nil? (art/capsule game))
            "no cache exists yet, so this call kicks a background fetch and returns nil")
        (is (nil? (art/prefetch! game)))
        ;; give the background fetch time to run and fail; it must never
        ;; write a cache file for a failed fetch.
        (Thread/sleep 500)
        (is (nil? (art/capsule game))
            "a failed fetch must still resolve to nil, not throw, and not cache anything")
        (finally
          (.stop ^HttpServer server 0)
          (when (.exists d) (run! #(io/delete-file % true) (reverse (file-seq d)))))))))

(deftest prefetch-warms-the-cache-in-the-background-and-capsule-then-succeeds
  (let [d      (io/file (System/getProperty "reliquary.data-dir") "art-prefetch-test")
        bytes  (tiny-jpg-bytes)
        server (local-http-server "/warm.jpg" 200 bytes)
        game   (game-with (server-url server "/warm.jpg"))]
    (binding [config/*data-dir* d]
      (try
        (is (nil? (art/capsule game)) "not cached yet -- placeholder for this render pass")
        (is (nil? (art/prefetch! game)) "prefetch! returns immediately")
        (is (wait-until #(some? (art/capsule game)) 5000)
            "once the background fetch lands, capsule must find it on disk")
        (finally
          (.stop ^HttpServer server 0)
          (when (.exists d) (run! #(io/delete-file % true) (reverse (file-seq d)))))))))

(deftest nothing-writes-outside-config-data-dir
  ;; Deliberately does NOT bind config/*data-dir* -- ensure-fetching! writes
  ;; from a raw Thread, and a dynamic binding never crosses that (see
  ;; catalog_test.clj's with-tmp-data-dir docstring for the same point made
  ;; about catalog/refresh!). What actually keeps this off the real
  ;; ~/.local/share/reliquary is the reliquary.data-dir JVM property the
  ;; :test alias sets, consulted directly by config/data-dir.
  (let [real-data-dir (io/file (System/getProperty "user.home") ".local" "share" "reliquary" "art")
        bytes         (tiny-jpg-bytes)
        server        (local-http-server "/loc.jpg" 200 bytes)
        url           (server-url server "/loc.jpg")
        game          (game-with url)
        expected-file (#'art/cache-file url)]
    (try
      (is (nil? (art/capsule game)))
      (is (wait-until #(.isFile expected-file) 5000)
          "the fetched file must land at the path cache-file computed")
      (let [cache-path (.getCanonicalPath expected-file)
            under-test (.getCanonicalPath (io/file "target" "test-state"))]
        (is (str/starts-with? cache-path under-test)
            (str "art must be cached under target/test-state via config/data-dir, "
                 "not the real user data dir -- got: " cache-path)))
      (is (not (.exists real-data-dir))
          "the real ~/.local/share/reliquary/art must remain untouched")
      (finally
        (.stop ^HttpServer server 0)
        (io/delete-file expected-file true)))))
