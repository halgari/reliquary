;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.theme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.theme :as theme]))

(deftest every-gilt-token-is-present-and-exact
  (testing "these hex values are the brand spec; a drift here is a brand bug"
    (is (= {:bg "#0C0C0C" :surface "#161616" :line "#292929" :line-strong "#383838"
            :text "#F2F0EE" :text-muted "#9A9A9A" :gold "#C2A35F" :amethyst "#7D6B91"}
           theme/color))))

(deftest fonts-load-from-the-bundle-not-the-network
  (theme/load-fonts!)
  (is (some? (theme/ui-font)))
  (is (some? (theme/mono-font)))
  (testing "the loaded families are the bundled ones, not a system fallback"
    (is (str/includes? (str/lower-case (theme/ui-font)) "hanken"))
    (is (str/includes? (str/lower-case (theme/mono-font)) "dm mono"))))

(deftest loading-fonts-twice-is-harmless
  (theme/load-fonts!)
  (is (= (theme/ui-font) (do (theme/load-fonts!) (theme/ui-font)))))

(deftest style-composes-fx-declarations
  (is (= "-fx-background-color: #161616; -fx-background-radius: 6;"
         (theme/style {:-fx-background-color (:surface theme/color)
                       :-fx-background-radius 6}))))

(deftest hex->rgb-parses-a-gilt-token
  (is (= [194 163 95] (theme/hex->rgb "#C2A35F"))))

(deftest rgba-composes-a-css-color
  (is (= "rgba(194, 163, 95, 0.9)" (theme/rgba "#C2A35F" 0.9))))

(deftest glow-approximates-the-primary-button-shadow
  (testing "0 6px 22px -10px rgba(194,163,95,.9) -- blur becomes radius, dx/dy
            pass through, and the negative spread (-10 of a 22 blur, a 45%
            fold) dims .9 alpha down to .49 rather than emitting a JavaFX
            spread argument"
    (is (= "dropshadow(gaussian, rgba(194, 163, 95, 0.49), 22, 0, 0, 6)"
           (theme/glow (:gold theme/color) {:blur 22 :spread -10 :dy 6 :alpha 0.9})))))

(deftest glow-with-no-spread-keeps-full-alpha
  (is (= "dropshadow(gaussian, rgba(125, 107, 145, 1.0), 26, 0, 0, 0)"
         (theme/glow (:amethyst theme/color) {:blur 26}))))

(deftest glow-alpha-floors-rather-than-vanishing
  (testing "an extreme negative spread must not fold alpha to (near) zero"
    (is (= "dropshadow(gaussian, rgba(194, 163, 95, 0.15), 10, 0, 0, 0)"
           (theme/glow (:gold theme/color) {:blur 10 :spread -100 :alpha 1.0})))))

(deftest linear-gradient-uses-javafx-side-keywords
  (is (= "linear-gradient(to bottom, #D3BA82, #C2A35F)"
         (theme/linear-gradient :to-bottom ["#D3BA82" "#C2A35F"]))))

(deftest linear-gradient-supports-percent-stops
  (is (= "linear-gradient(to right, #a8874a, #C2A35F 55%, #D3BA82)"
         (theme/linear-gradient :to-right ["#a8874a" ["#C2A35F" 55] "#D3BA82"]))))

(deftest linear-gradient-converts-a-css-angle-to-a-point-pair
  (testing "JavaFX has no raw-angle syntax; a numeric angle becomes an
            explicit from/to point pair via the box-corner-fitting formula"
    (is (= "linear-gradient(from 28.1% -10.2% to 71.9% 110.2%, rgba(242, 240, 238, 0.07), transparent 42%)"
           (theme/linear-gradient 160 ["rgba(242, 240, 238, 0.07)" ["transparent" 42]])))))

(deftest radial-gradient-names-center-and-radius-explicitly
  (is (= "radial-gradient(center 50% 50%, radius 50%, rgba(194, 163, 95, 0.3), transparent 68%)"
         (theme/radial-gradient {:radius 50} ["rgba(194, 163, 95, 0.3)" ["transparent" 68]]))))

(deftest gradients-cover-the-six-design-delta-values
  (is (= #{:button :progress-bar :progress-done :title-bar :card-sheen :logo-halo}
         (set (keys theme/gradients))))
  (is (every? string? (vals theme/gradients))))

(deftest tabular-nums-is-the-tnum-feature-token
  (is (= "\"tnum\"" theme/tabular-nums)))
