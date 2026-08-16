;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.library
  "The library screen: a filterable grid of catalog games on the left, and,
   when one is selected, a side panel to pick a version and start a
   download.

   `view` is a pure function of a plain state map -- no atoms, no side
   effects, nothing read off the classpath at call time except the fonts
   (already loaded by whoever renders this). Interaction is expressed the
   same way `reliquary.ui.login` does it: the caller hands in `:on-*`
   callbacks alongside the data, and this namespace calls them with plain
   values (an appid, a version id, nothing more) rather than mutating
   anything itself. Wiring those callbacks to real state changes is Task E's
   job, not this one's.

   Capsule art is deliberately NOT `reliquary.ui.art` -- that namespace is
   being built in parallel by another agent. Art arrives as `:capsule-fn`, a
   `(fn [game]) -> Image-or-nil` the caller supplies; the default,
   `(constantly nil)`, means every card renders the mockup's placeholder
   until Task E wires the real thing in. A missing or failed image must
   never be treated differently from a game that simply has no art -- both
   render the same placeholder."
  (:require [clojure.string :as str]
            [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

;; ---------------------------------------------------------------------
;; Pure data helpers -- covered directly by tests, no JavaFX involved.
;; ---------------------------------------------------------------------

(defn- owned?*
  "Is `appid` owned, given the `:owned` set from state? A nil set means no
   session answered the ownership question yet -- treating that as \"nothing
   is owned\" would grey out and lock the entire library over a missing
   session, which is worse than just not knowing. So nil means everyone is
   owned."
  [owned appid]
  (or (nil? owned) (contains? owned appid)))

(defn- matches-query?
  [game query]
  (let [q (str/lower-case (or query ""))]
    (or (str/blank? q)
        (str/includes? (str/lower-case (or (:title game) "")) q)
        (str/includes? (str/lower-case (or (:studio game) "")) q))))

(defn filter-games
  "`games` whose title or studio contains `query`, case-insensitively. A
   blank or nil query matches everything."
  [games query]
  (filterv #(matches-query? % query) games))

(defn- find-game [games appid]
  (when appid (first (filter #(= appid (:appid %)) games))))

(defn- find-version [game version-id]
  (when (and game version-id)
    (first (filter #(= version-id (:id %)) (:versions game)))))

(defn size-label
  "'N.N GB', or 'size unknown' when `bytes` is 0 or nil. Community-sourced
   catalog versions genuinely do not know their size -- Skyrim Special
   Edition's older single-source builds are the concrete example -- and
   rendering that gap as '0.0 GB' would be a lie the user acts on."
  [bytes]
  (if (and bytes (pos? bytes))
    (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024)))
    "size unknown"))

(defn build-label
  "'build N', or 'build unknown' when `build` is empty/nil -- the same
   community-sourced gap as `size-label`, for the build number instead of
   the byte count."
  [build]
  (if (seq build) (str "build " build) "build unknown"))

(defn- tracked
  "Upper-cases `s` and spaces its letters with a thin space, the same
   letter-spacing trick `reliquary.ui.app/tracked-text` uses for the
   wordmark -- JavaFX CSS has no `letter-spacing`. Used here for the small
   'VERSION' / 'INSTALL TO' section labels the mockup sets in tracked caps."
  [s]
  (str/join " " (str/upper-case s)))

;; ---------------------------------------------------------------------
;; Grid
;; ---------------------------------------------------------------------

(def ^:private card-width 168)
(def ^:private card-height (long (* card-width 1.5))) ; aspect 2/3

(defn- capsule-placeholder
  "The mockup's diagonal hatch, drawn with a repeating linear gradient --
   JavaFX CSS has no hatch pattern primitive -- with a mono 'capsule art'
   chip centred over it."
  []
  {:fx/type :stack-pane
   :style (theme/style
           {:-fx-background-color
            (str "linear-gradient(from 0px 0px to 9px 9px, repeat, "
                 (:line c) " 0%, " (:line c) " 50%, "
                 (:surface c) " 50%, " (:surface c) " 100%)")})
   :children [{:fx/type :label :text "capsule art"
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 10
                                     :-fx-text-fill (:text-muted c)
                                     :-fx-background-color (:surface c)
                                     :-fx-background-radius 3
                                     :-fx-padding "3 8 3 8"})}]})

(defn- capsule-art
  "The card's art area: a real `Image` from `capsule-fn` when one resolves,
   else the placeholder -- never a broken-image glyph. `capsule-fn` is
   caller-supplied and may throw or return garbage for a bad URL; either is
   treated exactly like `nil`, because a rendering bug in someone else's
   fetcher must not be able to crash the whole grid."
  [game capsule-fn]
  (let [image (try ((or capsule-fn (constantly nil)) game) (catch Exception _ nil))]
    {:fx/type :stack-pane
     :min-width card-width :max-width card-width
     :min-height card-height :max-height card-height
     :style (theme/style {:-fx-border-color (str "transparent transparent " (:line c) " transparent")
                           :-fx-border-width "0 0 1 0"})
     :children [(if image
                  {:fx/type :image-view :image image
                   :fit-width card-width :fit-height card-height
                   :preserve-ratio false}
                  (capsule-placeholder))]}))

(defn- card
  "One grid tile. Unowned games render muted (dimmed via opacity, since
   Gilt's palette has no separate 'disabled' token) and carry no click
   handler at all -- there is nothing sensible for a click on them to do --
   plus a plain one-line reason in place of the appid/size row."
  [{:keys [game selected? owned? capsule-fn on-select]}]
  (let [primary (first (:versions game))
        title   {:fx/type :label :text (:title game)
                 :max-width (- card-width 20)
                 :style (theme/style {:-fx-font-family (theme/ui-semibold-font)
                                       :-fx-font-size 13
                                       :-fx-text-fill (:text c)})}
        meta-row (if owned?
                   {:fx/type :h-box
                    :children [{:fx/type :label :text (str "app " (:appid game))
                                :style (theme/style {:-fx-font-family (theme/mono-font)
                                                      :-fx-font-size 11
                                                      :-fx-text-fill (:text-muted c)})}
                               {:fx/type :region :h-box/hgrow :always}
                               {:fx/type :label :text (size-label (:bytes primary))
                                :style (theme/style {:-fx-font-family (theme/mono-font)
                                                      :-fx-font-size 11
                                                      :-fx-text-fill (:text-muted c)})}]}
                   {:fx/type :label :text "Not owned"
                    :style (theme/style {:-fx-font-family (theme/mono-font)
                                          :-fx-font-size 11
                                          :-fx-text-fill (:text-muted c)})})]
    (cond-> {:fx/type :v-box
             :min-width card-width :max-width card-width
             :style (theme/style (cond-> {:-fx-background-color (:surface c)
                                           :-fx-background-radius 6
                                           :-fx-border-radius 6
                                           :-fx-border-width 1
                                           :-fx-border-color (if selected? (:gold c) (:line c))}
                                    (not owned?) (assoc :-fx-opacity 0.45)))
             :children [(capsule-art game capsule-fn)
                        {:fx/type :v-box :spacing 4
                         :padding {:top 8 :bottom 10 :left 10 :right 10}
                         :children [title meta-row]}]}
      owned? (assoc :on-mouse-clicked (fn [_] (on-select (:appid game)))))))

(defn- grid [{:keys [games selected-appid owned capsule-fn on-select-game]}]
  {:fx/type :flow-pane
   :hgap 18 :vgap 18
   :padding 24
   :children (mapv (fn [g]
                      (card {:game g
                             :selected? (= selected-appid (:appid g))
                             :owned? (owned?* owned (:appid g))
                             :capsule-fn capsule-fn
                             :on-select on-select-game}))
                    games)})

(defn- filter-box [{:keys [query on-query-change]}]
  {:fx/type :text-field
   :text (or query "")
   :prompt-text "Filter library"
   :on-text-changed on-query-change
   :min-width 280 :max-width 280 :min-height 36 :max-height 36
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-border-color (:line c)
                         :-fx-border-radius 3
                         :-fx-background-radius 3
                         :-fx-text-fill (:text c)
                         :-fx-font-size 13
                         :-fx-padding "0 10 0 10"})})

(defn- count-label [n total]
  {:fx/type :label
   :text (str n " of " total " titles")
   :style (theme/style {:-fx-font-family (theme/mono-font)
                         :-fx-font-size 11
                         :-fx-text-fill (:text-muted c)})})

(defn- grid-panel
  [{:keys [games filtered query selected-appid owned capsule-fn
           on-query-change on-select-game]}]
  {:fx/type :v-box
   :h-box/hgrow :always
   :min-width 0
   :style (theme/style {:-fx-background-color (:bg c)})
   :children
   [{:fx/type :h-box
     :alignment :center-left
     :spacing 14
     :padding {:top 20 :bottom 14 :left 24 :right 24}
     :children [(filter-box {:query query :on-query-change on-query-change})
                (count-label (count filtered) (count games))]}
    {:fx/type :scroll-pane
     :v-box/vgrow :always
     :fit-to-width true
     :style (theme/style {:-fx-background-color "transparent"
                           :-fx-background "transparent"})
     :content (grid {:games filtered
                      :selected-appid selected-appid
                      :owned owned
                      :capsule-fn capsule-fn
                      :on-select-game on-select-game})}]})

;; ---------------------------------------------------------------------
;; Side panel
;; ---------------------------------------------------------------------

(defn- version-row
  [{:keys [version selected? on-select]}]
  {:fx/type :v-box
   :spacing 4
   :padding {:top 10 :bottom 10 :left 12 :right 12}
   :on-mouse-clicked (fn [_] (on-select (:id version)))
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-background-radius 3
                         :-fx-border-radius 3
                         :-fx-border-width 1
                         :-fx-border-color (if selected? (:gold c) (:line c))})
   :children
   [{:fx/type :h-box :spacing 8 :alignment :center-left
     :children [{:fx/type :region
                 :min-width 9 :min-height 9 :max-width 9 :max-height 9
                 :style (theme/style {:-fx-background-radius 5
                                       :-fx-background-color (if selected? (:gold c) (:line-strong c))})}
                {:fx/type :label :text (:label version)
                 :style (theme/style {:-fx-font-family (theme/ui-semibold-font)
                                       :-fx-font-size 13
                                       :-fx-text-fill (:text c)})}]}
    {:fx/type :h-box
     :children [{:fx/type :label :text (str (build-label (:build version)) " · " (:date version))
                 :style (theme/style {:-fx-font-family (theme/mono-font)
                                       :-fx-font-size 11
                                       :-fx-text-fill (:text-muted c)})}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :label :text (size-label (:bytes version))
                 :style (theme/style {:-fx-font-family (theme/mono-font)
                                       :-fx-font-size 11
                                       :-fx-text-fill (:text-muted c)})}]}]})

(defn- panel-header [game]
  {:fx/type :v-box :spacing 6
   :children [{:fx/type :label :text (:title game)
               :wrap-text true :max-width 350
               :style (theme/style {:-fx-font-family (theme/ui-bold-font)
                                     :-fx-font-size 19
                                     :-fx-text-fill (:text c)})}
              {:fx/type :label
               :text (str (or (:studio game) "unknown studio")
                          " · app " (:appid game)
                          " · " (count (:versions game)) " builds retained")
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 12
                                     :-fx-text-fill (:text-muted c)})}]})

(defn download-button-label
  "What the primary button says, given the selected version (or nil).

   Three cases, and the middle one is the bug this replaced: no version
   selected reads `Select a version` -- a button labelled `Download`
   alongside a disabled style still reads as an offer, and the user's next
   question is 'download what?'. A version whose size the catalog knows
   names it, since a 27.7 GB commitment is worth seeing before the click. A
   version whose size is genuinely unknown -- `bytes` 0, which this catalog
   really contains -- says just `Download`, never `Download size unknown`,
   which the old code produced by pasting `size-label`'s not-a-size answer
   into a sentence that needed a size."
  [selected-version]
  (cond
    (nil? selected-version)                 "Select a version"
    (pos? (or (:bytes selected-version) 0)) (str "Download " (size-label (:bytes selected-version)))
    :else                                   "Download"))

(defn- panel-footer
  [{:keys [folder selected-version on-change-folder on-download]}]
  (let [ready?   (some? selected-version)
        btn-text (download-button-label selected-version)]
    {:fx/type :v-box :spacing 10
     :children
     [{:fx/type :label :text (tracked "Install to")
       :style (theme/style {:-fx-font-family (theme/mono-font)
                             :-fx-font-size 11
                             :-fx-text-fill (:text-muted c)})}
      {:fx/type :h-box :spacing 8 :alignment :center-left
       :children [{:fx/type :label :text (or folder "No folder selected")
                   :max-width 260
                   :style (theme/style {:-fx-font-family (theme/mono-font)
                                         :-fx-font-size 12
                                         :-fx-text-fill (:text c)})}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button :text "Change…"
                   :on-action on-change-folder
                   :style (theme/style {:-fx-background-color "transparent"
                                         :-fx-border-color (:line c)
                                         :-fx-border-radius 3
                                         :-fx-background-radius 3
                                         :-fx-text-fill (:text-muted c)
                                         :-fx-font-size 12})}]}
      {:fx/type :button :text btn-text
       :disable (not ready?)
       :on-action on-download
       :min-height 44 :max-width Double/MAX_VALUE
       :style (theme/style (if ready?
                              {:-fx-background-color (:gold c) :-fx-text-fill (:bg c)
                               :-fx-background-radius 3 :-fx-font-size 14
                               :-fx-font-family (theme/ui-semibold-font)}
                              {:-fx-background-color (:surface c) :-fx-text-fill (:text-muted c)
                               :-fx-background-radius 3 :-fx-font-size 14}))}]}))

