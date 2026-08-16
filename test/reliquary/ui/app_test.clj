;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.app :as app]))

(deftest the-legal-footer-is-verbatim-and-unconditional
  (testing "this sentence is a legal requirement, not copy to be improved"
    (doseq [screen [:login :library :download :done]]
      (is (str/includes? (pr-str (app/view {:screen screen}))
                          "Not associated with or endorsed by Valve Corporation or Steam.")))))

(deftest sign-out-appears-only-when-signed-in
  (is (not (str/includes? (pr-str (app/title-bar {:signed-in? false})) "Sign out")))
  (is (str/includes? (pr-str (app/title-bar {:signed-in? true})) "Sign out")))

(deftest the-status-line-renders-what-state-says
  (is (str/includes? (pr-str (app/title-bar {:status-line "not signed in"}))
                      "not signed in")))

(deftest the-window-carries-the-app-name
  (is (= "Reliquary" (:title (app/view {:screen :login})))))

(deftest the-wordmark-tracking-is-pinned
  (testing "tracked-text's docstring names the cost: this is no longer the string \"RELIQUARY\",
            so a screen reader spells it out letter by letter -- pinning it here means the next
            person to \"tidy up\" the odd-looking spaced string cannot silently drop the tracking"
    (let [tracked (#'app/tracked-text "RELIQUARY")]
      (is (= (clojure.string/join "\u2009" "RELIQUARY") tracked)
          "U+2009 THIN SPACE between every glyph, exactly")
      (is (not= "RELIQUARY" tracked))
      (is (str/includes? (pr-str (app/title-bar {})) tracked)
          "the title bar actually renders the tracked string, not just computes it"))))
