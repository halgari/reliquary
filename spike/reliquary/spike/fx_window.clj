(ns reliquary.spike.fx-window
  "A cljfx window that exists only to be compiled to a native binary.

   :gen-class is load-bearing -- bin/native.sh hands native-image a MAIN CLASS,
   and without it `compile` emits no such class and the build fails late with a
   confusing 'main entry point not found'."
  (:gen-class)
  (:require [cljfx.api :as fx])
  (:import (javafx.application Platform)
           (javafx.scene.canvas Canvas)
           (javafx.scene.paint Color)))

(defn- draw! [^Canvas canvas]
  (let [g (.getGraphicsContext2D canvas)]
    (.setFill g (Color/web "#0C0C0C"))
    (.fillRect g 0 0 120 120)
    (.setFill g (Color/web "#C2A35F"))
    (doseq [x (range 0 120 20) y (range 0 120 20)
            :when (even? (+ (quot x 20) (quot y 20)))]
      (.fillRect g x y 20 20))))

(defn view [{:keys [ticks]}]
  {:fx/type :stage
   :showing true
   :title   "Reliquary spike"
   :width   420 :height 320
   :scene {:fx/type :scene
           :fill    (Color/web "#0C0C0C")
           :root {:fx/type  :v-box
                  :spacing  16
                  :padding  24
                  :children [{:fx/type :label
                              :text    "RELIQUARY"
                              :style   {:-fx-text-fill "#F2F0EE"
                                        :-fx-font-size 18}}
                             ;; ext-on-instance-lifecycle is how cljfx hands
                             ;; you the real Node. :canvas has no :on-created
                             ;; prop -- drawing needs the instance itself.
                             {:fx/type    fx/ext-on-instance-lifecycle
                              :on-created draw!
                              :desc       {:fx/type :canvas
                                           :width 120 :height 120}}
                             {:fx/type :label
                              :text    (str "ticks " ticks)
                              :style   {:-fx-text-fill "#9A9A9A"}}]}}})

(defn -main [& _]
  (let [state    (atom {:ticks 0})
        renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
    (fx/mount-renderer state renderer)
    ;; prove Platform/runLater works from a plain thread, which every
    ;; background download update will depend on
    (.start (Thread. (fn []
                       (dotimes [i 5]
                         (Thread/sleep 400)
                         (Platform/runLater #(swap! state assoc :ticks (inc i))))
                       (Thread/sleep 600)
                       (println "SPIKE-OK ticks=5")
                       (Platform/exit)
                       (System/exit 0))))
    renderer))
