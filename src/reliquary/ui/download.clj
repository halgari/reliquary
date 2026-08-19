;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.download
  "The screen a user stares at for twenty minutes: throughput sparkline, time
   remaining, percent complete, a progress bar, a byte/speed/eta/stage line,
   and a rotating screenshot with a quote from the game. Plus the interrupted
   state, which resumes rather than scares.

   A pure view fn -- no atoms, no polling, no side effects -- reading
   `{:snapshot :game :version :shot-index :quote-index :screenshot-fn
   :on-cancel :on-retry :on-back}`.

   `:snapshot` is `reliquary.download/snapshot`'s exact shape: `:bytes-total`
   can be 0 (a version whose catalog size is unknown), and every rate in it
   is B/s, undivided -- the engine deliberately does not pre-scale, so this
   namespace does the MB/s division at the point of display. Every division
   in here is guarded against a zero or non-finite input; a NaN or Infinity
   reaching a `-fx-` style string is a crash, and this catalog genuinely
   contains versions with a zero byte total.

   `:screenshot-fn` is `(fn [game n] Image-or-nil)` -- `reliquary.ui.art`'s
   `screenshot`, but never required directly here so this namespace has no
   opinion on how art gets fetched or cached. Defaults to `(constantly nil)`
   so a caller that omits it renders the artwork-unavailable path rather than
   throwing."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene Node)
           (javafx.scene.image Image)
           (javafx.scene.layout Region)
           (javafx.scene.shape Rectangle)))

(def ^:private c theme/color)

;; The screen is a fixed 1100-wide window (ui/app.clj hardcodes the stage
;; size), not a responsive layout -- so, like login.clj's 200px QR box and
;; done.clj's 560px path box, the progress bar's fill width below is a
;; literal pixel constant rather than something computed from a live parent
;; width binding cljfx has no easy hook for.
(def ^:private root-padding 32.0)
(def ^:private content-width (- 1100.0 (* 2 root-padding)))

;; ---------------------------------------------------------------------------
;; formatting -- every one of these is a total function. `:bytes-total` 0 and
;; empty/absent `:samples` are real inputs this catalog produces, not edge
;; cases to special-case away.

(defn- finite-num?
  [n]
  (and (number? n)
       (let [d (double n)]
         (not (or (Double/isNaN d) (Double/isInfinite d))))))

(defn- fmt-bytes
  "A literal byte count -- '0 B' for a fresh download's zero bytes done, never
   'unknown'. `:bytes-done` legitimately starts at zero; that is not the same
   fact as `:bytes-total` being unknown, see `fmt-size` below."
  [n]
  (let [n (if (and (finite-num? n) (pos? n)) (double n) 0.0)]
    (cond
      (>= n (* 1024 1024 1024)) (format "%.1f GB" (/ n (* 1024.0 1024 1024)))
      (>= n (* 1024 1024))      (format "%.0f MB" (/ n (* 1024.0 1024)))
      (>= n 1024)               (format "%.0f KB" (/ n 1024.0))
      :else                     (format "%.0f B" n))))

(defn- fmt-size
  "A size for humans, or 'size unknown' -- mirrors reliquary.cli's `gb` and
   done.clj's `fmt-size`: community-sourced catalog versions genuinely lack a
   byte total, and 0 means unknown, not zero. Rendering that as '0.0 GB' or
   '0 B' would be a lie the user acts on."
  [n]
  (if (and (finite-num? n) (pos? n)) (fmt-bytes n) "size unknown"))

(defn- fmt-rate
  [bps]
  (let [bps (if (and (finite-num? bps) (pos? bps)) (double bps) 0.0)]
    (format "%.1f MB/s" (/ bps (* 1024.0 1024)))))

(defn- percent
  "0..100, or 0 whenever :bytes-total is 0/absent/non-finite -- a bar that
   cannot compute a ratio shows empty, never NaN%."
  [done total]
  (if (and (finite-num? done) (finite-num? total) (pos? (double total)))
    (max 0.0 (min 100.0 (* 100.0 (/ (double done) (double total)))))
    0.0))

(defn- fmt-percent
  [pct]
  (str (long (Math/round (double pct))) "%"))

(defn- eta-seconds
  "Remaining seconds at `bps`, or nil whenever any input is missing,
   non-finite, zero or negative -- an unknown total or a stalled rate has no
   meaningful ETA.

   Callers pass :session-bytes-per-sec, the average over the whole run, NOT
   the 250 ms :bytes-per-sec. Dividing the remaining bytes by an
   instantaneous rate produces a clock that swings between 04:11 and 47:02
   twice a second: accurate at each instant and useless to plan around. The
   speedometer and the sparkline still show the live rate, which is where a
   live rate belongs."
  [done total bps]
  (when (and (finite-num? done) (finite-num? total) (finite-num? bps)
             (pos? (double total)) (pos? (double bps)))
    (/ (max 0.0 (- (double total) (double done))) (double bps))))

