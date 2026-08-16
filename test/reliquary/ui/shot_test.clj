;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.shot-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.shot :as shot]))

(deftest renders-a-description-to-a-real-png
  (let [out (io/file (System/getProperty "java.io.tmpdir") "reliquary-shot-test.png")]
    (.delete out)
    (shot/render! {:fx/type :v-box
                   :style "-fx-background-color: #C2A35F;"
                   :children [{:fx/type :label :text "hello"}]}
                  out {:width 200 :height 100})
    (is (.isFile out))
    (is (pos? (.length out)))
    (let [img (javax.imageio.ImageIO/read out)]
      (is (= 200 (.getWidth img)))
      (is (= 100 (.getHeight img)))
      (testing "the fill actually rendered -- a blank PNG is the classic false pass"
        (let [argb (.getRGB img 100 50)
              hex  (format "#%06X" (bit-and argb 0xFFFFFF))]
          (is (= "#C2A35F" hex)))))))
