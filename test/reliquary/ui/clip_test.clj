;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.clip-test
  "Rounded corners and selection glows, measured off real rendered pixels.

   Every bug in here rendered a perfectly valid description, so `pr-str`
   assertions could never have caught any of them: a clip is a Node property
   whose EFFECT only exists once JavaFX has painted, and a `-fx-effect` that a
   sibling clip masks away is still present in the style string. The only
   witness is the pixels, so these tests render and then measure them.

   The two rules being defended, both of which this codebase learned the hard
   way and wrote down before drifting from:

   1. A child inset by a border and clipped at the border's OUTER radius still
      pokes through along the corner arc -- see reliquary.ui.library's
      `art-radius` comment for the geometry. The inner radius is the outer
      radius less the border width.
   2. A clip and an `-fx-effect` on the SAME node compose so the clip masks the
      effect's own bleed -- see `reliquary.ui.login/qr-panel` (glow one layer
      out, unclipped) and `reliquary.ui.download/stage-panel` (shadow on
      `outer`, clip on `inner`). A glow clipped to its own node's box is a glow
      nobody can see."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.download :as download]
            [reliquary.ui.library :as library]
            [reliquary.ui.shot :as shot])
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (javafx.scene.image Image)
           (javax.imageio ImageIO)))

;; ---------------------------------------------------------------------------
;; rendering helpers

(defn- tmp [name] (io/file (System/getProperty "java.io.tmpdir") (str "reliquary-clip-" name)))

