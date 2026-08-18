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
            [reliquary.steam.crypto :as crypto]
            [reliquary.error :as error])
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

;; ---- abandoning a login in flight ----
;;
;; The desktop app starts a QR poll the moment the login screen appears, and
;; that loop otherwise runs for the life of the process. A credential login
;; that wins the race has to be able to stop it, or the app keeps hitting
;; Steam's auth API every five seconds forever behind a library screen.

(deftest an-abandoned-qr-login-stops-polling-and-returns-nil
  (let [polls (atom 0)]
    (with-redefs [auth-api/begin-qr (fn [] {:client-id 1 :request-id "r"
                                            :challenge-url "url" :interval 5})
                  auth-api/poll (fn [_ _] (swap! polls inc) {})]
      (binding [auth/*poll-sleep-ms* 1]
        (is (nil? (auth/login-qr! (fn [_]) {:abort? (fn [] (>= @polls 2))}))
            "an abandoned login yields nil -- there is no token and no error"))
      (is (= 2 @polls)
          "abort is checked BEFORE each poll, so the aborting iteration makes no request"))))

(deftest an-abandoned-credential-login-stops-polling-and-returns-nil
  (let [polls (atom 0)]
    (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                  crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                  auth-api/begin-credentials (fn [_ _ _] {:client-id 1 :request-id "r"
                                                          :confirmations [1] :interval 5})
                  auth-api/poll (fn [_ _] (swap! polls inc) {})]
      (binding [auth/*poll-sleep-ms* 1]
        (is (nil? (auth/login-credentials! "me" "pw" (fn [_]) {:abort? (fn [] (>= @polls 2))}))))
      (is (= 2 @polls)))))

(deftest an-absent-abort-predicate-never-aborts
  (testing "the one-argument arities the QR flow and the CLI already use must
            keep polling to completion"
    (let [polls (atom 0)]
      (with-redefs [auth-api/begin-qr (fn [] {:client-id 1 :request-id "r" :interval 5})
                    auth-api/poll (fn [_ _] (if (< (swap! polls inc) 3)
                                              {}
                                              {:refresh-token token :account "me"}))]
        (binding [auth/*poll-sleep-ms* 1]
          (is (= token (:refresh-token (auth/login-qr! (fn [_])))))
          (is (= 3 @polls)))))))

;; ---- a code Steam rejects ----
;;
;; api/call raises on any non-OK x-eresult, so before this a single mistyped
;; character in a five-character code ended the whole login and sent the user
;; back to the password field. The client id and steamid are still perfectly
;; valid at that point; only the code was wrong.
;;
;; The retryable eresults are EResult 65 InvalidLoginAuthCode (a bad emailed
;; code) and 88 TwoFactorCodeMismatch (a bad authenticator code). That is the
;; whole set: nothing else on this call is fixed by typing again.

(defn- guard-rejection
  "What api/call raises when Steam refuses a Guard code -- same shape, since
   the retry decision reads :eresult out of the ex-data."
  [eresult]
  (error/raise :incorrect
               (str "steam UpdateAuthSessionWithSteamGuardCode failed, eresult " eresult)
               {:eresult eresult :method "UpdateAuthSessionWithSteamGuardCode"}))

(defn- with-credential-stubs
  "begin-credentials offering `confirmations`, a submit-guard driven by
   `submit`, and a poll that immediately succeeds."
  [confirmations submit f]
  (with-redefs [auth-api/rsa-key (fn [_] {:mod "c5" :exp "010001" :timestamp 1})
                crypto/encrypt-password (fn [_ _ _] "ENCRYPTED-FAKE")
                auth-api/begin-credentials (fn [_ _ _] {:client-id 5 :request-id "r"
                                                        :steamid "76561198000000000"
                                                        :confirmations confirmations})
                auth-api/submit-guard submit
                auth-api/poll (fn [_ _] {:refresh-token token :account "me"})]
    (binding [auth/*poll-sleep-ms* 1]
      (f))))

(deftest a-mistyped-authenticator-code-is-re-prompted-not-fatal
  (let [codes (atom [])
        events (atom [])]
    (with-credential-stubs
      [3]
      (fn [_ _ code _]
        (swap! codes conj code)
        (if (= "WRONG" code) (guard-rejection "88") {}))
      (fn []
        (let [r (auth/login-credentials!
                 "me" "pw"
                 (fn [e]
                   (swap! events conj e)
                   (if (:retry? e) "RIGHT" "WRONG")))]
          (is (= token (:refresh-token r))
              "the login must complete on the second code, not die on the first"))))
    (is (= ["WRONG" "RIGHT"] @codes))
    (is (= [{:type :guard-needed :code-type 3}
            {:type :guard-needed :code-type 3 :retry? true}]
           @events)
        ":retry? tells the ops layer to say the last code was refused")))

(deftest a-mistyped-emailed-code-is-re-prompted-too
  (testing "eresult 65 is the emailed-code equivalent of 88"
    (let [codes (atom [])]
      (with-credential-stubs
        [2]
        (fn [_ _ code _]
          (swap! codes conj code)
          (if (= "BAD" code) (guard-rejection "65") {}))
        (fn []
          (auth/login-credentials! "me" "pw" (fn [e] (if (:retry? e) "GOOD" "BAD")))))
      (is (= ["BAD" "GOOD"] @codes)))))

(deftest a-failure-that-retyping-cannot-fix-is-not-re-prompted
  (testing "eresult 84 is RateLimitExceeded -- asking for the code again would
            spend the user's next attempt on the same refusal"
    (let [attempts (atom 0)]
      (with-credential-stubs
        [3]
        (fn [_ _ _ _] (swap! attempts inc) (guard-rejection "84"))
        (fn []
          (let [e (try (auth/login-credentials! "me" "pw" (constantly "12345")) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) "a rate limit must surface, not loop")
            (is (= "84" (:eresult (ex-data e))) "the original eresult must survive"))))
      (is (= 1 @attempts) "exactly one submission"))))

(deftest re-prompting-for-a-guard-code-is-bounded
  (testing "an endlessly wrong code must eventually surface as the error it is"
    (let [attempts (atom 0)]
      (with-credential-stubs
        [3]
        (fn [_ _ _ _] (swap! attempts inc) (guard-rejection "88"))
        (fn []
          (is (thrown? clojure.lang.ExceptionInfo
                       (auth/login-credentials! "me" "pw" (constantly "00000"))))))
      (is (= 3 @attempts) "three tries at the code, then the error stands"))))

(deftest an-empty-retyped-code-ends-the-login-rather-than-submitting-blank
  (let [attempts (atom 0)]
    (with-credential-stubs
      [3]
      (fn [_ _ _ _] (swap! attempts inc) (guard-rejection "88"))
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
                     (auth/login-credentials! "me" "pw" (fn [e] (if (:retry? e) "" "FIRST")))))))
    (is (= 1 @attempts) "a blank retry must not reach Steam")))

(deftest a-code-steam-has-already-accepted-is-not-a-failure
  (testing "EResult 29 DuplicateRequest is what Steam says when the prompt was
            already approved in the mobile app and a typed code arrives after
            it. The reference client documents this exactly -- 'we do not throw
            on it here because authentication will succeed on the next poll' --
            and api/call raises on every non-OK eresult, so without this the
            login dies one poll short of the token it was about to get."
    (let [polls (atom 0)]
      (with-credential-stubs
        [3]
        (fn [_ _ _ _] (guard-rejection "29"))
        (fn []
          (with-redefs [auth-api/poll (fn [_ _] (swap! polls inc)
                                        {:refresh-token token :account "me"})]
            (is (= token (:refresh-token (auth/login-credentials!
                                          "me" "pw" (constantly "12345"))))))))
      (is (= 1 @polls) "it must go on to poll, not raise and not re-prompt"))))

(deftest a-duplicate-request-does-not-consume-a-retry-prompting-again
  (testing "re-prompting on 29 would ask for a code Steam has already accepted"
    (let [events (atom [])]
      (with-credential-stubs
        [3]
        (fn [_ _ _ _] (guard-rejection "29"))
        (fn []
          (auth/login-credentials! "me" "pw" (fn [e] (swap! events conj e) "12345"))))
      (is (= [{:type :guard-needed :code-type 3}] @events)
          "asked once, accepted once"))))