(defn- fmt-clock
  "mm:ss, or '--:--' for nil/non-finite/non-positive -- ETA rule: show
   '--:--' when paused or when the rate is 0, which `eta-seconds` above
   already collapses to nil."
  [secs]
  (if (and (finite-num? secs) (pos? (double secs)))
    (let [s (long (Math/ceil (double secs)))]
      (format "%02d:%02d" (quot s 60) (mod s 60)))
    "--:--"))

;; ---------------------------------------------------------------------------
;; stage

(def ^:private moving-stages
  "Stages that are actively moving bytes.

   Downloads only. Changing an existing install has its own screen --
   reliquary.ui.switch -- because its phases need their own buttons: Cancel has
   to stop a hash pass rather than a download context, and Resume has to re-hash
   rather than fetch a whole game into a folder nobody chose."
  #{:downloading})

(defn- paused?
  "'Paused', for this screen, means anything other than actively pulling
   bytes: preparing, copying, cancelled, failed, done or idle all read as
   paused in the header kicker, the sparkline colour and the clock colour."
  [stage]
  (not (contains? moving-stages stage)))

(defn- running-stage?
  "Whether the Cancel button belongs on screen at all -- not before the run
   starts and not once it has stopped, one way or another."
  [stage]
  (contains? #{:preparing :downloading :copying} stage))

(defn- track
  "Manual letter-spacing, the same technique ui/app.clj's wordmark uses:
   JavaFX has no CSS letter-spacing, so 'uppercase tracked' means a thin
   space (U+2009) inserted between every glyph of the upper-cased string."
  [s]
  (str/join " " (seq (str/upper-case s))))

;; ---------------------------------------------------------------------------
;; header

(def ^:private header-text-width
  "How much width the title/meta block may claim before it ellipsises.

   The header shares one row with the sparkline (260) and the two stat
   columns, which together need roughly 550px of the 1036px content width.
   A title is not allowed to push them off the right edge -- and it really
   could: this catalog carries `The Elder Scrolls IV: Oblivion® Game of the
   Year Edition (2009)`, which sets to about 700px at 25px. `shot/render!`
   and the real window both CROP rather than shrink, so an unbounded title
   would silently delete the percentage instead of ellipsising itself."
  440.0)

(defn- header
  [game version snapshot]
  (let [stage  (or (:stage snapshot) :idle)
        kicker (track (if (paused? stage) "Paused" "Downloading"))]
    {:fx/type :v-box
     :spacing 4
     :max-width header-text-width
     :children
     [{:fx/type :label :text kicker
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                             :-fx-text-fill (:text-muted c)})}
      {:fx/type :label :text (or (:title game) "")
       :max-width header-text-width
       :style (theme/style {:-fx-font-family (theme/ui-bold-font) :-fx-font-size 25
                             :-fx-text-fill (:text c)})}
      {:fx/type :label
       :text (str (:label version) " · "
                  (fmt-size (:bytes version)))
       :max-width header-text-width
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                             :-fx-text-fill (:text-muted c)})}]}))

;; ---------------------------------------------------------------------------
;; sparkline -- 260x44, 48 bars, 2px gaps, bottom-aligned on a 1px baseline.
;; MUST handle fewer than 48 samples without stretching: a download that just
;; started, or one still on its first few 250ms ticks, has 0-5 samples, and
;; the sparkline is 48 fixed-width bars whatever the sample count is -- the
;; missing history pads with zero-height bars at the OLDEST (left) end rather
;; than the 5 real bars being stretched to fill the box.

(def ^:private spark-w 260.0)
(def ^:private spark-h 44.0)
(def ^:private spark-n 48)
(def ^:private spark-gap 2.0)
(def ^:private spark-recent 5)
(def ^:private spark-glow-n
  "How many of the newest bars carry the tip glow (design delta: '...on the
   last ~3')."
  3)
(def ^:private spark-bar-w (/ (- spark-w (* (dec spark-n) spark-gap)) spark-n))

