;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app
  "The window frame every screen sits in: title bar, content slot, legal footer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.theme :as theme])
  (:import (java.awt Desktop Desktop$Action)
           (java.net URI)
           (java.util List)
           (javafx.scene Node)
           (javafx.scene.image Image)
           (javafx.scene.input MouseEvent)
           (javafx.stage Window)))

(def ^:private c theme/color)

(defn logo-mark
  "A 13px circle with a 3px gold ring and an amethyst centre. The brand spec
   requires it to stay legible at 16px, so it is drawn, not an image. This is
   the FALLBACK mark -- see `logo` -- used only when
   resources/reliquary-logo.png is missing or fails to load; the title bar
   otherwise renders that artwork instead."
  []
  {:fx/type :region
   :min-width 13 :min-height 13 :max-width 13 :max-height 13
   :style (theme/style {:-fx-background-color (:amethyst c)
                         :-fx-background-radius 7
                         :-fx-border-color (:gold c)
                         :-fx-border-width 3
                         :-fx-border-radius 7})})

(defn- tracked-text
  "JavaFX has no CSS `letter-spacing`, so `.2em` tracking on the wordmark is
   achieved by spacing the characters themselves -- inserting a thin space
   (U+2009) between each glyph -- rather than dropping the tracking.

   This has real costs, because the rendered label's text is no longer the
   string \"RELIQUARY\": exact-match assertions against it must account for
   the inserted thin spaces, copy/pasting the wordmark yields a
   thin-space-separated string rather than the word, and -- the one that
   matters most -- a screen reader spells the word out letter by letter
   instead of reading it as \"Reliquary\". Whoever wires up accessibility
   later needs an accessible-text override here; this function alone is not
   screen-reader-safe."
  [s]
  (str/join " " (map str s)))

(defn logo-resource
  "The classpath URL for the bundled logo artwork
   (resources/reliquary-logo.png), or nil if it is absent -- e.g. a
   packaging slip that dropped the file. `io/resource` returning nil is a
   DIFFERENT failure than a present-but-corrupt file (see `logo-image`'s
   docstring): this is the one where there is no URL to even attempt to
   load.

   Pulled out as its own function, rather than inlined into `logo-image`,
   so the test suite can stub the lookup with `with-redefs` -- exercising
   the fallback path without needing to actually delete the resource from
   the built jar/classpath."
  []
  (io/resource "reliquary-logo.png"))

(defn logo-image
  "Loads the bundled Reliquary logo artwork as a `javafx.scene.image.Image`,
   or nil if it is unusable for either of two distinct reasons: a missing
   resource (`logo-resource` returns nil -- a packaging slip), or a present
   resource that fails to decode. `javafx.scene.image.Image` does not throw
   for a bad payload; it sets `.isError` instead, so that has to be checked
   explicitly rather than relying on a `catch`. Also wrapped in `try` for
   any load path that does throw (e.g. a malformed URL), so a corrupt or
   missing image degrades `title-bar` to the drawn `logo-mark` + wordmark
   fallback rather than crashing the window."
  ^Image []
  (try
    (when-let [url (logo-resource)]
      (let [img (Image. (str url))]
        (when-not (.isError img)
          img)))
    (catch Exception _ nil)))

(defn- wordmark []
  {:fx/type :label :text (tracked-text "RELIQUARY")
   :style (theme/style {:-fx-font-family (theme/mono-font)
                         :-fx-font-size 12
                         :-fx-text-fill (:text c)})})

(defn- logo-fallback
  "The drawn ring-and-dot mark beside the tracked RELIQUARY wordmark --
   what the title bar renders when `logo-image` can't produce the bundled
   artwork. A packaging mistake that drops reliquary-logo.png must degrade
   to this, not to an empty rectangle."
  []
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :children [(logo-mark) (wordmark)]})

(defn- logo-halo
  "The breathing gold halo behind the logo: a 44px circle of the
   `:logo-halo` radial gradient (docs/design-delta-2026-08-17.md),
   offset 6px left and vertically centred on the logo via `:translate-x`,
   animated with `anim/breathe!` through `anim/with-anim` -- which is what
   stops the Timeline when cljfx discards this node on a later state
   change, rather than leaking it. `logo` lists this FIRST among the
   stack-pane's children, so it paints -- and sits -- behind the logo
   artwork/fallback, which is listed after it. `:mouse-transparent true` so
   the halo never intercepts a click meant for whatever sits in front of
   it."
  []
  (anim/with-anim
    {:fx/type :region
     :min-width 44 :min-height 44 :max-width 44 :max-height 44
     :mouse-transparent true
     :translate-x -6
     :style (theme/style {:-fx-background-color (:logo-halo theme/gradients)
                           :-fx-background-radius 22})}
    anim/breathe!))

