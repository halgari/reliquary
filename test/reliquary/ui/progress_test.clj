;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.progress-test
  "The hashing box, which both the library panel and the switch screen render.

   There were two of these. The library's was the design's `analyzing` state and
   bound its fill to the track's live width; the switch screen's was a later copy
   that sized the fill from a pixel constant and carried the rate and clock the
   original never had. Neither was wholly better, and only one of them could be
   right about the bar."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.progress :as progress])
  (:import (javafx.scene Node Scene)
           (javafx.scene.layout Region StackPane)))

(defn- box [extra]
  (pr-str (progress/hashing-box (merge {:label "Hashing local files"
                                        :done 9000000000 :total 15000000000
                                        :bytes-per-sec 1.0E9
                                        :session-bytes-per-sec 1.0E9}
                                       extra))))

(deftest it-shows-the-phase-the-percentage-and-the-bytes
  (let [s (box {})]
    (is (str/includes? s "Hashing local files"))
    (is (str/includes? s "60%"))
    (is (str/includes? s "8.4 GB of 14.0 GB"))))

(deftest it-shows-the-rate
  (testing "a fifteen gigabyte read that shows no rate reads as hung"
    (is (str/includes? (box {}) "953.7 MB/s"))))

(deftest it-shows-a-clock
  (is (str/includes? (box {}) "00:06 remaining")))

(deftest the-clock-divides-by-the-session-rate-not-the-live-one
  (testing "an instantaneous rate gives a clock that swings between four minutes
            and forty twice a second. ui/download settled this the same way."
    (let [s (box {:bytes-per-sec 1.0E7 :session-bytes-per-sec 1.0E9})]
      (is (str/includes? s "00:06 remaining") "the session rate decides the clock")
      (is (str/includes? s "9.5 MB/s") "while the live rate is what is shown"))))

(deftest a-rate-of-nothing-is-not-a-rate
  (testing "the first callback arrives before anything has been measured"
    (let [s (box {:bytes-per-sec nil :session-bytes-per-sec nil})]
      (is (str/includes? s "--"))
      (is (not (str/includes? s "remaining")) "no clock beats a wrong clock"))))

(deftest it-survives-a-first-callback-with-no-total
  (testing "the total is not known until the walk finishes"
    (let [s (box {:done 0 :total 0})]
      (is (str/includes? s "0%"))
      (is (not (str/includes? s "Infinity")))
      (is (not (str/includes? s "NaN"))))))

(deftest small-reads-are-shown-in-megabytes
  (testing "the box was written for a 15 GB switch and formatted only in GB.
            Identification reads two executables -- 41 MB on Skyrim SE -- and
            rendered them as `0.0 GB of 0.0 GB`, a bar with no numbers on it."
    (let [s (box {:done 4713472 :total 41870616})]
      (is (str/includes? s "4.5 MB of 39.9 MB"))
      (is (not (str/includes? s "0.0 GB"))))))

(deftest gigabyte-reads-are-still-shown-in-gigabytes
  (is (str/includes? (box {}) "8.4 GB of 14.0 GB")))

(deftest the-two-sides-do-not-disagree-about-units
  (testing "a bar reading `900.0 MB of 14.0 GB` is harder to read at a glance
            than one whose halves share a unit; the total decides for both"
    (let [s (box {:done 900000000 :total 15000000000})]
      (is (str/includes? s "0.8 GB of 14.0 GB")))))

(deftest the-label-is-the-callers
  (testing "the same box does duty for staging and switching on the switch
            screen; it is not always hashing"
    (is (str/includes? (box {:label "Preparing files that move"}) "Preparing files that move"))))

(defn- desc-at [frac width]
  {:fx/type :v-box :min-width width :max-width width
   :children [(progress/hashing-box {:label "Hashing" :done (long (* 1000 frac)) :total 1000
                                     :bytes-per-sec 1.0E6 :session-bytes-per-sec 1.0E6})]})

(defn- fill-widths
  "Every gold fill Region's laid-out width inside `node`, after giving `node` a
   real width.

   The resize matters: a node that is only `.layout`-ed sits at its PREFERRED
   width, and every measurement came back 40px -- the same number at 324 and at
   620, which looks exactly like a bar that ignores its container and is in fact
   a test that never gave it one."
  [^Node node width]
  (.resize ^Region node (double width) 80.0)
  (.applyCss node)
  (.layout node)
  (->> (tree-seq #(instance? javafx.scene.Parent %)
                 #(seq (.getChildrenUnmodifiable ^javafx.scene.Parent %))
                 node)
       (filter #(and (instance? Region %)
                     (let [st (.getStyle ^Node %)]
                       (and st (str/includes? st "#C2A35F") (str/includes? st "radius: 2")))))
       (mapv #(.getWidth ^Region %))))

(deftest the-bar-moves-when-the-fraction-changes
  (testing "ADVANCING one component, which is what the app does -- not rendering
            a fresh one per fraction, which is what the pixel test did and why
            this went unseen.

            The fill's width was established in an :on-created handler that fires
            exactly once, with `frac` captured in its closure. Every later render
            updated the label and the percentage and left the bar pinned at
            whatever fraction it was first created with: zero. The switch screen
            ran a whole 15 GB pass under a bar that never moved."
    (let [widths @(fx/on-fx-thread
                   (let [c0   (fx/create-component (desc-at 0.0 620))
                         node ^Node (fx/instance c0)]
                     (Scene. (StackPane. (into-array Node [node])) 700 200)
                     (mapv (fn [f]
                             (first (fill-widths
                                     (fx/instance (fx/advance-component c0 (desc-at f 620)))
                                     620)))
                           [0.0 0.25 0.6 1.0])))]
      (is (= 4 (count widths)))
      (is (zero? (first widths)) "nothing done, nothing filled")
      (is (apply < widths) (str "the fill must grow with the fraction, got " widths))
      (is (> (last widths) 400.0)
          (str "at 100% it should span the track, got " (last widths))))))

(deftest the-bar-fills-the-width-it-is-actually-given
  (testing "the same box renders in a 324px panel and a 620px screen, so the
            fill is a fraction of its track, never a pixel constant"
    (let [[narrow wide]
          @(fx/on-fx-thread
            (mapv (fn [w]
                    (let [c (fx/create-component (desc-at 1.0 w))
                          n ^Node (fx/instance c)]
                      (Scene. (StackPane. (into-array Node [n])) 900 200)
                      (first (fill-widths n w))))
                  [324 620]))]
      (is (< narrow wide) (str "narrow=" narrow " wide=" wide)))))

(deftest the-bar-keeps-no-state-that-can-go-stale
  (testing "this assertion used to say the OPPOSITE -- that the fill's width was
            established in an :on-created handler -- and it passed while the bar
            was frozen at zero, because a description map containing a lifecycle
            hook says nothing about whether that hook ever runs again.

            :on-created fires once and captures the fraction it was built with.
            The fraction is column percentages now, which the renderer recomputes
            on every update. `the-bar-moves-when-the-fraction-changes` is the
            test that actually proves it; this one keeps the mechanism from
            quietly coming back."
    (is (not (str/includes? (box {}) "on-created")))))