;; The CSS this screen was translated from puts `transition: height .16s
;; linear` on every one of the 48 bars. Deliberately NOT reproduced here.
;; `view` is polled and rebuilt on every snapshot tick -- ~4/s per the
;; engine's poll interval -- and `sparkline` has no stable per-bar identity
;; across those rebuilds: it is 48 freshly-built `:region` maps in a plain
;; vector every call, with no `:fx/key`, so cljfx has nothing to diff a
;; height *change* against; it just sees a new child list and a Timeline
;; attached in a prior frame would already be targeting a Node cljfx is
;; about to discard. Retrofitting stable identity (`:fx/key` per bar index)
;; so a height Transition would even have something to animate FROM, then
;; starting a fresh 160ms Transition on up to 48 Regions 4 times a second,
;; is real, continuous cost on a node this small and this frequently
;; rebuilt -- and the payoff is a 160ms ease on a bar that is about to be
;; overwritten again in ~250ms anyway, well before a human eye would
;; register the animation as anything other than a step. Stepping is
;; perfectly readable for a sparkline; 48 Timelines built-and-discarded
;; four times a second is not perfectly free. Chose the step.

(def ^:private spark-gutter
  "The mockup's gap between the sparkline and the time/percent group. Wide
   enough that a 34px clock -- `59:59` is as wide as this gets -- never
   crowds the last bar."
  26.0)

(defn- padded-samples
  "Exactly `spark-n` values, oldest first: real samples on the right, zero
   padding on the left for whatever history doesn't exist yet. Never
   stretches -- a 1-sample download gets 47 zero bars and 1 real one, not 48
   copies of the same bar."
  [samples]
  (let [samples (vec (take-last spark-n (or samples [])))
        pad     (max 0 (- spark-n (count samples)))]
    (into (vec (repeat pad 0.0)) samples)))

(defn- sample-bar
  [i value peak paused?]
  (let [max-h   (- spark-h 1.0)
        v       (if (finite-num? value) (max 0.0 (double value)) 0.0)
        pk      (if (and (finite-num? peak) (pos? (double peak))) (double peak) 1.0)
        h       (max 0.0 (min max-h (* max-h (/ v pk))))
        recent? (>= i (- spark-n spark-recent))
        ;; the leading (newest, rightmost) few bars glow -- but never while
        ;; paused, when every bar is already the flat line-strong grey and a
        ;; gold glow on a grey bar would read as a bug, not a design choice
        tip?    (and (not paused?) (>= i (- spark-n spark-glow-n)))
        colour  (cond paused? (:line-strong c)
                       recent? (:gold c)
                       :else   "rgba(194,163,95,0.38)")]
    {:fx/type :region
     :min-width spark-bar-w :max-width spark-bar-w
     :min-height h :max-height h
     :style (theme/style (cond-> {:-fx-background-color colour}
                            tip? (assoc :-fx-effect
                                        (theme/glow (:gold c) {:blur 10 :spread -1 :alpha 0.8}))))}))

(defn- sparkline
  "48 bars in exactly `spark-w` pixels.

   `:snap-to-pixel false` is what actually keeps that promise, and it is not
   a nicety. A bar is 3.458px wide (260 minus 47 two-pixel gaps, over 48),
   and JavaFX rounds every child up to a whole pixel by default -- 4px each,
   so the row lays out at 48*4 + 47*2 = 286px. HBox treats a children-derived
   min width as a floor that beats `:max-width`, so the box silently grew
   26px past its declared 260 and the last bars ran INTO the `Time remaining`
   clock beside it. That is the collision that shipped in the first live
   screenshot. Turning snapping off honours the fractional width and the row
   measures 260px, which is what every other number on this row was sized
   against."
  [samples paused?]
  (let [padded (padded-samples samples)
        peak   (apply max 0.0 padded)]
    {:fx/type :h-box
     :snap-to-pixel false
     :min-width spark-w :max-width spark-w
     :min-height spark-h :max-height spark-h
     :alignment :bottom-left
     :spacing spark-gap
     :style (theme/style {:-fx-border-color (str "transparent transparent " (:line c) " transparent")
                           :-fx-border-width "0 0 1 0"})
     :children (vec (map-indexed (fn [i v] (sample-bar i v peak paused?)) padded))}))

(defn- throughput-caption
  [samples]
  (let [padded (padded-samples samples)
        peak   (apply max 0.0 padded)]
    {:fx/type :h-box
     :min-width spark-w :max-width spark-w
     :children
     [{:fx/type :label :text (track "Throughput")
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 10
                             :-fx-text-fill (:text-muted c)})}
      {:fx/type :region :h-box/hgrow :always}
      {:fx/type :label :text (str (fmt-rate peak) " peak")
       :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 10
                             :-fx-text-fill (:text-muted c)})}]}))

;; ---------------------------------------------------------------------------
;; time-remaining / complete columns, right of the sparkline

