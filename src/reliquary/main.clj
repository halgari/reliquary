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
            [reliquary.session :as session]
            [reliquary.steam.auth :as auth]
            [reliquary.ui.app :as app]
            [reliquary.ui.login :as login]
            [reliquary.ui.signed-in :as signed-in]
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

(defn view
  "Dispatch on whether the user is signed in.

   This used to render `login/view` unconditionally, which produced the bug a
   user reported as \"I logged in via the QR code but the app doesn't move
   on\": approving the QR set `:qr-state :approved` and then left the user on
   the login screen forever, and RESTARTING with a valid token skipped
   `start-login!` entirely, so the screen showed a blank QR card still asking
   to be scanned. One missing branch, two broken states."
  [state]
  (app/view (assoc state :content (if (:signed-in? state)
                                    (signed-in/view state)
                                    (login/view state)))))

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

(defn usable-token
  "config/token's value, but only when it is not expired as of `now-secs` --
   presence on disk is not the same thing as usability. An expired token used
   to be enough to land -main on a signed-in screen whose QR panel never
   fetches anything and whose only working control is Sign out: a broken
   screen dressed up as a good one. session/expired? is pure and offline (it
   only reads the JWT's own exp claim), so this costs nothing to check before
   ever mounting a window."
  [now-secs]
  (when-let [t (config/token)]
    (when-not (session/expired? (:refresh-token t) now-secs)
      t)))

(defn initial-state
  "The renderer's starting state map, built before `fx/mount-renderer` ever
   runs. `:on-sign-out` is populated here -- not by a later `swap!` -- for a
   concrete reason: app/title-bar renders a Sign out button unconditionally
   whenever `:signed-in?` is true, wired to `:on-action on-sign-out`, and
   cljfx cannot coerce a nil handler. A first render with `:signed-in? true`
   and no `:on-sign-out` crashes the renderer -- this is not hypothetical, it
   is the exact bug main_test.clj's
   `the-initial-state-always-wires-a-sign-out-handler` guards, caught live
   while proving the QR flow against real Steam. Building the whole map in
   one function, called with the not-yet-populated `state` atom already in
   hand, is what makes that wiring a thing a plain unit test can assert on
   without mounting a real Stage."
  [state signed-in]
  {:screen :login
   :status-line (if signed-in (:account signed-in) "not signed in")
   :signed-in? (boolean signed-in)
   :on-sign-out (fn [_] (sign-out! state))})

(defn -main [& _]
  (theme/load-fonts!)
  (let [signed-in (usable-token (quot (System/currentTimeMillis) 1000))
        state (atom nil)]
    (reset! state (initial-state state signed-in))
    (let [renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
      (fx/mount-renderer state renderer)
      (when-not signed-in
        (start-login! state)))))
