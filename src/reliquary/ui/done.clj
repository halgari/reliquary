;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.done
  "The last screen a user sees after a successful download: the install path,
   an open-folder button, and a way back to the library.

   A pure view fn -- no atoms, no side effects -- reading
   `{:game :version :path :opened? :error :on-open :on-back}`. The one piece
   of real logic, `open-folder!`, lives in this namespace but is a plain
   function kept deliberately separate from `view` so it can be exercised
   without a desktop: `desktop-open!` and `xdg-open!` are public precisely so
   a test can `with-redefs` them rather than needing a real windowing system
   or a real `xdg-open` binary on PATH."
  (:require [clojure.java.io :as io]
            [reliquary.ui.anim :as anim]
            [reliquary.ui.theme :as theme])
  (:import (java.awt Desktop Desktop$Action)
           (java.util List)))

(def ^:private c theme/color)

;; ---------------------------------------------------------------------------
;; open-folder! -- the one piece of real logic on this screen

(defn desktop-open!
  "Tries `java.awt.Desktop/open` on `path`. Returns true on success, false on
   ANY failure -- unsupported toolkit, a headless JVM (Desktop/getDesktop
   throws HeadlessException there even when isDesktopSupported lied and said
   true), the OPEN action not being supported, or the open itself failing.
   Swallows every exception rather than letting one escape, so `open-folder!`
   can fall through to `xdg-open!` instead of crashing the screen.

   Public rather than private so a test can stub it without a real desktop."
  [^String path]
  (try
    (boolean
      (and (Desktop/isDesktopSupported)
           (let [d (Desktop/getDesktop)]
             (and (.isSupported d Desktop$Action/OPEN)
                  (do (.open d (io/file path)) true)))))
    (catch Exception _ false)))

(defn xdg-open!
  "Falls back to launching `xdg-open <path>` as a subprocess. Returns true if
   the process exits 0, false otherwise -- including when `xdg-open` is not
   on PATH at all, which `ProcessBuilder/start` reports as an IOException
   rather than a nonzero exit. A headless box or a minimal container may have
   neither this nor `java.awt.Desktop`; that combination is exactly what
   `open-folder!` must turn into an error string, not a crash.

   Public rather than private so a test can stub it without a real
   `xdg-open` binary."
  [^String path]
  (try
    (let [proc (.start (ProcessBuilder. ^List (List/of "xdg-open" ^String path)))]
      (zero? (.waitFor proc)))
    (catch Exception _ false)))

(defn open-folder!
  "Opens `path` in the OS file browser: `java.awt.Desktop` when it is
   available and actually supports opening a folder, `xdg-open` otherwise.

   Returns nil on success, or an error message string on failure. NEVER
   throws -- per the Gilt spec an error is gold text on `surface`, never a
   crash and never a red banner, and on a headless box or a minimal
   container neither route may exist at all."
  [path]
  (if (or (desktop-open! path) (xdg-open! path))
    nil
    (str "Could not open " path " — no file browser is available")))

;; ---------------------------------------------------------------------------
;; formatting -- mirrors reliquary.cli's `gb`: community-sourced catalog
;; versions genuinely do not know their build or their size, and rendering
;; that as "" or "0.0 GB" would be a lie the user acts on.

(defn- fmt-size [bytes]
  (if (and bytes (pos? bytes))
    (str (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024))) " verified")
    "size unknown"))

(defn- meta-line [version]
  (str (:label version) " · " (fmt-size (:bytes version))))

;; ---------------------------------------------------------------------------
;; pieces

(def ^:private halo-size
  "The done-ring halo: a 14px inset outward from the 54px ring, per the
   design delta's `inset -14px` -- so 54 + 2*14 = 82px, centred behind the
   ring in the same stack."
  82.0)

