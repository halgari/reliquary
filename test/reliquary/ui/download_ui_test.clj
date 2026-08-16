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
            [reliquary.ui.download :as download]
            [reliquary.ui.theme :as theme])
  (:import (java.awt.image BufferedImage)
           (javafx.scene.image Image)
           (javax.imageio ImageIO)))

(def ^:private game
  ;; A real catalog game (resources/catalog.json, appid 1086940) with real
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

(deftest a-real-screenshot-with-a-url-renders-as-a-background-image
  (let [f     (tiny-jpg-file)
        image (Image. (.toString (.toURI f)))
        s     (pr-str (download/view {:snapshot (snap {}) :game game :version version
                                       :screenshot-fn (fn [_ _] image)}))]
    (is (str/includes? s "-fx-background-image"))
    (is (str/includes? s "SHOT 01 / 03"))))

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
