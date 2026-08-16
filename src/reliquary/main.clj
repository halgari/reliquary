;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main
  "The desktop entry point: opens a real window and drives it against real
   Steam. Everything below it -- the theme, the screenshot harness, the
   window frame, the login screen, the engine -- is already proven; this is
   the wiring that makes them a running app.

   Deliberately does NOT require `reliquary.ui.shot`: that namespace sets
   `prism.lcdtext=false` at load time to make screenshots easier to review,
   and that property change would leak into the shipped app, degrading text
   rendering on a real monitor."
  (:gen-class)
  (:require [cljfx.api :as fx]
            [reliquary.config :as config]
            [reliquary.steam.auth :as auth]
            [reliquary.ui.app :as app]
            [reliquary.ui.login :as login]
            [reliquary.ui.theme :as theme])
  (:import (javafx.application Platform)))

(defn fx-run!
  "Marshal onto the FX thread. A var so tests can replace it with identity."
  [f]
  (Platform/runLater f))

(defn start-login!
  "Run the BLOCKING QR login on a background thread, marshalling its events
   into `state`. Returns a future so callers (and tests) can await it.

   The refresh token deliberately never enters `state` -- a token in the state
   atom is one careless render away from being on screen. It goes straight to
   config/save-token!."
  [state]
  (future
    (try
      (let [result (auth/login-qr!
                    (fn [event]
                      (when (= :qr (:type event))
                        (fx-run! #(swap! state assoc
                                         :challenge-url (:challenge-url event)
                                         :qr-state :waiting)))
                      nil))]
        (config/save-token! result)
        (fx-run! #(swap! state assoc :qr-state :approved
                         :signed-in? true
                         :status-line (or (:account result) "signed in"))))
      (catch clojure.lang.ExceptionInfo e
        (fx-run! #(swap! state assoc :error (ex-message e)))))))

(defn view [state]
  (app/view (assoc state :content (login/view state))))

(defn sign-out!
  "The title bar's Sign out button, wired here rather than left nil: a
   signed-in state without this handler crashes the renderer the moment
   `:signed-in?` is true, because cljfx cannot coerce a nil :on-action.
   Forgets the stored token and drops back to a fresh QR login."
  [state]
  (config/forget-token!)
  (swap! state assoc
        :signed-in? false :status-line "not signed in" :error nil
        :challenge-url nil :qr-state :waiting)
  (start-login! state))

(defn -main [& _]
  (theme/load-fonts!)
  (let [signed-in (config/token)
        state (atom {:screen :login
                     :status-line (if signed-in (:account signed-in) "not signed in")
                     :signed-in? (boolean signed-in)})]
    (swap! state assoc :on-sign-out (fn [_] (sign-out! state)))
    (let [renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
      (fx/mount-renderer state renderer)
      (when-not signed-in
        (start-login! state)))))
