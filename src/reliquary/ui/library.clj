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
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [reliquary.catalog :as catalog]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.progress :as progress]
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
  "DEPRECATED, unused by the view. Kept only so the catalog's `:build` field
   has a renderer if one is ever wanted again.

   The version row no longer shows a build id: it is Valve's own ordinal for a
   build, not the version the user is choosing and not what identifies the
   content, and it was blank on nine of Skyrim SE's ten rows. The release date
   says more and every version has one.

   'build N', or 'build unknown' when `build` is empty/nil -- the same
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

(def ^:private card-radius 6)
(def ^:private card-border 1)

;; The art spans the card minus its border on each side.
;;
;; NO padding on the card. JavaFX's Region.getInsets() is padding PLUS border
;; width, and children are laid out inside those insets -- so a 1px border
;; already reserves its own 1px. Adding `:padding 1` on top made the content
;; box card-width - 4 while the art was sized card-width - 2, and the art
;; overflowed by exactly one pixel per side, straight over the border. That is
;; the whole bug: the border was never being covered because children ignore
;; it, but because the card was told to inset them twice.
(def ^:private art-width (- card-width (* 2 card-border)))

;; The art's own radius: the card's outer radius less the border it sits
;; inside. The card's clip rounds to the border's OUTER edge, which is not
;; enough -- along a corner the arc sweeps inward by up to r - r/sqrt(2)
;; (~1.8px at r=6) further than the straight edge does, so a square-cornered
;; child inset by only the border width still pokes through the arc.
(def ^:private art-radius (- card-radius card-border))

(defn- top-rounded-clip
  "An SVG path: `w` x `h` with the TOP two corners rounded to `r`, bottom square.

   A :rectangle clip rounds all four corners, which would notch the bottom of
   the art where it meets the title block and show the card's surface through
   two little bites. The art has to be round on top -- following the card's
   inner border curve -- and flat on the bottom."
  [w h r]
  {:fx/type :svg-path
   :content (format "M %d,0 H %d A %d,%d 0 0 1 %d,%d V %d H 0 V %d A %d,%d 0 0 1 %d,0 Z"
                    r (- w r) r r w r h r r r r)})

;; art + (8 top padding + 34 title + 4 spacing + ~15 meta row + 10 bottom)
(def ^:private card-body-height (+ card-height 71 (* 2 card-border)))

;; the Installed tab adds a third line -- the install path -- so the card is
;; taller there. It has to be computed rather than fixed: the height is also the
;; CLIP, and a card clipped to the two-line height simply cut the path in half.
(def ^:private card-sub-height 18)

(defn- card-total-height [sub?]
  (+ card-body-height (if sub? card-sub-height 0)))

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

(defn- capsule-sheen
  "The 7%-white diagonal sheen laid over the art (design delta's
   `:card-sheen` gradient). A plain `:region` with no children of its own --
   it exists only to paint the gradient -- sized to match the art exactly and
   `:mouse-transparent true` so it never steals the card's click.

   Deliberately NOT given its own `:clip`: it is added as a sibling child
   inside `capsule-art`'s stack-pane, which already clips everything it
   contains to `top-rounded-clip`. Giving it a second, separate clip would
   be redundant at best; forgetting to round it (a plain rectangle) would
   reintroduce the square-corner bug the top-rounded clip exists to prevent.
   Riding the parent's clip for free avoids that risk entirely."
  []
  {:fx/type :region
   :mouse-transparent true
   :min-width art-width :max-width art-width
   :min-height card-height :max-height card-height
   :style (theme/style {:-fx-background-color (:card-sheen theme/gradients)})})

