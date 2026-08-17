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

;; ---------------------------------------------------------------------------
;; glow -- accent drop shadows (docs/design-delta-2026-08-17.md, "Glows")

(defn- clamp [lo hi v] (-> v (max lo) (min hi)))

(defn hex->rgb
  "\"#RRGGBB\" -> [r g b], each 0-255. Public (not just an internal of
   `glow`) because turning a Gilt hex token into an rgba() string is a
   generally useful thing for a caller composing its own translucent
   colours, not something worth reinventing per screen."
  [^String hex]
  (let [h (str/replace hex "#" "")]
    (mapv #(Integer/parseInt (subs h % (+ % 2)) 16) [0 2 4])))

(defn rgba
  "Builds an `rgba(r, g, b, a)` string -- valid JavaFX CSS color syntax --
   from a Gilt hex token and an alpha 0-1."
  [hex alpha]
  (let [[r g b] (hex->rgb hex)]
    (format "rgba(%d, %d, %d, %s)" r g b (str (double alpha)))))

(defn glow
  "Builds the VALUE for `-fx-effect` -- e.g. for
   `(style {:-fx-effect (glow (:gold color) {:blur 22 :spread -10 :dy 6
   :alpha 0.9})})` -- from a Gilt hex colour and a CSS `box-shadow`-shaped
   geometry map: `{:blur :spread :dx :dy :alpha}` (all default 0, alpha
   defaults 1.0).

   APPROXIMATION -- READ BEFORE 'FIXING' THE NUMBERS. CSS `box-shadow`
   (`dx dy blur spread color`) has a spread parameter that grows or SHRINKS
   the shadow's own shape independent of its blur; every glow in the design
   delta uses a NEGATIVE spread (e.g. `0 6px 22px -10px rgba(...,.9)`) to
   keep a soft blur from ballooning past the element it lights. JavaFX's
   `dropshadow` has no shape-shrinking parameter -- its own `spread`
   argument (0-1) instead blends between a soft blur and a hard-edged solid
   copy, a different axis entirely that would make the glow look pasted-on
   rather than tucked-in. So this function never emits that JavaFX spread
   argument (always 0) and instead approximates a negative CSS spread by
   DIMMING the colour: the more negative the spread relative to the blur,
   the lower the alpha, floored at 0.15 so nothing fully disappears.
   `:blur` maps straight to the JavaFX radius; `:dx`/`:dy` pass straight
   through. This will not pixel-match the CSS reference -- it is the
   closest a dropshadow can get to it, and a future reviewer comparing
   against the mockup should expect a softer, dimmer glow here, not a bug."
  [hex {:keys [blur spread dx dy alpha] :or {blur 12 spread 0 dx 0 dy 0 alpha 1.0}}]
  (let [fold      (if (neg? spread) (/ (double (- spread)) (double (max blur 1))) 0.0)
        eff-alpha (clamp 0.15 1.0 (* (double alpha) (- 1.0 fold)))
        rounded   (/ (Math/round (* eff-alpha 100.0)) 100.0)]
    (format "dropshadow(gaussian, %s, %s, %s, %s, %s)"
            (rgba hex rounded) blur 0 dx dy)))

;; ---------------------------------------------------------------------------
;; gradient -- linear-gradient / radial-gradient values
;; (docs/design-delta-2026-08-17.md, "Gradients")

(defn- angle->points
  "CSS gradient-angle (0deg = up, clockwise) -> [[x1 y1] [x2 y2]], the
   gradient line's endpoints as fractions 0-1 of a unit box. Uses the same
   box-corner-fitting the CSS spec itself uses: the line's length in a unit
   box is |sin θ| + |cos θ|, centred on the box, so it always reaches
   whichever pair of corners is farthest apart along that direction."
  [deg]
  (let [rad (Math/toRadians (double (mod deg 360)))
        dx  (Math/sin rad)
        dy  (- (Math/cos rad))
        len (+ (Math/abs dx) (Math/abs dy))
        hx  (* (/ len 2.0) dx)
        hy  (* (/ len 2.0) dy)]
    [[(- 0.5 hx) (- 0.5 hy)] [(+ 0.5 hx) (+ 0.5 hy)]]))

(defn- fmt-pct [frac] (format "%.1f%%" (* frac 100.0)))

(def ^:private side-or-corner
  "JavaFX linear-gradient's own direction keywords -- the cases that need no
   angle conversion at all."
  {:to-top "to top" :to-bottom "to bottom" :to-left "to left" :to-right "to right"
   :to-top-left "to top left" :to-top-right "to top right"
   :to-bottom-left "to bottom left" :to-bottom-right "to bottom right"})

(defn- direction->str [dir]
  (cond
    (contains? side-or-corner dir) (side-or-corner dir)
    (number? dir) (let [[[x1 y1] [x2 y2]] (angle->points dir)]
                    (str "from " (fmt-pct x1) " " (fmt-pct y1)
                         " to " (fmt-pct x2) " " (fmt-pct y2)))
    :else (throw (ex-info "unknown gradient direction" {:direction dir}))))

(defn- stop->str [stop]
  (if (vector? stop)
    (str (first stop) " " (second stop) "%")
    (str stop)))

(defn linear-gradient
  "The `linear-gradient(...)` VALUE string for `-fx-background-color`, e.g.
   `(style {:-fx-background-color (linear-gradient :to-bottom
   [\"#D3BA82\" \"#C2A35F\"])})`.

   `direction` is one of JavaFX's own side-or-corner keywords -- :to-top
   :to-bottom :to-left :to-right :to-top-left :to-top-right :to-bottom-left
   :to-bottom-right -- or a CSS-style angle in degrees (0 = up, clockwise)
   for anything JavaFX has no keyword for. JavaFX's linear-gradient syntax
   has no raw-angle form, only `to <side-or-corner>` or `from <point> to
   <point>`, so a numeric angle is converted to an explicit point pair with
   the box-corner-fitting formula CSS itself uses (`angle->points`) --
   NOT a literal transliteration of the degree number, which JavaFX would
   reject.

   `stops` is a seq of colours (evenly spaced) or `[colour percent]` pairs,
   e.g. `[\"#a8874a\" [\"#C2A35F\" 55] \"#D3BA82\"]`."
  [direction stops]
  (str "linear-gradient(" (direction->str direction) ", "
       (str/join ", " (map stop->str stops)) ")"))

(defn radial-gradient
  "The `radial-gradient(...)` VALUE string for `-fx-background-color`.

   `opts` -- `{:center-x :center-y :radius}`, each a percent number
   (default 50/50/50) -- matches JavaFX's own
   `radial-gradient(center X% Y%, radius R%, ...)` syntax, which (unlike
   CSS's `radial-gradient(circle, ...)`) always names its centre and radius
   explicitly rather than inferring them from the shape keyword.

   `stops` -- same shape as `linear-gradient`'s."
  ([stops] (radial-gradient {} stops))
  ([{:keys [center-x center-y radius] :or {center-x 50 center-y 50 radius 50}} stops]
   (str "radial-gradient(center " center-x "% " center-y "%, radius " radius "%, "
        (str/join ", " (map stop->str stops)) ")")))

(def gradients
  "The six gradients from the 2026-08-17 design delta, precomposed so a
   consuming screen doesn't recompute the direction/stop math itself -- see
   docs/design-delta-2026-08-17.md's Gradients table for the CSS this was
   translated from."
  {:button        (linear-gradient :to-bottom ["#D3BA82" "#C2A35F"])
   :progress-bar  (linear-gradient :to-right ["#a8874a" ["#C2A35F" 55] "#D3BA82"])
   :progress-done (linear-gradient :to-right ["#6b5b7d" "#7D6B91"])
   :title-bar     (linear-gradient :to-bottom ["#1a1a1a" "#161616"])
   :card-sheen    (linear-gradient 160 ["rgba(242, 240, 238, 0.07)" ["transparent" 42]])
   :logo-halo     (radial-gradient {:radius 50} ["rgba(194, 163, 95, 0.3)" ["transparent" 68]])})

;; ---------------------------------------------------------------------------
;; tabular numerals -- docs/design-delta-2026-08-17.md, "Other deltas"

(def tabular-nums
  "The `-fx-font-features` VALUE that fixes digit advance widths, JavaFX's
   analogue of CSS's `font-variant-numeric: tabular-nums` -- use as
   `(style {:-fx-font-features tabular-nums ...})`.

   Note for the next person auditing this: as of the JavaFX 26 this project
   bundles, `-fx-font-features` is not an actual recognised CSS property on
   Text/Label (there is no OpenType feature-settings hook in this JavaFX
   version's CssMetaData) -- the JavaFX CSS engine will log-and-ignore it
   rather than apply or error on it. It is included anyway because (a) it
   costs nothing and documents the design intent for a future JavaFX
   version that does support it, and (b) most of the digits this matters
   for -- the download screen's percent and ETA -- are ALREADY set in
   `mono-font` (DM Mono), a monospace face where every glyph including
   every digit has identical advance width, so tabular numerals are already
   true there by construction. This token exists for the case that isn't:
   a numeral rendered in the proportional `ui-font`."
  "\"tnum\"")
