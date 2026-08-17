;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.anim-test
  "Every test here that touches a Node, a Timeline, or a Transition runs on
   the JavaFX Application Thread via `fx/on-fx-thread` -- those classes are
   thread-confined, `.play` on a background thread throws. Following
   `reliquary.ui.shot`'s pattern: the FX-thread block computes and returns
   plain values (statuses, booleans), and every `is` runs back on the test
   thread afterward -- clojure.test's pass/fail counters are bound via
   `binding` on the thread that calls the test fn, so an `is` evaluated
   deep inside `fx/on-fx-thread`'s body would silently not count against
   them.

   Requiring `reliquary.ui.shot` starts the JavaFX toolkit as a side effect
   (see that namespace's docstring); nothing here shows a Stage, so nothing
   here needs to call `Platform/exit` -- the cognitect test-runner calls
   `System/exit` once the suite finishes, per the same docstring."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.shot]) ; side effect: starts the JavaFX toolkit
  (:import (javafx.animation Animation Animation$Status)
           (javafx.scene.layout Region)))

(defn- new-node ^Region []
  (doto (Region.) (.setPrefWidth 20.0) (.setPrefHeight 20.0)))

(deftest animate-false-disables-every-constructor
  (testing "no Timeline/Transition is even built when *animate* is false"
    (let [results
          @(fx/on-fx-thread
             (binding [anim/*animate* false]
               (let [node (new-node)]
                 {:breathe  (anim/breathe! node)
                  :pulse    (anim/pulse! node)
                  :scan     (anim/scan! node)
                  :sheen    (anim/sheen! node)
                  :rise-in  (anim/rise-in! node)
                  :ring-in  (anim/ring-in! node)})))]
      (doseq [[k v] results]
        (testing (str k)
          (is (nil? v)))))))

(deftest animate-true-starts-a-real-running-animation-of-each-kind
  (let [statuses
        @(fx/on-fx-thread
           (let [node (new-node)]
             (into {}
               (map (fn [[k f]] [k (.getStatus ^Animation (f node))]))
               {:breathe #(anim/breathe! %)
                :pulse   #(anim/pulse! %)
                :scan    #(anim/scan! %)
                :sheen   #(anim/sheen! %)
                :rise-in #(anim/rise-in! %)
                :ring-in #(anim/ring-in! %)})))]
    (doseq [[k status] statuses]
      (testing (str k)
        (is (= Animation$Status/RUNNING status))))))

(deftest indefinite-animations-actually-loop
  (testing "the indefinite constructors are Animation/INDEFINITE, not a
            one-shot that happens to still be RUNNING at the moment we check"
    (let [cycle-counts
          @(fx/on-fx-thread
             (let [node (new-node)]
               {:breathe (.getCycleCount ^Animation (anim/breathe! node))
                :pulse   (.getCycleCount ^Animation (anim/pulse! node))
                :scan    (.getCycleCount ^Animation (anim/scan! node))
                :sheen   (.getCycleCount ^Animation (anim/sheen! node))}))]
      (doseq [[k n] cycle-counts]
        (testing (str k)
          (is (= Animation/INDEFINITE n)))))))

(deftest one-shot-animations-play-exactly-once
  (let [cycle-counts
        @(fx/on-fx-thread
           (let [node (new-node)]
             {:rise-in (.getCycleCount ^Animation (anim/rise-in! node))
              :ring-in (.getCycleCount ^Animation (anim/ring-in! node))}))]
    (doseq [[k n] cycle-counts]
      (testing (str k)
        (is (= 1 n))))))

(deftest with-anim-stops-the-animation-when-the-node-is-deleted
  (testing "the leak that matters: an animation attached via `with-anim`
            must not keep running once cljfx tears the node down"
    (let [captured (atom nil)
          [running-status stopped-status]
          @(fx/on-fx-thread
             (let [desc (anim/with-anim
                          {:fx/type :region :pref-width 10 :pref-height 10}
                          (fn [node] (let [a (anim/breathe! node)]
                                       (reset! captured a)
                                       a)))
                   component (fx/create-component desc)
                   running (.getStatus ^Animation @captured)]
               (fx/delete-component component)
               [running (.getStatus ^Animation @captured)]))]
      (is (some? @captured) "with-anim's start-fn must actually have run")
      (is (= Animation$Status/RUNNING running-status))
      (is (= Animation$Status/STOPPED stopped-status)))))

(deftest with-anim-is-a-noop-when-animate-is-false
  (testing "start-fn returning nil (because *animate* is false) must not
            make on-deleted blow up looking for something that was never
            stored"
    (let [outcome
          @(fx/on-fx-thread
             (binding [anim/*animate* false]
               (let [desc (anim/with-anim
                            {:fx/type :region :pref-width 10 :pref-height 10}
                            anim/breathe!)
                     component (fx/create-component desc)]
                 (fx/delete-component component)
                 :deleted-without-throwing)))]
      (is (= :deleted-without-throwing outcome)))))

(deftest with-anim-instantiates-a-real-node-via-create-component
  (testing "a real fx/create-component instantiation, not just a bare
            constructor call against a hand-built Node"
    (let [outcome
          @(fx/on-fx-thread
             (let [desc (anim/with-anim
                          {:fx/type :region :pref-width 10 :pref-height 10}
                          anim/pulse!)
                   component (fx/create-component desc)
                   node (fx/instance component)]
               {:class  (class node)
                :status (some-> (.get (.getProperties node) ::anim/running)
                          (as-> a (.getStatus ^Animation a)))}))]
      (is (= javafx.scene.layout.Region (:class outcome)))
      (is (= Animation$Status/RUNNING (:status outcome))))))
