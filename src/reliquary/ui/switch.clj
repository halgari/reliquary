;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.switch
  "Changing an install that is already on disk: one screen, the whole flow.

   The design's switch panel is this screen's content -- `Installed at`, the
   Steam path, what is there now and what it would become, and the hashing box
   with its bar. It is a SCREEN rather than a corner of the library panel because
   the flow does not fit in a corner: it reads fifteen gigabytes, transfers, and
   rewrites a game in place, each with its own state, and every one of those
   needs somewhere to say what is happening and a way to stop.

   It was previously split between the library's side panel and the download
   screen, and neither half fitted. The download screen's buttons belong to a
   download: Cancel did nothing to a switch, and `Resume switch` would have
   fetched the whole game into a folder nobody chose. The panel's hashing box was
   never reachable at all. One screen owns it now, with its own handlers.

   States, driven entirely by `:snapshot`:

     nil        ready -- what is installed, what it would become, one button
     :hashing   reading the install to find out what it actually is
     :staging   copying the chunks that move, before anything is overwritten
     :switching transferring what is not already on disk
     :failed    the reason, and an offer to carry on
     :done      finished, with the folder"
  (:require [clojure.string :as str]
            [reliquary.ui.progress :as progress]
            [reliquary.ui.theme :as theme]))

(def ^:private c theme/color)

(def ^:private block-width 620)

(defn- tracked [s] (str/join " " (seq (str/upper-case s))))

(defn buttons
  "Every button description in a rendered screen, for tests and for the
   `every handler is wired` guard -- cljfx cannot coerce a nil :on-action, so a
   missing handler is not an inert button but a renderer that dies on first
   paint."
  [desc]
  (cond
    (and (map? desc) (= :button (:fx/type desc))) (cons desc (mapcat buttons (vals desc)))
    (map? desc) (mapcat buttons (vals desc))
    (sequential? desc) (mapcat buttons desc)
    :else nil))

;; ---------------------------------------------------------------------------
;; pieces

(defn- caption [text]
  {:fx/type :label :text text
   :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                         :-fx-text-fill (:text-muted c)})})

(defn- install-block
  "Where Steam put it, and what those bytes are.

   `Unrecognised build` rather than the nearest guess: identification is by
   content, and a build the catalog does not carry is exactly the state a user is
   in right after Steam updates the game."
  [install installed-version]
  {:fx/type :v-box :spacing 6 :max-width block-width :alignment :center
   :children
   ;; The two captions sit together rather than at opposite ends of the block.
   ;; In the design the panel was ~320px wide and a spacer between them read as
   ;; one labelled row; at 620 it left `FROM STEAM` marooned in white space with
   ;; nothing to attach itself to.
   [{:fx/type :h-box :spacing 8 :alignment :center
     :children [(caption (tracked "Installed at"))
                {:fx/type :label :text "· FROM STEAM"
                 :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 10
                                       :-fx-text-fill (:text-dim c)})}]}
    {:fx/type :label :text (str (:path install)) :wrap-text true :max-width block-width
     :alignment :center
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 12
                           :-fx-text-fill (:text c)})}
    {:fx/type :label
     :text (str (if installed-version (:label installed-version) "Unrecognised build")
                " · " (progress/gb (:bytes install)))
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                           :-fx-text-fill (:text-muted c)})}]})

(defn- phase-label [stage]
  (case stage
    :hashing   "Hashing local files"
    :staging   "Preparing files that move"
    :switching "Switching"
    "Working"))

(defn- progress-block
  "The design's hashing box -- the library panel's, literally: see
   reliquary.ui.progress. Doing duty for every working phase, which is why the
   label is passed in."
  [{:keys [stage bytes-done bytes-total bytes-per-sec session-bytes-per-sec]}]
  (assoc (progress/hashing-box {:label (phase-label stage)
                                :done bytes-done :total bytes-total
                                :bytes-per-sec bytes-per-sec
                                :session-bytes-per-sec session-bytes-per-sec})
         :max-width block-width))

(defn- button
  [text primary? on-action]
  {:fx/type :button :text text
   :on-action (or on-action (fn [_]))
   :min-height 40 :min-width 150
   :style (theme/style
           (if primary?
             {:-fx-background-color (:button theme/gradients) :-fx-text-fill (:bg c)
              :-fx-background-radius 3 :-fx-font-size 14
              :-fx-font-family (theme/ui-semibold-font)
              :-fx-effect (theme/glow (:gold c) {:blur 22 :spread -10 :dy 6 :alpha 0.9})}
             {:-fx-background-color "transparent" :-fx-text-fill (:text-muted c)
              :-fx-border-color (:line-strong c)
              :-fx-border-radius 3 :-fx-background-radius 3 :-fx-font-size 13}))})