(defn- capsule-art
  "The card's art area: a real `Image` from `capsule-fn` when one resolves,
   else the placeholder -- never a broken-image glyph. `capsule-fn` is
   caller-supplied and may throw or return garbage for a bad URL; either is
   treated exactly like `nil`, because a rendering bug in someone else's
   fetcher must not be able to crash the whole grid."
  [game capsule-fn]
  (let [image (try ((or capsule-fn (constantly nil)) game) (catch Exception _ nil))]
    {:fx/type :stack-pane
     :clip (top-rounded-clip art-width card-height art-radius)
     :min-width art-width :max-width art-width
     :min-height card-height :max-height card-height
     :style (theme/style {:-fx-border-color (str "transparent transparent " (:line c) " transparent")
                           :-fx-border-width "0 0 1 0"})
     :children [(if image
                  {:fx/type :image-view :image image
                   :fit-width art-width :fit-height card-height
                   :preserve-ratio false}
                  (capsule-placeholder))
                (capsule-sheen)]}))

(defn- card
  "One grid tile.

   Unowned games are still SELECTABLE. Browsing what versions exist is useful
   whether or not you own the game -- it is how you find out that 1.5.97 is
   the SKSE-stable build before you decide to buy -- so the ownership gate
   belongs on the download button, not on the click. The card stays dimmed and
   says `Not owned` so the state is legible, but the panel opens."
  [{:keys [game selected? owned? install installed-label on-disk-label reserve-sub?
           capsule-fn on-select]}]
  (let [primary (first (catalog/versions game))
        ;; Two lines, wrapped. On one line every Elder Scrolls title truncates
        ;; to "The Elder Scrolls V: S..." -- Skyrim and Skyrim Special Edition
        ;; become the same card, which defeats the point of a grid you pick a
        ;; game from. Two lines fit both in full at this width. The height is
        ;; pinned so a one-line title does not make its card shorter than its
        ;; neighbours and ragged the row.
        title   {:fx/type :label :text (:title game)
                 :wrap-text true
                 :max-width (- art-width 20)
                 :min-height 34 :max-height 34
                 :style (theme/style {:-fx-font-family (theme/ui-semibold-font)
                                       :-fx-font-size 13
                                       :-fx-text-fill (:text c)})}
        mono     (fn [text fill]
                   {:fx/type :label :text text
                    :style (theme/style {:-fx-font-family (theme/mono-font)
                                          :-fx-font-size 11
                                          :-fx-text-fill fill})})
        ;; On the Installed tab a card answers a different question: not "what
        ;; is this and how big", but "which build is on disk and where". The
        ;; version reads gold because it is the thing the tab exists for.
        meta-row (cond
                   install
                   {:fx/type :h-box
                    :children [(mono (or installed-label (str "app " (:appid game)))
                                     (if installed-label (:gold c) (:text-muted c)))
                               {:fx/type :region :h-box/hgrow :always}
                               (mono (size-label (:bytes install)) (:text-muted c))]}

                   owned?
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

                   :else
                   {:fx/type :h-box
                    :children [{:fx/type :label :text (str "app " (:appid game))
                                :style (theme/style {:-fx-font-family (theme/mono-font)
                                                      :-fx-font-size 11
                                                      :-fx-text-fill (:text-muted c)})}
                               {:fx/type :region :h-box/hgrow :always}
                               {:fx/type :label :text "Not owned"
                                :style (theme/style {:-fx-font-family (theme/mono-font)
                                                      :-fx-font-size 11
                                                      :-fx-text-fill (:text-muted c)})}]})
        ;; The third line. On the Installed tab it is where the bytes are; on
        ;; Owned it is merely THAT there are some, because the question that tab
        ;; answers is "do I have this", not "where is it" -- and a full install
        ;; path is a great deal of card to spend saying yes.
        sub-row  (cond
                   ;; Reserved but blank. The Owned tab mixes cards that have a
                   ;; third line with cards that do not, and since the card's
                   ;; height is computed from its contents, a row came out
                   ;; ragged -- some tiles ending 18px above their neighbours,
                   ;; which reads as a layout bug rather than as information.
                   (and reserve-sub? (not on-disk-label))
                   {:fx/type :region :min-height card-sub-height :max-height card-sub-height}

                   on-disk-label
                   {:fx/type :label :text on-disk-label
                    :max-width (- art-width 20)
                    :style (theme/style {:-fx-font-family (theme/mono-font)
                                          :-fx-font-size 10
                                          :-fx-text-fill (:gold c)})}

                   install
                   {:fx/type :label :text (str (:path install))
                    :max-width (- art-width 20)
                    ;; the TAIL is the useful half of an install path -- the
                    ;; folder the game is in -- so the ellipsis eats the front
                    :text-overrun :leading-ellipsis
                    :style (theme/style {:-fx-font-family (theme/mono-font)
                                          :-fx-font-size 10
                                          :-fx-text-fill (:text-dim c)})})]
    (-> (cond-> {;; The GLOW FRAME. It carries the selected state's glow and
                 ;; lift and is deliberately NOT clipped, because a clip and an
                 ;; -fx-effect on the SAME node compose: the clip masks the
                 ;; effect's own bleed too, cropping the glow to the card's box
                 ;; and making it invisible. That is exactly what happened here
                 ;; -- the gold bloom under a selected card measured identical
                 ;; to bare background. reliquary.ui.login/qr-panel (glow one
                 ;; layer out of its clip) and reliquary.ui.download/stage-panel
                 ;; (shadow on `outer`, clip on `inner`) both already keep the
                 ;; effect and the clip on separate nodes; this drifted.
                 ;;
                 ;; The frame is sized exactly like the card so the FlowPane
                 ;; still lays the grid out on the old geometry.
                 :fx/type :stack-pane
                 :min-width card-width :max-width card-width
                 :min-height (card-total-height (some? sub-row))
                 :max-height (card-total-height (some? sub-row))
                 :style (theme/style
                         (cond-> {}
                           ;; Selected card gets the ring's glow beneath it and
                           ;; a 2px lift; the 1px gold ring itself is the
                           ;; :-fx-border-color swap on the body below, so this
                           ;; only adds what that border alone doesn't give.
                           selected? (assoc :-fx-translate-y -2
                                             :-fx-effect (theme/glow (:gold c)
                                                                      {:blur 34 :spread -14
                                                                       :dy 10 :alpha 0.6}))))
                 :children
                 [{:fx/type :v-box
                   ;; A rounded background does NOT clip children in JavaFX --
                   ;; -fx-background-radius only rounds the fill that is painted
                   ;; behind them. The capsule art is an ImageView drawn on top, so
                   ;; without an explicit clip it keeps its square corners and
                   ;; visibly overhangs the card's rounded border. The clip is a
                   ;; rounded rectangle over the whole card; arc = 2 x radius,
                   ;; because JavaFX's arcWidth is the full width of the corner
                   ;; ellipse rather than its radius.
                   :clip {:fx/type :rectangle
                          :width card-width :height (card-total-height (some? sub-row))
                          :arc-width (* 2 card-radius) :arc-height (* 2 card-radius)}
                   :min-width card-width :max-width card-width
                   :min-height (card-total-height (some? sub-row))
                   :max-height (card-total-height (some? sub-row))
                   :style (theme/style
                           (cond-> {:-fx-background-color (:surface c)
                                    :-fx-background-radius card-radius
                                    :-fx-border-radius card-radius
                                    :-fx-border-width 1
                                    :-fx-border-color (if selected? (:gold c) (:line c))}
                             ;; dimmed, but not so far that it reads as
                             ;; inert -- these cards are clickable now
                             (not owned?) (assoc :-fx-opacity 0.7)))
                   :children [(capsule-art game capsule-fn)
                              {:fx/type :v-box :spacing 4
                               :padding {:top 8 :bottom 10 :left 10 :right 10}
                               :children (cond-> [title meta-row]
                                           sub-row (conj sub-row))}]}]}
          :always (assoc :on-mouse-clicked (fn [_] (on-select (:appid game)))))
        ;; Fade + rise + settle, ONCE. cljfx's default :children diffing
        ;; (cljfx.lifecycle/wrap-many with no :fx/key on these cards) keys
        ;; each position by its plain index, so retyping the filter query
        ;; -- which only ever shrinks the list or replaces trailing
        ;; positions -- ADVANCES the existing Node at each surviving
        ;; position instead of recreating it; :on-created (and so
        ;; `rise-in!`) only fires for a position that did not exist before,
        ;; i.e. the grid's first mount, or a position beyond the previous
        ;; longest filtered list. Selecting a card changes no child count at
        ;; all, so it never replays either. That is why this can hang off
        ;; every card unconditionally without the grid strobing on
        ;; keystrokes -- it only needs `with-anim` for consistency with the
        ;; rest of the app (harmless here since it stops itself) and so the
        ;; screenshot harness's `*animate*` false still suppresses it.
        (anim/with-anim anim/rise-in!))))

