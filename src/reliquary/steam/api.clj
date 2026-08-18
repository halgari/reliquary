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
                          HttpResponse HttpResponse$BodyHandlers)
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

(def ^:private verbs
  "The HTTP verbs Steam answers on this service. Each method accepts exactly
   ONE of them; there is no sensible default, which is why `defcall` demands it."
  #{:get :post})

(defn method-url
  "The URL for `method` under `verb`. A GET has no body, so the payload rides in
   the query string -- which is the form encoding verbatim. A POST carries it in
   the body, so its URL is bare."
  ^String [^String method verb ^bytes payload]
  (str base method "/v1/"
       (when (= :get verb) (str "?" (form-body payload)))))

(def ^:private eresult-prose
  "Plain sentences for the EResults a login can actually produce. The numbering
   is Steam's EResult enum.

   Deliberately NOT a translation of the whole enum -- only the codes a person
   can act on, which is what an error strip is for. Each one is a whole sentence
   naming what to do about it where there is anything to do, and none of them
   mentions the code, the enum, or the API method: a mistyped password is the
   commonest failure on the login screen, and

     steam BeginAuthSessionViaCredentials failed: that account name or password
     was not accepted (eresult 5)

   put three pieces of Valve-internal vocabulary around the one clause that told
   the user anything. The machine-readable half of that string lives in the
   exception's data, where `auth/submit-guard!` already reads :eresult and a bug
   report can quote both."
  ;; The names are from SteamKit's Resources/SteamLanguage/eresult.steamd and
  ;; were checked there, not recalled -- 85 read "needs a phone number verified
  ;; with Steam" until it was, which sent the user into Steam's phone settings
  ;; for a login that a code this flow already knows how to ask for would have
  ;; completed. A well-formed sentence can still be false, so anything added
  ;; here gets its MEANING asserted in api_test, not just its shape.
  {"5"  "Steam did not accept that account name or password."          ; InvalidPassword
   "20" "Steam is temporarily unavailable. Try again in a few minutes." ; ServiceUnavailable
   "63" "This account needs the Steam Guard code Steam emails you, and none was given." ; AccountLogonDenied
   "65" "Steam did not accept that Steam Guard code."                  ; InvalidLoginAuthCode
   "84" "Steam has seen too many attempts on this account. Wait a few minutes before trying again." ; RateLimitExceeded
   "85" "This account needs a Steam Guard code from its authenticator app, and none was given." ; AccountLoginDeniedNeedTwoFactor
   "88" "Steam did not accept that Steam Guard code."})                ; TwoFactorCodeMismatch

(defn eresult-message
  "What to show a person for a non-OK eresult.

   A recognised code becomes its sentence and nothing else. An unrecognised one
   has no sentence to offer, so it says which call failed and with what number
   rather than inventing a diagnosis -- that is the case where the method and the
   code ARE the useful content, because the only thing to do with them is report
   them."
  [^String method eresult]
  (or (eresult-prose (str eresult))
      (str "steam " method " failed, eresult " eresult)))

(defn decode-response
  "Steam's answer, checked and then decoded.

   Both checks here are load-bearing, and neither existed. A credential login
   died on its very first call with `InvalidProtocolBufferException: Protocol
   message end-group tag did not match expected tag`, because:

     1. the HTTP status was never looked at, and
     2. an ABSENT x-eresult header was defaulted to \"1\" -- read as success.

   Steam had answered 405 with an HTML page reading \"This API must be called
   with a HTTP GET request\", and that page went straight to the protobuf
   parser. Probed against the live API: real responses are
   application/octet-stream WITH an x-eresult, and error pages are text/html
   with none. So an absent header is not agreement, and the status is the most
   useful thing in the whole response when something is wrong."
  [^String method status eresult resp-type ^bytes body]
  (when-not (= 200 status)
    (error/raise (if (<= 500 (long status)) :unavailable :incorrect)
                 (str "steam " method " returned http " status)
                 {:status status :method method}))
  (when-not eresult
    (error/raise :unavailable
                 (str "steam " method " answered http 200 with no eresult header")
                 {:status status :method method}))
  (when-not (= "1" eresult)
    (error/raise :incorrect (eresult-message method eresult)
                 {:eresult eresult :method method}))
  (proto/decode resp-type body))

(defn call
  "Send `req-map` as `req-type` to `method` using `verb`, returning the decoded
   `resp-type`."
  [^String method verb req-type req-map resp-type]
  (let [payload (proto/encode req-type req-map)
        builder (-> (HttpRequest/newBuilder (URI/create (method-url method verb payload)))
                    (.timeout (Duration/ofSeconds 30)))
        req (-> (if (= :get verb)
                  (.GET builder)
                  (-> builder
                      (.header "Content-Type" "application/x-www-form-urlencoded")
                      (.POST (HttpRequest$BodyPublishers/ofString
                              (form-body payload) StandardCharsets/UTF_8))))
                (.build))
        ^HttpResponse resp
        (try
          (.send ^HttpClient @shared-http-client req (HttpResponse$BodyHandlers/ofByteArray))
          (catch Exception e
            (error/raise :unavailable (str "steam auth api unreachable: " (ex-message e)))))]
    (decode-response method
                     (.statusCode resp)
                     (-> (.headers resp) (.firstValue "x-eresult") (.orElse nil))
                     resp-type
                     (.body resp))))

(defmacro defcall
  "Define a wrapper for an IAuthenticationService method.

   `verb` is :get or :post and is REQUIRED, never defaulted. Steam answers each
   method on exactly one verb and returns a 405 HTML page for the other, so the
   verb is part of the method's contract rather than a detail of the transport.
   GetPasswordRSAPublicKey is the only GET on this service; every other method
   is a POST.

   The message type names AND the verb are validated AT MACROEXPANSION -- the
   types against the descriptor set -- so a typo or a wrong verb is a compile
   error instead of a runtime failure on a path that only runs mid-login. The
   verb is also recorded on the defined var as :reliquary/http-verb, which is
   what lets a plain unit test assert it.

     (defcall begin-qr \"BeginAuthSessionViaQR\" :post
       CAuthentication_BeginAuthSessionViaQR_Request
       CAuthentication_BeginAuthSessionViaQR_Response)"
  [sym method verb req-type resp-type]
  (let [req (name req-type)
        resp (name resp-type)]
    (when-not (contains? verbs verb)
      (throw (ex-info (str "defcall " sym ": verb must be :get or :post, got " (pr-str verb))
                      {:verb verb})))
    (doseq [t [req resp]]
      (when-not (proto/message-type? t)
        (throw (ex-info (str "defcall " sym ": no such protobuf message type: " t
                             " -- is resources/steam/steam.desc stale?")
                        {:type-name t}))))
    `(defn ~(vary-meta sym assoc :reliquary/http-verb verb)
       [req-map#] (call ~method ~verb ~req req-map# ~resp))))
