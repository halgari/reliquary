;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.library-test
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.library :as library]))

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
