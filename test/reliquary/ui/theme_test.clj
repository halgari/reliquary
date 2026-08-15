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