(defn- grid [{:keys [games selected-appid owned installs installed-labels on-disk
                     capsule-fn on-select-game]}]
  {:fx/type :flow-pane
   :hgap 18 :vgap 18
   :padding 24
   :children (mapv (fn [g]
                      (card {:game g
                             :selected? (= selected-appid (:appid g))
                             :owned? (owned?* owned (:appid g))
                             :install (get installs (:appid g))
                             :installed-label (get installed-labels (:appid g))
                             :on-disk-label (when (get on-disk (:appid g))
                                              (if-let [v (get installed-labels (:appid g))]
                                                (str "Installed · " v)
                                                "Installed"))
                             :reserve-sub? (some? on-disk)
                             :capsule-fn capsule-fn
                             :on-select on-select-game}))
                    games)})

;; The filter used to live here, over the grid, with an "N of M titles"
;; counter beside it. Both are gone: the design puts the filter in the title
;; bar next to the Installed/Owned switch, and has no counter at all.

(defn- nothing-installed
  "Shown when the Installed tab has nothing to show AND the filter is not the
   reason. The design has no empty state -- it was drawn with a populated
   fixture -- but the app opens on this tab, so without one a user who has none
   of these games meets a blank window and no hint that Owned is where the
   catalogue lives."
  []
  {:fx/type :v-box
   :alignment :center
   :spacing 8
   :padding 60
   :children
   [{:fx/type :label :text "No games from this catalog are installed"
     :style (theme/style {:-fx-font-family (theme/ui-semibold-font)
                           :-fx-font-size 15 :-fx-text-fill (:text c)})}
    {:fx/type :label
     ;; "Switch to" is the switch ACTION's verb, on the button three inches
     ;; away. Using it here for a tab change overloads the one word this screen
     ;; most needs to mean one thing.
     :text "Use the Owned tab to browse everything Reliquary can download."
     :style (theme/style {:-fx-font-family (theme/mono-font)
                           :-fx-font-size 12 :-fx-text-fill (:text-muted c)})}]})

