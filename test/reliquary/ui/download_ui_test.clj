;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.download-ui-test
  "Named `download-ui-test` (not `download-test`) deliberately: the engine
   already owns `reliquary.download-test` for `reliquary.download`, and this
   file is testing the screen, `reliquary.ui.download`, a different
   namespace entirely."
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.download :as download]
            [reliquary.ui.theme :as theme])
  (:import (java.awt.image BufferedImage)
           (javafx.scene.image Image)
           (javax.imageio ImageIO)))

(def ^:private game
  ;; A real catalog game (resources/catalog.edn, appid 1086940) with real
  ;; quotes and a real screenshot list, per the task brief: "the quote must
  ;; still be readable ... use a real game in your renders."
  {:appid 1086940
   :title "Baldur's Gate 3"
   :studio "Larian Studios"
   :art {:capsule "https://cdn.example/capsule.jpg"
         :screenshots ["https://cdn.example/ss1.jpg" "https://cdn.example/ss2.jpg"
                       "https://cdn.example/ss3.jpg"]}
   :quotes [{:text "Tongs. A wide variety of tongs." :attrib "the Narrator, examining a workbench"}
            {:text "Why do beautiful people taste better?" :attrib "Astarion"}]})

(def ^:private no-quotes-game
  {:appid 999 :title "A Game With No Quotes" :studio "Nobody" :art {}})

(def ^:private version
  {:id "public" :label "Latest — public" :branch "public" :build "24532579"
   :date "2026-08-03" :bytes 155594720745})

(def ^:private unknown-version
  {:id "1.0" :label "1.0" :branch "public" :build "" :date "2019-01-01" :bytes 0})

(defn- snap
  [overrides]
  (merge {:stage :downloading :bytes-done 0 :bytes-total 0
          :chunks-done 0 :chunks-total 0 :wire-bytes 0
          :bytes-per-sec 0.0 :wire-bytes-per-sec 0.0 :samples [] :error nil}
         overrides))

;; ---------------------------------------------------------------------------
;; sparkline: 0, 1, 5, 48, and past-48 samples must all render exactly 48
;; bars, padded rather than stretched

