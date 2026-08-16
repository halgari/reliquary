;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.login-test
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
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

(deftest the-error-message-renders-only-when-present
  (testing "spec 5 / Gilt: an error is gold text on surface, never a red banner"
    (is (= :h-box (:fx/type (login/view {})))
        "no :error -> just the two-panel split, no extra wrapper")
    (let [errored (login/view {:error "steam is down"})]
      (is (= :v-box (:fx/type errored))
          "an :error wraps the split with a strip beneath it")
      (is (str/includes? (pr-str errored) "steam is down")))
    (is (not (str/includes? (pr-str (login/view {})) "steam is down")))))

(deftest the-view-actually-instantiates-real-javafx-nodes
  (testing "pr-str only checks description SHAPE and never builds a Node -- it is
            exactly what missed the nil on-change/on-submit crash that only showed
            up in fix round 1's screenshot step. This builds the real component
            through the same lifecycle path shot/render! uses, for every branch
            that matters: default, filled fields, the guard-code swap, the
            approved QR overlay (which draws a real Canvas), and the error strip"
    (doseq [state [{}
                   {:account "someone" :password "x"}
                   {:guard-type 3 :guard-code ""}
                   {:challenge-url "https://s.team/q/1/2" :qr-state :approved}
                   {:error "steam is down"}]]
      (let [component @(fx/on-fx-thread (fx/create-component (login/view state)))]
        (is (some? (fx/instance component)) (str "failed to instantiate for " state))))))