(defn- stat-column
  "`:glow?` -- only the percent column gets the gold text-shadow haze
   (design delta: `text-shadow: 0 0 22px rgba(194,163,95,.45)`); both
   columns get tabular numerals so the digits stop jittering as they count."
  [label value colour & [{:keys [glow?]}]]
  {:fx/type :v-box
   :spacing 6
   :children
   [{:fx/type :label :text label
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 10
                           :-fx-text-fill (:text-muted c)})}
    {:fx/type :label :text value
     :style (theme/style
             (cond-> {:-fx-font-family (theme/mono-font) :-fx-font-size 34
                      :-fx-text-fill colour
                      :-fx-font-features theme/tabular-nums}
               glow? (assoc :-fx-effect (theme/glow (:gold c) {:blur 22 :alpha 0.45}))))}]})

(defn- time-remaining-column
  [snapshot paused?]
  (let [secs  (when-not paused?
                (eta-seconds (:bytes-done snapshot) (:bytes-total snapshot)
                             (:session-bytes-per-sec snapshot)))
        clock (fmt-clock secs)]
    (stat-column "Time remaining" clock
                 (if (= clock "--:--") (:text-muted c) (:text c)))))

(defn- complete-column
  [snapshot]
  (stat-column "Complete"
               (fmt-percent (percent (:bytes-done snapshot) (:bytes-total snapshot)))
               (:gold c)
               {:glow? true}))

(defn- header-row
  "Title on the left, measurements on the right, one row.

   This used to be two stacked rows -- `header` and then a `progress-row`
   holding the sparkline and both stat columns -- which left-packed all four
   blocks against the same edge and wasted the whole right half of a
   1100px-wide window. The mockup puts the game title on the left taking the
   free space, with throughput / time remaining / complete right-aligned
   opposite it.

   The free space is a spacer Region with `:h-box/hgrow :always`, NOT hgrow
   on the header itself: a Region's default max width is USE_COMPUTED_SIZE,
   which resolves to its preferred width, so HBox has no room to grow the
   header into and the right-hand group would stay glued to the title."
  [game version snapshot paused?]
  {:fx/type :h-box
   :spacing spark-gutter
   :alignment :bottom-left
   :children [(header game version snapshot)
              {:fx/type :region :h-box/hgrow :always :min-width 12}
              ;; the throughput block is pinned to the sparkline's own width,
              ;; so nothing inside it can push the clock right of it -- the
              ;; spacing below is then a real gap rather than a hope
              {:fx/type :v-box
               :spacing 6
               :min-width spark-w :max-width spark-w
               :children [(throughput-caption (:samples snapshot))
                          (sparkline (:samples snapshot) paused?)]}
              (time-remaining-column snapshot paused?)
              (complete-column snapshot)]})

;; ---------------------------------------------------------------------------
;; the 6px bar and the byte/speed/eta/stage line beneath it

(defn- progress-bar
  "Gold gradient fill under 100%, switching to the amethyst `:progress-done`
   gradient (and an amethyst glow) once the bar is actually full -- the same
   colour-by-state rule `progress-bar`'s caller already applies to the
   Complete column and the done screen's ring."
  [pct]
  (let [pct        (max 0.0 (min 100.0 (double pct)))
        full?      (>= pct 100.0)
        glow-color (if full? (:amethyst c) (:gold c))
        fill-grad  (if full? (:progress-done theme/gradients) (:progress-bar theme/gradients))
        fill-w     (* content-width (/ pct 100.0))]
    {:fx/type :h-box
     :min-width content-width :max-width content-width
     :min-height 6 :max-height 6
     :style (theme/style {:-fx-background-color (:line c) :-fx-background-radius 2})
     :children [{:fx/type :region
                 :min-width fill-w :max-width fill-w
                 :min-height 6 :max-height 6
                 :style (theme/style {:-fx-background-color fill-grad
                                       :-fx-background-radius 2
                                       :-fx-effect (theme/glow glow-color {:blur 22 :spread -4 :alpha 0.85})})}
                {:fx/type :region :h-box/hgrow :always}]}))

(defn- stats-line
  [snapshot paused?]
  (let [{:keys [stage bytes-done bytes-total wire-bytes-per-sec
                session-bytes-per-sec]} snapshot
        stage (or stage :idle)
        eta   (fmt-clock (when-not paused?
                           (eta-seconds bytes-done bytes-total session-bytes-per-sec)))]
    {:fx/type :label
     :text (str (fmt-bytes bytes-done) " / " (fmt-size bytes-total) "  ·  "
                (fmt-rate wire-bytes-per-sec) "  ·  " eta " remaining  ·  "
                (str/capitalize (name stage)))
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                           :-fx-text-fill (:text-muted c)})}))

