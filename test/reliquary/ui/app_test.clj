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