(defn- render!
  "Render `desc` and hand back the BufferedImage.

   Animations OFF, and not as a nicety: `anim/rise-in!` starts a card at opacity
   0, so an un-suppressed snapshot catches the grid mid-fade and measures an
   empty screen while reporting nothing wrong."
  ^BufferedImage [desc w h name]
  (let [out (tmp name)]
    (binding [anim/*animate* false]
      (shot/render! desc out {:width w :height h}))
    (ImageIO/read out)))

(defn- solid-image
  "A garishly bright `Image`, written to a temp PNG so it has a URL (the stage
   panel paints through `-fx-background-image`, which needs one).

   Bright on purpose: real capsule art and real screenshots are dark at the
   edges, which is precisely why corner overflow hides in a live screenshot of
   the running app. A saturated fill against the near-black surface makes one
   stray pixel unmistakable."
  ^Image [name ^Color color]
  (let [f   (tmp name)
        img (BufferedImage. 600 400 BufferedImage/TYPE_INT_RGB)
        g   (.createGraphics img)]
    (.setColor g color)
    (.fillRect g 0 0 600 400)
    (.dispose g)
    (ImageIO/write img "png" f)
    (Image. (str (.toURI f)))))

;; ---------------------------------------------------------------------------
;; pixel helpers

(defn- chan [^BufferedImage img x y]
  (let [p (.getRGB img x y)]
    [(bit-and (bit-shift-right p 16) 0xff)
     (bit-and (bit-shift-right p 8) 0xff)
     (bit-and p 0xff)]))

(defn- fill-at? [img pred x y] (apply pred (chan img x y)))

(defn- bbox
  "The bounding box of every pixel matching `pred`."
  [^BufferedImage img pred]
  (let [hits (for [y (range (.getHeight img))
                   x (range (.getWidth img))
                   :when (fill-at? img pred x y)]
               [x y])]
    (when (seq hits)
      {:x (apply min (map first hits)) :y (apply min (map second hits))})))

(defn- corner-profile
  "For each of the first `n` rows of the fill, how far in from the fill's own
   left edge it starts. That sequence IS the rendered corner arc, and it is the
   thing a clip either gets right or does not."
  [^BufferedImage img pred n]
  (let [{:keys [x y]} (bbox img pred)]
    (vec (for [dy (range n)]
           (let [row (first (for [dx (range 0 40)
                                  :when (fill-at? img pred (+ x dx) (+ y dy))]
                              dx))]
             (or row 99))))))

(defn- max-goldness
  "The strongest gold signal in a band. The glow is #C2A35F on near-black, so
   red-minus-blue separates it from the background without tripping on the
   grey furniture."
  [^BufferedImage img x0 x1 y0 y1]
  (apply max (for [x (range x0 x1) y (range y0 y1)
                   :let [[r _ b] (chan img x y)]]
               (- r b))))

;; ---------------------------------------------------------------------------
;; 1. the install page's artwork must respect the panel's INNER radius

(def ^:private green (Color. 0 255 90))
(defn- green? [r g b] (and (< r 120) (> g 200) (< b 150)))

(defn- install-page []
  (let [shot (solid-image "shot.png" green)
        game {:appid 1 :title "A Game" :studio "S"
              :art {:capsule nil :screenshots ["a" "b"]}
              :quotes [{:text "q" :attrib "a"}]
              :versions [{:id "public" :label "Latest" :date "2026-01-01" :bytes 1}]}]
    (download/view {:game game :version (first (:versions game))
                    :shot-index 0 :quote-index 0
                    :screenshot-fn (fn [_ _] shot)
                    :snapshot {:stage :downloading :bytes-done 1 :bytes-total 2
                               :chunks-done 1 :chunks-total 2 :wire-bytes 1
                               :bytes-per-sec 1.0 :wire-bytes-per-sec 1.0
                               :samples [1.0] :error nil}})))

(deftest the-install-pages-artwork-does-not-overrun-the-panel-corner
  (testing "The panel is a 6px-radius rounded rect with a 1px border, and the
            artwork is a child inset by that border. Clipping it at the panel's
            OUTER radius is not enough: along the arc the curve sweeps inward
            further than the straight edge does, so a square-cornered child
            inset by only the border width still pokes through and paints over
            the hairline at each corner. The artwork must follow the border's
            INNER radius (6 - 1 = 5).

            Measured as the arc profile -- how far in from its own left edge the
            fill starts, row by row. Under-clipping shows up as the profile
            collapsing to 0 too early."
    (let [img  (render! (install-page) 900 640 "install.png")
          prof (corner-profile img green? 4)]
      (is (some? (bbox img green?)) "the artwork must actually have rendered")
      ;; a 5px arc, measured from the fill's own edge, starts ~3 in and takes
      ;; three rows to reach the edge. The bug rendered [2 1 0 0].
      (is (>= (first prof) 3)
          (str "top row of the artwork starts only " (first prof)
               "px in -- it is overrunning the corner; profile " prof))
      (is (>= (second prof) 2)
          (str "second row too shallow; profile " prof))
      (is (pos? (nth prof 2))
          (str "third row already flush with the edge; profile " prof)))))

;; ---------------------------------------------------------------------------
;; 2. a selected card's glow must actually be visible

(def ^:private magenta (Color. 255 0 200))
(defn- magenta? [r g b] (and (> r 200) (< g 120) (> b 150)))

(defn- library-page [selected-appid]
  (let [caps (solid-image "capsule.png" magenta)
        game (fn [id] {:appid id :title (str "Game " id) :studio "S"
                       :art {:capsule nil :screenshots []}
                       :quotes []
                       :versions [{:id "public" :label "Latest"
                                   :date "2026-01-01" :bytes 1}]})
        games (mapv game [1 2 3])]
    (library/view {:games games :query "" :owned #{1 2 3}
                   :selected-appid selected-appid
                   :folder "/tmp/x"
                   :capsule-fn (constantly caps)})))

(deftest a-selected-cards-glow-reaches-outside-the-card
  (testing "`card` carried its :clip and its selected-state :-fx-effect on the
            SAME node, and a clip masks that node's own effect -- so the gold
            glow was cropped to the card's rounded box and never appeared at
            all. Both ui/login (glow one layer out) and ui/download (shadow on
            `outer`) already put the effect outside the clip; this one drifted.

            The glow is dy 10 / blur 34, so it blooms below the card. Comparing
            the band under a SELECTED card against the same band under an
            unselected one is what makes this a test of the glow rather than of
            the background: with the bug both read identically."
    (let [sel   (render! (library-page 1) 700 620 "lib-sel.png")
          plain (render! (library-page 99) 700 620 "lib-plain.png")
          ;; the first card sits at the grid's 24px padding; its art is magenta,
          ;; so the card's own extent is found rather than assumed
          {:keys [x y]} (bbox sel magenta?)]
      (is (some? x) "the capsule art must have rendered")
      (let [;; a band below the card: art top + art height + the title block
            band-y0 (+ y 252 75)
            band-y1 (+ band-y0 14)
            under-selected (max-goldness sel   x (+ x 160) band-y0 band-y1)
            under-plain    (max-goldness plain x (+ x 160) band-y0 band-y1)]
        (is (> under-selected (+ under-plain 6))
            (str "no gold bloom under the selected card (selected "
                 under-selected " vs unselected " under-plain
                 ") -- the glow is being clipped away"))))))

;; ---------------------------------------------------------------------------
;; 3. a selected version row's glow needs somewhere to go

(deftest a-selected-version-rows-glow-is-not-shaved-off-sideways
  (testing "The version list is a scroll-pane with :fit-to-width true, so each
            row is sized to exactly the viewport and the viewport then clips
            it. The row's glow had room above and below (the 8px list spacing)
            and none at all to the sides, so it was shaved flat against both
            edges. Measured left of the row: gold present means the glow has
            room; zero means it is being cut."
    (let [games [{:appid 1 :title "Game" :studio "S"
                  :art {:capsule nil :screenshots []}
                  :quotes []
                  :versions [{:id "a" :label "1.0" :date "2026-01-01" :bytes 1}
                             {:id "b" :label "2.0" :date "2026-02-01" :bytes 1}
                             {:id "c" :label "3.0" :date "2026-03-01" :bytes 1}]}]
          img (render! (library/view {:games games :query "" :owned #{1}
                                      :selected-appid 1 :selected-version-id "b"
                                      :folder "/tmp/x"
                                      :capsule-fn (constantly nil)})
                       900 620 "lib-vrow.png")
          w   (.getWidth img)
          ;; the selected row is the only gold-bordered thing in the panel
          gold-rows (for [y (range 100 (- (.getHeight img) 100))
                          :when (> (max-goldness img (- w 360) (- w 20) y (inc y)) 30)]
                      y)]
      (is (seq gold-rows) "a selected row must render its gold hairline")
      (let [y0 (first gold-rows)
            y1 (last gold-rows)
            ;; the row's own left edge, found by scanning in from the panel
            row-x (first (for [x (range (- w 380) w)
                               :when (> (max-goldness img x (inc x) y0 y1) 30)]
                           x))]
        (is (some? row-x))
        (is (> (max-goldness img (- row-x 5) (- row-x 1) y0 y1) 4)
            "no gold to the left of the selected row -- its glow is shaved off
             flat against the scroll viewport")))))

;; ---------------------------------------------------------------------------
;; the hashing bar actually spans its track
;;
;; A progress bar whose fill is sized from a guessed pixel width is a bar that
;; never reaches the end. The first version used a hardcoded 236px basis inside a
;; track that is ~324px wide, so 100% rendered as about three-quarters full --
;; visible in a screenshot, invisible to any assertion over the description map,
;; because the number in it looked perfectly reasonable.

(defn- hashing-panel-at [frac]
  (let [g {:appid 1 :title "G" :studio "S" :art {:capsule nil :screenshots []} :quotes []
           :versions [{:id "public" :label "Latest" :branch "public"
                       :date "2026-01-01" :bytes 1 :depots [{}]}]}]
    (library/view {:games [g] :selected-appid 1
                   ;; the switch footer, and so the hashing bar, lives on the
                   ;; Installed tab
                   :tab :installed
                   :install {:appid 1 :path "/x" :bytes 1000}
                   :installed-version {:id "public" :label "Latest"}
                   :hashing {:done (long (* 1000 frac)) :total 1000}})))

(defn- gold-run
  "The widest horizontal run of gold pixels INSIDE THE SIDE PANEL -- the bar's
   fill.

   Restricted to x >= 820 deliberately: the selected card in the grid carries a
   gold border, which is a ~166px horizontal run and wider than a half-full bar.
   Measuring the whole image reported that border instead and made a broken bar
   look like a working one at 50%."
  [^BufferedImage img]
  (apply max 0
         (for [y (range (.getHeight img))]
           (let [row (for [x (range 820 (.getWidth img))
                           :let [[r _ b] (chan img x y)]]
                       (> (- r b) 40))]
             (->> row (partition-by identity) (filter first) (map count) (apply max 0))))))

(deftest the-hashing-bar-fills-its-track-at-100-percent
  (let [full (gold-run (render! (hashing-panel-at 1.0) 1200 900 "hash-full.png"))
        half (gold-run (render! (hashing-panel-at 0.5) 1200 900 "hash-half.png"))]
    (is (pos? full) "the bar must render at all")
    ;; the track sits inside a 400px panel: 24px panel padding and 14px box
    ;; padding a side leaves ~324
    (is (> full 300)
        (str "at 100% the fill should span the whole track (~324px), got " full))
    (is (< (Math/abs (- half (/ full 2.0))) 12)
        (str "and 50% should be half of it -- got " half " against " (/ full 2.0)))))
