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
  (api/defcall probe-call "BeginAuthSessionViaQR"
    CAuthentication_BeginAuthSessionViaQR_Request
    CAuthentication_BeginAuthSessionViaQR_Response)
  (is (fn? probe-call))
  (is (= 1 (count (first (:arglists (meta #'probe-call)))))))

(deftest defcall-passes-arguments-to-call-in-the-right-order
  (testing "method, request type, request map, response type -- matches call's signature"
    (api/defcall begin-qr-call "BeginAuthSessionViaQR"
      CAuthentication_BeginAuthSessionViaQR_Request
      CAuthentication_BeginAuthSessionViaQR_Response)
    (let [captured (atom nil)
          req-map {:website-id "Client"}]
      (with-redefs [api/call (fn [method req-type req-map* resp-type]
                               (reset! captured [method req-type req-map* resp-type]))]
        (begin-qr-call req-map))
      (is (= ["BeginAuthSessionViaQR"
              "CAuthentication_BeginAuthSessionViaQR_Request"
              req-map
              "CAuthentication_BeginAuthSessionViaQR_Response"]
             @captured)))))

(deftest defcall-rejects-an-unknown-message-type-at-compile-time
  (testing "the whole point of the macro -- a typo must not survive to runtime"
    (is (thrown? Exception
                 (eval '(reliquary.steam.api/defcall bad-call "Whatever"
                          CAuthentication_NoSuchRequest
                          CAuthentication_BeginAuthSessionViaQR_Response))))))
