;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.proto-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.proto :as proto]))

(deftest round-trips-a-simple-message
  (let [m {:steamid "76561198000000000" :client-sessionid 42}
        out (proto/decode "CMsgProtoBufHeader" (proto/encode "CMsgProtoBufHeader" m))]
    (is (= "76561198000000000" (:steamid out)))
    (is (= 42 (:client-sessionid out)))))

;; ---- the three wire quirks -- each is load-bearing, see the ns docstring ----

(deftest absent-fields-decode-to-nil-not-empty-string
  (testing "a defaulted \"\" would read as a completed login on the first poll"
    (let [out (proto/decode "CAuthentication_PollAuthSessionStatus_Response"
                            (proto/encode "CAuthentication_PollAuthSessionStatus_Response" {}))]
      (is (not (contains? out :refresh-token)))
      (is (nil? (:refresh-token out))))))

(deftest sixty-four-bit-fields-decode-to-strings
  (let [out (proto/decode "CMsgProtoBufHeader"
                          (proto/encode "CMsgProtoBufHeader"
                                        {:steamid "76561198000000000"}))]
    (is (string? (:steamid out)))))

(deftest uint64-renders-unsigned
  (testing "a value past Long/MAX_VALUE must not come back negative"
    (let [big "18446744073709551615"        ; 2^64 - 1
          out (proto/decode "CMsgProtoBufHeader"
                            (proto/encode "CMsgProtoBufHeader" {:steamid big}))]
      (is (= big (:steamid out))))))

(deftest encode-accepts-a-number-where-decode-yields-a-string
  (let [out (proto/decode "CMsgProtoBufHeader" (proto/encode "CMsgProtoBufHeader"
                                                             {:steamid 12345}))]
    (is (= "12345" (:steamid out)))))

(deftest bytes-fields-round-trip-through-base64
  (let [raw (byte-array [1 2 3 4 5])
        b64 (.encodeToString (java.util.Base64/getEncoder) raw)
        out (proto/decode "CMsgMulti" (proto/encode "CMsgMulti" {:message-body raw}))]
    (is (= b64 (:message-body out)))
    (is (= b64 (:message-body (proto/decode "CMsgMulti"
                                            (proto/encode "CMsgMulti" {:message-body b64})))))))

(deftest nil-values-and-unknown-keys-are-ignored
  (let [out (proto/decode "CMsgProtoBufHeader"
                          (proto/encode "CMsgProtoBufHeader"
                                        {:steamid nil :not-a-real-field "x"
                                         :client-sessionid 7}))]
    (is (= 7 (:client-sessionid out)))
    (is (nil? (:steamid out)))))

(deftest every-message-in-the-set-round-trips-empty
  (testing "catches a descriptor that parses but cannot build"
    (doseq [n ["CMsgProtoBufHeader" "CMsgMulti" "CMsgClientLogon"
               "CMsgClientLogonResponse" "CMsgClientLicenseList"
               "CAuthentication_BeginAuthSessionViaQR_Request"
               "CAuthentication_BeginAuthSessionViaQR_Response"
               "CAuthentication_PollAuthSessionStatus_Request"
               "CAuthentication_PollAuthSessionStatus_Response"]]
      (is (map? (proto/decode n (proto/encode n {}))) n))))

(deftest an-unknown-message-type-is-a-categorized-error
  (let [e (try (proto/encode "CMsgNoSuchThing" {}) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest message-type?-answers-both-ways
  (is (proto/message-type? "CMsgClientLogon"))
  (is (not (proto/message-type? "CMsgNoSuchThing"))))
