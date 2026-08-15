;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.api
  "POST a protobuf request to an IAuthenticationService method and decode the
   response.

   Wire shape, unchanged from the reference client: the request is base64'd and
   form-encoded as `input_protobuf_encoded=<url-encoded base64>`; the response's
   error code is the x-eresult HEADER (\"1\" = OK) and the body is raw protobuf."
  (:require [reliquary.steam.proto :as proto]
            [reliquary.error :as error])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util Base64)))

(def ^:private ^String base "https://api.steampowered.com/IAuthenticationService/")

(def ^:private shared-http-client
  ;; one client: connection reuse across the calls of a single login. The
  ;; connectTimeout is an addition -- the reference left it unbounded, so an
  ;; unreachable Steam hung the login instead of failing.
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 15))
             (.build))))

(defn form-body
  "The urlencoded form body for a protobuf payload. Pure, so the encoding is
   testable without a network."
  ^String [^bytes payload]
  (str "input_protobuf_encoded="
       (URLEncoder/encode (.encodeToString (Base64/getEncoder) payload) "UTF-8")))

(defn call
  "POST `req-map` as `req-type` to `method`, returning the decoded `resp-type`.
   A non-OK x-eresult raises :incorrect carrying the code."
  [^String method req-type req-map resp-type]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str base method "/v1/")))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.timeout (Duration/ofSeconds 30))
                (.POST (HttpRequest$BodyPublishers/ofString
                        (form-body (proto/encode req-type req-map))
                        StandardCharsets/UTF_8))
                (.build))
        resp (try
               (.send ^HttpClient @shared-http-client req (HttpResponse$BodyHandlers/ofByteArray))
               (catch Exception e
                 (error/raise :unavailable (str "steam auth api unreachable: " (ex-message e)))))
        eresult (-> (.headers resp) (.firstValue "x-eresult") (.orElse "1"))]
    (when-not (= "1" eresult)
      (error/raise :incorrect (str "steam " method " failed, eresult " eresult)
                   {:eresult eresult :method method}))
    (proto/decode resp-type (.body resp))))

(defmacro defcall
  "Define a wrapper for an IAuthenticationService method. Message type names are
   validated AT MACROEXPANSION against the descriptor set, so a typo is a compile
   error instead of a runtime failure on a path that only runs mid-login.

     (defcall begin-qr \"BeginAuthSessionViaQR\"
       CAuthentication_BeginAuthSessionViaQR_Request
       CAuthentication_BeginAuthSessionViaQR_Response)"
  [sym method req-type resp-type]
  (let [req (name req-type)
        resp (name resp-type)]
    (doseq [t [req resp]]
      (when-not (proto/message-type? t)
        (throw (ex-info (str "defcall " sym ": no such protobuf message type: " t
                             " -- is resources/steam/steam.desc stale?")
                        {:type-name t}))))
    `(defn ~sym [req-map#] (call ~method ~req req-map# ~resp))))