(defn- grid-panel
  [{:keys [filtered selected-appid owned installs installed-labels on-disk
           empty-tab? capsule-fn on-select-game]}]
  {:fx/type :v-box
   :h-box/hgrow :always
   :min-width 0
   :style (theme/style {:-fx-background-color (:bg c)})
   :children
   [(if empty-tab?
      (assoc (nothing-installed) :v-box/vgrow :always)
      {:fx/type :scroll-pane
       :v-box/vgrow :always
       :fit-to-width true
       :style (theme/style {:-fx-background-color "transparent"
                             :-fx-background "transparent"})
       :content (grid {:games filtered
                      :selected-appid selected-appid
                      :owned owned
                      :installs installs
                      :installed-labels installed-labels
                      :on-disk on-disk
                        :capsule-fn capsule-fn
                        :on-select-game on-select-game})})]})

;; ---------------------------------------------------------------------
;; Side panel
;; ---------------------------------------------------------------------

(defn version-label
  "How a version is named in the picker.

   The build Steam ships today gets its number AND the word: \"1.6.1170
   (Latest)\". Naming it by number alone -- which is what the catalog carries,
   since the number is the durable fact and \"Latest\" is only true until the
   next update -- lost the one thing the old label was good for, which is
   knowing which of these rows Steam would give you right now.

   Keyed on the version ID, not the branch: historical entries live on the
   public branch too, because that is the branch their manifest request codes
   have to be asked for. `public` is the entry that means the current build.

   When the number is not known the label already IS \"Latest\", and saying it
   twice helps nobody."
  [{:keys [id label]}]
  (let [label (str label)]
    (if (and (= "public" id) (not= "Latest" label))
      (str label " (Latest)")
      label)))