;; ---------------------------------------------------------------------------
;; stage panel -- rotating screenshot + quote, or the flat surface fallback

(defn- image-url
  "The `file:` URI a JavaFX Image carries when built from one (as
   `reliquary.ui.art/load-image` always builds them), or nil. Used to draw
   the screenshot as a CSS `-fx-background-image` on a plain Region instead
   of an ImageView: a Region is resizable and JavaFX clips its background to
   the Region's own bounds automatically, so it fills whatever height the
   surrounding VBox's `:v-box/vgrow :always` hands the stage panel without
   this namespace having to compute that height by hand -- an ImageView is
   NOT resizable and would need an exact pixel `:fit-height` this screen has
   no reliable way to know ahead of layout. An Image with no URL (e.g. built
   some other way in a test) falls back to the flat placeholder rather than
   guessing a size -- never a broken image, per the spec."
  [^Image image]
  (when image
    (try (let [u (.getUrl image)] (when (seq u) u))
         (catch Exception _ nil))))

(def ^:private scrim
  "The mockup's caption scrim: transparent at the top of the caption block,
   solid `bg` by 42% of it.

   Not decoration. A screenshot is arbitrary third-party artwork, and
   Stardew Valley's farm scene -- bright greens and oranges, the brightest
   art in this catalog -- put near-white 21px text on top of near-white
   pixels. The quote is one of the few things a user actually reads during a
   twenty-minute download, and a scrim tuned against a dark screenshot fails
   completely against a bright one, so this is opaque enough to read against
   the worst case rather than the average one."
  (str "linear-gradient(to bottom, "
       "rgba(12,12,12,0) 0%, "
       "rgba(12,12,12,0.93) 42%, "
       "rgba(12,12,12,0.97) 100%)"))

(def ^:private chip-backing
  "Backing for the small chips that sit ON the artwork rather than under the
   scrim. Same problem as the quote: `SHOT 01 / 06` in muted grey over a
   bright sky is unreadable, and there is no scrim up there to help."
  "rgba(12,12,12,0.9)")

(defn- shot-chip
  [idx total]
  {:fx/type :label :text (format "SHOT %02d / %02d" (inc idx) total)
   :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 10
                         :-fx-text-fill (:text c)
                         :-fx-background-color chip-backing
                         :-fx-background-radius 3
                         :-fx-padding "3 6 3 6"})})

(defn- shot-dots
  [total idx]
  {:fx/type :h-box
   :spacing 6
   :alignment :center
   :padding {:top 5 :bottom 5 :left 7 :right 7}
   ;; the dots ride on the artwork too, and #383838 on a bright sky is
   ;; invisible -- they get the same backing the chip does
   :style (theme/style {:-fx-background-color chip-backing
                         :-fx-background-radius 8})
   :children (vec (for [i (range total)]
                     {:fx/type :region
                      :min-width 6 :max-width 6 :min-height 6 :max-height 6
                      :style (theme/style
                              (cond-> {:-fx-background-color
                                       (if (= i idx) (:gold c) (:line-strong c))
                                       :-fx-background-radius 3}
                                (= i idx) (assoc :-fx-effect
                                                  (theme/glow (:gold c) {:blur 12 :spread -1 :alpha 0.9}))))}))})

(defn- quote-block
  "The quote card, or -- when the game has no catalog quotes at all -- the
   stage name alone. Either way the panel underneath must keep the text
   readable whether or not artwork loaded, so this never depends on there
   being an image."
  [game stage quote-index]
  (let [quotes (:quotes game)]
    (if (seq quotes)
      (let [{:keys [text attrib]} (nth quotes (mod (long (or quote-index 0)) (count quotes)))]
        {:fx/type :v-box
         :spacing 8
         :max-width 520
         :children
         [{:fx/type :label :text (str/upper-case (str "Overheard in " (:title game)))
           :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                                 :-fx-text-fill (:gold c)})}
          {:fx/type :label :text (or text "") :wrap-text true :max-width 480
           :style (theme/style {:-fx-font-family (theme/ui-font) :-fx-font-size 21
                                 :-fx-text-fill (:text c)})}
          {:fx/type :label :text (or attrib "")
           :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                                 :-fx-text-fill (:text-muted c)})}]})
      {:fx/type :label :text (str/capitalize (name (or stage :idle)))
       :style (theme/style {:-fx-font-family (theme/ui-font) :-fx-font-size 21
                             :-fx-text-fill (:text c)})})))