(defn- logo
  "The title bar's brand mark: the breathing halo behind, then either the
   bundled artwork (height 26, width auto, amethyst drop-shadow) or the
   drawn fallback, stacked so the halo paints first and sits behind."
  []
  {:fx/type :stack-pane
   :alignment :center-left
   :children [(logo-halo)
              (if-let [img (logo-image)]
                {:fx/type :image-view
                 :image img
                 :fit-height 26
                 :preserve-ratio true
                 :style (theme/style {:-fx-effect (theme/glow (:amethyst c) {:blur 9 :dy 3 :alpha 0.5})})}
                (logo-fallback))]})

(defn icon-resource
  "The classpath URL for the window icon (resources/reliquary-icon.png), or nil.

   Separate artwork from the header logo for a reason: the logo is a 330x78
   wordmark, and an icon has to be square and legible at 16px, where the word
   is not. bin/make-icons.py crops the emblem out of that same logo, so the two
   cannot drift apart, and writes this PNG plus the .ico jpackage stamps on the
   .exe. Pulled out as its own var so a test can stub the lookup."
  []
  (io/resource "reliquary-icon.png"))

(defn icon-image
  "The window icon as an `Image`, or nil if it is missing or undecodable.

   Same two distinct failures `logo-image` documents, handled the same way: a
   nil resource is a packaging slip, and `javafx.scene.image.Image` reports a
   bad payload through `.isError` rather than by throwing. A missing icon costs
   the window its taskbar artwork; it must never cost the user their app."
  ^Image []
  (try
    (when-let [url (icon-resource)]
      (let [img (Image. (str url))]
        (when-not (.isError img) img)))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; moving an undecorated window
;;
;; Removing the OS chrome removes the only thing the user could grab to move the
;; window, so the title bar becomes that handle.

(def ^:private drag-offset
  "Where inside the window the pointer grabbed, so dragging moves the window by
   the pointer's delta instead of snapping the window's corner to the cursor.

   Interaction state, not application state: it lives for the duration of one
   drag and has no business in the state atom, which is rendered and printed."
  (atom nil))

(defn- event-window
  "The Window an event was delivered into, or nil. Reached through the node
   rather than captured in a closure, because `app/view` is a pure function that
   never sees the Stage it describes."
  ^Window [^MouseEvent e]
  (when-let [^Node node (.getSource e)]
    (some-> node .getScene .getWindow)))

(defn- begin-drag!
  [^MouseEvent e]
  (when-let [w (event-window e)]
    (reset! drag-offset [(- (.getScreenX e) (.getX w))
                         (- (.getScreenY e) (.getY w))])))

(defn- continue-drag!
  [^MouseEvent e]
  (when-let [[dx dy] @drag-offset]
    (when-let [w (event-window e)]
      (.setX w (- (.getScreenX e) (double dx)))
      (.setY w (- (.getScreenY e) (double dy))))))

(defn- close-button
  "The window's close control.

   Placed LAST in the title bar, so it sits at the far right where Windows and
   every app on it put window controls.

   `:accessible-help` because the glyph is a multiplication sign: a screen reader
   reads a button by its text, and \"times\" is not a useful name for the only
   control that shuts the app. cljfx exposes :accessible-help, :accessible-role
   and :accessible-role-description but not :accessible-text, so this is the
   available lever."
  [on-close]
  {:fx/type :button
   :text "\u00d7"
   :accessible-help "Close Reliquary"
   :on-action (or on-close (fn [_]))
   :focus-traversable false
   :min-width 28 :min-height 28 :max-width 28 :max-height 28
   :style-class ["button" "window-close"]
   :style (theme/style {:-fx-background-color "transparent"
                         :-fx-text-fill (:text-muted c)
                         :-fx-font-size 16
                         :-fx-padding 0})})

(def ^:private tab-active-bg
  "The selected tab's fill. One step above :surface and not in the palette,
   because the switch sits ON :bg in the title bar and :surface against it is
   almost invisible (design: #242424)."
  "#242424")

(defn- tab-button
  "Named tab-BUTTON because `tab` is also the state key this switch reads, and
   the destructured parameter shadowed the function."
  [{:keys [label selected? on-select]}]
  {:fx/type :button :text label
   :on-action (fn [_] (when on-select (on-select)))
   :min-height 26 :max-height 26
   :padding {:left 14 :right 14}
   :style (theme/style
           (if selected?
             {:-fx-background-color tab-active-bg
              :-fx-text-fill (:text c)
              :-fx-background-radius 3
              :-fx-font-size 12
              :-fx-font-family (theme/ui-semibold-font)
              ;; JavaFX has no inset box-shadow: the design's gold inset ring
              ;; becomes a real 1px border in the same colour, and the outer
              ;; bloom stays an effect
              :-fx-border-color (theme/rgba (:gold c) 0.35)
              :-fx-border-radius 3
              :-fx-effect (theme/glow (:gold c) {:blur 16 :spread -8 :alpha 0.9})}
             {:-fx-background-color "transparent"
              :-fx-text-fill (:text-muted c)
              :-fx-background-radius 3
              :-fx-font-size 12
              :-fx-font-family (theme/ui-semibold-font)}))})

(defn- library-controls
  "The Installed/Owned switch and the filter, in the title bar where the design
   puts them -- immediately right of the logo, not over the card grid."
  [{:keys [tab query on-tab on-query-change]}]
  (let [installed? (not= :owned tab)]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 16
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :spacing 2
       :padding 2
       ;; PINNED. HBox fillHeight defaults to true, so a child with an unbounded
       ;; maxHeight is stretched to the parent's content height whatever
       ;; :alignment says -- this box grew to 50px in a 52px bar, with its 26px
       ;; pills floating in the middle and a band of dead space above and below
       ;; them. 26 of pill inside 2 of padding, as the design has it.
       :min-height 30 :max-height 30
       :style (theme/style {:-fx-background-color (:bg c)
                             :-fx-border-color (:line c)
                             :-fx-border-radius 4
                             :-fx-background-radius 4})
       :children [(tab-button {:label "Installed" :selected? installed?
                        :on-select #(when on-tab (on-tab :installed))})
                  (tab-button {:label "Owned" :selected? (not installed?)
                        :on-select #(when on-tab (on-tab :owned))})]}
      {:fx/type :text-field
       :text (or query "")
       ;; the placeholder names what is being searched, so the switch and the
       ;; filter read as one control rather than two
       :prompt-text (if installed? "Search installed" "Search library")
       :on-text-changed (or on-query-change (fn [_]))
       :min-width 230 :max-width 230 :min-height 30 :max-height 30
       :style (theme/style {:-fx-background-color (:bg c)
                             :-fx-border-color (:line c)
                             :-fx-border-radius 4
                             :-fx-background-radius 4
                             :-fx-text-fill (:text c)
                             :-fx-font-size 12
                             :-fx-padding "0 11 0 11"})}]}))

(defn title-bar [{:keys [status-line signed-in? tab query
                         on-tab on-query-change on-sign-out on-close]}]
  {:fx/type :h-box
   :alignment :center-left
   ;; and the same rule one level up: without this every child of the bar is
   ;; stretched to its full height rather than centred at its own
   :fill-height false
   :spacing 26
   :min-height 52 :max-height 52
   :padding {:left 22 :right 14}
   ;; the window's grab handle, now that the OS provides none
   :on-mouse-pressed begin-drag!
   :on-mouse-dragged continue-drag!
   :style (theme/style {:-fx-background-color (:title-bar theme/gradients)
                         ;; JavaFX has no inset box-shadow, so the design
                         ;; delta's `inset 0 1px 0 rgba(242,240,238,.04)`
                         ;; hairline is approximated with a literal 1px top
                         ;; border in that colour, rather than faking it via
                         ;; an effect -- the bottom border is the
                         ;; pre-existing separator line, kept as-is.
                         :-fx-border-color (str (theme/rgba (:text c) 0.04)
                                                 " transparent " (:line c) " transparent")
                         :-fx-border-width "1 0 1 0"
                         :-fx-effect (theme/glow "#000000" {:blur 30 :spread -24 :dy 12})})
   ;; The close button is appended LAST, after the conditional Sign out, because
   ;; "far right" has to hold whether or not Sign out is there -- a cond-> that
   ;; conj'd Sign out after it would put Sign out to its right when signed in.
   :children (-> [(logo)]
                 (cond-> signed-in?
                   (conj (library-controls {:tab tab :query query
                                            :on-tab on-tab
                                            :on-query-change on-query-change})))
                 (conj
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :label :text (or status-line "")
                   :style (theme/style {:-fx-font-family (theme/mono-font)
                                         :-fx-font-size 11
                                         :-fx-text-fill (:text-muted c)})})
                 (cond-> signed-in?
                   (conj {:fx/type :button :text "Sign out"
                          :on-action on-sign-out
                          :style (theme/style {:-fx-background-color "transparent"
                                                :-fx-border-color (:line c)
                                                :-fx-border-radius 3
                                                :-fx-background-radius 3
                                                :-fx-text-fill (:text-muted c)
                                                :-fx-font-size 12})}))
                 (conj (close-button on-close)))})

(def legal
  "Verbatim, on every screen. A legal requirement, not copy to be improved."
  "Not associated with or endorsed by Valve Corporation or Steam.")

(defn browse!
  "Opens `url` in the default browser: `java.awt.Desktop/BROWSE` when it is
   available, `xdg-open` otherwise. Returns nil on success or an error message
   string on failure, and NEVER throws -- the same contract, and the same two
   routes, as `done/open-folder!`, because a headless box or a minimal container
   may have neither.

   Blocking: `xdg-open` is a subprocess whose exit code has to be waited on, so
   callers run this off the FX thread."
  [^String url]
  (letfn [(desktop! []
            (try
              (boolean
               (and (Desktop/isDesktopSupported)
                    (let [d (Desktop/getDesktop)]
                      (and (.isSupported d Desktop$Action/BROWSE)
                           (do (.browse d (URI. url)) true)))))
              (catch Exception _ false)))
          (xdg! []
            (try
              (zero? (.waitFor (.start (ProcessBuilder. ^List (List/of "xdg-open" url)))))
              (catch Exception _ false)))]
    (if (or (desktop!) (xdg!))
      nil
      (str "Could not open " url))))

(def credit-url
  "halgari's Nexus Mods profile, as the design links it."
  "https://next.nexusmods.com/profile/halgari")

(defn footer
  "`{:on-credit (fn [url] ...)}` -- the caller opens it, because opening a URL
   means a subprocess whose exit code someone has to wait for, and the FX thread
   may not be that someone. See `main/open-credit!`."
  [{:keys [on-credit]}]
  {:fx/type :h-box
   :alignment :center
   :spacing 8
   :min-height 34 :max-height 34
   :style (theme/style {:-fx-background-color (:bg c)
                         :-fx-border-color (str (:line c) " transparent transparent transparent")
                         :-fx-border-width "1 0 0 0"})
   :children [{:fx/type :label :text legal
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 10
                                     :-fx-text-fill (:text-muted c)})}
              {:fx/type :label :text "·"
               :style (theme/style {:-fx-text-fill (:line-strong c)})}
              ;; "Created by" and the name are one phrase, so they sit closer
              ;; than the footer's 8px between its two clauses
              {:fx/type :h-box :alignment :center :spacing 4
               :children
               [{:fx/type :label :text "Created by"
                 :style (theme/style {:-fx-font-family (theme/mono-font)
                                       :-fx-font-size 10
                                       :-fx-text-fill (:text-muted c)})}
                {:fx/type :hyperlink :text "halgari"
                 :on-action (fn [_] (when on-credit (on-credit credit-url)))
                 ;; the hover rule lives in reliquary.css: JavaFX pseudo-classes
                 ;; are only reachable from a stylesheet, and an inline -fx-
                 ;; style has no way to express one
                 :style-class ["hyperlink" "credit-link"]
                 :style (theme/style {:-fx-font-family (theme/mono-font)
                                       :-fx-font-size 10
                                       :-fx-text-fill (:gold c)
                                       ;; Modena underlines a Hyperlink and
                                       ;; gives it a focus ring; the design has
                                       ;; a 1px rule under it instead
                                       :-fx-underline false
                                       :-fx-padding 0
                                       :-fx-background-color "transparent"
                                       :-fx-border-color (str "transparent transparent "
                                                              (:line-strong c) " transparent")
                                       :-fx-border-width "0 0 1 0"})}]}]})

(defn view
  "`:content` is the screen's own description; this frames it."
  [{:keys [content] :as state}]
  {:fx/type :stage
   :showing true
   :title "Reliquary"
   ;; UNDECORATED because the app draws its own title bar. With the OS bar as
   ;; well, Windows showed two stacked bars. The cost is that the OS no longer
   ;; supplies a close button, a grab handle or resize edges: `close-button` and
   ;; the title bar's drag handlers replace the first two, and the window is no
   ;; longer user-resizable.
   :style :undecorated
   ;; An undecorated window still appears in the taskbar and alt-tab, and
   ;; without this it appears there as a generic Java mug.
   :icons (if-let [img (icon-image)] [img] [])
   :width 1100 :height 720
   :scene {:fx/type :scene
           :stylesheets [(theme/stylesheet)]
           :fill (:bg c)
           :root {:fx/type :v-box
                  :style (theme/style {:-fx-background-color (:bg c)})
                  :children [(title-bar state)
                             (assoc (or content {:fx/type :region}) :v-box/vgrow :always)
                             (footer state)]}}})