(defn- version-row
  [{:keys [version selected? on-select]}]
  {:fx/type :v-box
   :spacing 4
   :padding {:top 10 :bottom 10 :left 12 :right 12}
   :on-mouse-clicked (fn [_] (on-select (:id version)))
   :style (theme/style
           (cond-> {:-fx-background-color (:surface c)
                    :-fx-background-radius 3
                    :-fx-border-radius 3
                    :-fx-border-width 1
                    :-fx-border-color (if selected? (:gold c) (:line c))}
             ;; Soft gold glow behind the selected row -- the inset gold
             ;; hairline itself is the :-fx-border-color swap above.
             selected? (assoc :-fx-effect (theme/glow (:gold c)
                                                        {:blur 20 :spread -12 :alpha 0.9}))))
   :children
   [{:fx/type :h-box :spacing 8 :alignment :center-left
     :children [{:fx/type :region
                 :min-width 9 :min-height 9 :max-width 9 :max-height 9
                 :style (theme/style
                         (cond-> {:-fx-background-radius 5
                                  :-fx-background-color (if selected? (:gold c) (:line-strong c))}
                           ;; The version dot's point light -- a small, tight
                           ;; glow so a selected row reads at a glance even
                           ;; scrolled past the row's own gold border.
                           selected? (assoc :-fx-effect (theme/glow (:gold c)
                                                                     {:blur 12 :spread -1 :alpha 0.9}))))}
                {:fx/type :label :text (version-label version)
                 :style (theme/style {:-fx-font-family (theme/ui-semibold-font)
                                       :-fx-font-size 13
                                       :-fx-text-fill (:text c)})}]}
    {:fx/type :h-box
     :children [{:fx/type :label :text (str (:date version))
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
  ([selected-version] (download-button-label selected-version true))
  ([selected-version owned?]
   (cond
     ;; Ownership outranks version selection: telling someone to pick a
     ;; version they could not download either way sends them down a path
     ;; that ends in a Steam refusal.
     (not owned?)                            "You don't own this game"
     (nil? selected-version)                 "Select a version"
     (pos? (or (:bytes selected-version) 0)) (str "Download " (size-label (:bytes selected-version)))
     :else                                   "Download")))

(defn- gb [bytes]
  (if (and bytes (pos? bytes))
    (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024)))
    "size unknown"))

(defn- switch-action-label
  "What the button offers, given what is on disk and what is selected.

   Naming the target version rather than saying \"Switch\" is the whole point:
   this is a destructive act on an existing install, and the user should read
   what it will do before they press it."
  [installed-version selected-version]
  (cond
    (nil? selected-version) "Select a version"
    (and installed-version (= (:id installed-version) (:id selected-version)))
    "Already installed"
    ;; the same word the screen uses. A panel that says `Switch to` and a screen
    ;; that then says `Force switch to` reads as two different actions
    (nil? installed-version) (str "Force switch to " (:label selected-version))
    :else (str "Switch to " (:label selected-version))))

(defn- hashing-panel
  "The design's `analyzing` state, now shared with the switch screen -- see
   reliquary.ui.progress. It was written twice, and each copy knew something the
   other did not."
  [{:keys [done total bytes-per-sec session-bytes-per-sec]}]
  (progress/hashing-box {:label "Hashing local files"
                         :done done :total total
                         :bytes-per-sec bytes-per-sec
                         :session-bytes-per-sec session-bytes-per-sec}))

(defn- switch-footer
  "The panel when the game is ALREADY installed: where Steam put it, what
   version those bytes are, and the one action worth offering.

   This replaces the install-to section rather than joining it. A user changing
   an existing install is not choosing a folder -- the folder is Steam's, and
   offering to pick another would invite them to install a second copy of a game
   they already have."
  [{:keys [install installed-version selected-version hashing on-analyze on-change-install]}]
  {:fx/type :v-box :spacing 14
   :children
   (cond-> [{:fx/type :v-box :spacing 6
             :children
             [{:fx/type :h-box :spacing 10 :alignment :center-left
               :children [{:fx/type :label :text (tracked "Installed at")
                           :style (theme/style {:-fx-font-family (theme/mono-font)
                                                 :-fx-font-size 11
                                                 :-fx-text-fill (:text-muted c)})}
                          {:fx/type :region :h-box/hgrow :always}
                          ;; The design labels the source, and it earns its
                          ;; place: this path is Steam's, not one the user chose
                          ;; here. Uppercased in the literal because JavaFX has
                          ;; no text-transform, the same reason `tracked` exists
                          ;; for letter-spacing. Its .1em tracking is finer than
                          ;; `tracked` produces, so it is left plain rather than
                          ;; spaced out to twice the intended width at 10px.
                          ;; and it stops saying STEAM once it is not Steam's:
                          ;; the tag is there to tell the user whose folder this
                          ;; is, so on a folder they picked it would be a lie
                          {:fx/type :label
                           :text (if (:chosen? install) "CHOSEN FOLDER" "FROM STEAM")
                           :style (theme/style {:-fx-font-family (theme/mono-font)
                                                 :-fx-font-size 10
                                                 :-fx-text-fill (:text-dim c)})}]}
              {:fx/type :h-box :spacing 8 :alignment :center-left
               :children
               [{:fx/type :label :text (str (:path install))
                 :wrap-text true :max-width 232 :h-box/hgrow :always
                 :style (theme/style {:-fx-font-family (theme/mono-font)
                                       :-fx-font-size 12
                                       :-fx-text-fill (:text c)})}
                ;; Same shape as the download footer's folder row, deliberately:
                ;; it is the same act, and a user who has already met one should
                ;; recognise the other.
                {:fx/type :button :text "Change…"
                 :on-action (or on-change-install (fn [_]))
                 :style (theme/style {:-fx-background-color "transparent"
                                       :-fx-border-color (:line c)
                                       :-fx-border-radius 3
                                       :-fx-background-radius 3
                                       :-fx-text-fill (:text-muted c)
                                       :-fx-font-size 12})}]}
              {:fx/type :label
               :text (str (if installed-version
                            ;; same rule as the picker above it: if these bytes
                            ;; are the build Steam ships today, say so
                            (version-label installed-version)
                            ;; identification is by content; when the bytes match
                            ;; no version we carry, saying so beats naming the
                            ;; nearest one and being believed
                            "Unrecognised build")
                          " · " (gb (:bytes install)))
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 11
                                     :-fx-text-fill (:text-muted c)})}]}]

     hashing (conj (hashing-panel hashing))

     ;; while hashing there is nothing to press: the button is GONE rather than
     ;; disabled, which is what the design shows and what stops a second pass
     ;; being started over the first
     (not hashing)
     (conj (let [label  (switch-action-label installed-version selected-version)
                 ;; from the DATA, not from the label's prefix. It used to be
                 ;; (str/starts-with? label "Switch"), so renaming the label to
                 ;; "Force switch to …" disabled the button without touching a
                 ;; line of logic -- and no test noticed, because they all
                 ;; asserted on the text.
                 ready? (boolean
                         (and selected-version
                              (not (and installed-version
                                        (= (:id installed-version)
                                           (:id selected-version))))))]
             {:fx/type :button :text label
              :disable (not ready?)
              :on-action (or on-analyze (fn [_]))
              :min-height 44 :max-width Double/MAX_VALUE
              :style (theme/style
                      (if ready?
                        {:-fx-background-color (:button theme/gradients) :-fx-text-fill (:bg c)
                         :-fx-background-radius 3 :-fx-font-size 14
                         :-fx-font-family (theme/ui-semibold-font)
                         :-fx-effect (theme/glow (:gold c)
                                                  {:blur 22 :spread -10 :dy 6 :alpha 0.9})}
                        ;; recessed and outlined, exactly like the disabled
                        ;; download button: a disabled control must read as
                        ;; inert, and surface-on-surface has no visible bounds
                        {:-fx-background-color (:bg c) :-fx-text-fill (:text-muted c)
                         :-fx-border-color (:line c)
                         :-fx-border-radius 3
                         :-fx-background-radius 3 :-fx-font-size 14}))})))})

(declare panel-download-footer)

(defn- panel-footer
  "Which footer the side panel gets.

   The design is explicit -- `switchMode = installedTab && !!inst` -- and the
   TAB is half of it. Keying on the install alone meant that selecting a game
   you already have, while browsing the catalogue on Owned, replaced the folder
   picker with the switch panel: no folder, no Download, no way to install a
   second copy anywhere else. Changing a build is a thing you do on Installed;
   Owned is the catalogue, and the action there is always install-to-a-location."
  [{:keys [tab install] :as state}]
  (if (or
       ;; A folder the user PICKED, on whichever tab they picked it from. The
       ;; design's rule is about Steam-detected installs, and it has to be: the
       ;; way in for an undetected game lives on the Owned tab, so keying on the
       ;; tab alone made choosing a folder there set :install and change nothing
       ;; on screen. Choosing a folder is an explicit "operate on this one".
       (:chosen? install)
       (and (= :installed tab) install))
    (switch-footer state)
    (panel-download-footer state)))

(defn- panel-download-footer
  [{:keys [folder selected-version owned? on-change-folder on-download on-change-install]}]
  (let [owned?   (not (false? owned?))
        ready?   (and owned? (some? selected-version))
        btn-text (download-button-label selected-version owned?)]
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
       ;; Enabled: the gold :button gradient plus its bloom underneath.
       ;; Disabled -- BOTH "You don't own this game" and "Select a
       ;; version" land here -- carries no :-fx-effect key at all, so there
       ;; is no gradient and no glow to turn off; a disabled control must
       ;; read as inert, not as the primary action dimmed.
       ;;
       ;; It is RECESSED (bg fill) and OUTLINED (line border), not filled
       ;; with `surface`. The side panel is itself `surface`, so a
       ;; surface-on-surface button had no visible bounds whatsoever: the
       ;; reason text floated in the panel as though it were a caption, and
       ;; nothing on screen said a button was there to become enabled once
       ;; you picked a version or bought the game. The spec asks for "the
       ;; download button disabled and a plain reason" -- that is a button
       ;; you can see, holding the reason, not a reason on its own.
       :style (theme/style (if ready?
                              {:-fx-background-color (:button theme/gradients) :-fx-text-fill (:bg c)
                               :-fx-background-radius 3 :-fx-font-size 14
                               :-fx-font-family (theme/ui-semibold-font)
                               :-fx-effect (theme/glow (:gold c)
                                                        {:blur 22 :spread -10 :dy 6 :alpha 0.9})}
                              {:-fx-background-color (:bg c) :-fx-text-fill (:text-muted c)
                               :-fx-border-color (:line c)
                               :-fx-border-radius 3
                               :-fx-background-radius 3 :-fx-font-size 14}))}
      ;; The way in for a copy Steam never reported. Without it the switch is
      ;; reachable only through `installs/find-install`, so a second install
      ;; kept at a known-good build, a folder restored from a backup or a game
      ;; moved by hand cannot be switched at all -- and those are exactly the
      ;; folders somebody is most likely to want pinned to a version.
      ;;
      ;; A link rather than a button: it is the alternative to the action above
      ;; it, not a competing one.
      {:fx/type :hyperlink :text "Already have a copy? Choose its folder…"
       :on-action (or on-change-install (fn [_]))
       ;; its own class, not the footer credit's: that one is styled gold with a
       ;; rule under it, and borrowing it would have meant this link inherited a
       ;; hover rule drawn for a border it does not have
       :style-class ["hyperlink" "panel-link"]
       :style (theme/style {:-fx-font-family (theme/mono-font)
                             :-fx-font-size 11
                             :-fx-text-fill (:text-muted c)
                             :-fx-underline false
                             :-fx-padding 0
                             :-fx-background-color "transparent"})}]}))