(def ^:private panel-hairline
  "The stage panel's inset top hairline (design delta: `inset 0 1px 0
   rgba(242,240,238,.04)`). JavaFX has no inset-shadow equivalent, so this
   is approximated as a real 1px top border in a near-invisible tint,
   composed into `:-fx-border-color`'s per-side value alongside the
   existing `:line` border on the other three sides -- the same
   one-side-different-colour trick `sparkline`'s bottom baseline above
   already uses."
  (theme/rgba (:text c) 0.04))

(def ^:private panel-shadow
  "The stage panel's own drop shadow (design delta: `0 24px 60px -34px
   #000`), so it sits seated in the page rather than flush with it."
  (theme/glow "#000000" {:blur 60 :spread -34 :dy 24 :alpha 1.0}))

(def ^:private sheen-w
  "22% of the panel's width (design delta: 'a 22%-wide diagonal highlight'),
   against the screen's own fixed content width -- see `content-width`'s
   docstring for why a literal pixel constant is right for this
   non-responsive window."
  (* 0.22 content-width))

(def ^:private sheen-gradient
  "A soft light band, faked as *diagonal* purely by the gradient's own
   angle: `anim/sheen!`'s docstring explains why the CSS's -18deg skew is
   dropped rather than bolted on via a JavaFX `Shear` nobody asked for, so
   the only diagonal cue left available WAS the gradient direction itself --
   and it turns out not to be available either, so this band is straight.

   The angle is measured from UP, clockwise, and it has to be exactly 90
   (straight across) for the fade to run along the band's own 228px width
   and reach zero at both edges. It started at 20 -- near-vertical -- which
   faded top-to-bottom instead and left the alpha constant along every
   horizontal row: a flat-topped slab with two hard vertical edges that
   swept across the panel as a curtain.

   Tilting it back toward horizontal does not fix that, it only shrinks it.
   A tilted gradient's iso-lines cut across the rectangle, so two opposite
   CORNERS always sit at non-zero alpha, and the region's straight edge
   there is a visible seam. Measured against a surface base of 22 and a
   peak of 41: at 72 the worst edge was 38, at 80 it was 32, at 85 it was
   28, and only at 90 did every row read 22 at both edges. So the sheen is
   a straight vertical band. The diagonal was already an approximation of
   an approximation -- the mockup's -18deg skew was dropped before this --
   and a clean sweep is worth more than a slanted one with seams."
  (theme/linear-gradient 90 ["rgba(242,240,238,0)"
                              ["rgba(242,240,238,0.09)" 50]
                              "rgba(242,240,238,0)"]))

(defn- sheen-highlight
  "The stage panel's animated highlight -- sweeps left to right every 7s via
   `anim/sheen!`, mouse-transparent so it never intercepts a click, pinned
   to the panel's left edge so `sheen!`'s own translateX percentages (which
   resolve against THIS node's own width, not the panel's) sweep it the
   width the design delta intends. Placed as the FIRST child of the overlay
   anchor-pane the caller builds -- i.e. UNDER the caption's scrim, the
   chip's backing and the shot dots -- specifically so it never crosses the
   one thing on this screen that must stay reliably readable: sweeping under
   an opaque scrim leaves the quote exactly as legible as it already was,
   and the sheen only shows where nothing is protecting readability anyway."
  []
  (assoc (anim/with-anim
           {:fx/type :region
            :min-width sheen-w :max-width sheen-w
            :mouse-transparent true
            :style (theme/style {:-fx-background-color sheen-gradient})}
           (fn [node] (anim/sheen! node {:unit sheen-w})))
         :anchor-pane/top 0.0 :anchor-pane/bottom 0.0 :anchor-pane/left 0.0))

(def ^:private panel-radius
  "The stage panel's corner radius, matching its `-fx-background-radius`."
  6.0)

(def ^:private panel-border
  "The panel's hairline. Load-bearing for the artwork's clip -- see
   `panel-inner-radius`."
  1.0)

(def ^:private panel-inner-radius
  "The radius the panel's CONTENT has to follow: the outer radius less the
   border it sits inside.

   Children of a bordered Region are laid out inside its insets, so the artwork
   is already inset by `panel-border` on every side -- but it is still square
   at the corners. Clipping it at the panel's OUTER radius therefore leaves it
   poking through: along a corner the arc sweeps inward by up to r - r/sqrt(2)
   (~1.8px at r=6) further than the straight edge does, so a square-cornered
   child inset by only the border width paints straight over the hairline at
   each corner. reliquary.ui.library's `art-radius` is the same constant for
   the same reason on the game cards; the stage panel had the outer radius and
   the artwork overran all four corners."
  (- panel-radius panel-border))

