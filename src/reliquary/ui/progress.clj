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
  (:require [cljfx.api :as fx]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene.layout Region StackPane)))

(def ^:private c theme/color)

(defn gb [bytes]
  (if (and bytes (pos? (long (or bytes 0))))
    (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024)))
    "size unknown"))

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
        frac  (if (pos? total) (/ (double done) total) 0.0)
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
      ;; The fill's width is BOUND to the track's live width rather than computed
      ;; from a pixel constant. Both earlier copies of this box got that wrong in
      ;; turn: the panel's first version hardcoded 236 inside a track about 324
      ;; wide, and the switch screen's scaled by its 620px box inside a track
      ;; 28px narrower. Both looked entirely reasonable in the description map
      ;; and were wrong on screen. A binding is right at any width -- which is
      ;; now the point, since this renders in a 324px panel and a 620px screen --
      ;; and survives a re-layout, which a constant does not.
      {:fx/type fx/ext-on-instance-lifecycle
       :on-created
       (fn [^StackPane track]
         (when-let [fill (first (.getChildren track))]
           (.bind (.maxWidthProperty ^Region fill)
                  (.multiply (.widthProperty track) (double frac)))))
       :desc
       {:fx/type :stack-pane
        :alignment :center-left
        :min-height 3 :max-height 3
        :style (theme/style {:-fx-background-color (:line c) :-fx-background-radius 2})
        :children [{:fx/type :region
                    :min-height 3 :max-height 3
                    :style (theme/style {:-fx-background-color (:gold c)
                                          :-fx-background-radius 2})}]}}
      ;; wrapped, not clipped: this line is at its longest in the narrowest
      ;; place it renders, the library's ~324px panel
      {:fx/type :label
       :wrap-text true
       :text (str (gb done) " of " (gb total)
                  "  ·  " (mb-per-sec bytes-per-sec)
                  (when eta (str "  ·  " eta " remaining")))
       :style (theme/style {:-fx-font-family (theme/mono-font)
                             :-fx-font-size 10
                             :-fx-text-fill (:text-dim c)})}]}))
