;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.app-test
  "Some of these tests need a real JavaFX Node graph -- checking
   `:mouse-transparent` and whether an animation actually got started is
   not something a plain cljfx description map (a nested Clojure map, not
   yet realised) can answer. Those go through `fx/create-component` on the
   JavaFX Application Thread, following `reliquary.ui.anim-test`'s pattern:
   requiring `reliquary.ui.shot` starts the toolkit as a side effect, and
   `fx/on-fx-thread` computes plain values back on the test thread so
   clojure.test's counters see every `is`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljfx.api :as fx]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.app :as app]
            [reliquary.ui.shot]) ; side effect: starts the JavaFX toolkit
  (:import (javafx.animation Animation)
           (javafx.scene.layout Pane Region)))

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
      (is (= (clojure.string/join " " "RELIQUARY") tracked)
          "U+2009 THIN SPACE between every glyph, exactly")
      (is (not= "RELIQUARY" tracked))
      (testing "the wordmark only renders in the drawn fallback, when the logo image is unavailable"
        (with-redefs [app/logo-resource (constantly nil)]
          (is (str/includes? (pr-str (app/title-bar {})) tracked)
              "the title bar's fallback branch actually renders the tracked string, not just computes it"))))))

;; ---------------------------------------------------------------------------
;; the logo image and its fallback

