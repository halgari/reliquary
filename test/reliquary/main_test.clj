;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.main :as main]
            [reliquary.steam.auth :as auth])
  (:import (java.nio.file Files)
           (java.util Base64)))

(defn- jwt
  "A minimal, unsigned-but-well-formed Steam-shaped JWT carrying only `exp`,
   built the same way steam.auth-test does -- enough for
   reliquary.session/expired? to read, nothing more."
  [exp]
  (let [enc #(.encodeToString (Base64/getUrlEncoder) (.getBytes ^String % "UTF-8"))]
    (str (enc "{}") "." (enc (str "{\"sub\":\"1\",\"exp\":" exp "}")) ".sig")))

;; The first test below does NOT stub config/save-token! -- start-login!
;; really does call it. Without this isolation it would write a literal
;; "SECRET" refresh token over the developer's actual
;; ~/.config/reliquary/config.edn, destroying a real signed-in session for a
;; test assertion. Every test in this namespace runs against a throwaway
;; directory for exactly that reason -- same pattern as config-test's
;; with-tmp.
(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-main-test"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (binding [config/*config-dir* d config/*data-dir* d] (f))
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest a-qr-event-lands-in-state-on-the-fx-thread
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (fn [on-event]
                                        (on-event {:type :qr :challenge-url "https://s.team/q/9"})
                                        {:refresh-token "SECRET" :account "someone"})
                      main/fx-run! (fn [f] (f))]   ; identity in tests
          @(main/start-login! state)
          (is (= "https://s.team/q/9" (:challenge-url @state))))))))

(deftest the-token-reaches-config-and-never-state
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (constantly {:refresh-token "SECRET" :account "someone"})
                      main/fx-run! (fn [f] (f))
                      config/save-token! (fn [t] (is (= "SECRET" (:refresh-token t))) t)]
          @(main/start-login! state)
          (is (not (str/includes? (pr-str @state) "SECRET"))
              "a token in the state atom is a token one render away from the screen"))))))

(deftest signing-out-forgets-the-token-and-restarts-login
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token "OLD" :account "someone"})
      (let [state (atom {:signed-in? true :status-line "someone"})
            forgot? (atom false)]
        (with-redefs [auth/login-qr! (fn [on-event]
                                        (on-event {:type :qr :challenge-url "https://s.team/q/new"})
                                        {:refresh-token "NEW" :account "someone"})
                      main/fx-run! (fn [f] (f))
                      config/forget-token! (fn [] (reset! forgot? true) nil)]
          @(main/sign-out! state)
          (is @forgot? "sign-out must actually forget the stored token")
          (is (= "https://s.team/q/new" (:challenge-url @state))
              "sign-out restarts the login flow rather than leaving a dead screen"))))))

(deftest a-failed-login-becomes-a-rendered-error-not-a-crash
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (fn [_] (error/raise :unavailable "steam is down"))
                      main/fx-run! (fn [f] (f))]
          @(main/start-login! state)
          (is (str/includes? (str (:error @state)) "steam is down")))))))

(deftest the-initial-state-always-wires-a-sign-out-handler
  (testing "app/title-bar renders a Sign out button unconditionally whenever
            :signed-in? is true, wired to :on-action on-sign-out -- cljfx
            cannot coerce a nil handler, so a missing wiring here is not a
            style nit, it is the exact crash caught live while proving the
            QR flow against real Steam. This asserts the wiring directly,
            without mounting a real Stage, so removing it fails a plain unit
            test instead of only a manual run."
    (is (fn? (:on-sign-out (main/initial-state (atom {}) {:refresh-token "x" :account "a"})))
        "signed-in state must carry a real handler")
    (is (fn? (:on-sign-out (main/initial-state (atom {}) nil)))
        "wired unconditionally -- the atom's shape must not depend on this")))

(deftest an-expired-token-is-not-usable
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token (jwt 1) :account "someone"})
      (is (nil? (main/usable-token (quot (System/currentTimeMillis) 1000)))
          "presence on disk is not enough -- an expired token must not produce a signed-in state")
      (is (false? (:signed-in? (main/initial-state (atom {}) (main/usable-token
                                                               (quot (System/currentTimeMillis) 1000)))))
          "an expired token must land on the login screen, not a broken signed-in one"))))

(deftest a-valid-unexpired-token-is-usable
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token (jwt 4102444800) :account "someone"}) ; year 2100
      (is (some? (main/usable-token (quot (System/currentTimeMillis) 1000)))
          "a token that has not yet expired is exactly what \"usable\" means"))))

(deftest every-state-mutation-during-start-login-goes-through-fx-run
  (testing "the four other tests above all replace fx-run! with identity, so
            none of them prove marshalling actually happens -- a start-login!
            that mutated `state` directly, bypassing fx-run!, would still
            pass every one of them. This watches the atom itself and records
            whether a change ever landed outside fx-run!'s dynamic extent."
    (with-tmp
      (fn []
        (let [state (atom {})
              in-fx-run? (atom false)
              violations (atom [])]
          (add-watch state ::probe
                     (fn [_ _ _ _]
                       (when-not @in-fx-run?
                         (swap! violations conj @state))))
          (with-redefs [auth/login-qr! (fn [on-event]
                                          (on-event {:type :qr :challenge-url "https://s.team/q/9"})
                                          {:refresh-token "SECRET" :account "someone"})
                        main/fx-run! (fn [f]
                                       (reset! in-fx-run? true)
                                       (try (f) (finally (reset! in-fx-run? false))))]
            @(main/start-login! state))
          (remove-watch state ::probe)
          (is (empty? @violations)
              "every mutation start-login! made to state must have happened inside fx-run!"))))))

;; --- regression: the app must not show the login screen once signed in -----
;; Reported live: "I logged in via the QR code but the app doesn't move on."
;; `view` rendered login/view unconditionally, so a signed-in start showed a
;; BLANK QR card reading "Waiting for approval on your device" -- asking the
;; user to scan a challenge that was never fetched.

(deftest a-signed-in-state-does-not-render-the-login-screen
  (testing "signed in with no challenge url -- the restart case that was broken"
    (let [s (pr-str (main/view {:signed-in? true :status-line "someone"
                                :challenge-url nil :qr-state nil}))]
      (is (not (str/includes? s "Waiting for approval on your device"))
          "a signed-in user must never be told to scan a code")
      (is (not (str/includes? s "Scan to sign in")))
      (is (str/includes? s "someone")
          "the signed-in screen should say who is signed in"))))

(deftest the-login-screen-still-renders-when-not-signed-in
  (let [s (pr-str (main/view {:signed-in? false :challenge-url "https://s.team/q/1/2"
                              :qr-state :waiting}))]
    (is (str/includes? s "Scan to sign in"))))
