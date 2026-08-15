;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.qr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [reliquary.steam.qr :as qr]))

(def ^:private challenge "https://s.team/q/1/1234567890123456789")

(deftest span-includes-the-quiet-zone-on-both-sides
  (let [m (qr/module-matrix challenge)]
    (is (= (+ (.getWidth m) 8) (qr/module-span challenge))
        "4 modules of quiet zone each side -- scanners need it")))

(deftest terminal-string-is-square-in-modules
  (let [lines (str/split-lines (qr/terminal-string challenge))
        span (qr/module-span challenge)]
    (is (= span (count lines)))))

(deftest each-module-is-two-cells-wide-so-it-renders-square
  (testing "terminal cells are taller than wide; one cell per module reads as a rectangle"
    (let [line (first (str/split-lines (qr/terminal-string challenge)))
          span (qr/module-span challenge)
          ;; strip ANSI escapes; what remains is two spaces per module.
          ;; \x1b is load-bearing here -- esc is (char 27), so terminal-string
          ;; emits ESC[40m etc; drop the \x1b and the ESC byte survives the
          ;; strip, one per module, inflating the count by exactly `span`
          ;; without an obviously-wrong failure message.
          visible (str/replace line #"\x1b\[[0-9;]*m" "")]
      (is (= (* 2 span) (count visible))))))

(deftest the-quiet-zone-is-light
  (let [m (qr/module-matrix challenge)]
    (is (not (qr/dark-at? m 0 0)) "top-left corner is quiet zone")
    (is (not (qr/dark-at? m (dec (qr/module-span challenge)) 0)))))

(deftest the-finder-pattern-is-dark
  (testing "module (4,4) is the corner of the top-left finder square"
    (is (qr/dark-at? (qr/module-matrix challenge) 4 4))))

(deftest different-urls-produce-different-renderings
  (is (not= (qr/terminal-string challenge)
            (qr/terminal-string "https://s.team/q/1/9999999999999999999"))))