(defn- checkmark-halo
  "The breathing amethyst halo behind the ring (design delta: `radial-
   gradient(circle, rgba(125,107,145,.5), transparent 68%)` via `breathe!`).
   Mouse-transparent -- it is pure decoration sitting behind a real ring,
   and must never steal a click meant for whatever's near it."
  []
  (anim/with-anim
    {:fx/type :region
     :min-width halo-size :min-height halo-size
     :max-width halo-size :max-height halo-size
     :mouse-transparent true
     :style (theme/style
             {:-fx-background-radius (/ halo-size 2)
              :-fx-background-color
              (theme/radial-gradient {:radius 50}
                                      ["rgba(125,107,145,0.5)" ["transparent" 68]])})}
    (fn [node] (anim/breathe! node))))

(defn- checkmark-ring
  "A 54px ring, 2px amethyst, with an amethyst check at 24px inside. Gilt's
   'completed' colour -- the one place amethyst, not gold, leads a screen
   region. Gains an amethyst bloom (`0 0 26px -6px rgba(125,107,145,.9)`)
   and enters once via `ring-in!` (scale .6 -> 1.06 -> 1, an overshoot)."
  []
  (anim/with-anim
    {:fx/type :stack-pane
     :min-width 54 :min-height 54 :max-width 54 :max-height 54
     :style (theme/style {:-fx-border-color (:amethyst c)
                           :-fx-border-width 2
                           :-fx-border-radius 27
                           :-fx-background-radius 27
                           :-fx-background-color "transparent"
                           :-fx-effect (theme/glow (:amethyst c)
                                                    {:blur 26 :spread -6 :alpha 0.9})})
     :children [{:fx/type :label :text "✓"
                 :style (theme/style {:-fx-font-family (theme/ui-bold-font)
                                       :-fx-font-size 24
                                       :-fx-text-fill (:amethyst c)})}]}
    (fn [node] (anim/ring-in! node))))

(defn- checkmark
  "The halo behind, the ring in front -- same slot `view` always used, so no
   caller needs to change."
  []
  {:fx/type :stack-pane
   :children [(checkmark-halo) (checkmark-ring)]})

(defn- title-line [game]
  {:fx/type :label
   :text (str (:title game) " is ready")
   :style (theme/style {:-fx-font-family (theme/ui-bold-font)
                         :-fx-font-size 24
                         :-fx-text-fill (:text c)})})

(defn- meta-label [version]
  {:fx/type :label
   :text (meta-line version)
   :style (theme/style {:-fx-font-family (theme/mono-font)
                         :-fx-font-size 12
                         :-fx-text-fill (:text-muted c)})})

(defn- path-box [path]
  {:fx/type :h-box
   :alignment :center
   :max-width 560
   :padding {:top 10 :bottom 10 :left 16 :right 16}
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-background-radius 3
                         :-fx-border-color (:line c)
                         :-fx-border-radius 3
                         :-fx-border-width 1})
   :children [{:fx/type :label :text (str path)
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 12
                                     :-fx-text-fill (:text c)})}]})

(defn- button-row [on-open on-back]
  {:fx/type :h-box
   :alignment :center
   :spacing 12
   :children [{:fx/type :button :text "Open folder"
               :on-action (or on-open (fn [_]))
               :min-height 40 :min-width 160
               :style (theme/style {:-fx-background-color (:button theme/gradients)
                                     :-fx-text-fill (:bg c)
                                     :-fx-background-radius 3
                                     :-fx-font-family (theme/ui-semibold-font)
                                     :-fx-font-size 14
                                     :-fx-effect (theme/glow (:gold c)
                                                              {:blur 22 :spread -10 :dy 6 :alpha 0.9})})}
              {:fx/type :button :text "Back to library"
               :on-action (or on-back (fn [_]))
               :min-height 40 :min-width 160
               :style (theme/style {:-fx-background-color "transparent"
                                     :-fx-border-color (:line-strong c)
                                     :-fx-border-radius 3
                                     :-fx-background-radius 3
                                     :-fx-text-fill (:text-muted c)
                                     :-fx-font-size 14})}]})

(defn- opened-line []
  {:fx/type :label
   :text "Opened in your file browser."
   :style (theme/style {:-fx-font-family (theme/ui-font)
                         :-fx-font-size 12
                         :-fx-text-fill (:text-muted c)})})

(defn- error-line
  "Gilt: an error is gold text on `surface`, never a red banner. `:error`
   here is a plain message string -- e.g. from `open-folder!` failing --
   rendered in the mono face since it is describing a path/command failure,
   the same family the rest of this screen uses for exactly that kind of
   text."
  [message]
  {:fx/type :h-box
   :alignment :center
   :max-width 560
   :padding {:top 8 :bottom 8 :left 16 :right 16}
   :style (theme/style {:-fx-background-color (:surface c)
                         :-fx-background-radius 3})
   :children [{:fx/type :label :text message :wrap-text true
               :style (theme/style {:-fx-font-family (theme/mono-font)
                                     :-fx-font-size 12
                                     :-fx-text-fill (:gold c)})}]})

(defn view
  "`:game` and `:version` are catalog maps (`:title`/`:studio` and
   `:label`/`:build`/`:bytes` respectively). `:path` is the install
   destination. `:opened?` shows the post-open confirmation line; `:error`,
   when present, shows a gold error line instead of ever crashing or
   red-bannering."
  [{:keys [game version path opened? error on-open on-back]}]
  {:fx/type :v-box
   :alignment :center
   :spacing 18
   :padding 48
   :children
   (into [(checkmark)
          (title-line game)
          (meta-label version)
          (path-box path)]
         (keep identity
               [(when error (error-line error))
                (button-row on-open on-back)
                (when opened? (opened-line))]))})
