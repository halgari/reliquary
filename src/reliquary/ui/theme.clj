;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.theme
  "The Gilt design tokens: colours, bundled fonts, and a tiny `-fx-` style
   composer. Every screen in the shell draws from this palette instead of
   inlining hex values, so a brand change is a one-file change.

   Fonts are bundled under resources/fonts and loaded from the classpath with
   `Font/loadFont` -- never fetched at runtime. The app must render correctly
   with no network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (javafx.scene.text Font)))

(def color
  "The Gilt palette. Hex values are the brand spec -- do not adjust them to
   make a test pass; the test is right and the code is wrong."
  {:bg          "#0C0C0C"
   :surface     "#161616"
   :line        "#292929"
   :line-strong "#383838"
   :text        "#F2F0EE"
   :text-muted  "#9A9A9A"
   :gold        "#C2A35F"
   :amethyst    "#7D6B91"})

(defn- load-font-resource!
  "Loads a single font file from the classpath at `size` points, returning
   the java.awt/javafx Font, or nil if the resource is missing."
  [resource-path size]
  (when-let [stream (io/resource resource-path)]
    (with-open [in (io/input-stream stream)]
      (Font/loadFont in (double size)))))

(defonce ^:private fonts
  (delay
    ;; Hanken Grotesk ships from Google Fonts only as a variable font, and
    ;; JavaFX's Font/loadFont exposes a variable TTF's default instance only
    ;; -- requesting other weights from it silently renders the same weight
    ;; (verified: a 400/600/700 swatch came out pixel-identical). So the
    ;; three weights below are static instances pinned out of that variable
    ;; font with `fonttools varLib.instancer --update-name-table`, which is
    ;; a permitted OFL modification. SemiBold's legacy name-table slot can't
    ;; hold a non-Regular/Bold style, so it registers under the family
    ;; "Hanken Grotesk SemiBold" rather than "Hanken Grotesk"; Regular and
    ;; Bold both register as styles of plain "Hanken Grotesk".
    (let [ui-regular   (load-font-resource! "fonts/HankenGrotesk-Regular.ttf" 12)
          ui-semibold  (load-font-resource! "fonts/HankenGrotesk-SemiBold.ttf" 12)
          ui-bold      (load-font-resource! "fonts/HankenGrotesk-Bold.ttf" 12)
          mono         (load-font-resource! "fonts/DMMono-Regular.ttf" 12)
          mono-medium  (load-font-resource! "fonts/DMMono-Medium.ttf" 12)]
      {:ui-font          (some-> ui-regular .getFamily)
       :ui-semibold-font (some-> ui-semibold .getFamily)
       :ui-bold-font     (some-> ui-bold .getFamily)
       :mono-font        (some-> mono .getFamily)
       :mono-medium-font (some-> mono-medium .getFamily)
       :loaded           [ui-regular ui-semibold ui-bold mono mono-medium]})))

(defn load-fonts!
  "Loads the bundled fonts into the JavaFX font registry, once. Safe to call
   repeatedly -- the underlying `delay` only does the work on the first call."
  []
  @fonts
  nil)

(defn ui-font
  "The loaded Hanken Grotesk family name, or nil if fonts have not been
   loaded yet (call `load-fonts!` first)."
  []
  (:ui-font @fonts))

(defn mono-font
  "The loaded DM Mono family name, or nil if fonts have not been loaded yet
   (call `load-fonts!` first)."
  []
  (:mono-font @fonts))

(defn ui-semibold-font
  "The loaded Hanken Grotesk SemiBold family name. Distinct from `ui-font`
   because OpenType's legacy name-table slots only hold Regular/Bold/Italic
   styles -- SemiBold registers as its own family rather than as a style of
   `ui-font`."
  []
  (:ui-semibold-font @fonts))

(defn ui-bold-font
  "The loaded Hanken Grotesk Bold family name (registers as the Bold style
   of `ui-font`'s family; provided separately in case a caller wants to
   select it by family name rather than by -fx-font-weight)."
  []
  (:ui-bold-font @fonts))

(defn mono-medium-font
  "The loaded DM Mono Medium family name."
  []
  (:mono-medium-font @fonts))

(defn- fx-prop->str
  "Turns :-fx-background-color into \"-fx-background-color\"."
  [k]
  (name k))

(defn style
  "Composes a JavaFX inline style string from a map of `:-fx-*` keys to
   values, e.g. {:-fx-background-color \"#161616\" :-fx-background-radius 6}
   -> \"-fx-background-color: #161616; -fx-background-radius: 6;\"."
  [decls]
  (str/join " "
    (for [[k v] decls]
      (str (fx-prop->str k) ": " v ";"))))

(defn stylesheet
  "The Gilt stylesheet's URL, for a Scene's :stylesheets.

   Inline `:style` strings reach one node. A ScrollBar is assembled from
   sub-nodes the view never names -- .thumb, .track, the stepper buttons -- so
   without this the default Modena skin shows through: pale grey scrollbars on
   a near-black window. Anything that must style a control's internals belongs
   in resources/reliquary.css, not here."
  []
  (str (io/resource "reliquary.css")))