(deftest sparkline-always-renders-48-bars-padded-not-stretched
  (doseq [n [0 1 5 48 60]]
    (testing (str n " samples")
      (let [samples (vec (repeat n 12345.0))
            spark   (#'download/sparkline samples false)]
        (is (= 48 (count (:children spark))))))))

(deftest sparkline-with-zero-samples-does-not-crash-on-an-empty-peak
  (testing "an all-zero/empty sample set must not divide by a zero peak"
    (let [spark (#'download/sparkline [] false)
          s     (pr-str spark)]
      (is (not (str/includes? s "NaN")))
      (is (not (str/includes? s "Infinity"))))))

(deftest recent-bars-are-gold-the-rest-are-dim-gold-unless-paused
  (let [samples (vec (repeat 48 1000.0))
        running (pr-str (#'download/sparkline samples false))
        paused  (pr-str (#'download/sparkline samples true))]
    (is (str/includes? running (:gold theme/color)))
    (is (str/includes? running "rgba(194,163,95,0.38)"))
    (testing "paused collapses every bar to line-strong, none gold"
      (is (str/includes? paused (:line-strong theme/color)))
      (is (not (str/includes? paused (:gold theme/color)))))))

(deftest the-tip-bars-glow-and-the-rest-do-not
  (testing "the leading (newest) 3 bars carry the tip glow while running"
    (let [samples (vec (repeat 48 1000.0))
          bars    (:children (#'download/sparkline samples false))
          glow    (theme/glow (:gold theme/color) {:blur 10 :spread -1 :alpha 0.8})]
      (is (= 48 (count bars)))
      (is (every? #(str/includes? (pr-str %) glow) (take-last 3 bars))
          "the last 3 (newest) bars carry the exact tip-glow effect")
      (is (not-any? #(str/includes? (pr-str %) glow) (drop-last 3 bars))
          "no other bar does")))
  (testing "paused suppresses the tip glow too -- every bar is already flat
            line-strong grey, and a gold glow on a grey bar would look like a bug"
    (let [samples (vec (repeat 48 1000.0))
          bars    (:children (#'download/sparkline samples true))
          glow    (theme/glow (:gold theme/color) {:blur 10 :spread -1 :alpha 0.8})]
      (is (not-any? #(str/includes? (pr-str %) glow) bars)))))

;; ---------------------------------------------------------------------------
;; the header row: title left, measurements right, ONE row

(deftest the-header-row-puts-the-title-left-and-the-measurements-right
  (testing "these four blocks were stacked against the left edge, which left
            the right half of a 1100px window empty; the mockup puts the
            title left, taking the free space, with the sparkline, time
            remaining and complete right-aligned opposite it"
    (let [desc (download/view {:snapshot (snap {:bytes-done 5 :bytes-total 10})
                               :game game :version version})
          row  (first (:children desc))
          kids (:children row)
          s    (pr-str row)]
      (is (= :h-box (:fx/type row)) "one row, not two stacked blocks")
      (is (str/includes? (pr-str (first kids)) "Baldur's Gate 3")
          "the title block leads the row")
      (is (some #(= :always (:h-box/hgrow %)) kids)
          "a spacer takes the free space -- a Region's default max width is its
           preferred width, so hgrow on the title block itself would not grow it")
      (is (str/includes? s (str/join "\u2009" "THROUGHPUT"))
          "tracked caps (thin spaces), as the mockup sets it")
      (is (str/includes? s "Time remaining"))
      (is (str/includes? s "Complete"))
      (is (str/includes? (pr-str (last kids)) "Complete")
          "Complete is the last thing on the row, hard against the right edge"))))

(deftest a-long-title-ellipsises-rather-than-pushing-the-numbers-off-the-edge
  (testing "shot/render! and the real window both CROP; an unbounded title --
            this catalog carries `The Elder Scrolls IV: Oblivion(R) Game of
            the Year Edition (2009)` -- would silently delete the percentage"
    (let [long-game (assoc game :title "The Elder Scrolls IV: Oblivion® Game of the Year Edition (2009)")
          desc      (download/view {:snapshot (snap {}) :game long-game :version version})
          header    (first (:children (first (:children desc))))]
      (is (= :v-box (:fx/type header)))
      (is (number? (:max-width header)) "the title block is width-bounded")
      (is (every? #(number? (:max-width %)) (rest (:children header)))
          "and so are the title and meta labels, so JavaFX ellipsises instead of overflowing"))))

;; ---------------------------------------------------------------------------
;; formatting: bytes, percentage, ETA

(deftest byte-formatting-scales-and-never-lies-about-zero
  (is (= "0 B" (#'download/fmt-bytes 0)))
  (is (= "0 B" (#'download/fmt-bytes nil)))
  (is (= "512 B" (#'download/fmt-bytes 512)))
  (is (= "2 KB" (#'download/fmt-bytes 2048)))
  (is (= "5 MB" (#'download/fmt-bytes (* 5 1024 1024))))
  (is (= "2.0 GB" (#'download/fmt-bytes (* 2 1024 1024 1024)))))

(deftest size-says-unknown-for-a-zero-or-absent-total-never-0-0-gb
  (testing "a version's bytes 0 -- genuinely unknown for community-sourced catalog entries"
    (is (= "size unknown" (#'download/fmt-size 0)))
    (is (= "size unknown" (#'download/fmt-size nil)))
    (is (= "1.0 GB" (#'download/fmt-size (* 1024 1024 1024))))))

(deftest percentage-clamps-and-never-divides-by-a-zero-total
  (is (= 50.0 (#'download/percent 50 100)))
  (is (= 0.0 (#'download/percent 50 0)))
  (is (= 0.0 (#'download/percent 50 nil)))
  (is (= 100.0 (#'download/percent 500 100)) "clamped, not overshooting 100")
  (is (= "50%" (#'download/fmt-percent 50.0))))

(deftest eta-is-dash-dash-when-paused-or-when-the-rate-is-zero
  (is (= 15.0 (#'download/eta-seconds 0 150 10)))
  (is (nil? (#'download/eta-seconds 0 0 10)) "unknown total -> no ETA")
  (is (nil? (#'download/eta-seconds 0 150 0)) "stalled rate -> no ETA")
  (is (= "00:15" (#'download/fmt-clock 15)))
  (is (= "--:--" (#'download/fmt-clock nil)))
  (is (= "--:--" (#'download/fmt-clock 0))))

;; ---------------------------------------------------------------------------
;; progress bar: gold gradient/glow under 100%, amethyst gradient/glow at 100%

(deftest the-progress-bar-switches-gradient-and-glow-colour-at-100-percent
  (let [forty     (pr-str (#'download/progress-bar 40.0))
        full      (pr-str (#'download/progress-bar 100.0))
        gold-grad (:progress-bar theme/gradients)
        done-grad (:progress-done theme/gradients)
        gold-glow (theme/glow (:gold theme/color) {:blur 22 :spread -4 :alpha 0.85})
        amy-glow  (theme/glow (:amethyst theme/color) {:blur 22 :spread -4 :alpha 0.85})]
    (is (str/includes? forty gold-grad))
    (is (str/includes? forty gold-glow))
    (is (not (str/includes? forty done-grad)))
    (is (not (str/includes? forty amy-glow)))
    (is (str/includes? full done-grad))
    (is (str/includes? full amy-glow))
    (is (not (str/includes? full gold-grad)))
    (is (not (str/includes? full gold-glow)))
    (is (not= gold-glow amy-glow) "the two glow colours actually differ")))

;; ---------------------------------------------------------------------------
;; percent and ETA: tabular numerals, and only percent gets the gold haze

(deftest percent-and-eta-carry-tabular-numerals-and-only-percent-glows
  (testing "walking the plain description data rather than `pr-str`-ing it,
            because `pr-str` backslash-escapes the embedded double quotes in
            `tabular-nums`' own value (\"tnum\"), which then never matches
            literally inside the printed, re-escaped string"
    (let [snapshot (snap {:bytes-done 40 :bytes-total 100})
          row      (download/view {:snapshot snapshot :game game :version version})
          header   (first (:children row))
          eta-col  (nth (:children header) 3)
          pct-col  (nth (:children header) 4)
          eta-value-style (:style (second (:children eta-col)))
          pct-value-style (:style (second (:children pct-col)))
          pct-glow (theme/glow (:gold theme/color) {:blur 22 :alpha 0.45})]
      (is (str/includes? eta-value-style theme/tabular-nums) "ETA carries tabular numerals")
      (is (str/includes? pct-value-style theme/tabular-nums) "percent carries tabular numerals")
      (is (str/includes? pct-value-style pct-glow) "percent carries the gold haze behind the number")
      (is (not (str/includes? eta-value-style pct-glow)) "ETA does not -- only percent gets the glow"))))

;; ---------------------------------------------------------------------------
;; :bytes-total 0 must never produce NaN/Infinity anywhere in the rendered
;; description -- this catalog genuinely contains such versions

(deftest a-zero-bytes-total-produces-no-nan-or-infinity-anywhere
  (let [snapshot (snap {:bytes-done 500 :bytes-total 0 :bytes-per-sec 100.0
                         :wire-bytes-per-sec 100.0 :samples [50.0 100.0]})
        s (pr-str (download/view {:snapshot snapshot :game game :version unknown-version}))]
    (is (not (str/includes? s "NaN")))
    (is (not (str/includes? s "Infinity")))
    (is (str/includes? s "size unknown"))))

(deftest a-zero-bytes-total-with-an-idle-snapshot-also-never-crashes-formatting
  (let [s (pr-str (download/view {:snapshot (snap {:stage :idle}) :game game :version unknown-version}))]
    (is (not (str/includes? s "NaN")))
    (is (not (str/includes? s "Infinity")))))

;; ---------------------------------------------------------------------------
;; interrupted state: gold on surface, NEVER red

(deftest the-interrupted-state-is-gold-on-surface-with-no-red-anywhere
  (let [snapshot (snap {:stage :failed :bytes-done 1234567
                         :error {:category :io :message "connection reset by peer"}})
        desc (download/view {:snapshot snapshot :game game :version version})
        s    (pr-str desc)]
    (is (str/includes? s "DOWNLOAD INTERRUPTED"))
    (is (str/includes? s "connection reset by peer"))
    (is (str/includes? s "IO"))
    (is (str/includes? s "kept on disk"))
    (is (str/includes? s "nothing needs to be re-fetched"))
    (is (str/includes? s (:gold theme/color)))
    (is (str/includes? s (:surface theme/color)))
    (testing "no red anywhere -- an explicit Gilt rule, not a style preference"
      (is (not (str/includes? (str/lower-case s) "red")))
      (is (not (re-find #"(?i)#ff0000" s))))))

(deftest the-interrupted-state-has-no-amethyst-success-glow-either
  (testing "amethyst is this app's ONE 'completed/success' cue (the done
            screen's ring) -- the interrupted state gets the same gold
            button treatment every primary action gets elsewhere, but must
            never pick up amethyst, which would read as 'this succeeded'"
    (let [snapshot (snap {:stage :failed :error {:category :io :message "disk full"}})
          s        (pr-str (download/view {:snapshot snapshot :game game :version version}))]
      (is (not (str/includes? s (:amethyst theme/color)))))))

(deftest the-interrupted-panel-has-resume-and-back-buttons-not-a-cancel-button
  (let [snapshot (snap {:stage :failed :error {:category :unavailable :message "host unreachable"}})
        s (pr-str (download/view {:snapshot snapshot :game game :version version}))]
    (is (str/includes? s "Resume download"))
    (is (str/includes? s "Back to library"))
    (is (not (str/includes? s "\"Cancel\"")))))

;; ---------------------------------------------------------------------------
;; cancel button: only while a run is actually in flight

(deftest cancel-appears-only-while-running-not-idle-done-cancelled-or-failed
  (doseq [stage [:preparing :downloading :copying]]
    (testing stage
      (is (str/includes? (pr-str (download/view {:snapshot (snap {:stage stage})
                                                   :game game :version version}))
                          "\"Cancel\""))))
  (doseq [stage [:idle :done :cancelled]]
    (testing stage
      (is (not (str/includes? (pr-str (download/view {:snapshot (snap {:stage stage})
                                                        :game game :version version}))
                               "\"Cancel\""))))))

;; ---------------------------------------------------------------------------
;; quote / stage-text fallback and the artwork-unavailable path

(deftest a-game-with-no-quotes-shows-the-stage-text-alone
  (let [s (pr-str (download/view {:snapshot (snap {:stage :downloading})
                                   :game no-quotes-game :version version}))]
    (is (str/includes? s "Downloading"))
    (is (not (str/includes? s "Overheard in")))))

(deftest a-game-with-quotes-shows-a-real-catalog-quote
  (let [s (pr-str (download/view {:snapshot (snap {}) :game game :version version
                                   :quote-index 0}))]
    (is (str/includes? s "OVERHEARD IN BALDUR'S GATE 3"))
    (is (str/includes? s "Tongs. A wide variety of tongs."))
    (is (str/includes? s "the Narrator, examining a workbench"))))

(deftest quote-index-wraps-so-a-huge-index-never-throws
  (is (some? (download/view {:snapshot (snap {}) :game game :version version
                              :quote-index 999999}))))

(deftest the-sparkline-cannot-overflow-its-260px-box
  (testing "a bar is 3.458px wide and JavaFX rounds every child up to a whole
            pixel by default, laying 48 bars out at 286px -- and HBox lets a
            children-derived min width beat :max-width, so the row grew past
            its declared width and the last bars ran into the clock. That is
            the collision the first live screenshot shipped with."
    (let [spark (#'download/sparkline [1.0 2.0 3.0] false)]
      (is (false? (:snap-to-pixel spark))
          "fractional bar widths must be honoured, or the row is 26px too wide")
      (is (= 260.0 (:max-width spark)))
      (is (= 260.0 (:min-width spark)))
      (is (= 48 (count (:children spark))))
      (is (<= (+ (* 48 (:min-width (first (:children spark))))
                 (* 47 (:spacing spark)))
              260.0)
          "48 bars plus 47 gaps must fit inside the declared width")))
  (testing "the block around it is pinned to the same width, so the gap before
            the clock is a real gap"
    (let [row   (first (:children (download/view {:snapshot (snap {}) :game game
                                                  :version version})))
          block (nth (:children row) 2)]
      (is (= 260.0 (:max-width block)))
      (is (= 26.0 (:spacing row)) "the mockup's gutter before time/percent"))))

(deftest few-samples-pad-left-rather-than-stretching
  (testing "an early download has three samples; they belong at the RIGHT of
            the sparkline with zero-height padding behind them"
    (let [spark (#'download/sparkline [1000.0 2000.0 4000.0] false)
          hs    (mapv :min-height (:children spark))]
      (is (= 48 (count hs)))
      (is (every? zero? (take 45 hs)) "45 empty bars, oldest first")
      (is (every? pos? (drop 45 hs)) "the three real samples are the newest")
      (is (= 43.0 (last hs)) "the peak sample fills the full bar height"))))

(deftest the-default-screenshot-fn-renders-the-artwork-unavailable-fallback
  (testing "no :screenshot-fn at all -> (constantly nil) -> flat surface, never a broken image"
    (let [s (pr-str (download/view {:snapshot (snap {}) :game game :version version}))]
      (is (str/includes? s (:surface theme/color)))
      (is (not (str/includes? s "-fx-background-image"))))))

(defn- tiny-jpg-file
  "A real, decodable 4x4 JPEG on disk, so a real javafx.scene.image.Image can
   be built from a `file:` URI the same way reliquary.ui.art does it --
   loading from a file URI works without the JavaFX toolkit running, per
   art.clj's own docstring."
  ^java.io.File []
  (let [f   (io/file (System/getProperty "java.io.tmpdir") "reliquary-download-test-shot.jpg")
        img (BufferedImage. 8 8 BufferedImage/TYPE_INT_RGB)]
    (ImageIO/write img "jpg" f)
    f))

(deftest the-caption-over-artwork-carries-a-scrim-and-the-chips-a-backing
  (testing "Stardew's farm scene -- the brightest art in this catalog -- put
            near-white 21px text on near-white pixels in the first live
            screenshot. A scrim tuned against a dark screenshot fails
            completely against a bright one, so the caption block gets the
            mockup's gradient and the two chips that ride ON the artwork,
            above any scrim, get their own backing."
    (let [f     (tiny-jpg-file)
          image (Image. (.toString (.toURI f)))
          s     (pr-str (download/view {:snapshot (snap {}) :game game :version version
                                        :screenshot-fn (fn [_ _] image)}))]
      (is (str/includes? s "rgba(12,12,12,0.93) 42%")
          "the caption scrim: transparent at the top, solid bg by 42%")
      (is (str/includes? s "rgba(12,12,12,0.9)")
          "the SHOT chip and the shot dots are backed against bright art")
      (is (str/includes? s (:text theme/color))
          "the chip label is full-strength text, not muted, over artwork")))
  (testing "with no artwork there is nothing to scrim -- the flat surface
            panel must not be darkened for no reason"
    (let [s (pr-str (download/view {:snapshot (snap {}) :game game :version version}))]
      (is (not (str/includes? s "rgba(12,12,12,0.93)"))))))

(deftest a-real-screenshot-with-a-url-renders-as-a-background-image
  (let [f     (tiny-jpg-file)
        image (Image. (.toString (.toURI f)))
        s     (pr-str (download/view {:snapshot (snap {}) :game game :version version
                                       :screenshot-fn (fn [_ _] image)}))]
    (is (str/includes? s "-fx-background-image"))
    (is (str/includes? s "SHOT 01 / 03"))))

(deftest the-active-shot-dot-glows-and-the-others-do-not
  (let [dots (#'download/shot-dots 3 1)
        glow (theme/glow (:gold theme/color) {:blur 12 :spread -1 :alpha 0.9})
        strs (mapv pr-str (:children dots))]
    (is (= 3 (count strs)))
    (is (str/includes? (nth strs 1) glow) "the active (index 1) dot glows")
    (is (not (str/includes? (nth strs 0) glow)))
    (is (not (str/includes? (nth strs 2) glow)))))

;; ---------------------------------------------------------------------------
;; the animate-false guarantee: *animate* false must start neither the stage
;; panel's rise-in! entrance nor its sheen

(deftest animate-false-starts-nothing-on-the-stage-panel
  (testing "rise-in! only forces starting opacity 0 INSIDE its own
            `(when *animate* ...)` guard -- with *animate* false a freshly
            created node's opacity stays JavaFX's own default (1.0) rather
            than being forced low by an entrance animation that never ran"
    (let [opacity (fn [animate?]
                    @(fx/on-fx-thread
                       (binding [anim/*animate* animate?]
                         (let [component (fx/create-component
                                           (#'download/stage-panel
                                            {:game game :snapshot (snap {}) :screenshot-fn (constantly nil)}))]
                           (.getOpacity (fx/instance component))))))]
      (is (= 1.0 (opacity false)))
      (is (= 0.0 (opacity true))))))

;; ---------------------------------------------------------------------------
;; real component instantiation -- pr-str only checks shape, not a nil
;; handler or a bad prop, which has already bitten this project twice

(deftest the-view-actually-instantiates-real-javafx-nodes
  (let [f     (tiny-jpg-file)
        image (Image. (.toString (.toURI f)))]
    (doseq [state [{:snapshot (snap {:stage :idle}) :game game :version version}
                   {:snapshot (snap {:stage :preparing}) :game game :version version}
                   {:snapshot (snap {:stage :downloading :bytes-done 500 :bytes-total 1000
                                      :bytes-per-sec 100.0 :wire-bytes-per-sec 80.0
                                      :samples [10.0 20.0 30.0]})
                    :game game :version version :shot-index 1 :quote-index 1}
                   {:snapshot (snap {:stage :downloading :samples (vec (repeat 60 500.0))})
                    :game game :version version :screenshot-fn (fn [_ n] (when (= n 0) image))}
                   {:snapshot (snap {:stage :downloading}) :game no-quotes-game :version unknown-version}
                   {:snapshot (snap {:stage :copying}) :game game :version version}
                   {:snapshot (snap {:stage :cancelled}) :game game :version version}
                   {:snapshot (snap {:stage :failed
                                      :error {:category :io :message "disk full"}})
                    :game game :version version}
                   {:snapshot (snap {:stage :failed
                                      :error {:category :unauthenticated :message "token expired"}})
                    :game game :version version
                    :on-cancel (fn [_]) :on-retry (fn [_]) :on-back (fn [_])}]]
      (let [component @(fx/on-fx-thread (fx/create-component (download/view state)))]
        (is (some? (fx/instance component)) (str "failed to instantiate for " state))))))

(deftest the-interrupted-message-is-centred-not-merely-text-aligned
  ;; -fx-text-alignment only positions wrapped lines against each other. A
  ;; one-line message ("connection reset by peer") sat flush left in its
  ;; 460px box while the heading above and detail below were centred.
  ;; :alignment is the property that centres text within the box.
  (let [snapshot (snap {:stage :failed :bytes-done 1234567
                        :error {:category :io :message "connection reset by peer"}})
        desc (download/view {:snapshot snapshot :game game :version version})
        lbl  (->> (tree-seq coll? seq desc)
                  (filter #(and (map? %)
                                 (= "connection reset by peer" (:text %))))
                  first)]
    (is (some? lbl) "the interrupted message is on screen")
    (is (= :center (:alignment lbl))
        "centred within its box, not just text-aligned")))

(deftest the-eta-uses-the-session-average-not-the-instantaneous-rate
  ;; Dividing remaining bytes by a 250 ms rate makes a clock that swings
  ;; wildly twice a second. The speedometer and sparkline keep the live rate;
  ;; only the clock is smoothed.
  (let [;; half done; the instant rate is a 10x spike, the session average is calm
        snapshot (snap {:stage :downloading :bytes-done 500 :bytes-total 1000
                        :bytes-per-sec 1000.0 :wire-bytes-per-sec 1000.0
                        :session-bytes-per-sec 100.0})
        s (pr-str (download/view {:snapshot snapshot :game game :version version}))]
    ;; 500 remaining / 100 B/s = 5s -> 00:05. The instant rate would say 00:01.
    (is (str/includes? s "00:05")
        "the clock follows the session average")
    (is (not (str/includes? s "00:01"))
        "and not the momentary spike"))
  (testing "no session history yet reads --:-- rather than a wild number"
    (let [snapshot (snap {:stage :downloading :bytes-done 10 :bytes-total 1000
                          :bytes-per-sec 5000.0 :session-bytes-per-sec 0.0})
          s (pr-str (download/view {:snapshot snapshot :game game :version version}))]
      (is (str/includes? s "--:--")))))
