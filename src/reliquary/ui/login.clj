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
            [reliquary.ui.anim :as anim]
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
      ;; The gradient/glow are accents on an otherwise-flat surface (design
      ;; delta: "No gradients, glows or drop shadows on surfaces ... almost
      ;; entirely on accents"). A DISABLED button is not an accent -- it is
      ;; explicitly the flat, inert state -- so it keeps the old solid
      ;; surface/text-muted styling and carries NO :-fx-effect key at all,
      ;; not even a dim one.
      {:fx/type :button :text "Sign in" :disable (not ready?)
       :on-action (or on-submit (fn [_]))
       :min-height 42 :max-width Double/MAX_VALUE
       :style (theme/style (if ready?
                              {:-fx-background-color (:button theme/gradients)
                               :-fx-text-fill (:bg c)
                               :-fx-background-radius 3 :-fx-font-size 14
                               :-fx-effect (theme/glow (:gold c)
                                                        {:blur 22 :spread -10 :dy 6 :alpha 0.9})}
                              {:-fx-background-color (:surface c) :-fx-text-fill (:text-muted c)
                               :-fx-background-radius 3 :-fx-font-size 14}))}]}))

(def ^:private frame-size
  "The QR card's full outer box -- the 200px matrix plus 18px of cream
   padding on every side (design delta: qr-box + 36)."
  (+ qr-box 36))

(defn- qr-glow
  "The QR frame's state-carrying glow (design delta, 'Glows'): gold while
   waiting, amethyst once approved -- the strongest signal on this screen
   that approval landed, stronger than the dot. `theme/glow` folds the
   CSS's negative box-shadow spread into alpha; see its docstring."
  [approved?]
  (if approved?
    (theme/glow (:amethyst c) {:blur 42 :spread -6 :alpha 0.85})
    (theme/glow (:gold c) {:blur 34 :spread -8 :alpha 0.55})))

