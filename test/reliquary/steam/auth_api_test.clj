;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.auth-api-test
  "The wrappers are thin: a mock would test the mock. What is worth asserting
   is that every message type resolves (defcall enforces this at COMPILE time,
   so merely loading this namespace is most of the test) and that each wrapper
   passes the arguments Steam expects."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.api :as api]
            [reliquary.steam.auth-api :as auth-api]))

(deftest namespace-compiles-so-every-message-type-resolves
  (is (fn? auth-api/begin-qr))
  (is (fn? auth-api/poll))
  (is (fn? auth-api/rsa-key))
  (is (fn? auth-api/begin-credentials))
  (is (fn? auth-api/submit-guard)))

(deftest begin-qr-sends-the-device-identity-steam-expects
  (let [sent (atom nil)]
    (with-redefs [api/call (fn [_m _v _rt req-map _st] (reset! sent req-map) {})]
      (auth-api/begin-qr))
    (is (= "Reliquary" (:device-friendly-name @sent))
        "this is the name Steam lists on the account's authorized-devices page --
         a leftover from the mauvi copy named someone else's application there")
    (is (= 1 (:platform-type @sent)))
    (is (= "Client" (:website-id @sent)))))

(deftest both-flows-identify-themselves-as-reliquary
  (testing "the credential flow sends the same device identity as the QR flow --
            a login is a login, and an account listing two different device names
            for one application is the copy showing through"
    (let [sent (atom nil)]
      (with-redefs [api/call (fn [_m _v _rt req-map _st] (reset! sent req-map) {})]
        (auth-api/begin-credentials "me" "Y2lwaGVy" 1))
      (is (= "Reliquary" (:device-friendly-name @sent)))
      (is (= "Reliquary" (:device-friendly-name (:device-details @sent)))
          "nested in device_details too, which is what Steam actually reads"))))

(deftest poll-maps-account-name-onto-account
  (let [r (with-redefs [api/call (fn [_ _ _ _ _] {:refresh-token "rt" :account-name "me"})]
            (auth-api/poll 1 "rid"))]
    (is (= "me" (:account r)))
    (is (= "rt" (:refresh-token r)))))

(deftest rsa-key-returns-hex-and-timestamp
  (let [r (with-redefs [api/call (fn [_ _ _ _ _] {:publickey-mod "ab" :publickey-exp "010001"
                                                :timestamp 42})]
            (auth-api/rsa-key "me"))]
    (is (= {:mod "ab" :exp "010001" :timestamp 42} r))))

(deftest begin-credentials-echoes-the-encryption-timestamp
  (testing "Steam rejects the login if the timestamp does not match the key it issued"
    (let [sent (atom nil)]
      (with-redefs [api/call (fn [_m _v _rt req-map _st] (reset! sent req-map) {})]
        (auth-api/begin-credentials "me" "Y2lwaGVy" 99))
      (is (= 99 (:encryption-timestamp @sent)))
      (is (= "Y2lwaGVy" (:encrypted-password @sent)))
      (is (= "me" (:account-name @sent))))))

(deftest submit-guard-passes-code-and-type-through
  (let [sent (atom nil)]
    (with-redefs [api/call (fn [_m _v _rt req-map _st] (reset! sent req-map) {})]
      (auth-api/submit-guard 7 "76561198000000000" "ABCDE" 2))
    (is (= {:client-id 7 :steamid "76561198000000000" :code "ABCDE" :code-type 2} @sent))))

(deftest the-rsa-key-call-is-a-get-and-every-other-call-is-a-post
  (testing "the regression test for a login that died on its FIRST call: we
            POSTed GetPasswordRSAPublicKey, Steam answered 405 with an HTML
            page saying 'This API must be called with a HTTP GET request', and
            that page went into the protobuf parser. Probed against the live
            API -- Steam accepts exactly one verb per method."
    (is (= :get (:reliquary/http-verb (meta #'auth-api/rsa-key*))))
    (doseq [v [#'auth-api/begin-qr* #'auth-api/poll*
               #'auth-api/begin-credentials* #'auth-api/submit-guard*]]
      (is (= :post (:reliquary/http-verb (meta v)))
          (str v " must be a POST")))))
