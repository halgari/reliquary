;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.api-test
  "No network. The HTTP call itself is covered by the live gate -- mocking it
   would only test the mock. What IS tested here is the pure encoding and the
   macro's compile-time contract."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [reliquary.steam.api :as api]
            [reliquary.steam.proto :as proto]))

(deftest form-body-is-base64-then-urlencoded
  (let [payload (proto/encode "CAuthentication_BeginAuthSessionViaQR_Request"
                              {:website-id "Client"})
        body (api/form-body payload)]
    (is (str/starts-with? body "input_protobuf_encoded="))
    (testing "base64 padding and + must be percent-encoded, not sent raw"
      (let [v (subs body (count "input_protobuf_encoded="))]
        (is (not (str/includes? v "+")))
        (is (not (str/includes? v "=")))))))

(deftest form-body-round-trips-back-to-the-message
  (let [m {:website-id "Client" :platform-type 1}
        body (api/form-body (proto/encode "CAuthentication_BeginAuthSessionViaQR_Request" m))
        b64 (java.net.URLDecoder/decode (subs body (count "input_protobuf_encoded=")) "UTF-8")
        raw (.decode (java.util.Base64/getDecoder) b64)]
    (is (= "Client" (:website-id (proto/decode
                                   "CAuthentication_BeginAuthSessionViaQR_Request" raw))))))

