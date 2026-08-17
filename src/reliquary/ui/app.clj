;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app
  "The window frame every screen sits in: title bar, content slot, legal footer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene.image Image)))

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

(defn title-bar [{:keys [status-line signed-in? on-sign-out]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :min-height 52 :max-height 52
   :padding {:left 22 :right 22}
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
   :children (cond-> [(logo)
                       {:fx/type :region :h-box/hgrow :always}
                       {:fx/type :label :text (or status-line "")
                        :style (theme/style {:-fx-font-family (theme/mono-font)
                                              :-fx-font-size 11
                                              :-fx-text-fill (:text-muted c)})}]
               signed-in? (conj {:fx/type :button :text "Sign out"
                                 :on-action on-sign-out
                                 :style (theme/style {:-fx-background-color "transparent"
                                                       :-fx-border-color (:line c)
                                                       :-fx-border-radius 3
                                                       :-fx-background-radius 3
                                                       :-fx-text-fill (:text-muted c)
                                                       :-fx-font-size 12})}))})

(def legal
  "Verbatim, on every screen. A legal requirement, not copy to be improved."
  "Not associated with or endorsed by Valve Corporation or Steam.")

(defn footer []
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
              {:fx/type :label :text "Created by halgari"
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 10
                                     :-fx-text-fill (:text-muted c)})}]})

(defn view
  "`:content` is the screen's own description; this frames it."
  [{:keys [content] :as state}]
  {:fx/type :stage
   :showing true
   :title "Reliquary"
   :width 1100 :height 720
   :scene {:fx/type :scene
           :stylesheets [(theme/stylesheet)]
           :fill (:bg c)
           :root {:fx/type :v-box
                  :style (theme/style {:-fx-background-color (:bg c)})
                  :children [(title-bar state)
                             (assoc (or content {:fx/type :region}) :v-box/vgrow :always)
                             (footer)]}}})