(defn- failure-block
  [{:keys [bytes-done error]}]
  ;; No heading of its own: the kicker at the top of the screen already reads
  ;; SWITCH INTERRUPTED, and a second copy directly beneath it read as two
  ;; separate failures rather than one.
  {:fx/type :v-box :spacing 8 :max-width block-width :alignment :center
   :children
   ;; :alignment centres the text block inside the label; -fx-text-alignment only
   ;; centres wrapped lines relative to EACH OTHER, so on its own it left a
   ;; one-line error message hard against the left edge of a 620px box.
   [{:fx/type :label :text (or (:message error) "") :wrap-text true :max-width block-width
     :alignment :center
     :style (theme/style {:-fx-font-family (theme/ui-font) :-fx-font-size 17
                           :-fx-text-fill (:text c)
                           :-fx-text-alignment "center"})}
    ;; "nothing needs to be re-fetched" is a download's promise, made good by a
    ;; progress file recording exactly what landed. A switch keeps no such record
    ;; on purpose and may re-fetch a file it had half written.
    {:fx/type :label
     :text (str (str/upper-case (name (or (:category error) :unknown))) " · "
                (progress/gb bytes-done) " already written · re-running will re-check the "
                "install and carry on")
     :wrap-text true :max-width block-width
     :alignment :center
     :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                           :-fx-text-fill (:text-muted c)
                           :-fx-text-alignment "center"})}]})

;; ---------------------------------------------------------------------------

(defn- action-row
  [{:keys [installed-version target-version snapshot
           on-switch on-cancel on-retry on-open on-back]}]
  (let [stage (:stage snapshot)
        same? (and installed-version target-version
                   (= (:id installed-version) (:id target-version)))]
    {:fx/type :h-box :spacing 12 :alignment :center
     :children
     (case stage
       (:hashing :staging :switching)
       [(button "Cancel" false on-cancel)
        (button "Back to library" false on-back)]

       :failed
       [(button "Resume switch" true on-retry)
        (button "Back to library" false on-back)]

       :done
       [(button "Open folder" true on-open)
        (button "Back to library" false on-back)]

       ;; ready.
       (cond-> []
         (and installed-version target-version (not same?))
         (conj (button (str "Switch to " (:label target-version)) true on-switch))

         ;; An install the catalog cannot name is still switchable, by force:
         ;; the target's own manifest becomes the boundary map. Named
         ;; differently because it is a different bargain -- see forced-note.
         (and (nil? installed-version) target-version)
         (conj (button (str "Force switch to " (:label target-version)) true on-switch))

         same?
         (conj (assoc (button "Already installed" false nil) :disable true))

         :always
         (conj (button "Back to library" false on-back))))}))

(defn- forced-note
  "What `Force switch` costs, said before it is pressed.

   The user is about to rewrite a game in place. With no source manifest there
   are no source boundaries, so the target's own are used: every chunk is still
   hash-verified, and what already matches is kept -- but less lines up, so more
   is fetched than an ordinary switch of the same two versions."
  []
  {:fx/type :label
   ;; states the COST, not a reassurance. "Nothing is deleted that it cannot
   ;; replace" was the first draft and it is a weaselly non-promise: nothing is
   ;; deleted at all, and what the user actually needs to weigh is how much this
   ;; will fetch compared with the ordinary path.
   :text (str "This build is not in the catalog, so Reliquary will verify every "
              "file against the target and fetch whatever does not already match. "
              "Far less than a full download, but more than a switch between two "
              "builds it knows.")
   :wrap-text true :max-width block-width
   :alignment :center
   :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 11
                         :-fx-text-fill (:text-muted c)
                         :-fx-text-alignment "center"})})

(defn view
  "`{:game :install :installed-version :target-version :snapshot}` plus the
   `:on-*` handlers. Every handler defaults to a no-op: cljfx cannot coerce a nil
   :on-action, so a missing one is a renderer that dies on first paint rather
   than a button that does nothing."
  [{:keys [game install installed-version target-version snapshot] :as state}]
  (let [stage (:stage snapshot)]
    {:fx/type :v-box
     ;; Centred, like the done screen. As a side panel this content was a narrow
     ;; column and left-packing was right; as a full window it left everything
     ;; crammed into the top-left corner with two thirds of the screen empty
     ;; below it, which reads as a page that failed to load rather than a
     ;; deliberate one.
     :alignment :center
     :padding 48
     :spacing 22
     :children
     (into
      [{:fx/type :v-box :spacing 4 :alignment :center :max-width block-width
        :children
        (filterv
         some?
         [(caption (tracked (case stage
                             (:hashing :staging :switching) "Changing install"
                             :done "Install changed"
                             :failed "Switch interrupted"
                             "Change install")))
         {:fx/type :label :text (or (:title game) "") :wrap-text true :max-width block-width
          :alignment :center
          :style (theme/style {:-fx-font-family (theme/ui-bold-font) :-fx-font-size 25
                                :-fx-text-fill (:text c)
                                :-fx-text-alignment "center"})}
         ;; not on :done -- it would read "1.6.1130 -> 1.6.1130"
         (when-not (= :done stage)
           {:fx/type :label
            :text (str (:label installed-version "Unrecognised build")
                       "  →  " (:label target-version ""))
            :style (theme/style {:-fx-font-family (theme/mono-font) :-fx-font-size 13
                                  :-fx-text-fill (:text-muted c)})})])}
       ;; Once the switch is done those bytes ARE the target: the block names
       ;; the target rather than what used to be there, whatever the caller
       ;; still has in :installed-version.
       (install-block install (if (= :done stage) target-version installed-version))]

      (cond-> []
        (contains? #{:hashing :staging :switching} stage)
        (conj (progress-block snapshot))

        (= :failed stage) (conj (failure-block snapshot))

        ;; only before it runs: once it is going, the progress box says what is
        ;; happening and this would be restating a decision already taken
        (and (nil? stage) (nil? installed-version) target-version)
        (conj (forced-note))

        :always (conj (action-row state))))}))
