;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.auth :as auth]
            [reliquary.steam.auth-api :as auth-api]
            [reliquary.steam.crypto :as crypto])
  (:import (java.util Base64)))

(def ^:private token
  (let [enc #(.encodeToString (Base64/getUrlEncoder) (.getBytes ^String % "UTF-8"))]
    (str (enc "{}") "." (enc "{\"sub\":\"76561198000000000\",\"exp\":1799999999}") ".sig")))

;; ---- the confirmation preference rule ----
;;
;; The numbering is EAuthSessionGuardType in
;; resources/steam/protos/steam_auth.proto -- 1 None, 2 EmailCode,
;; 3 DeviceCode (TOTP), 4 DeviceConfirmation, 5 EmailConfirmation,
;; 6 MachineToken, 7 LegacyMachineAuth. Anything asserted here that disagrees
;; with that file is wrong; the file is the authority.

(deftest device-confirmation-wins-when-offered
  (testing "type 4 needs no typing -- prefer it over any code type"
    (is (= 4 (auth/preferred-confirmation [2 3 4])))
    (is (= 4 (auth/preferred-confirmation [4])))
    (is (= 4 (auth/preferred-confirmation [3 4])))
    (is (= 4 (auth/preferred-confirmation [4 5]))
        "device confirmation outranks email confirmation")))

(deftest email-confirmation-beats-a-code-but-not-device-confirmation
  (is (= 5 (auth/preferred-confirmation [2 5])))
  (is (= 5 (auth/preferred-confirmation [3 5])))
  (is (= 5 (auth/preferred-confirmation [5])))
  (is (= 4 (auth/preferred-confirmation [5 4 3 2]))))

(deftest otherwise-the-lowest-code-type-wins
  (is (= 2 (auth/preferred-confirmation [2 3])))
  (is (= 2 (auth/preferred-confirmation [2])))
  (is (= 3 (auth/preferred-confirmation [3])) "TOTP alone must still be pursued"))

(deftest types-that-need-no-confirmation-yield-nil
  (testing "1 is None -- the account has no Steam Guard at all"
    (is (nil? (auth/preferred-confirmation [1]))))
  (is (nil? (auth/preferred-confirmation [6])) "6 is a machine token")
  (is (nil? (auth/preferred-confirmation [7])) "7 is legacy machine auth")
  (is (nil? (auth/preferred-confirmation [0])))
  (is (nil? (auth/preferred-confirmation [])))
  (is (nil? (auth/preferred-confirmation [1 6 7]))))

;; ---- the QR flow ----

(deftest qr-fires-the-challenge-then-returns-the-token
  (let [events (atom [])
        polls (atom 0)]
    (with-redefs [auth-api/begin-qr (fn [] {:client-id 1 :request-id "r"
                                            :challenge-url "https://s.team/q/1/1" :interval 5})
                  auth-api/poll (fn [_ _]
                                  (if (< (swap! polls inc) 3)
                                    {}
                                    {:refresh-token token :account "me"}))]
      (binding [auth/*poll-sleep-ms* 1]
        (let [r (auth/login-qr! (fn [e] (swap! events conj e)))]
          (is (= token (:refresh-token r)))
          (is (= "me" (:account r)))
          (is (= "76561198000000000" (:steam-id r)) "steam-id comes from the token's sub claim")))
      (is (= [{:type :qr :challenge-url "https://s.team/q/1/1"}] @events))
      (is (= 3 @polls)))))

(deftest a-rotated-challenge-refires-the-event-and-switches-client-id
  (let [events (atom [])
        seen-ids (atom [])
        polls (atom 0)]
    (with-redefs [auth-api/begin-qr (fn [] {:client-id 1 :request-id "r"
                                            :challenge-url "url-1" :interval 5})
                  auth-api/poll (fn [cid _]
                                  (swap! seen-ids conj cid)
                                  (if (< (swap! polls inc) 2)
                                    {:new-challenge-url "url-2" :new-client-id 99}
                                    {:refresh-token token :account "me"}))]
      (binding [auth/*poll-sleep-ms* 1]
        (auth/login-qr! (fn [e] (swap! events conj e))))
      (is (= ["url-1" "url-2"] (mapv :challenge-url @events)))
      (is (= [1 99] @seen-ids) "the second poll must use the new client id"))))

(deftest an-empty-refresh-token-is-not-a-completed-login
  (testing "the no-defaults-fill wire quirk exists exactly for this"
    (let [polls (atom 0)]
      (with-redefs [auth-api/begin-qr (fn [] {:client-id 1 :request-id "r" :interval 5})
                    auth-api/poll (fn [_ _]
                                    (if (< (swap! polls inc) 2)
                                      {:refresh-token ""}
                                      {:refresh-token token}))]
        (binding [auth/*poll-sleep-ms* 1]
          (auth/login-qr! (fn [_])))
        (is (= 2 @polls) "a blank token must keep polling")))))

;; ---- the credential flow ----

(deftest credentials-encrypt-the-password-and-echo-the-timestamp
  (let [sent (atom nil)
        encrypt-args (atom nil)]
    (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 77})
                  crypto/encrypt-password (fn [pw m e]
                                            (reset! encrypt-args [pw m e])
                                            "ENCRYPTED-FAKE")
                  auth-api/begin-credentials (fn [u enc ts]
                                               (reset! sent {:u u :enc enc :ts ts})
                                               {:client-id 1 :request-id "r" :confirmations []})
                  auth-api/poll (fn [_ _] {:refresh-token token :account "me"})]
      (binding [auth/*poll-sleep-ms* 1]
        (auth/login-credentials! "me" "hunter2" (fn [_])))
      (is (= ["hunter2" "c5" "010001"] @encrypt-args)
          "the rsa-key material must reach encrypt-password")
      (is (= "me" (:u @sent)))
      (is (= 77 (:ts @sent)))
      (is (string? (:enc @sent)))
      (is (not= "hunter2" (:enc @sent)) "the password must never go out in the clear")
      (is (= "ENCRYPTED-FAKE" (:enc @sent))
          "begin-credentials must receive encrypt-password's output, not the plaintext"))))

(deftest a-code-type-prompts-and-submits
  (let [submitted (atom nil)]
    (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                  crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                  auth-api/begin-credentials (fn [_ _ _] {:client-id 5 :request-id "r"
                                                          :steamid "76561198000000000"
                                                          :confirmations [2]})
                  auth-api/submit-guard (fn [cid sid code t]
                                          (reset! submitted [cid sid code t]) {})
                  auth-api/poll (fn [_ _] {:refresh-token token})]
      (binding [auth/*poll-sleep-ms* 1]
        (auth/login-credentials! "me" "pw" (fn [e]
                                             (is (= :guard-needed (:type e)))
                                             (is (= 2 (:code-type e)))
                                             "ABCDE")))
      (is (= [5 "76561198000000000" "ABCDE" 2] @submitted)))))

(deftest a-totp-account-is-prompted-and-submits-its-code
  (testing "type 3 is the authenticator-app code -- it MUST be typed and submitted"
    (let [submitted (atom nil)
          events (atom [])]
      (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                    crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                    auth-api/begin-credentials (fn [_ _ _] {:client-id 7 :request-id "r"
                                                            :steamid "76561198000000000"
                                                            :confirmations [3]})
                    auth-api/submit-guard (fn [cid sid code t]
                                            (reset! submitted [cid sid code t]) {})
                    auth-api/poll (fn [_ _] {:refresh-token token})]
        (binding [auth/*poll-sleep-ms* 1]
          (auth/login-credentials! "me" "pw" (fn [e] (swap! events conj e) "98765")))
        (is (= [{:type :guard-needed :code-type 3}] @events)
            "a TOTP account must be asked for its code, not left polling forever")
        (is (= [7 "76561198000000000" "98765" 3] @submitted))))))

(deftest an-account-with-no-steam-guard-completes-without-a-prompt
  (testing "type 1 is None -- there is nothing to confirm, so demanding a code
            would make credential login impossible"
    (let [events (atom [])
          submitted (atom false)]
      (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                    crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                    auth-api/begin-credentials (fn [_ _ _] {:client-id 5 :request-id "r"
                                                            :confirmations [1]})
                    auth-api/submit-guard (fn [& _] (reset! submitted true) {})
                    auth-api/poll (fn [_ _] {:refresh-token token :account "me"})]
        (binding [auth/*poll-sleep-ms* 1]
          (is (= token (:refresh-token (auth/login-credentials!
                                        "me" "pw" (fn [e] (swap! events conj e) nil))))))
        (is (= [] @events) "nothing to confirm means nothing to prompt")
        (is (not @submitted))))))

(deftest device-confirmation-does-not-prompt-but-does-tell-the-user
  (testing "type 4 is approved in the app; asking for a code would be wrong, but
            saying nothing leaves the user staring at a silent poll loop"
    (let [submitted (atom false)
          events (atom [])]
      (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                    crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                    auth-api/begin-credentials (fn [_ _ _] {:client-id 5 :request-id "r"
                                                            :confirmations [4]})
                    auth-api/submit-guard (fn [& _] (reset! submitted true) {})
                    auth-api/poll (fn [_ _] {:refresh-token token})]
        (binding [auth/*poll-sleep-ms* 1]
          (auth/login-credentials! "me" "pw" (fn [e] (swap! events conj e) "x")))
        (is (not @submitted) "no code exists to submit")
        (is (= [{:type :confirmation-pending :confirmation-type 4}] @events))))))

(deftest email-confirmation-does-not-prompt-but-does-tell-the-user
  (let [events (atom [])]
    (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                  crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                  auth-api/begin-credentials (fn [_ _ _] {:client-id 5 :request-id "r"
                                                          :confirmations [5]})
                  auth-api/submit-guard (fn [& _] (throw (AssertionError. "must not submit")))
                  auth-api/poll (fn [_ _] {:refresh-token token})]
      (binding [auth/*poll-sleep-ms* 1]
        (auth/login-credentials! "me" "pw" (fn [e] (swap! events conj e) nil)))
      (is (= [{:type :confirmation-pending :confirmation-type 5}] @events)))))

(deftest an-unknown-username-is-a-categorized-error
  (with-redefs [auth-api/rsa-key (fn [_] {})]
    (let [e (try (auth/login-credentials! "nobody" "pw" (fn [_])) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :incorrect (:reliquary/error (ex-data e)))))))