(defn- clip-to-live-bounds
  "Wraps `desc` so its own rendered clip tracks its live width/height, rounded
   to `radius`. Needed because Region/StackPane/AnchorPane do not clip children
   to their own bounds, and two things here legitimately paint outside theirs:
   `sheen-highlight`, which sweeps past its anchored box by design, and the
   artwork, whose square corners would otherwise overrun the panel's curve.

   `radius` is a RADIUS; a Rectangle's arcWidth/arcHeight are the full width and
   height of the corner ellipse, hence the doubling."
  [desc radius]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Node node]
                 (let [rect (doto (Rectangle.)
                              (.setArcWidth (* 2.0 radius))
                              (.setArcHeight (* 2.0 radius)))]
                   (.bind (.widthProperty rect) (.widthProperty ^Region node))
                   (.bind (.heightProperty rect) (.heightProperty ^Region node))
                   (.setClip node rect)))
   :desc desc})

(defn- stage-panel
  [{:keys [game snapshot shot-index quote-index screenshot-fn]}]
  (let [stage    (or (:stage snapshot) :idle)
        shots    (get-in game [:art :screenshots])
        total    (count shots)
        idx      (if (pos? total) (mod (long (or shot-index 0)) total) 0)
        image    (when (pos? total) (screenshot-fn game idx))
        url      (image-url image)
        caption  (quote-block game stage quote-index)
        caption  (if url
                   ;; over artwork the caption gets the scrim, and it spans
                   ;; the full width of the panel rather than the text's own
                   ;; box: a scrim that stops where the text stops reads as a
                   ;; grey rectangle pasted on a photo.
                   {:fx/type :v-box
                    :padding {:top 64 :bottom 20 :left 24 :right 24}
                    ;; inset by the panel's border like everything else here, so
                    ;; its bottom corners follow the INNER radius -- a 6 here
                    ;; rounds the fill further than the border's own curve and
                    ;; leaves a sliver of panel showing through at each corner
                    :style (theme/style {:-fx-background-color scrim
                                          :-fx-background-radius (str "0 0 " panel-inner-radius
                                                                      " " panel-inner-radius)})
                    :children [caption]}
                   caption)
        overlay
        {:fx/type :anchor-pane
         :children
         (into [(sheen-highlight)]
               (cond-> [(if url
                          (assoc caption :anchor-pane/left 0.0 :anchor-pane/right 0.0
                                 :anchor-pane/bottom 0.0)
                          (assoc caption :anchor-pane/left 24.0 :anchor-pane/right 24.0
                                 :anchor-pane/bottom 20.0))]
                 url (conj (assoc (shot-chip idx total)
                                   :anchor-pane/left 14.0 :anchor-pane/top 14.0))
                 url (conj (assoc (shot-dots total idx)
                                   :anchor-pane/right 14.0 :anchor-pane/top 14.0))))}
        inner
        {:fx/type :stack-pane
         :style (theme/style (cond-> {:-fx-background-radius 6 :-fx-border-radius 6
                                       :-fx-border-color (str panel-hairline " " (:line c) " "
                                                               (:line c) " " (:line c))
                                       :-fx-border-width 1}
                                (not url) (assoc :-fx-background-color (:surface c))))
         :children
         (if url
           ;; The artwork and its vignette are wrapped and clipped TOGETHER at
           ;; the panel's inner radius. They are square-cornered fills and the
           ;; panel's own clip rounds at its outer edge, which is not enough to
           ;; keep them off the hairline in the corners -- see
           ;; `panel-inner-radius`. The overlay stays outside this wrapper: it
           ;; is text and chips that never reach a corner, and its caption
           ;; already rounds its own bottom edge.
           [(clip-to-live-bounds
             {:fx/type :stack-pane
              :children
              [{:fx/type :region
                :style (theme/style {:-fx-background-image (str "url('" url "')")
                                      :-fx-background-size "cover"
                                      :-fx-background-position "center center"
                                      :-fx-background-repeat "no-repeat"})}
               ;; a gentle vignette over the whole image; the caption's own scrim
               ;; below is what makes the text readable, and stacking two heavy
               ;; gradients would just black out the artwork the panel exists to show
               {:fx/type :region
                :style (theme/style {:-fx-background-color
                                      "linear-gradient(to bottom, transparent 50%, rgba(12,12,12,0.45) 100%)"})}]}
             panel-inner-radius)
            overlay]
           [overlay])}
        outer
        {:fx/type :stack-pane
         :style (theme/style {:-fx-effect panel-shadow})
         ;; the drop shadow lives on THIS node, one level up from the clip --
         ;; a shadow paints outside its node's own layout bounds by design,
         ;; and clipping that same node to its own bounds (for the sheen's
         ;; sake) would cut the shadow off right where it's meant to bloom
         :children [(clip-to-live-bounds inner panel-radius)]}]
    (assoc (anim/with-anim outer (fn [node] (anim/rise-in! node)))
           :v-box/vgrow :always)))