(defn- side-panel
  [{:keys [game selected-version-id folder on-select-version on-change-folder on-download]}]
  (let [versions         (:versions game)
        selected-version (find-version game selected-version-id)]
    ;; :border-pane, not a :v-box with a :v-box/vgrow :always middle child.
    ;; That was the first draft, and it is exactly the trap this project has
    ;; already been bitten by once (per the plan: "a :v-box/vgrow bug caught
    ;; only by a screenshot"): a game with enough versions to overflow the
    ;; panel's height -- Skyrim Special Edition has 11 -- puts VBox's layout
    ;; into a height deficit, and VBox does not confine that shrink to the
    ;; growing child alone; it also steals height from the NON-growing
    ;; siblings, including the wrap-text title above. The visible symptom
    ;; was silent: the title just lost its second line, sometimes with no
    ;; ellipsis at all, with nothing pr-str would ever catch. Even swapping
    ;; the middle child for a :scroll-pane didn't fix it, because the
    ;; deficit-shrink still applied to the header. BorderPane's :top and
    ;; :bottom always get their full preferred size; only :center absorbs
    ;; whatever space is left, all the way to zero -- which is exactly the
    ;; header/footer-never-shrinks, list-scrolls behaviour this panel needs.
    {:fx/type :border-pane
     :min-width 400 :max-width 400
     :padding 24
     :style (theme/style {:-fx-background-color (:surface c)
                           :-fx-border-color (str "transparent transparent transparent " (:line c))
                           :-fx-border-width "0 0 0 1"})
     :top {:fx/type :v-box :spacing 16
           :border-pane/margin {:bottom 16}
           :children [(panel-header game)
                      {:fx/type :label :text (tracked "Version")
                       :style (theme/style {:-fx-font-family (theme/mono-font)
                                             :-fx-font-size 11
                                             :-fx-text-fill (:text-muted c)})}]}
     :center {:fx/type :scroll-pane
              :fit-to-width true
              :style (theme/style {:-fx-background-color "transparent"
                                    :-fx-background "transparent"})
              :content {:fx/type :v-box :spacing 8
                        :children (mapv (fn [v] (version-row {:version v
                                                               :selected? (= selected-version-id (:id v))
                                                               :on-select on-select-version}))
                                        versions)}}
     :bottom {:fx/type :v-box
              :border-pane/margin {:top 16}
              :children [(panel-footer {:folder folder :selected-version selected-version
                                         :on-change-folder on-change-folder :on-download on-download})]}}))

;; ---------------------------------------------------------------------
;; Top level
;; ---------------------------------------------------------------------

(defn view
  "State: `{:games [...] :query \"\" :selected-appid n :selected-version-id s
   :owned #{appids} :folder \"/path\" :capsule-fn (fn [game] Image-or-nil)}`,
   plus caller-supplied `:on-query-change`, `:on-select-game`,
   `:on-select-version`, `:on-change-folder`, `:on-download` callbacks
   (each defaulting to a no-op, the same shape `reliquary.ui.login` uses).

   The side panel only appears once `:selected-appid` names a game actually
   present in `:games` -- an id left over from a previous, now-filtered-out
   library must not conjure a panel for a game the grid isn't showing."
  [{:keys [games query selected-appid selected-version-id owned folder capsule-fn
           on-query-change on-select-game on-select-version on-change-folder on-download]
    :or   {capsule-fn         (constantly nil)
           on-query-change    (fn [_])
           on-select-game     (fn [_])
           on-select-version  (fn [_])
           on-change-folder   (fn [_])
           on-download        (fn [_])}}]
  (let [games         (or games [])
        filtered      (filter-games games query)
        selected-game (find-game games selected-appid)]
    {:fx/type :h-box
     :children
     (cond-> [(grid-panel {:games games :filtered filtered :query query
                            :selected-appid selected-appid :owned owned
                            :capsule-fn capsule-fn
                            :on-query-change on-query-change
                            :on-select-game on-select-game})]
       selected-game
       (conj (side-panel {:game selected-game
                           :selected-version-id selected-version-id
                           :folder folder
                           :on-select-version on-select-version
                           :on-change-folder on-change-folder
                           :on-download on-download})))}))
