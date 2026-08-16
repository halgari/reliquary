;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.main :as main]
            [reliquary.steam.auth :as auth])
  (:import (java.nio.file Files)))

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
