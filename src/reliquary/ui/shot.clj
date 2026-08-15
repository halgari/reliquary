;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.shot
  "The screenshot harness: renders a cljfx description to a real PNG file.

   There is no browser and no pixel-diff tooling on this machine, so a PNG a
   human or an agent can open is the verification gate for every screen built
   after this one. If this harness ever produced a blank or transparent
   image, every screen that follows would 'pass' while rendering nothing --
   see `reliquary.ui.shot-test/renders-a-description-to-a-real-png`, whose
   final assertion checks an actual rendered pixel for exactly that reason.

   Requiring `cljfx.api` starts the JavaFX toolkit as a side effect (a
   `defonce`, guarded internally against a double `Platform/startup`, so it
   is safe however many times this namespace's functions get called within
   one JVM -- one call per screen in a test run is the expected shape) and
   sets implicit-exit to false. That second part matters: this harness never
   shows a Stage, so there is no 'last window closed' event to trigger an
   automatic shutdown, and explicitly disabling it means the JavaFX
   Application Thread just sits idle rather than trying anything surprising.
   That thread is non-daemon, so it does not, by itself, let the JVM exit --
   under `clojure -M:test` that's fine, because cognitect's test-runner calls
   `System/exit` once the suite finishes, which tears down the whole JVM
   (FX thread included) regardless of daemon status. A bare script that
   requires this namespace and does not itself call `System/exit` or
   `javafx.application.Platform/exit` will hang forever -- see
   `spike/reliquary/spike/fx_window.clj` for the same tradeoff made
   explicit."
  (:require [clojure.java.io :as io]
            [cljfx.api :as fx]
            [reliquary.ui.theme :as theme])
  (:import (java.awt.image BufferedImage)
           (javafx.scene Scene)
           (javafx.scene.image PixelFormat WritableImage)
           (javax.imageio ImageIO)))

(defn- fx-image->buffered-image
  "Converts a JavaFX WritableImage to a java.awt BufferedImage via one bulk
   pixel read. Written by hand rather than pulling in
   javafx.embed.swing.SwingFXUtils, which lives in the javafx-swing module
   this project does not otherwise depend on and which is not on the
   classpath here."
  ^BufferedImage [^WritableImage image]
  (let [w      (int (.getWidth image))
        h      (int (.getHeight image))
        pixels (int-array (* w h))]
    (.getPixels (.getPixelReader image) 0 0 w h
                (PixelFormat/getIntArgbInstance) pixels 0 w)
    (doto (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
      (.setRGB 0 0 w h pixels 0 w))))

(defn render!
  "Renders `desc`, a cljfx component description (a plain node description
   such as a `:v-box`, not wrapped in `:stage`/`:scene`), to a PNG at
   `out-file`. `opts` takes `:width` and `:height`, used both for the Scene
   and the resulting image. Returns `out-file` as a `java.io.File`.

   Loads the bundled fonts first, so text renders in Hanken Grotesk / DM
   Mono rather than silently falling back to whatever the OS happens to
   have. Building the component, taking the snapshot, and writing the file
   all happen on the JavaFX Application Thread via `fx/on-fx-thread`, which
   blocks this (calling) thread until the work is done and rethrows any
   exception raised there -- Scene construction and `.snapshot` are only
   legal on that thread."
  [desc out-file {:keys [width height]}]
  (let [out (io/file out-file)]
    @(fx/on-fx-thread
       (theme/load-fonts!)
       (let [component (fx/create-component desc)
             root      (fx/instance component)
             scene     (Scene. root (double width) (double height))
             image     (WritableImage. (int width) (int height))]
         (.snapshot scene image)
         (ImageIO/write (fx-image->buffered-image image) "png" out)))
    out))
