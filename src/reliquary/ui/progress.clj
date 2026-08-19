;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.progress
  "The hashing box: a bar over bytes being read or written, with the rate and
   the clock.

   The design calls this the `analyzing` state and it lives in the library
   panel; the switch screen shows the same box for hashing, staging and
   switching. It was written twice, once in each, and each copy had something
   the other lacked -- the panel's fill was bound to its track's live width, the
   switch screen's carried a rate and a clock. This is the union, in one place,
   so that the next fix lands on both.

   Progress is in BYTES, not files: depot files are wildly uneven, and a
   per-file bar sits at 2% and then jumps to 98% on one .bsa. Measured on a real
   15 GB install a hashing pass takes about sixteen seconds, so this is a bar
   that genuinely moves rather than a spinner standing in for one."
  (:require [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

(defn gb [bytes]
  (if (and bytes (pos? (long (or bytes 0))))
    (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024)))
    "size unknown"))

(defn span
  "`done of total`, in a unit chosen by the TOTAL.

   The box was written for a switch, where both sides are gigabytes, and
   formatted only in gigabytes. The library panel's identification pass reads
   two executables -- 41 MB on Skyrim SE -- which came out as `0.0 GB of 0.0 GB`:
   a bar with no numbers on it.

   The total picks the unit for both halves rather than each choosing its own,
   so the line does not read `900.0 MB of 14.0 GB` and make the reader convert
   before they can compare."
  [done total]
  (let [done (double (or done 0))
        total (double (or total 0))
        gib (* 1024.0 1024 1024)
        mib (* 1024.0 1024)]
    (cond
      (>= total gib) (format "%.1f GB of %.1f GB" (/ done gib) (/ total gib))
      (>= total mib) (format "%.1f MB of %.1f MB" (/ done mib) (/ total mib))
      (pos? total)   (format "%.0f KB of %.0f KB" (/ done 1024.0) (/ total 1024.0))
      :else          "size unknown")))

(defn mb-per-sec [bps]
  (if (and bps (pos? (double bps)))
    (format "%.1f MB/s" (/ (double bps) (* 1024.0 1024)))
    "--"))

(defn clock
  "mm:ss remaining at `bps`, or nil when there is no meaningful answer.

   Divides by the SESSION average rather than the live rate, the rule
   ui/download already follows: an instantaneous rate gives a clock that swings
   between four minutes and forty twice a second."
  [done total bps]
  (when (and total bps (pos? (double bps)) (pos? (long total)))
    (let [s (long (Math/ceil (/ (max 0.0 (- (double total) (double (or done 0))))
                                (double bps))))]
      (format "%02d:%02d" (quot s 60) (mod s 60)))))

(defn hashing-box
  "`{:label :done :total :bytes-per-sec :session-bytes-per-sec}`.

   `:label` because the same box reads an install, stages the chunks that move
   and then writes them; only the caller knows which. The rate shown is the live
   one and the clock is computed from the session average -- see `clock`."
  [{:keys [label done total bytes-per-sec session-bytes-per-sec]}]
  (let [done  (long (or done 0))
        total (long (or total 0))
        ;; the first callback can arrive before a total is known
        ;; clamped: a phase that re-runs can report more done than it first
        ;; announced as the total, and a percentage over 100 becomes a negative
        ;; column width, which GridPane rejects outright
        frac  (if (pos? total) (min 1.0 (max 0.0 (/ (double done) total))) 0.0)
        eta   (clock done total session-bytes-per-sec)]
    {:fx/type :v-box :spacing 9
     :padding 14
     :style (theme/style {:-fx-background-color (:bg c)
                           :-fx-border-color (:line c)
                           :-fx-border-radius 3
                           :-fx-background-radius 3})
     :children
     [{:fx/type :h-box :spacing 10
       :children [{:fx/type :label :text (str label)
                   :style (theme/style {:-fx-font-family (theme/mono-font)
                                         :-fx-font-size 11
                                         :-fx-text-fill (:text c)})}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :label :text (format "%d%%" (long (* 100 frac)))
                   :style (theme/style {:-fx-font-family (theme/mono-font)
                                         :-fx-font-size 11
                                         :-fx-text-fill (:gold c)})}]}
      ;; A 3px track with a gold fill sized by fraction. A :progress-bar would
      ;; drag JavaFX's Modena skin in and need overriding in four places.
      ;;
      ;; The fraction is expressed as GridPane column percentages, so it is
      ;; recomputed by the renderer on every update and costs no pixel constant.
      ;; Both earlier attempts got this wrong in different ways. The switch
      ;; screen scaled a :max-width by its 620px box while sitting in a track
      ;; 28px narrower, so the bar ran long. The panel bound the fill's width to
      ;; the track's inside an :on-created handler -- which fires exactly ONCE,
      ;; with `frac` captured in its closure, so every later render left the bar
      ;; pinned at the fraction it was first created with. That is zero, and a
      ;; whole 15 GB pass ran under a bar that never moved.
      ;;
      ;; A binding would have to be re-established on advance to be correct. The
      ;; percentages simply have no state to go stale.
      {:fx/type :grid-pane
       :min-height 3 :max-height 3
       ;; a Region's default maxWidth is its COMPUTED size, so without this the
       ;; track shrank to the ~40px its content asked for and the bar was a
       ;; fraction of that instead of a fraction of the box
       :max-width Double/MAX_VALUE
       :style (theme/style {:-fx-background-color (:line c) :-fx-background-radius 2})
       :column-constraints [{:fx/type :column-constraints :percent-width (* 100.0 frac)}
                            {:fx/type :column-constraints
                             :percent-width (* 100.0 (- 1.0 frac))}]
       :children [{:fx/type :region
                   :grid-pane/column 0 :grid-pane/row 0
                   :min-height 3 :max-height 3
                   ;; fill the cell the percentage sized, rather than a Region's
                   ;; computed preferred width, which is zero
                   :max-width Double/MAX_VALUE
                   :style (theme/style {:-fx-background-color (:gold c)
                                         :-fx-background-radius 2})}]}
      ;; wrapped, not clipped: this line is at its longest in the narrowest
      ;; place it renders, the library's ~324px panel
      {:fx/type :label
       :wrap-text true
       :text (str (span done total)
                  "  ·  " (mb-per-sec bytes-per-sec)
                  (when eta (str "  ·  " eta " remaining")))
       :style (theme/style {:-fx-font-family (theme/mono-font)
                             :-fx-font-size 10
                             :-fx-text-fill (:text-dim c)})}]}))
