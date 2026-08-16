;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.login
  "The login screen: a QR code to scan with the Steam mobile app on the left,
   an account-name/password fallback on the right, split by a hairline that
   fades at both ends.

   Two deliberate departures from the mockup (spec §5) live here:

   1. The QR is drawn from zxing's REAL matrix, not the mock's impossible
      21x21 grid -- a Steam challenge URL needs 29-37 modules. See
      `draw-qr!`.
   2. The password field is replaced by a Guard-code field when Steam asks
      for a TYPED code (confirmation types 2 and 3). See `credential-panel`."
  (:require [cljfx.api :as fx]
            [reliquary.steam.qr :as qr]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene.canvas Canvas)
           (javafx.scene.paint Color)))

(def ^:private c theme/color)
(def ^:private qr-box 200.0)   ; the physical size the mock draws, in px

(defn draw-qr!
  "Draw the REAL zxing matrix scaled into a fixed box.

   The mock shows a 21x21 grid -- QR version 1, which holds about 25
   alphanumeric characters and cannot encode a Steam challenge URL. Real ones
   are 29-37 modules. Deriving the module size from qr/module-span keeps the
   card the same size whatever version zxing picks, so it looks identical to
   the design AND scans."
  [^Canvas canvas ^String url]
  (let [g    (.getGraphicsContext2D canvas)
        span (qr/module-span url)
        m    (qr/module-matrix url)
        px   (/ qr-box (double span))]
    (.setFill g (Color/web "#F2F0EE"))
    (.fillRect g 0 0 qr-box qr-box)
    (.setFill g (Color/web (:bg c)))
    (dotimes [y span]
      (dotimes [x span]
        (when (qr/dark-at? m x y)
          (.fillRect g (* x px) (* y px) px px))))))

(defn- field [{:keys [label value on-change secret?]}]
  {:fx/type :v-box :spacing 7
   :children [{:fx/type :label :text label
               :style (theme/style {:-fx-font-size 12 :-fx-text-fill (:text-muted c)})}
              {:fx/type (if secret? :password-field :text-field)
               :text (or value "") :on-text-changed (or on-change (fn [_]))
               :min-height 42
               :style (theme/style {:-fx-background-color (:surface c)
                                     :-fx-border-color (:line-strong c)
                                     :-fx-border-radius 3 :-fx-background-radius 3
                                     :-fx-text-fill (:text c) :-fx-font-size 14})}]})

(defn credential-panel
  "The right half. When Steam demands a TYPED Guard code -- confirmation types
   2 (emailed) and 3 (authenticator) -- the password field is replaced by a code
   field. Types 4 and 5 are approved elsewhere and need no field. Without this
   swap the credential flow blocks forever with no explanation (spec 5)."
  [{:keys [account password guard-code guard-type on-account on-password on-guard on-submit]}]
  (let [guard? (boolean (#{2 3} guard-type))
        ready? (if guard? (seq guard-code) (and (seq account) (seq password)))]
    {:fx/type :v-box :spacing 18 :alignment :center-left
     :padding {:left 72 :right 72}
     :children
     [{:fx/type :label :text "Or use your account"
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                             :-fx-text-fill (:text-muted c)})}
      (field {:label "Account name" :value account :on-change on-account})
      (if guard?
        (field {:label (str "Steam Guard code ("
                             (case guard-type 2 "emailed to you" 3 "from your authenticator app")
                             ")")
                :value guard-code :on-change on-guard})
        (field {:label "Password" :value password :on-change on-password :secret? true}))
      {:fx/type :button :text "Sign in" :disable (not ready?)
       :on-action (or on-submit (fn [_]))
       :min-height 42
       :style (theme/style (if ready?
                              {:-fx-background-color (:gold c) :-fx-text-fill (:bg c)
                               :-fx-background-radius 3 :-fx-font-size 14}
                              {:-fx-background-color (:surface c) :-fx-text-fill (:text-muted c)
                               :-fx-background-radius 3 :-fx-font-size 14}))}]}))

(defn qr-panel
  "The left half. The dot pulses while waiting and goes solid gold on approval;
   the explanatory sentence is load-bearing copy -- users look for a button that
   does not exist."
  [{:keys [challenge-url qr-state]}]
  (let [approved? (= :approved qr-state)]
    {:fx/type :v-box :alignment :center :spacing 22 :padding 48
     :children
     [{:fx/type :label :text "Scan to sign in"
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                             :-fx-text-fill (:text-muted c)})}
      {:fx/type :stack-pane
       :max-width (+ qr-box 36) :max-height (+ qr-box 36)
       :style (theme/style {:-fx-background-color "#F2F0EE"
                             :-fx-background-radius 6 :-fx-padding 18})
       :children (cond-> [{:fx/type fx/ext-on-instance-lifecycle
                            ;; the challenge URL doubles as this node's
                            ;; :fx/key: cljfx's keyed reconciliation
                            ;; (`wrap-many`, used for :children vectors) then
                            ;; deletes and recreates this component -- rather
                            ;; than mutating it in place -- whenever the URL
                            ;; changes, which is exactly what re-drawing the
                            ;; canvas for a new challenge needs.
                            :fx/key challenge-url
                            :on-created #(when challenge-url (draw-qr! % challenge-url))
                            :desc {:fx/type :canvas :width qr-box :height qr-box}}]
                   approved?
                   (conj {:fx/type :v-box :alignment :center
                          :style (theme/style {:-fx-background-color "rgba(12,12,12,0.82)"
                                                :-fx-background-radius 6})
                          :children [{:fx/type :label :text "APPROVED"
                                      :style (theme/style {:-fx-font-family (theme/mono-font)
                                                            :-fx-font-size 13
                                                            :-fx-text-fill (:gold c)})}]}))}
      {:fx/type :h-box :alignment :center :spacing 9
       :children [{:fx/type :region
                   :min-width 7 :min-height 7 :max-width 7 :max-height 7
                   :style (theme/style {:-fx-background-color (if approved? (:gold c) (:text-muted c))
                                        :-fx-background-radius 4})}
                  {:fx/type :label
                   :text (if approved? "Approved — signing in"
                             "Waiting for approval on your device")
                   :style (theme/style {:-fx-font-size 13 :-fx-text-fill (:text-muted c)})}]}
      {:fx/type :label :wrap-text true :max-width 290
       :text (str "Approve the request in the mobile app. "
                  "Sign-in completes on its own — there is no button here.")
       :style (theme/style {:-fx-font-size 12 :-fx-text-fill (:text-muted c)
                             :-fx-text-alignment "center"})}]}))

(defn view
  "The two halves, split by a hairline that fades at both ends -- a 1px region
   with a vertical gradient from transparent through `line` and back."
  [state]
  {:fx/type :h-box
   :children
   [(assoc (qr-panel state) :h-box/hgrow :always)
    {:fx/type :region
     :min-width 1 :max-width 1
     :style (theme/style
             {:-fx-background-color
              (str "linear-gradient(to bottom, transparent, " (:line c)
                   " 20%, " (:line c) " 80%, transparent)")})}
    (assoc (credential-panel state) :h-box/hgrow :always)]})