;; ---------------------------------------------------------------------------
;; interrupted state -- gold on surface, NEVER a red banner. This replaces
;; the stage panel entirely (same slot) whenever `:error` is set.

(defn- interrupted-panel
  [{:keys [category message]} snapshot on-retry on-back]
  {:fx/type :v-box
   :v-box/vgrow :always
   :alignment :center
   :spacing 14
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-background-radius 6
                         :-fx-border-color (:line-strong c)
                         :-fx-border-radius 6
                         :-fx-border-width 1})
   :children
   [{:fx/type :label :text (str/upper-case "Download interrupted")
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                           :-fx-text-fill (:gold c)})}
    ;; BOTH alignments, and they are not the same thing: -fx-text-alignment
    ;; only positions wrapped lines relative to EACH OTHER, so a message
    ;; short enough to fit one line ("connection reset by peer") sat flush
    ;; against the left edge of this label's 460px box while the gold
    ;; heading and the mono detail line above and below it were centred.
    ;; :alignment is what centres the text within the box.
    {:fx/type :label :text (or message "") :wrap-text true :max-width 460
     :alignment :center
     :style (theme/style {:-fx-font-family (theme/ui-font) :-fx-font-size 18
                           :-fx-text-fill (:text c) :-fx-text-alignment "center"})}
    {:fx/type :label
     :text (str (str/upper-case (name (or category :unknown))) " · "
                (fmt-bytes (:bytes-done snapshot)) " kept on disk · "
                "nothing needs to be re-fetched")
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                           :-fx-text-fill (:text-muted c)})}
    {:fx/type :region :min-height 8 :max-height 8}
    {:fx/type :h-box
     :alignment :center
     :spacing 12
     :children
     [{:fx/type :button :text "Resume download"
       :on-action (or on-retry (fn [_]))
       :min-height 40 :min-width 160
       :style (theme/style {:-fx-background-color (:button theme/gradients) :-fx-text-fill (:bg c)
                             :-fx-background-radius 3
                             :-fx-font-family (theme/ui-semibold-font)
                             :-fx-font-size 14
                             :-fx-effect (theme/glow (:gold c) {:blur 22 :spread -10 :dy 6 :alpha 0.9})})}
      {:fx/type :button :text "Back to library"
       :on-action (or on-back (fn [_]))
       :min-height 40 :min-width 160
       :style (theme/style {:-fx-background-color "transparent"
                             :-fx-border-color (:line-strong c)
                             :-fx-border-radius 3 :-fx-background-radius 3
                             :-fx-text-fill (:text-muted c) :-fx-font-size 14})}]}]})

;; ---------------------------------------------------------------------------
;; cancel row -- bottom-right, only while a run is actually in flight

(defn- cancel-row
  [on-cancel]
  {:fx/type :h-box
   :alignment :center-right
   :children
   [{:fx/type :button :text "Cancel"
     :on-action (or on-cancel (fn [_]))
     :min-height 36 :min-width 100
     :style (theme/style {:-fx-background-color "transparent"
                           :-fx-border-color (:line-strong c)
                           :-fx-border-radius 3 :-fx-background-radius 3
                           :-fx-text-fill (:text-muted c) :-fx-font-size 13})}]})

;; ---------------------------------------------------------------------------

(defn view
  "`state` is `{:snapshot :game :version :shot-index :quote-index
   :screenshot-fn :on-cancel :on-retry :on-back}`. Returns the screen's own
   root description -- not wrapped in ui/app's window frame; the caller
   assocs this in as `:content`."
  [{:keys [snapshot game version shot-index quote-index screenshot-fn
           on-cancel on-retry on-back]
    :as state}]
  (let [snapshot      (or snapshot {})
        stage         (or (:stage snapshot) :idle)
        error         (:error snapshot)
        screenshot-fn (or screenshot-fn (constantly nil))
        paused        (paused? stage)]
    {:fx/type :v-box
     :padding root-padding
     :spacing 20
     :children
     (vec
      (concat
       [(header-row game version snapshot paused)
        (progress-bar (percent (:bytes-done snapshot) (:bytes-total snapshot)))
        (stats-line snapshot paused)
        (if error
          (interrupted-panel error snapshot on-retry on-back)
          (stage-panel {:game game :snapshot snapshot :shot-index shot-index
                        :quote-index quote-index :screenshot-fn screenshot-fn}))]
       (when (and (not error) (running-stage? stage))
         [(cancel-row on-cancel)])))}))
