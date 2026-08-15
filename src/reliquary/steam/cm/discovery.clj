;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.discovery
  "Steam CM (Connection Manager) discovery: fetch the list of WebSocket CM
  servers to connect to via Steam's ISteamDirectory/GetCMListForConnect WebAPI
  endpoint, and return full wss:// URLs ready for a WebSocket client.

  Blocking port of the CLJS discovery ns (which used js/fetch). Errors are
  raised as (error/raise :unavailable …)."
  (:require [clojure.data.json :as json]
            [reliquary.error :as error])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private base-url
  "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/")

;; one client for discovery: connection reuse across reconnect attempts. The
;; connectTimeout is an addition -- the reference left it unbounded, so an
;; unreachable Steam hung discovery instead of failing.
(def ^:private shared-http-client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 15))
             (.build))))

(defn- fetch-text
  "GET url, returning the response body as text. Non-2xx or transport failure →
  (error/raise :unavailable …)."
  [url]
  (let [^HttpClient client @shared-http-client
        ^HttpRequest req (-> (HttpRequest/newBuilder (URI/create url))
                             (.GET)
                             (.build))
        ^HttpResponse resp (try
                             (.send client req (HttpResponse$BodyHandlers/ofString))
                             (catch Exception e
                               (error/raise :unavailable (str "cm discovery: GET " url
                                                        ": " (ex-message e)))))
        code (.statusCode resp)]
    (when-not (<= 200 code 299)
      (error/raise :unavailable (str "cm discovery: HTTP " code)))
    (.body ^HttpResponse resp)))

(defn parse-server-list
  "The wss:// URLs from a GetCMListForConnect response body. Only websocket
   entries are usable -- the list also carries netfilter and TCP endpoints."
  [^String text]
  (let [data (try
               (json/read-str text :key-fn keyword)
               (catch Exception e
                 (error/raise :unavailable (str "cm discovery: bad JSON: " (ex-message e)))))]
    (->> (get-in data [:response :serverlist] [])
         (filter #(= "websockets" (:type %)))
         (mapv #(str "wss://" (:endpoint %) "/cmsocket/")))))

(defn cm-servers
  "Fetch the list of Steam CM WebSocket servers and return a vector of full
  wss://<endpoint>/cmsocket/ URLs (the shape a WebSocket client consumes
  directly). Optional cell-id (default 0) selects a region hint."
  ([] (cm-servers 0))
  ([cell-id]
   (parse-server-list
     (fetch-text (str base-url "?cellid=" (long cell-id) "&format=json")))))
