;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cdn
  "One GET policy for every SteamPipe CDN path: host rotation, bounded retry
   with linear backoff, and the URL-secrecy rule.

   EXTRACTED from reliquary.steam.manifest in spec 2c. The chunk path (2c) needs
   exactly this behaviour, and growing a second, subtly different retry loop
   next to the first is the failure this avoids. manifest/fetch now delegates
   here and keeps its own signature."
  (:require [reliquary.error :as error])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private http
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 15)) (.build))))

(def ^:dynamic *fetch-attempts*
  "Attempts per host before rotating to the next. Dynamic so tests need not pay
   the backoff."
  2)

(defn get-once
  "One GET against an ALREADY-VALIDATED URI. Returns the body on 2xx; raises
   :incorrect on 4xx (retrying will not help) and :unavailable on 5xx or a
   transport failure (it might).

   The URI is built and validated by `fetch-with-rotation`, once per host,
   outside this fn and outside its retry loop -- see that docstring for why.
   What is left here is only the socket, and its exceptions (ConnectException
   et al) carry no secret, so folding `ex-message` into the :unavailable
   message is safe.

   PUBLIC, not private: it is the seam tests redef instead of opening a socket."
  ^bytes [^URI uri]
  (let [resp (try
               (.send ^HttpClient @http
                      (-> (HttpRequest/newBuilder uri)
                          (.timeout (Duration/ofSeconds 60))
                          (.build))
                      (HttpResponse$BodyHandlers/ofByteArray))
               (catch Exception e
                 (error/raise :unavailable (str "cdn unreachable: " (ex-message e)))))
        code (.statusCode resp)]
    (cond
      (<= 200 code 299) (.body resp)
      (<= 400 code 499) (error/raise :incorrect (str "cdn rejected the request, HTTP " code)
                                     {:status code})
      :else             (error/raise :unavailable (str "cdn error, HTTP " code) {:status code}))))

(defn fetch-with-rotation
  "GET from the first CDN host that answers, and return the raw body.

   `hosts` is tried in order, *fetch-attempts* times each with linear backoff.
   `url-fn` builds the URL for one host. `what` is a short noun for messages.
   A 4xx aborts immediately -- a rejected request is not a transport problem
   and no other host will answer differently. Exhausting the list is
   :unavailable.

   A CDN URL may embed a manifest request code, which is an unauthenticated
   capability: anyone holding it can pull that manifest. Both the host (from
   the CDN directory) and the path segments (Steam-supplied) are unvalidated
   for shape, so `URI/create` can throw on either. That construction happens
   HERE, once per host, OUTSIDE the retry loop: a malformed URI is not a
   transport problem either, and java.net's exception message quotes the whole
   URL -- request code included -- so it is never folded into the raised
   message, only the exception's class name is."
  ^bytes [hosts url-fn ^String what]
  (when (empty? hosts)
    (error/raise :unavailable "no https-capable cdn hosts"))
  (loop [remaining hosts last-err nil]
    (if (empty? remaining)
      (throw last-err)
      (let [uri (try (URI/create (url-fn (first remaining)))
                     (catch IllegalArgumentException e
                       (error/raise :incorrect
                                    (str "malformed " what " url ("
                                         (.getSimpleName (class e)) ")"))))
            r   (loop [n 1]
                  (let [out (try {:ok (get-once uri)}
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= :incorrect (:reliquary/error (ex-data e)))
                                     (throw e)              ; do not retry a 4xx
                                     {:err e})))]
                    (if (:ok out)
                      out
                      (if (< n (long *fetch-attempts*))
                        (do (Thread/sleep (* 250 n)) (recur (inc n)))
                        out))))]
        (if-let [b (:ok r)]
          b
          (recur (rest remaining) (:err r)))))))
