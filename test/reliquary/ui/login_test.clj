;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.login-test
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.anim :as anim]
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

(defn- node-seq
  "Depth-first seq of a real JavaFX Node and every descendant, walking
   through `Parent`'s children -- used to inspect actually-instantiated
   nodes (as opposed to `find-node`'s walk over cljfx description maps)."
  [^javafx.scene.Node node]
  (cons node
        (when (instance? javafx.scene.Parent node)
          (mapcat node-seq (.getChildrenUnmodifiable ^javafx.scene.Parent node)))))

(defn- any-running-anim?
  "True if some node in `node` and its descendants carries the property
   `with-anim` stores an animation under (`::anim/running`, i.e.
   `:reliquary.ui.anim/running`) -- regardless of whether that animation has
   since finished, since the property is only ever removed on delete."
  [^javafx.scene.Node node]
  (boolean (some #(.get (.getProperties ^javafx.scene.Node %) ::anim/running)
                 (node-seq node))))

(deftest the-qr-frame-glow-differs-by-state
  (testing "gold while waiting, amethyst once approved -- the design delta's
            own colours (theme/glow folds the CSS's negative spread into
            alpha, so these are the FOLDED values, not the raw CSS alpha --
            see theme/glow's docstring)"
    (let [waiting  (pr-str (login/qr-panel {:qr-state :waiting}))
          approved (pr-str (login/qr-panel {:qr-state :approved}))]
      (is (str/includes? waiting "rgba(194, 163, 95, 0.42)")
          "gold glow, blur 34 / spread -8 / alpha .55 folded")
      (is (not (str/includes? waiting "rgba(125, 107, 145, 0.73)"))
          "must not carry the approved amethyst glow while waiting")
      (is (str/includes? approved "rgba(125, 107, 145, 0.73)")
          "amethyst glow, blur 42 / spread -6 / alpha .85 folded")
      (is (not (str/includes? approved "rgba(194, 163, 95, 0.42)"))
          "must not carry the waiting gold glow once approved"))))

(deftest the-scan-line-is-only-present-while-waiting
  (testing "a 'still waiting' signal must not render over the approved overlay"
    (is (str/includes? (pr-str (login/qr-panel {:qr-state :waiting}))
                        ":mouse-transparent true")
        "the scan line is the only mouse-transparent node this screen builds")
    (is (not (str/includes? (pr-str (login/qr-panel {:qr-state :approved}))
                             ":mouse-transparent true")))))

(deftest a-disabled-sign-in-button-carries-no-glow-and-no-gradient
  (let [disabled (pr-str (find-node (login/view {:account "" :password ""}) :button))
        enabled  (pr-str (find-node (login/view {:account "someone" :password "x"}) :button))]
    (is (not (str/includes? disabled "-fx-effect")))
    (is (not (str/includes? disabled "linear-gradient")))
    (is (str/includes? enabled "-fx-effect")
        "the enabled button gets the gold glow")
    (is (str/includes? enabled "linear-gradient")
        "the enabled button gets the :button gradient")))

(deftest animate-false-starts-nothing-on-a-real-component
  (testing "*animate* false must stop every with-anim call this screen makes
            -- the scan line, the pulsing dot, and the approved overlay's
            fade-in -- from ever storing a running animation on a real,
            fx/create-component-instantiated node tree"
    (let [state {:challenge-url "https://s.team/q/1/2" :qr-state :waiting}
          [off on]
          @(fx/on-fx-thread
             [(binding [anim/*animate* false]
                (any-running-anim?
                 (fx/instance (fx/create-component (login/view state)))))
              (binding [anim/*animate* true]
                (any-running-anim?
                 (fx/instance (fx/create-component (login/view state)))))])]
      (is (false? off) "*animate* false started an animation somewhere")
      (is (true? on) "*animate* true should start at least one (sanity check)"))))

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
