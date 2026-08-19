;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.progress-test
  "The hashing box, which both the library panel and the switch screen render.

   There were two of these. The library's was the design's `analyzing` state and
   bound its fill to the track's live width; the switch screen's was a later copy
   that sized the fill from a pixel constant and carried the rate and clock the
   original never had. Neither was wholly better, and only one of them could be
   right about the bar."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.progress :as progress]))

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

(deftest the-label-is-the-callers
  (testing "the same box does duty for staging and switching on the switch
            screen; it is not always hashing"
    (is (str/includes? (box {:label "Preparing files that move"}) "Preparing files that move"))))

(deftest the-fill-is-bound-to-the-tracks-live-width
  (testing "the first switch-screen copy sized the fill from a pixel constant
            equal to the box width, inside a track 28px narrower, so it ran long
            at every fraction. A binding is right at any width, and survives the
            panel being re-laid out -- which matters precisely because this box
            now renders in two places of different widths."
    (is (str/includes? (box {}) "on-created")
        "the fill's width is bound at instance creation, not computed here")))
