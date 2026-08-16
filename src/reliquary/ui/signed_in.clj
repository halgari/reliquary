;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.signed-in
  "Where a signed-in user lands until the library screen exists.

   This screen is a placeholder, and it exists because the honest alternative
   was worse. `main/view` used to render the login screen unconditionally, so
   signing in successfully left the user staring at a blank QR card that said
   `Waiting for approval on your device` -- asking them to scan a challenge
   that had never been fetched. A screen that admits it is a waypoint beats a
   screen that lies about what it wants.

   It is deliberately useful rather than a `coming soon` panel: the engine and
   the catalog both work today through the CLI, so it says so and names the
   command."
  (:require [reliquary.catalog :as catalog]
            [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

(defn- line
  ([text] (line text {}))
  ([text {:keys [mono? size fill]}]
   {:fx/type :label
    :text    text
    :wrap-text true
    :max-width 620
    :style (theme/style {:-fx-font-family (if mono? (theme/mono-font) (theme/ui-font))
                         :-fx-font-size   (or size 13)
                         :-fx-text-fill   (or fill (:text-muted c))})}))

(defn view
  "`:status-line` carries the account name. The catalog counts are read live so
   the screen is never stale against a refreshed catalog."
  [{:keys [status-line]}]
  (let [cat    (catalog/load!)
        games  (catalog/games cat)
        versions (reduce + 0 (map (comp count :versions) games))]
    {:fx/type  :v-box
     :alignment :center
     :spacing  18
     :padding  48
     :children
     [{:fx/type :label
       :text    (str "Signed in as " (or status-line "your Steam account"))
       :style   (theme/style {:-fx-font-family (theme/ui-bold-font)
                              :-fx-font-size 25
                              :-fx-text-fill (:text c)})}

      (line (str (count games) " games · " versions " versions in the catalog")
            {:mono? true :size 12})

      {:fx/type :region :min-height 10 :max-height 10}

      (line (str "The library screen is not built yet. Downloading works today "
                 "through the terminal, and the engine behind it is the same one "
                 "this window will use.")
            {:size 14})

      {:fx/type :v-box
       :spacing 6
       :max-width 620
       :style (theme/style {:-fx-background-color (:surface c)
                            :-fx-background-radius 6
                            :-fx-padding 18})
       :children [(line "reliquary list" {:mono? true :size 13 :fill (:gold c)})
                  (line "every game and version the catalog knows" {:size 12})
                  {:fx/type :region :min-height 6 :max-height 6}
                  (line "reliquary download <appid> <version-id> <folder>"
                        {:mono? true :size 13 :fill (:gold c)})
                  (line "resumes if interrupted; Ctrl-C is safe" {:size 12})]}]}))
