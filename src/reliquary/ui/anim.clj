;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.anim
  "Animation constructors for the Gilt visual pass -- the JavaFX
   `Timeline`/`FadeTransition`/`TranslateTransition`/etc. translations of
   the CSS keyframes in docs/design-delta-2026-08-17.md's 'Keyframes' table.

   Every constructor here has the same shape: `(f node)` or `(f node opts)`,
   returns the started `Animation` it built, and is one of two kinds --

   - INDEFINITE (`breathe!` `pulse!` `scan!` `sheen!`): loops forever until
     something calls `.stop` on it. See `with-anim` below -- attaching one
     of these to a node without going through `with-anim` (or reinventing
     its stop-on-delete by hand) is a leak: cljfx recreates node instances
     on state change, and an Animation left running targets a Node that is
     no longer on screen and never will be again, for as long as the app
     stays open.
   - ONE-SHOT (`rise-in!` `ring-in!`): plays once and stops itself.

   Every constructor also honours `*animate*` -- see below."
  (:require [cljfx.api :as fx])
  (:import (javafx.animation Animation FadeTransition Interpolator KeyFrame
                              KeyValue ParallelTransition ScaleTransition
                              Timeline TranslateTransition)
           (javafx.scene Node)
           (javafx.util Duration)))

(def ^:dynamic *animate*
  "When bound to false, every constructor in this namespace returns nil
   immediately and starts nothing -- no Timeline or Transition is even
   built, let alone played.

   This exists for the screenshot harness (`reliquary.ui.shot`) and for the
   test suite: a still PNG captured mid-animation shows an arbitrary point
   in that animation's cycle, so two runs of the same screen produce
   different-looking 'identical' screenshots. Bind this to false before
   rendering anything meant to be reproducible:

     (binding [reliquary.ui.anim/*animate* false)
       (shot/render! desc out opts))

   Defaults to true so the running app animates normally without every
   caller having to opt in."
  true)

(defn with-anim
  "Wraps `desc` (a cljfx node description) in `fx/ext-on-instance-lifecycle`
   so that `start-fn` -- a 1-arg fn `(fn [node] animation-or-nil)`, meant to
   be one of this namespace's `*!` constructors (or a fn calling one with
   opts) -- runs when the node is created, and whatever `Animation` it
   returns is unconditionally `.stop`ped when cljfx deletes that node.

   THIS is the idiom every screen should use to attach an indefinite
   animation (`breathe!` `pulse!` `scan!` `sheen!`) to a node -- see this
   namespace's docstring for why an unstopped indefinite animation is a
   leak. `start-fn` returning nil (because `*animate*` is false, or because
   it simply chose not to animate) is fine: `:on-deleted` then finds nothing
   stored and is a no-op.

   One-shot animations (`rise-in!` `ring-in!`) don't strictly need this --
   they stop themselves -- but routing them through it too costs nothing
   and keeps every screen's animation attachment looking the same."
  [desc start-fn]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Node node]
                 (when-let [anim (start-fn node)]
                   (.put (.getProperties node) ::running anim)))
   :on-deleted (fn [^Node node]
                 (when-let [anim (.get (.getProperties node) ::running)]
                   (.stop ^Animation anim)))
   :desc desc})

;; ---------------------------------------------------------------------------
;; indefinite -- must go through `with-anim` (or an equivalent stop-on-delete)

(defn breathe!
  "The logo halo / done-ring halo: opacity and scale both oscillate forever
   between `from-*` and `to-*`, `duration` each direction (CSS: `haloBreathe
   4.5s infinite`, opacity .35<->.75, scale 1<->1.12). A single `Timeline`
   KeyFrame with `setAutoReverse true` and `Animation/INDEFINITE`, matching
   the design delta's own 'Timeline on opacity + scaleX/Y, autoReverse'
   mapping. Sets the node's starting opacity/scale itself so the first frame
   is correct rather than whatever the node happened to have.

   Returns the running `Timeline`, or nil (starting nothing) if `*animate*`
   is false."
  ([node] (breathe! node {}))
  ([^Node node {:keys [duration from-opacity to-opacity from-scale to-scale]
                :or   {duration     (Duration/seconds 4.5)
                       from-opacity 0.35 to-opacity 0.75
                       from-scale   1.0  to-scale    1.12}}]
   (when *animate*
     (.setOpacity node from-opacity)
     (.setScaleX node from-scale)
     (.setScaleY node from-scale)
     (let [kf (KeyFrame. ^Duration duration
                (into-array KeyValue
                  [(KeyValue. (.opacityProperty node) (double to-opacity))
                   (KeyValue. (.scaleXProperty node) (double to-scale))
                   (KeyValue. (.scaleYProperty node) (double to-scale))]))
           tl (Timeline. (into-array KeyFrame [kf]))]
       (.setAutoReverse tl true)
       (.setCycleCount tl Animation/INDEFINITE)
       (.play tl)
       tl))))

(defn pulse!
  "The QR waiting dot: opacity oscillates forever between `from-opacity`
   and `to-opacity`, `duration` each direction (CSS: `pulseDot 1.4s
   infinite`, opacity .35<->1). A `FadeTransition` with `setAutoReverse
   true` and `Animation/INDEFINITE`, per the design delta's own mapping.

   Returns the running `FadeTransition`, or nil if `*animate*` is false."
  ([node] (pulse! node {}))
  ([^Node node {:keys [duration from-opacity to-opacity]
                :or   {duration (Duration/seconds 1.4) from-opacity 0.35 to-opacity 1.0}}]
   (when *animate*
     (doto (FadeTransition. ^Duration duration node)
       (.setFromValue (double from-opacity))
       (.setToValue (double to-opacity))
       (.setAutoReverse true)
       (.setCycleCount Animation/INDEFINITE)
       (.play)))))

(defn scan!
  "The QR scan bar: a `TranslateTransition` sweeping `translateY` from
   -100% to 2100% of `unit`, forever, `duration` per sweep, no reverse (CSS:
   `scan 3.4s infinite`). `unit` is the pixel value CSS's 100% resolves to
   for THIS node -- CSS percentage translates resolve against the moving
   element's own bounding-box height, not its container's, so pass the scan
   bar's own height (defaults to 2.0px, a plain hairline-bar guess; override
   it with the real value once the bar exists).

   Returns the running `TranslateTransition`, or nil if `*animate*` is
   false."
  ([node] (scan! node {}))
  ([^Node node {:keys [duration unit] :or {duration (Duration/seconds 3.4) unit 2.0}}]
   (when *animate*
     (doto (TranslateTransition. ^Duration duration node)
       (.setFromY (* (double unit) -1.0))
       (.setToY (* (double unit) 21.0))
       (.setInterpolator Interpolator/LINEAR)
       (.setCycleCount Animation/INDEFINITE)
       (.play)))))

(defn sheen!
  "The stage-panel highlight: a `TranslateTransition` sweeping `translateX`
   from -140% to 320% of `unit`, forever, `duration` per sweep, no reverse
   (CSS: `sheen 7s infinite`; the CSS also skews the sheen -18deg, which has
   no JavaFX `Node` property -- the design delta's own mapping only calls
   for the `TranslateTransition`, so the skew is dropped rather than bolted
   on via a `Shear` transform nobody asked for). `unit` is the pixel value
   CSS's 100% resolves to for THIS node -- pass the highlight's own width;
   defaults to 100.0px as a placeholder.

   Returns the running `TranslateTransition`, or nil if `*animate*` is
   false."
  ([node] (sheen! node {}))
  ([^Node node {:keys [duration unit] :or {duration (Duration/seconds 7) unit 100.0}}]
   (when *animate*
     (doto (TranslateTransition. ^Duration duration node)
       (.setFromX (* (double unit) -1.4))
       (.setToX (* (double unit) 3.2))
       (.setInterpolator Interpolator/LINEAR)
       (.setCycleCount Animation/INDEFINITE)
       (.play)))))

;; ---------------------------------------------------------------------------
;; one-shot -- play once and stop themselves; `with-anim` is optional for
;; these but harmless

(defn rise-in!
  "Cards, the stage panel: fade in, translate up, and settle from a slight
   scale, ONCE (CSS: `riseIn .4-.5s`, opacity 0->1, translateY 10->0, scale
   .985->1). A `ParallelTransition` of a `FadeTransition`, a
   `TranslateTransition`, and a `ScaleTransition`, all sharing `duration`.
   Sets the node's starting opacity/translateY/scale itself, so it is
   correct even if this runs before the node's first layout pass.

   Returns the running `ParallelTransition`, or nil if `*animate*` is
   false."
  ([node] (rise-in! node {}))
  ([^Node node {:keys [duration dy from-scale]
                :or   {duration (Duration/seconds 0.45) dy 10.0 from-scale 0.985}}]
   (when *animate*
     (.setOpacity node 0.0)
     (.setTranslateY node (double dy))
     (.setScaleX node (double from-scale))
     (.setScaleY node (double from-scale))
     (let [fade  (doto (FadeTransition. ^Duration duration node)
                   (.setFromValue 0.0) (.setToValue 1.0))
           move  (doto (TranslateTransition. ^Duration duration node)
                   (.setFromY (double dy)) (.setToY 0.0))
           scale (doto (ScaleTransition. ^Duration duration node)
                   (.setFromX (double from-scale)) (.setFromY (double from-scale))
                   (.setToX 1.0) (.setToY 1.0))
           group (ParallelTransition. (into-array Animation [fade move scale]))]
       (.play group)
       group))))

(defn ring-in!
  "The done checkmark ring: scale bounces past its target and settles,
   ONCE (CSS: `ringIn .55s`, scale .6 -> 1.06 -> 1, opacity 0 -> 1). A
   `Timeline` with an overshoot `KeyFrame` at `overshoot-at` (fraction of
   `duration`, default 0.65) hitting `overshoot-scale` (default 1.06), then
   a second `KeyFrame` at `duration` settling to scale 1. Sets the node's
   starting opacity/scale itself.

   Returns the running `Timeline`, or nil if `*animate*` is false."
  ([node] (ring-in! node {}))
  ([^Node node {:keys [duration overshoot-at overshoot-scale]
                :or   {duration (Duration/seconds 0.55) overshoot-at 0.65 overshoot-scale 1.06}}]
   (when *animate*
     (.setOpacity node 0.0)
     (.setScaleX node 0.6)
     (.setScaleY node 0.6)
     (let [overshoot-ms (Duration/millis (* (.toMillis ^Duration duration) (double overshoot-at)))
           kf1 (KeyFrame. overshoot-ms
                 (into-array KeyValue
                   [(KeyValue. (.opacityProperty node) 1.0)
                    (KeyValue. (.scaleXProperty node) (double overshoot-scale))
                    (KeyValue. (.scaleYProperty node) (double overshoot-scale))]))
           kf2 (KeyFrame. ^Duration duration
                 (into-array KeyValue
                   [(KeyValue. (.scaleXProperty node) 1.0)
                    (KeyValue. (.scaleYProperty node) 1.0)]))
           tl (Timeline. (into-array KeyFrame [kf1 kf2]))]
       (.play tl)
       tl))))
