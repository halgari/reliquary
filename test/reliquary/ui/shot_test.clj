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

(deftest a-description-with-no-background-renders-on-the-app-background
  ;; A JavaFX Scene's default fill is WHITE. Screens whose root paints no
  ;; background of its own -- login/view is a bare :h-box, because in the
  ;; running app it sits inside app/view's :bg-filled root -- would render
  ;; as dark controls floating on cream: the whole palette inverted. That
  ;; happened, was screenshotted, was reviewed, and was reported as correct.
  (let [out (io/file (System/getProperty "java.io.tmpdir")
                     "reliquary-shot-bg-test.png")]
    (.delete out)
    (shot/render! {:fx/type :h-box :children [{:fx/type :label :text "unpainted"}]}
                  out {:width 60 :height 40})
    (let [img (javax.imageio.ImageIO/read out)
          ;; a corner, away from the label's glyphs
          argb (.getRGB img 2 2)
          [r g b] [(bit-and (bit-shift-right argb 16) 0xFF)
                   (bit-and (bit-shift-right argb 8) 0xFF)
                   (bit-and argb 0xFF)]]
      (is (< r 40) (str "expected the Gilt #0C0C0C background, got " [r g b]))
      (is (< g 40))
      (is (< b 40)))))