(defn- side-panel
  [{:keys [game selected-version-id folder owned? install installed-version hashing
           on-select-version on-change-folder on-download on-analyze on-change-install]
    :as   state}]
  (let [versions         (catalog/versions game)
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
              ;; The horizontal padding is what gives a SELECTED row's glow
              ;; somewhere to go. :fit-to-width sizes the content to exactly the
              ;; viewport, which then clips it, so a row flush to both edges had
              ;; its glow shaved off flat on the left and right while the 8px
              ;; :spacing let it bloom above and below -- a glow visible on two
              ;; sides only. Costs each row 12px of width; the rows have space.
              :content {:fx/type :v-box :spacing 8
                        :padding {:left 6 :right 6}
                        :children (mapv (fn [v] (version-row {:version v
                                                               :selected? (= selected-version-id (:id v))
                                                               :on-select on-select-version}))
                                        versions)}}
     :bottom {:fx/type :v-box
              :border-pane/margin {:top 16}
              ;; the whole panel state is forwarded, plus the version resolved
              ;; here, so switch mode's keys reach the footer without every
              ;; layer having to enumerate them
              :children [(panel-footer (assoc state
                                              :folder folder
                                              :selected-version selected-version
                                              :owned? owned?))]}}))

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
  [{:keys [games query tab installs installed-labels
           selected-appid selected-version-id owned folder capsule-fn
           install installed-version hashing
           on-query-change on-select-game on-select-version on-change-folder
           on-download on-analyze on-change-install]
    :or   {capsule-fn         (constantly nil)
           on-query-change    (fn [_])
           on-select-game     (fn [_])
           on-select-version  (fn [_])
           on-change-folder   (fn [_])
           on-download        (fn [_])
           on-analyze         (fn [_])
           on-change-install  (fn [_])}}]
  (let [games         (or games [])
        ;; The Installed tab is a different LIBRARY, not a different sort: only
        ;; games Steam actually has on disk. The filter then applies within
        ;; whichever pool the switch has chosen, which is why the placeholder
        ;; in the title bar names the tab.
        ;; Only an EXPLICIT :installed narrows. nil means the caller is not
        ;; using the switch, and a view that hid every card because it was not
        ;; told which tab it was on would be a blank window on a missing key.
        pool          (if (= :installed tab)
                        (filterv #(get installs (:appid %)) games)
                        games)
        filtered      (cond->> (filter-games pool query)
                        ;; Owned is the whole catalog; the games worth acting on
                        ;; are the ones already on disk, so they come first.
                        ;; `sort-by` is stable, so catalog order survives inside
                        ;; each group rather than being reshuffled.
                        (= :owned tab)
                        (sort-by #(if (get installs (:appid %)) 0 1)))
        selected-game (find-game games selected-appid)]
    {:fx/type :h-box
     :children
     (cond-> [(grid-panel {:filtered filtered
                            ;; empty because nothing is INSTALLED, not because
                            ;; the filter matched nothing -- typing gibberish is
                            ;; not the same statement about the machine
                            :empty-tab? (and (= :installed tab)
                                             (empty? pool))
                            :selected-appid selected-appid :owned owned
                            :installs (when (= :installed tab) installs)
                            ;; Owned marks what is on disk without spending the
                            ;; card on a path; Installed shows the path itself
                            :on-disk (when (= :owned tab) installs)
                            :installed-labels installed-labels
                            :capsule-fn capsule-fn
                            :on-select-game on-select-game})]
       selected-game
       (conj (side-panel {:game selected-game
                           :tab tab
                           :selected-version-id selected-version-id
                           :folder folder
                           :owned? (owned?* owned (:appid selected-game))
                           ;; switch mode -- see panel-footer
                           :install install
                           :installed-version installed-version
                           :hashing hashing
                           :on-select-version on-select-version
                           :on-change-folder on-change-folder
                           :on-download on-download
                           :on-analyze on-analyze
                           :on-change-install on-change-install})))}))
