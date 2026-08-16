;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.login-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.login :as login]))

(defn find-node
  "Walks a cljfx description tree depth-first and returns the first map whose
   :fx/type is `type`. Descriptions nest through many different keys
   (:children, :desc, :root, ...) depending on the node, so this walks every
   value rather than assuming a particular shape."
  [desc type]
  (cond
    (and (map? desc) (= type (:fx/type desc))) desc
    (map? desc) (some #(find-node % type) (vals desc))
    (sequential? desc) (some #(find-node % type) desc)
    :else nil))

(deftest the-sign-in-button-is-disabled-until-both-fields-are-filled
  (is (:disable (find-node (login/view {:account "" :password ""}) :button)))
  (is (:disable (find-node (login/view {:account "someone" :password ""}) :button)))
  (is (not (:disable (find-node (login/view {:account "someone" :password "x"}) :button)))))

(deftest the-guard-code-field-replaces-the-password-field-when-steam-asks
  (testing "spec 5: without this the credential flow blocks with no explanation"
    (let [s (pr-str (login/view {:guard-type 3}))]
      (is (str/includes? s "authenticator"))
      (is (not (str/includes? s "Password"))))
    (is (str/includes? (pr-str (login/view {:guard-type 2})) "emailed"))))

(deftest the-qr-panel-never-renders-a-token
  (testing "the challenge URL is shown on purpose; nothing else secret may be"
    (let [s (pr-str (login/view {:challenge-url "https://s.team/q/1/2"
                                  :qr-state :waiting}))]
      (is (str/includes? s "https://s.team/q/1/2"))
      (is (str/includes? s "Sign-in completes on its own")))))

(deftest the-approved-state-says-so
  (is (str/includes? (pr-str (login/view {:qr-state :approved})) "Approved")))
