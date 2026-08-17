;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.library-test
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.library :as library]
            [reliquary.ui.theme :as theme])
  (:import (javafx.scene Node Parent Scene)
           (javafx.scene.image WritableImage)
           (javafx.scene.layout Region)))

(defn find-nodes
  "Walks a cljfx description tree depth-first and returns every map whose
   :fx/type is `type`. Descriptions nest through many different keys
   (:children, :content, :desc, ...) depending on the node, so this walks
   every value rather than assuming a particular shape."
  [desc type]
  (cond
    (and (map? desc) (= type (:fx/type desc)))
    (cons desc (mapcat #(find-nodes % type) (vals desc)))

    (map? desc) (mapcat #(find-nodes % type) (vals desc))
    (sequential? desc) (mapcat #(find-nodes % type) desc)
    :else nil))

(defn find-node [desc type] (first (find-nodes desc type)))

(defn game-cards
  "Every card's top-level :v-box description, in game order. A card is
   distinguished from the OTHER :v-box nodes nested inside it (the
   title/meta-row block) by carrying both a click handler and the card's
   own rounded-rectangle clip -- the inner block has neither. `find-nodes`
   recurses through every map value regardless of key name, so this finds
   cards whether or not they are wrapped in `anim/with-anim`'s
   `fx/ext-on-instance-lifecycle`."
  [desc]
  (filter #(and (:on-mouse-clicked %) (= :rectangle (get-in % [:clip :fx/type])))
          (find-nodes desc :v-box)))

(def games
  [{:appid 100 :title "Stardew Valley" :studio "ConcernedApe"
    :versions [{:id "public" :label "Latest" :branch "public"
                :build "12345" :date "2024-01-01" :bytes 500000000}]}
   {:appid 200 :title "Skyrim Special Edition" :studio "Bethesda Game Studios"
    :versions [{:id "public" :label "Latest — public" :branch "public"
                :build "13189953" :date "2024-01-17" :bytes 29742606498}
               {:id "1_5_97" :label "1.5.97" :branch "1_5_97"
                :build "" :date "2019-11-20" :bytes 0}]}])

;; ---------------------------------------------------------------------
;; Pure data
;; ---------------------------------------------------------------------

(deftest filtering-matches-title-and-studio-case-insensitively
  (testing "title match, any case"
    (is (= ["Stardew Valley"] (mapv :title (library/filter-games games "stardew"))))
    (is (= ["Stardew Valley"] (mapv :title (library/filter-games games "STARDEW")))))
  (testing "studio match, any case"
    (is (= ["Skyrim Special Edition"] (mapv :title (library/filter-games games "bethesda")))))
  (testing "no match"
    (is (empty? (library/filter-games games "no such game"))))
  (testing "blank/nil query matches everything"
    (is (= 2 (count (library/filter-games games ""))))
    (is (= 2 (count (library/filter-games games nil))))))

(deftest the-count-label-reads-filtered-of-total
  (let [desc (library/view {:games games :query "stardew"})
        label (find-node desc :label)]
    (is (str/includes? (pr-str desc) "1 of 2 titles"))))

(deftest unowned-games-render-muted-with-a-plain-reason-and-no-owned-games-block-nothing
  (testing "an explicit owned set mutes the games outside it"
    (let [s (pr-str (library/view {:games games :owned #{100}}))]
      (is (str/includes? s "Not owned"))))
  (testing "a nil :owned set treats everything as owned -- a missing session must not
            block the whole UI"
    (let [s (pr-str (library/view {:games games :owned nil}))]
      (is (not (str/includes? s "Not owned"))))))

(deftest unknown-size-and-build-never-render-as-zero
  (is (= "size unknown" (library/size-label 0)))
  (is (= "size unknown" (library/size-label nil)))
  (is (not (str/includes? (library/size-label 0) "0.0 GB")))
  (is (= "1.0 GB" (library/size-label (* 1024 1024 1024))))

  (is (= "build unknown" (library/build-label "")))
  (is (= "build unknown" (library/build-label nil)))
  (is (= "build 13189953" (library/build-label "13189953"))))

(defn- primary-button
  "The side panel's full-width primary button -- the one that is 44px tall.
   Found by height rather than by label, because the label is exactly what
   these tests are about and a filter on it would beg the question."
  [desc]
  (first (filter #(= 44 (:min-height %)) (find-nodes desc :button))))

(deftest the-download-button-says-what-it-will-actually-do
  (testing "no version selected -- 'Download' alongside a disabled style still
            reads as an offer, and the answer to 'download what?' is nothing"
    (let [btn (primary-button (library/view {:games games :selected-appid 100}))]
      (is (some? btn))
      (is (true? (:disable btn)))
      (is (= "Select a version" (:text btn)))))

  (testing "a selected version whose size the catalog knows names the size"
    (let [btn (primary-button (library/view {:games games :selected-appid 200
                                              :selected-version-id "public"}))]
      (is (some? btn))
      (is (not (:disable btn)))
      (is (= "Download 27.7 GB" (:text btn)))))

  (testing "a selected version whose size is genuinely unknown (bytes 0, which
            this catalog really contains) says just 'Download' -- never
            'Download size unknown', which is what pasting size-label's
            not-a-size answer into a sentence needing a size produced"
    (let [btn (primary-button (library/view {:games games :selected-appid 200
                                              :selected-version-id "1_5_97"}))]
      (is (some? btn))
      (is (not (:disable btn)))
      (is (= "Download" (:text btn)))
      (is (not (str/includes? (:text btn) "size unknown")))))

  (testing "the label function directly, since the button style keys off the
            same three cases"
    (is (= "Select a version" (library/download-button-label nil)))
    (is (= "Download 1.0 GB" (library/download-button-label {:bytes (* 1024 1024 1024)})))
    (is (= "Download" (library/download-button-label {:bytes 0})))
    (is (= "Download" (library/download-button-label {})))))

(deftest the-side-panel-only-appears-for-a-real-selection
  (testing "no selection -> no side panel, single grid child"
    (is (= 1 (count (:children (library/view {:games games}))))))
  (testing "a selected appid that exists -> side panel appears"
    (is (= 2 (count (:children (library/view {:games games :selected-appid 100}))))))
  (testing "a selected appid that does not exist (e.g. filtered out) -> no panel"
    (is (= 1 (count (:children (library/view {:games games :selected-appid 999})))))))

;; ---------------------------------------------------------------------
;; Real component instantiation -- pr-str only checks description SHAPE and
;; never builds a Node, which is exactly what missed a nil handler/bad prop
;; before, per this project's history (login_test.clj carries the same
;; comment). This builds the real component through the same lifecycle path
;; shot/render! uses, for every branch that matters: empty state, no
;; selection, a selection with an owned game, a selection with an unowned
;; game mixed into the grid, and a filtered grid.
;; ---------------------------------------------------------------------

(deftest the-view-actually-instantiates-real-javafx-nodes
  (doseq [state [{}
                 {:games games}
                 {:games games :selected-appid 100}
                 {:games games :selected-appid 100 :selected-version-id "public"}
                 {:games games :owned #{100} :selected-appid 200}
                 {:games games :query "sky"}]]
    (let [component @(fx/on-fx-thread (fx/create-component (library/view state)))]
      (is (some? (fx/instance component)) (str "failed to instantiate for " state)))))

;; --- unowned games are browsable, not blocked ------------------------------
;; Requested directly: "allow users to click on and view versions for games
;; they don't own, just disable the download button if it's not owned."

(deftest an-unowned-game-is-still-selectable
  (testing "the card carries a click handler regardless of ownership -- finding
            out which versions exist is useful before you decide to buy"
    (let [games [{:appid 1 :title "Owned" :versions [{:id "public" :label "Latest" :bytes 10 :depots [{}]}]}
                 {:appid 2 :title "Not owned" :versions [{:id "public" :label "Latest" :bytes 10 :depots [{}]}]}]
          s (pr-str (library/view {:games games :owned #{1}}))]
      (is (str/includes? s "Not owned"))
      ;; both cards must carry a handler; count them
      (is (= 2 (count (re-seq #":on-mouse-clicked" (pr-str (library/view {:games games :owned #{1}})))))
          "an unowned card without a click handler cannot open its version list"))))

(deftest the-side-panel-opens-for-an-unowned-game
  (let [games [{:appid 2 :title "Not owned"
                :versions [{:id "public" :label "Latest" :bytes 10 :depots [{}]}
                           {:id "old" :label "1.2.3" :bytes 20 :depots [{}]}]}]
        s (pr-str (library/view {:games games :owned #{} :selected-appid 2}))]
    (is (str/includes? s "1.2.3") "its versions must be visible")))

(deftest the-download-button-is-disabled-for-an-unowned-game
  (testing "ownership outranks version selection: offering 'Select a version'
            for a game you cannot download sends the user down a dead path"
    (let [games [{:appid 2 :title "Not owned"
                  :versions [{:id "public" :label "Latest" :bytes 10 :depots [{}]}]}]
          s (pr-str (library/view {:games games :owned #{} :selected-appid 2
                               :selected-version-id "public"}))]
      (is (str/includes? s "You don't own this game"))
      (is (not (str/includes? s "Download 0.0 GB")))))

  (testing "and it still works normally when owned"
    (let [games [{:appid 1 :title "Owned"
                  :versions [{:id "public" :label "Latest" :bytes 13628807699 :depots [{}]}]}]
          s (pr-str (library/view {:games games :owned #{1} :selected-appid 1
                               :selected-version-id "public"}))]
      (is (str/includes? s "Download 12.7 GB")))))

(deftest a-missing-owned-set-still-treats-everything-as-owned
  (testing "no Steam session must not make the whole library look unowned"
    (let [games [{:appid 1 :title "G" :versions [{:id "public" :label "Latest" :bytes 10 :depots [{}]}]}]
          s (pr-str (library/view {:games games :selected-appid 1 :selected-version-id "public"}))]
      (is (not (str/includes? s "You don't own this game"))))))

;; ---------------------------------------------------------------------
;; Visual pass (docs/design-delta-2026-08-17.md) -- ring/lift on the
;; selected card, the card art sheen, version row/dot glows, and the
;; gradient Download button.
;; ---------------------------------------------------------------------

(deftest the-selected-card-gets-a-ring-lift-and-glow-the-unselected-one-does-not
  (let [desc      (library/view {:games games :selected-appid 200})
        by-appid  (zipmap (map :appid games) (game-cards desc))
        selected  (get by-appid 200)
        unselected (get by-appid 100)]
    (is (some? selected))
    (is (some? unselected))
    (testing "selected: gold ring (border), lift, and a glow beneath"
      (is (str/includes? (:style selected) (str "-fx-border-color: " (:gold theme/color) ";")))
      (is (str/includes? (:style selected) "-fx-translate-y: -2;"))
      (is (str/includes? (:style selected) "-fx-effect: dropshadow")))
    (testing "unselected: plain line border, no lift, no glow"
      (is (str/includes? (:style unselected) (str "-fx-border-color: " (:line theme/color) ";")))
      (is (not (str/includes? (:style unselected) "-fx-translate-y")))
      (is (not (str/includes? (:style unselected) "-fx-effect"))))))

(deftest the-card-art-sheen-is-mouse-transparent-and-inside-the-arts-clip
  (let [desc   (library/view {:games games})
        sheens (filter #(str/includes? (or (:style %) "") "rgba(242, 240, 238, 0.07)")
                        (find-nodes desc :region))]
    (is (= (count games) (count sheens))
        "one sheen per card")
    (doseq [sheen sheens]
      (is (true? (:mouse-transparent sheen))
          "the sheen must never steal the card's click"))))

(deftest selected-version-row-and-dot-glow
  (let [desc (library/view {:games games :selected-appid 200 :selected-version-id "public"})
        s    (pr-str desc)]
    (is (str/includes? s "-fx-effect: dropshadow")
        "a selected version row and its dot both carry a glow")))

(deftest a-disabled-download-button-has-no-gradient-and-no-glow
  (testing "unowned -- the ownership case"
    (let [btn (primary-button (library/view {:games games :selected-appid 100
                                              :selected-version-id "public" :owned #{}}))]
      (is (true? (:disable btn)))
      (is (not (str/includes? (:style btn) "linear-gradient")))
      (is (not (str/includes? (:style btn) "-fx-effect")))))
  (testing "no version selected -- owned, but nothing chosen yet"
    (let [btn (primary-button (library/view {:games games :selected-appid 100 :owned #{100}}))]
      (is (true? (:disable btn)))
      (is (not (str/includes? (:style btn) "linear-gradient")))
      (is (not (str/includes? (:style btn) "-fx-effect"))))))

(deftest an-enabled-download-button-gets-the-gradient-and-glow
  (let [btn (primary-button (library/view {:games games :selected-appid 200
                                            :selected-version-id "public" :owned #{200}}))]
    (is (not (:disable btn)))
    (is (str/includes? (:style btn) "linear-gradient"))
    (is (str/includes? (:style btn) "-fx-effect: dropshadow"))))

(deftest binding-animate-false-starts-nothing
  (testing "a card's real Node keeps its default opacity when *animate* is
            false -- rise-in! would otherwise force it to 0.0 as the very
            first thing it does, before playing anything"
    (let [root      @(fx/on-fx-thread
                        (binding [anim/*animate* false]
                          (let [component (fx/create-component (library/view {:games games}))
                                root      (fx/instance component)]
                            ;; ScrollPane only realises its content into the
                            ;; real scene graph once a Skin exists, which
                            ;; needs a CSS/layout pass -- .snapshot forces
                            ;; one synchronously, same as shot/render! does,
                            ;; without needing a shown Stage.
                            (.snapshot (Scene. root) (WritableImage. 1 1))
                            root)))
          card      (letfn [(find-card [^Node node]
                               (if (and (instance? Region node)
                                        (== 168.0 (.getMinWidth ^Region node)))
                                 node
                                 (when (instance? Parent node)
                                   (some find-card (.getChildrenUnmodifiable ^Parent node)))))]
                      (find-card root))]
      (is (some? card) "a card-sized node must exist in the real scene graph")
      (is (= 1.0 (.getOpacity ^Node card))
          "rise-in! never ran, so nothing set opacity away from its default"))))
