;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.

;; Forces grayscale text antialiasing instead of LCD-subpixel antialiasing,
;; for screenshots only -- see the "LCD text and screenshot review" section
;; of the namespace docstring below for why. This has to run before
;; `cljfx.api` loads: requiring it starts the JavaFX toolkit as a side
;; effect, which spins up the Prism rendering pipeline, which reads
;; `prism.lcdtext` once during its own init and never again. By the time any
;; form after the `ns` below runs, cljfx.api is already required and it is
;; too late to change this -- so it has to be a form ahead of `ns`, not
;; inside `render!`.
(System/setProperty "prism.lcdtext" "false")

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
   `javafx.application.Platform/exit` will hang forever.

   SIZE IS A HARD FRAME, NOT A SUGGESTION. `render!`'s `:width`/`:height`
   become the Scene's exact size; JavaFX lays the root out to precisely that
   box. Content that doesn't fit is CROPPED at the frame edge -- not scaled
   down, not letterboxed, not flagged in any way. A screenshot that looks
   complete may simply be missing whatever ran past the right or bottom
   edge. This was fine to overlook for a 200x100 swatch; it stops being fine
   once real screens render at 1100x720 and a layout bug can overflow that
   frame in silence. When in doubt, render taller/wider than the expected
   content and look for a suspiciously flush edge, or render at the exact
   design size and treat any content touching that edge as suspect.

   LCD TEXT AND SCREENSHOT REVIEW. On a real LCD monitor, JavaFX's default
   subpixel (LCD) text antialiasing is correct and looks right. In
   screenshots meant for a human or an agent to visually review, though, it
   produces reddish/bluish fringes on glyph edges that are easy to mistake
   for an actual colour bug -- especially on the light-text-on-dark-surface
   combinations this app's whole palette is built from. Measured on a
   rendered label: with JavaFX's default LCD antialiasing, 1670 pixels in a
   300x80 swatch had a >18-value spread between their R/G/B channels
   (chromatic fringing); with `prism.lcdtext=false` forcing grayscale
   antialiasing instead, that count was 0. Because this property only
   affects this screenshot harness -- the packaged app (`reliquary.cli`,
   under the `:cli` alias) never requires this namespace and so never sets
   it -- the shipped app keeps the LCD antialiasing that's actually correct
   for a real display; only PNGs produced by `shot/render!` render text in
   grayscale for easier review."
  (:require [clojure.java.io :as io]
            [cljfx.api :as fx]
            [reliquary.ui.theme :as theme])
  (:import (java.awt.image BufferedImage)
           (javafx.scene Scene)
           (javafx.scene.paint Color)
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

   `:width`/`:height` are a hard frame, not a suggestion: `desc` is laid out
   into exactly that box, and anything that doesn't fit is cropped at the
   edge, silently -- see the namespace docstring's 'SIZE IS A HARD FRAME'
   section before assuming a screenshot that looks complete actually is.

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
             ;; Attach the Gilt stylesheet, because the real app does.
             ;; render! is handed a bare root node -- callers pass
             ;; (get-in (app/view ...) [:scene :root]) -- so the :stylesheets
             ;; the app sets on its Scene never reach this one. Without this
             ;; line every screenshot silently renders scrollbars, focus rings
             ;; and disabled buttons in JavaFX's default Modena skin: pale grey
             ;; furniture that does not exist in the running application. A
             ;; screenshot that does not match the app is worse than no
             ;; screenshot, because it is the thing being reviewed.
             _         (.add (.getStylesheets scene) (theme/stylesheet))
             ;; Paint the Scene with the app's background, because the real
             ;; app does -- the Scene's default fill is WHITE. This is the
             ;; belt to the stylesheet's braces: reliquary.css gives `.root`
             ;; an opaque #0C0C0C, which is what actually fixed the inverted
             ;; palette (Modena paints the root node #F4F4F4, on top of any
             ;; scene fill). This line still matters for a root that IS
             ;; transparent, where the scene fill is what shows through.
             _         (.setFill scene (Color/web (theme/color :bg)))
             image     (WritableImage. (int width) (int height))]
         (.snapshot scene image)
         (ImageIO/write (fx-image->buffered-image image) "png" out)))
    out))