(deftest defcall-defines-a-one-arg-fn
  (api/defcall probe-call "BeginAuthSessionViaQR" :post
    CAuthentication_BeginAuthSessionViaQR_Request
    CAuthentication_BeginAuthSessionViaQR_Response)
  (is (fn? probe-call))
  (is (= 1 (count (first (:arglists (meta #'probe-call)))))))

(deftest defcall-passes-arguments-to-call-in-the-right-order
  (testing "method, verb, request type, request map, response type -- matches
            call's signature"
    (api/defcall begin-qr-call "BeginAuthSessionViaQR" :post
      CAuthentication_BeginAuthSessionViaQR_Request
      CAuthentication_BeginAuthSessionViaQR_Response)
    (let [captured (atom nil)
          req-map {:website-id "Client"}]
      (with-redefs [api/call (fn [method verb req-type req-map* resp-type]
                               (reset! captured [method verb req-type req-map* resp-type]))]
        (begin-qr-call req-map))
      (is (= ["BeginAuthSessionViaQR"
              :post
              "CAuthentication_BeginAuthSessionViaQR_Request"
              req-map
              "CAuthentication_BeginAuthSessionViaQR_Response"]
             @captured)))))

(deftest defcall-rejects-an-unknown-message-type-at-compile-time
  (testing "the whole point of the macro -- a typo must not survive to runtime"
    (is (thrown? Exception
                 (eval '(reliquary.steam.api/defcall bad-call "Whatever" :post
                          CAuthentication_NoSuchRequest
                          CAuthentication_BeginAuthSessionViaQR_Response))))))

;; ---------------------------------------------------------------------------
;; the HTTP verb is part of each method's contract
;;
;; Steam answers GetPasswordRSAPublicKey ONLY to GET and the other four
;; IAuthenticationService methods ONLY to POST. Probed against the live API:
;;
;;   GetPasswordRSAPublicKey              POST=405  GET=200
;;   BeginAuthSessionViaQR                POST=200  GET=405
;;   BeginAuthSessionViaCredentials       POST=200  GET=405
;;   PollAuthSessionStatus                POST=400  GET=405
;;   UpdateAuthSessionWithSteamGuardCode  POST=400  GET=405
;;
;; The 405 body says so in as many words: "This API must be called with a HTTP
;; GET request". The verb is per-method and not guessable, so every defcall
;; states it rather than inheriting a default.

(deftest defcall-carries-its-verb-to-call
  (api/defcall verb-call "GetPasswordRSAPublicKey" :get
    CAuthentication_GetPasswordRSAPublicKey_Request
    CAuthentication_GetPasswordRSAPublicKey_Response)
  (let [captured (atom nil)]
    (with-redefs [api/call (fn [method verb rt rm st] (reset! captured [method verb]))]
      (verb-call {:account-name "me"}))
    (is (= ["GetPasswordRSAPublicKey" :get] @captured))))

(deftest defcall-records-its-verb-on-the-var
  (testing "so a wrong verb is assertable in a unit test rather than only
            discoverable as a 405 mid-login"
    (api/defcall meta-get-call "GetPasswordRSAPublicKey" :get
      CAuthentication_GetPasswordRSAPublicKey_Request
      CAuthentication_GetPasswordRSAPublicKey_Response)
    (api/defcall meta-post-call "BeginAuthSessionViaQR" :post
      CAuthentication_BeginAuthSessionViaQR_Request
      CAuthentication_BeginAuthSessionViaQR_Response)
    (is (= :get (:reliquary/http-verb (meta #'meta-get-call))))
    (is (= :post (:reliquary/http-verb (meta #'meta-post-call))))))

(deftest defcall-rejects-a-verb-steam-does-not-answer
  (is (thrown? Exception
               (eval '(reliquary.steam.api/defcall bad-verb-call "BeginAuthSessionViaQR" :put
                        CAuthentication_BeginAuthSessionViaQR_Request
                        CAuthentication_BeginAuthSessionViaQR_Response)))))

(deftest a-get-puts-the-payload-in-the-query-string
  (testing "there is no body on a GET -- the same encoding rides in the URL"
    (let [payload (proto/encode "CAuthentication_GetPasswordRSAPublicKey_Request"
                                {:account-name "someone"})
          url (api/method-url "GetPasswordRSAPublicKey" :get payload)]
      (is (str/includes? url "/IAuthenticationService/GetPasswordRSAPublicKey/v1/"))
      (is (str/includes? url "?input_protobuf_encoded="))
      (is (str/includes? url (api/form-body payload))
          "the query string is exactly the form encoding"))))

(deftest a-post-url-carries-no-query-string
  (let [payload (proto/encode "CAuthentication_BeginAuthSessionViaQR_Request"
                              {:website-id "Client"})]
    (is (not (str/includes? (api/method-url "BeginAuthSessionViaQR" :post payload) "?")))))

;; ---------------------------------------------------------------------------
;; reading Steam's answer
;;
;; This is the gate that turned a perfectly clear 405 into
;; "InvalidProtocolBufferException: Protocol message end-group tag did not
;; match expected tag" thrown from a login thread. Two separate faults: the
;; HTTP status was never looked at, and an ABSENT x-eresult was defaulted to
;; "1" -- so an HTML error page counted as a success and went to the parser.
;; Probed live: real responses are application/octet-stream WITH x-eresult;
;; error pages are text/html with NO x-eresult.

(def ^:private html-405
  (.getBytes (str "<html><head><title>Method Not Allowed</title></head><body>"
                  "<h1>Method Not Allowed</h1>This API must be called with a "
                  "HTTP GET request</body></html>")
             "UTF-8"))

(defn- decode-405 []
  (api/decode-response "GetPasswordRSAPublicKey" 405 nil
                       "CAuthentication_GetPasswordRSAPublicKey_Response" html-405))

(deftest a-non-200-status-is-reported-as-itself-not-as-a-parse-failure
  (let [e (try (decode-405) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "a 405 must raise")
    (is (str/includes? (ex-message e) "405") "the status is the useful part of the message")
    (is (str/includes? (ex-message e) "GetPasswordRSAPublicKey"))
    (is (= 405 (:status (ex-data e))))
    (is (some? (:reliquary/error (ex-data e)))
        "categorized, so cli/-main maps it to an exit code instead of a stack trace")))

(deftest a-405-never-reaches-the-protobuf-parser
  (testing "the actual bug, stated as itself: an HTML page reaching the parser.
            The gate must raise BEFORE any parse is attempted, so what comes out
            is a categorized ExceptionInfo and never a protobuf exception."
    (let [e (try (decode-405) nil (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (not (instance? com.google.protobuf.InvalidProtocolBufferException e))))))

(deftest a-server-side-failure-is-unavailable-and-a-rejection-is-incorrect
  (let [cat (fn [status]
              (try (api/decode-response "PollAuthSessionStatus" status nil
                                        "CAuthentication_PollAuthSessionStatus_Response"
                                        html-405)
                   nil
                   (catch clojure.lang.ExceptionInfo e (:reliquary/error (ex-data e)))))]
    (is (= :unavailable (cat 503)) "steam being unwell is not the caller's fault")
    (is (= :unavailable (cat 500)))
    (is (= :incorrect (cat 400)) "a rejected request is bad input")
    (is (= :incorrect (cat 405)))))

(deftest a-missing-eresult-header-is-not-a-success
  (testing "defaulting an absent header to \"1\" is what let the HTML page
            through -- absence is not agreement"
    (let [e (try (api/decode-response "BeginAuthSessionViaQR" 200 nil
                                      "CAuthentication_BeginAuthSessionViaQR_Response"
                                      (byte-array 0))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "eresult")))))

(deftest a-non-ok-eresult-still-carries-its-code
  (let [e (try (api/decode-response "UpdateAuthSessionWithSteamGuardCode" 200 "88"
                                    "CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response"
                                    (byte-array 0))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "88" (:eresult (ex-data e)))
        "auth/submit-guard! reads this to decide whether to re-prompt")
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest an-ok-response-decodes-to-the-message
  ;; :timestamp comes back a STRING: it is a uint64 on the wire, and
  ;; proto/decode-scalar renders uint64 as a string rather than risk a signed
  ;; long. proto/encode's coerce-scalar parses it back, which is what makes
  ;; auth-api/begin-credentials able to echo it.
  (let [body (proto/encode "CAuthentication_GetPasswordRSAPublicKey_Response"
                           {:publickey-mod "ab" :publickey-exp "010001" :timestamp 42})]
    (is (= {:publickey-mod "ab" :publickey-exp "010001" :timestamp "42"}
           (api/decode-response "GetPasswordRSAPublicKey" 200 "1"
                                "CAuthentication_GetPasswordRSAPublicKey_Response" body)))))

;; ---------------------------------------------------------------------------
;; eresults a person can read
;;
;; A mistyped password is the commonest failure on the login screen, and what
;; that screen showed for it was:
;;
;;   steam BeginAuthSessionViaCredentials failed: that account name or password
;;   was not accepted (eresult 5)
;;
;; Three pieces of jargon in one line, none of them the user's business:
;; `eresult` is Valve's internal C++ enum name, `BeginAuthSessionViaCredentials`
;; is an API method, and both bracket the one clause that actually says what
;; went wrong. The diagnosis leads now, and the machine detail goes in ex-data
;; where the code and the method are already kept.

(defn- message-for
  ([eresult] (message-for "BeginAuthSessionViaCredentials" eresult))
  ([method eresult]
   (try (api/decode-response method 200 eresult
                             "CAuthentication_BeginAuthSessionViaCredentials_Response"
                             (byte-array 0))
        nil
        (catch clojure.lang.ExceptionInfo e (ex-message e)))))

(deftest a-rejected-password-reads-as-a-sentence
  (testing "EResult 5 is InvalidPassword"
    (let [m (message-for "5")]
      (is (= "Steam did not accept that account name or password." m)))))

(deftest a-known-failure-carries-no-jargon-at-all
  (testing "not the enum name, not the API method, not a bare number -- none of
            the three tells the user anything, and the code is in ex-data"
    (doseq [code ["5" "84" "88" "65" "63" "85" "20"]]
      (let [m (message-for code)]
        (is (not (str/includes? m "eresult")) (str code ": no enum name"))
        (is (not (str/includes? m "BeginAuthSessionViaCredentials"))
            (str code ": no API method name"))
        (is (re-matches #"^[A-Z].*[.]$" m)
            (str code ": reads as a sentence, got " (pr-str m)))))))

(deftest the-codes-that-mean-a-code-is-needed-say-so
  (testing "verified against SteamKit's Resources/SteamLanguage/eresult.steamd,
            not from memory: 63 is AccountLogonDenied (an emailed Steam Guard
            code) and 85 is AccountLoginDeniedNeedTwoFactor (an authenticator
            code). 85 read \"needs a phone number verified with Steam\" -- it
            sent the user into Steam's phone settings for a login that would
            have worked with the code this flow can already ask for. The shape
            assertions above passed the whole time, because a well-formed
            sentence can still be false."
    (let [m85 (message-for "85")]
      (is (str/includes? m85 "code"))
      (is (not (str/includes? m85 "phone number"))))
    (is (str/includes? (message-for "63") "code"))))

(deftest a-rate-limit-says-to-wait-rather-than-blaming-the-password
  (testing "EResult 84 is RateLimitExceeded. Reading it as a bad password sends
            the user round the loop that caused it"
    (let [m (message-for "84")]
      (is (str/includes? m "too many"))
      (is (not (str/includes? m "password"))))))

(deftest a-refused-guard-code-names-the-code-not-the-password
  (doseq [code ["88" "65"]]
    (let [m (message-for "UpdateAuthSessionWithSteamGuardCode" code)]
      (is (str/includes? m "Steam Guard code"))
      (is (not (str/includes? m "password"))))))

(deftest an-unrecognised-eresult-still-says-where-it-came-from
  (testing "prose is a courtesy for codes we know; an unknown one has nothing to
            offer the user, so it must not pretend -- it reports the method and
            the number, which is what makes a bug report answerable"
    (let [m (message-for "31337")]
      (is (str/includes? m "31337"))
      (is (str/includes? m "BeginAuthSessionViaCredentials")))))

(deftest the-code-and-method-are-always-in-the-data
  (testing "dropping them from the prose must not drop them from the exception:
            auth/submit-guard!'s retry decision reads :eresult, and a bug report
            needs both"
    (let [e (try (api/decode-response "UpdateAuthSessionWithSteamGuardCode" 200 "88"
                                      "CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response"
                                      (byte-array 0))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= "88" (:eresult (ex-data e))))
      (is (= "UpdateAuthSessionWithSteamGuardCode" (:method (ex-data e)))))))