(deftest the-logo-resource-is-bundled
  (is (some? (app/logo-resource))
      "resources/reliquary-logo.png must actually be on the classpath -- if
       this fails, every other 'logo renders' assertion below is vacuous")
  (is (some? (app/logo-image))))

(deftest the-logo-renders-when-the-resource-is-present
  (testing "the bundled artwork is used, not the drawn ring-and-dot mark + wordmark"
    (let [rendered (pr-str (app/title-bar {}))]
      (is (str/includes? rendered ":image-view"))
      (is (not (str/includes? rendered (#'app/tracked-text "RELIQUARY")))
          "the tracked wordmark must not also render alongside the image"))))

(deftest logo-image-is-nil-when-the-resource-lookup-returns-nil
  (testing "a missing resources/reliquary-logo.png -- io/resource returns nil, a different
            failure from a present-but-corrupt file -- must not throw"
    (with-redefs [app/logo-resource (constantly nil)]
      (is (nil? (app/logo-image))))))

(deftest logo-image-is-nil-for-a-corrupt-resource
  (testing "javafx.scene.image.Image sets .isError rather than throwing for a bad payload;
            logo-image must check it explicitly rather than relying on a missing exception"
    (let [garbage (io/resource "reliquary.css")] ; present on the classpath, not a decodable image
      (with-redefs [app/logo-resource (constantly garbage)]
        (is (nil? (app/logo-image)))))))

(deftest the-drawn-fallback-renders-when-the-logo-resource-is-missing
  (testing "stub the resource lookup: a packaging slip that drops the image must degrade to
            the old title bar (drawn mark + wordmark), not to an empty rectangle"
    (with-redefs [app/logo-resource (constantly nil)]
      (let [rendered (pr-str (app/title-bar {}))]
        (is (not (str/includes? rendered ":image-view")))
        (is (str/includes? rendered (#'app/tracked-text "RELIQUARY")))))))

;; ---------------------------------------------------------------------------
;; the halo: mouse-transparent, and obeys anim/*animate*, via real instantiation

(defn- find-halo-node
  "Real fx/create-component instantiation of the title bar, then a walk down to
   the actual JavaFX Region the halo renders as.

   The logo is found by TYPE rather than by child index. It used to be
   `(.get (.getChildren root) 0)`, which broke the moment a close button was
   added to the left of it -- a positional walk encodes the bar's layout into a
   test that is not about layout, and quietly casts whatever is at index 0."
  ^Region [state]
  (let [root (fx/instance (fx/create-component (app/title-bar state)))
        logo ^Pane (first (filter #(instance? Pane %) (.getChildren ^Pane root)))]
    (.get (.getChildren logo) 0)))

(deftest the-halo-is-mouse-transparent
  (let [transparent? @(fx/on-fx-thread (.isMouseTransparent (find-halo-node {})))]
    (is (true? transparent?))))

(deftest with-animate-false-nothing-is-started
  (testing "*animate* false must mean no Timeline is even built for the halo, per anim/with-anim's
            contract of stashing the running animation in the node's properties under ::anim/running"
    (let [has-running-anim?
          @(fx/on-fx-thread
             (binding [anim/*animate* false]
               (let [node (find-halo-node {})]
                 (some? (.get (.getProperties node) ::anim/running)))))]
      (is (false? has-running-anim?)))))

(deftest with-animate-true-the-halo-actually-breathes
  (testing "the counterpart to the false case above: a real running, indefinite animation exists"
    (let [status
          @(fx/on-fx-thread
             (binding [anim/*animate* true]
               (let [node (find-halo-node {})
                     tl   ^Animation (.get (.getProperties node) ::anim/running)]
                 [(some? tl) (when tl (.getStatus tl)) (when tl (.getCycleCount tl))])))
          [running? fx-status cycle-count] status]
      (is (true? running?))
      (is (= javafx.animation.Animation$Status/RUNNING fx-status))
      (is (= Animation/INDEFINITE cycle-count)))))

;; ---------------------------------------------------------------------------
;; the window's own chrome
;;
;; The app draws its own title bar -- logo, status line, Sign out -- so the OS
;; drawing another one above it produced two stacked bars on Windows. Going
;; undecorated removes the OS one, and with it everything the OS bar provided:
;; a close button and somewhere to grab the window. Both have to come back from
;; here, or the window is one a user cannot move or shut.

(defn- stage-instance
  "Realise `app/view` into an actual Stage and hand `f` its instance. A Stage
   description is not a Stage: `:style :undecorated` is a cljfx keyword that
   only becomes a StageStyle when the component is created, so asserting on the
   description alone would prove nothing about the window."
  [state f]
  @(fx/on-fx-thread
    (let [component (fx/create-component (assoc (app/view state) :showing false))]
      (try (f (fx/instance component))
           (finally (fx/delete-component component))))))

(deftest the-window-has-no-os-chrome
  (testing "two title bars is the bug; the app's own is the one that stays"
    (is (= javafx.stage.StageStyle/UNDECORATED
           (stage-instance {:screen :login} (fn [^javafx.stage.Stage s] (.getStyle s)))))))

(deftest the-window-carries-the-application-icon
  (testing "an undecorated window still shows up in the taskbar and alt-tab, and
            without :icons it shows up there as a generic Java mug"
    (let [n (stage-instance {:screen :login}
                            (fn [^javafx.stage.Stage s] (count (.getIcons s))))]
      (is (pos? n) "the stage must carry at least one icon image"))))

(deftest the-icon-resource-is-bundled
  (is (some? (app/icon-resource))
      "resources/reliquary.ico is what jpackage stamps on the .exe and
       resources/reliquary-icon.png is what the running window shows -- if this
       is missing, the icon assertions above are vacuous")
  (is (some? (app/icon-image))))

(deftest the-close-button-is-the-last-thing-in-the-title-bar
  (testing "far right, where every other Windows app puts it. It has to come
            after Sign out too, not merely somewhere on the right-hand side --
            Sign out is conditional, so 'last' is the only position that holds
            whether or not it is present."
    (doseq [signed-in? [true false]]
      (let [children (:children (app/title-bar {:signed-in? signed-in?}))
            last-child (last children)]
        (is (= :button (:fx/type last-child))
            (str "signed-in? " signed-in? ": the rightmost item must be the close button"))
        (is (str/includes? (pr-str last-child) "Close")
            "and it must say so for screen readers, whatever glyph it draws")))
    (testing "and the logo stays leftmost"
      (is (not= :button (:fx/type (first (:children (app/title-bar {})))))))))

(deftest the-close-button-is-wired-not-decorative
  (testing "with no OS chrome this is the only way to shut the app"
    (let [closed? (atom false)
          bar (app/title-bar {:on-close (fn [_] (reset! closed? true))})
          btn (last (:children bar))]
      (is (fn? (:on-action btn)))
      ((:on-action btn) nil)
      (is @closed? "pressing it must reach the handler the caller supplied")))
  (testing "and it renders even when nobody wired it, rather than crashing the
            renderer the way a nil :on-action does"
    (is (fn? (:on-action (last (:children (app/title-bar {}))))))))

(deftest the-title-bar-can-be-dragged
  (testing "an undecorated window has no OS grab handle, so without this the
            window cannot be moved at all -- it is not a nicety"
    (let [bar (app/title-bar {})]
      (is (fn? (:on-mouse-pressed bar)))
      (is (fn? (:on-mouse-dragged bar))))))

;; ---------------------------------------------------------------------------
;; the library controls, which belong in the title bar
;;
;; Both were built in the wrong place. The design puts the Installed/Owned
;; switch and the filter in the title bar, immediately right of the logo; the
;; first implementation had no switch at all and hung the filter over the card
;; grid with a "N of M titles" counter the design does not have.

(deftest the-installed-owned-switch-is-in-the-title-bar
  (let [s (pr-str (app/title-bar {:signed-in? true :tab :installed}))]
    (is (str/includes? s "Installed"))
    (is (str/includes? s "Owned"))))

(deftest the-filter-is-in-the-title-bar
  (let [s (pr-str (app/title-bar {:signed-in? true :tab :owned}))]
    (is (str/includes? s "Search library")
        "and its placeholder names the tab it is filtering")
    (is (str/includes? (pr-str (app/title-bar {:signed-in? true :tab :installed}))
                       "Search installed"))))

(deftest neither-control-shows-when-signed-out
  (testing "there is no library behind the login screen to filter"
    (let [s (pr-str (app/title-bar {:signed-in? false}))]
      (is (not (str/includes? s "Installed")))
      (is (not (str/includes? s "Search"))))))

(deftest the-controls-sit-left-of-the-status-line-not-right
  (testing "design order: logo, switch, filter, then everything else pushed
            right by the spacer"
    (let [kids (:children (app/title-bar {:signed-in? true :tab :installed
                                          :status-line "halgari"}))
          idx  (fn [pred] (first (keep-indexed #(when (pred %2) %1) kids)))
          spacer (idx #(= :region (:fx/type %)))
          ;; the switch and the filter are one group, as in the design, so this
          ;; looks for the child CONTAINING the filter rather than for a
          ;; text-field sitting directly in the bar
          filt   (idx #(str/includes? (pr-str %) "Search installed"))]
      (is (some? spacer))
      (is (some? filt))
      (is (< filt spacer) "the filter is before the spacer, so it sits on the left"))))

(deftest the-selected-tab-is-the-one-that-reads-as-selected
  (let [installed (pr-str (app/title-bar {:signed-in? true :tab :installed}))
        owned     (pr-str (app/title-bar {:signed-in? true :tab :owned}))]
    (is (not= installed owned) "the switch shows which side is active")))

(deftest both-tabs-are-clickable
  (let [hits (atom [])
        bar  (app/title-bar {:signed-in? true :tab :installed
                             :on-tab (fn [t] (swap! hits conj t))})
        btns (filter #(#{"Installed" "Owned"} (:text %))
                     (tree-seq coll? #(if (map? %) (vals %) (seq %)) bar))]
    (is (= 2 (count btns)))
    (doseq [b btns] ((:on-action b) nil))
    (is (= #{:installed :owned} (set @hits)))))

(deftest the-tab-switch-does-not-stretch-to-the-height-of-the-title-bar
  (testing "HBox fillHeight defaults to TRUE, so a child with an unbounded
            maxHeight is resized to the parent's content height regardless of
            :alignment. The switch's bordered box grew to 50px in a 52px bar
            with 26px pills floating in the middle of it -- a band of dead space
            above and below every tab.

            Measured on a real instance: the description map said 26 and the
            layout said 50."
    (let [h @(fx/on-fx-thread
              (let [bar  (fx/instance (fx/create-component
                                       (app/title-bar {:signed-in? true :tab :installed
                                                       :status-line "x"
                                                       :on-close (fn [_]) :on-sign-out (fn [_])
                                                       :on-tab (fn [_]) :on-query-change (fn [_])})))
                    root (javafx.scene.layout.StackPane.
                          (into-array javafx.scene.Node [bar]))]
                (javafx.scene.Scene. root 1100 120)
                (.applyCss root)
                (.layout root)
                ;; the group is the HBox that holds the two tab buttons
                (->> (tree-seq #(instance? javafx.scene.Parent %)
                               #(seq (.getChildrenUnmodifiable ^javafx.scene.Parent %))
                               bar)
                     (filter #(and (instance? javafx.scene.layout.HBox %)
                                   (= 2 (count (.getChildrenUnmodifiable ^javafx.scene.Parent %)))
                                   (every? (fn [c] (instance? javafx.scene.control.Button c))
                                           (.getChildrenUnmodifiable ^javafx.scene.Parent %))))
                     first
                     (#(.getHeight ^Region %)))))]
      (is (<= h 32.0)
          (str "the switch is " h "px tall; the design's is 26px of pill in 2px of padding")))))