(defn- scan-line
  "The gold sweep across the QR while waiting (design delta: `scan 3.4s
   infinite`, translateY -100% -> 2100% of the bar's OWN 10px height). Sized
   to `frame-size` -- the card's full outer width, not just the 200px
   matrix -- and laid out as a sibling of the padded card rather than a
   child of it, so it is not pinched down to the card's padded content box.
   `:mouse-transparent true` because a decorative bar must not steal clicks
   from whatever is underneath it; `anim/with-anim` starts and stops
   `anim/scan!` with this node's lifecycle so nothing leaks when the QR
   panel re-renders for a new challenge or state."
  []
  ;; :stack-pane/alignment is an EXTRA prop the stack-pane lifecycle strips
  ;; off the top level of each :children entry -- it has to sit on the
  ;; with-anim wrapper itself (what actually appears in :children), not
  ;; nested inside the wrapped :desc, or the stack-pane never sees it and
  ;; the region's own prop creation chokes on an unknown :region prop.
  (assoc
   (anim/with-anim
     {:fx/type :region
      :min-width frame-size :max-width frame-size :pref-width frame-size
      :min-height 10 :max-height 10 :pref-height 10
      :mouse-transparent true
      :style (theme/style
              {:-fx-background-color
               (theme/linear-gradient :to-right
                 ["transparent" (theme/rgba (:gold c) 0.55) "transparent"])})}
     (fn [node] (anim/scan! node {:unit 10.0})))
   :stack-pane/alignment :top-center))

(defn qr-panel
  "The left half. The dot pulses while waiting and goes solid gold on approval;
   the explanatory sentence is load-bearing copy -- users look for a button that
   does not exist.

   The card is three nested layers, innermost to outermost order in the
   code below reversed for clarity here:

   1. `card` -- the ORIGINAL cream stack-pane (background, radius, 18px
      padding, the canvas, the approved overlay) -- unchanged from before
      this pass, so none of its existing layout behaviour shifts.
   2. `clip-layer` -- wraps `card` and the scan line together and clips
      BOTH to the card's own rounded rect (design delta: the card needs
      `overflow:hidden`, which JavaFX only has as a `:clip`). The scan line
      lives here, as `card`'s sibling rather than its child, specifically
      so it gets the card's FULL `frame-size` width instead of being
      squeezed into `card`'s padded 200px content box.
   3. `glow-frame` -- wraps `clip-layer` and carries the `-fx-effect` glow.
      It is NOT clipped: a clip and an effect on the SAME node compose so
      the clip masks the effect's own bleed too, which would crop the glow
      to invisibility right at the card's edge. Keeping the glow one layer
      out, on an unclipped node, is what lets it bloom into the panel
      around the card the way the mockup shows."
  [{:keys [challenge-url qr-state]}]
  (let [approved? (= :approved qr-state)
        card {:fx/type :stack-pane
              :max-width frame-size :max-height frame-size
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
                          ;; The mockup's own values, not a re-guess: a LIGHT
                          ;; 94%-opaque overlay (rgba(242,240,238,.94) --
                          ;; moved up from .92 in this pass) with near-black
                          ;; text (:bg, #0C0C0C). The card underneath is
                          ;; already the same light #F2F0EE, so this reads as
                          ;; the card itself going opaque -- not an odd
                          ;; bright patch dropped on a dark window. It also
                          ;; keeps the (now-solid) dot below as the ONLY gold
                          ;; element in this region (Gilt: one gold element
                          ;; per screen region); an earlier draft used gold
                          ;; text here too and doubled up. `anim/rise-in!`
                          ;; adds the fade-in this pass asks for.
                          (conj (anim/with-anim
                                  {:fx/type :v-box :alignment :center
                                   :style (theme/style {:-fx-background-color "rgba(242, 240, 238, 0.94)"
                                                         :-fx-background-radius 6})
                                   :children [{:fx/type :label :text "APPROVED"
                                               :style (theme/style {:-fx-font-family (theme/mono-font)
                                                                     :-fx-font-size 13
                                                                     :-fx-text-fill (:bg c)})}]}
                                  (fn [node] (anim/rise-in! node)))))}
        clip-layer {:fx/type :stack-pane
                    :max-width frame-size :max-height frame-size
                    :clip {:fx/type :rectangle
                           :width frame-size :height frame-size
                           :arc-width 12 :arc-height 12}
                    ;; the scan line must not render at all once approved --
                    ;; it is a "still waiting" signal, and rendering it over
                    ;; the approved overlay would contradict the glow/dot/
                    ;; overlay all agreeing the request is done
                    :children (cond-> [card] (not approved?) (conj (scan-line)))}
        glow-frame {:fx/type :stack-pane
                    :max-width frame-size :max-height frame-size
                    :style (theme/style {:-fx-effect (qr-glow approved?)})
                    :children [clip-layer]}]
    {:fx/type :v-box :alignment :center :spacing 22 :padding 48
     :children
     [{:fx/type :label :text "Scan to sign in"
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                             :-fx-text-fill (:text-muted c)})}
      glow-frame
      {:fx/type :h-box :alignment :center :spacing 9
       :children [(anim/with-anim
                    {:fx/type :region
                     :min-width 7 :min-height 7 :max-width 7 :max-height 7
                     :style (theme/style {:-fx-background-color (if approved? (:gold c) (:text-muted c))
                                          :-fx-background-radius 4})}
                    (fn [node] (when-not approved? (anim/pulse! node))))
                  {:fx/type :label
                   :text (if approved? "Approved — signing in"
                             "Waiting for approval on your device")
                   :style (theme/style {:-fx-font-size 13 :-fx-text-fill (:text-muted c)})}]}
      ;; :alignment as well as -fx-text-alignment -- see the note in
      ;; download.clj's interrupted panel. This copy happens to wrap to two
      ;; full lines today, which hides the difference; shorten it to one
      ;; line and it would go flush left inside its 290px box.
      {:fx/type :label :wrap-text true :max-width 290
       :alignment :center
       :text (str "Approve the request in the mobile app. "
                  "Sign-in completes on its own — there is no button here.")
       :style (theme/style {:-fx-font-size 12 :-fx-text-fill (:text-muted c)
                             :-fx-text-alignment "center"})}]}))

(defn- half
  "Pins a panel to an equal, content-independent share of the split.

   `:h-box/hgrow :always` alone is not enough: HBox first sizes every child
   to its own preferred width, then distributes only the LEFTOVER space to
   growing children. Two children whose preferred widths differ -- e.g. the
   right panel widening because the Guard-code caption is longer than
   \"Password\" -- come out different sizes even with matching hgrow, and the
   divider visibly jumps sideways when the panel switches. Flattening
   `:min-width`/`:pref-width` to 0 removes the content-driven floor, so the
   only thing left to size the child is the even hgrow split; unbounded
   `:max-width` lets it actually take that share instead of capping out at
   its (now-zero) preferred width."
  [desc]
  (assoc desc :h-box/hgrow :always
              :min-width 0 :pref-width 0 :max-width Double/MAX_VALUE))

(defn- error-strip
  "A failed sign-in attempt (Task 5's `start-login!` catches Steam's
   ExceptionInfo and puts `(ex-message e)` here). Gilt is explicit: gold text
   on `surface`, never a red banner -- an error is not an emergency, it is
   information. Spans the full width beneath both halves rather than sitting
   under just one panel, because a failure here can originate from either
   side: the QR poll (Steam unreachable, challenge expired) or the credential
   submit (bad password, rejected code). `:error` today is a plain message
   string, not a {:message :code} map, so it renders in the UI font
   throughout; a future caller that wants to show a distinct Steam error code
   alongside the prose should render that portion in `theme/mono-font`, per
   Gilt's \"gold text on surface with a mono code\", rather than growing new
   parsing logic in here to split a plain string apart."
  [message]
  {:fx/type :h-box :alignment :center-left
   :padding {:top 14 :bottom 14 :left 24 :right 24}
   :style (theme/style {:-fx-background-color (:surface c)})
   :children [{:fx/type :label :text message :wrap-text true
               :style (theme/style {:-fx-text-fill (:gold c) :-fx-font-size 13})}]})

(defn view
  "The two halves, split by a hairline that fades at both ends -- a 1px region
   with a vertical gradient from transparent through `line` and back -- plus,
   when `:error` is set, a strip beneath both spanning the full width."
  [{:keys [error] :as state}]
  (let [split {:fx/type :h-box
               :children
               [(half (qr-panel state))
                {:fx/type :region
                 :min-width 1 :max-width 1
                 :style (theme/style
                         {:-fx-background-color
                          (str "linear-gradient(to bottom, transparent, " (:line c)
                               " 20%, " (:line c) " 80%, transparent)")})}
                (half (credential-panel state))]}]
    (if error
      {:fx/type :v-box
       :children [split (error-strip error)]}
      split)))
