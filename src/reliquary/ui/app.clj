;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app
  "The window frame every screen sits in: title bar, content slot, legal footer."
  (:require [clojure.string :as str]
            [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

(defn logo-mark
  "A 13px circle with a 3px gold ring and an amethyst centre. The brand spec
   requires it to stay legible at 16px, so it is drawn, not an image."
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

(defn title-bar [{:keys [status-line signed-in? on-sign-out]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :min-height 52 :max-height 52
   :padding {:left 22 :right 22}
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-border-color (str "transparent transparent " (:line c) " transparent")
                         :-fx-border-width "0 0 1 0"})
   :children (cond-> [(logo-mark)
                       {:fx/type :label :text (tracked-text "RELIQUARY")
                        :style (theme/style {:-fx-font-family (theme/mono-font)
                                              :-fx-font-size 12
                                              :-fx-text-fill (:text c)})}
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
           :fill (:bg c)
           :root {:fx/type :v-box
                  :style (theme/style {:-fx-background-color (:bg c)})
                  :children [(title-bar state)
                             (assoc (or content {:fx/type :region}) :v-box/vgrow :always)
                             (footer)]}}})
