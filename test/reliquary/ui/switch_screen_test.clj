;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.switch-screen-test
  "The switch screen owns the whole flow: what is installed, what it would
   become, and every state in between.

   It exists because the flow was previously split across the library's side
   panel and the download screen, and neither fitted. The download screen's two
   buttons were wired to a download's handlers -- Cancel did nothing to a switch,
   and `Resume switch` would have downloaded the whole game -- and the panel's
   hashing box was unreachable. One screen, one set of handlers, one place to
   look."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.switch :as switch-screen]))

(def ^:private game {:appid 489830 :title "The Elder Scrolls V: Skyrim Special Edition"})
(def ^:private install {:path "/home/me/.local/share/Steam/steamapps/common/Skyrim Special Edition"
                        :bytes 16095731388})
(def ^:private from {:id "public" :label "Latest"})
(def ^:private to   {:id "1_6_1130" :label "1.6.1130" :bytes 16095467175})

(defn- view [extra]
  (switch-screen/view (merge {:game game :install install
                              :installed-version from :target-version to}
                             extra)))

;; ---------------------------------------------------------------------------
;; ready to go

(deftest the-screen-says-where-the-install-is-and-what-it-is
  (let [s (pr-str (view {}))]
    (is (str/includes? s (:path install)) "the design's `Installed at`")
    (is (str/includes? s "FROM STEAM") "and that the folder is Steam's, not one chosen here")
    (is (str/includes? s "Latest") "the version on disk")
    (is (str/includes? s "1.6.1130") "and the one it would become")))

(deftest the-action-names-the-target-version
  (testing "this rewrites a game in place; the button should say what it will do"
    (is (str/includes? (pr-str (view {})) "Switch to 1.6.1130"))))

(deftest the-action-is-wired
  (let [fired (atom false)
        v (view {:on-switch (fn [_] (reset! fired true))})
        btn (first (filter #(str/includes? (str (:text %)) "Switch to")
                           (switch-screen/buttons v)))]
    (is (some? btn))
    ((:on-action btn) nil)
    (is @fired)))

(deftest an-unidentified-install-is-offered-a-forced-switch
  (testing "a hand-downgraded game is a build the catalog cannot name, and it is
            the install most likely to want changing. The screen used to say
            `Unrecognised build` and offer nothing, which left the one user who
            needs this feature looking at a dead end."
    (let [s (pr-str (view {:installed-version nil}))]
      (is (str/includes? s "Unrecognised"))
      (is (str/includes? s "Force switch to 1.6.1130")))))

(deftest a-forced-switch-says-what-is-different-about-it
  (testing "it is not the ordinary path: without a source manifest the boundary
            map is the target's own, so less is reused and more is fetched. The
            user is about to rewrite a game in place and should know which of
            the two they are getting."
    (let [s (pr-str (view {:installed-version nil}))]
      (is (str/includes? s "verify"))
      (is (str/includes? s "fetch")))))

(deftest a-forced-switch-is-wired-to-the-same-handler
  (let [fired (atom false)
        v (view {:installed-version nil :on-switch (fn [_] (reset! fired true))})
        btn (first (filter #(str/includes? (str (:text %)) "Force switch")
                           (switch-screen/buttons v)))]
    (is (some? btn))
    ((:on-action btn) nil)
    (is @fired)))

(deftest a-recognised-install-is-not-offered-a-forced-switch
  (testing "the ordinary path reuses more and should not be bypassed"
    (let [s (pr-str (view {}))]
      (is (not (str/includes? s "Force switch"))))))

(deftest switching-to-what-is-already-installed-is-not-offered
  (let [s (pr-str (view {:target-version from}))]
    (is (str/includes? s "Already installed"))
    (is (not (str/includes? s "Switch to Latest")))))

;; ---------------------------------------------------------------------------
;; working

(defn- progress [stage extra]
  (view {:snapshot (merge {:stage stage :bytes-done 9000000000 :bytes-total 15000000000
                           :bytes-per-sec 1.0E9 :session-bytes-per-sec 1.0E9
                           :samples [8.0E8 9.0E8 1.0E9]}
                          extra)}))

(deftest reading-the-install-shows-the-designs-hashing-box
  (let [s (pr-str (progress :hashing {}))]
    (is (str/includes? s "Hashing local files") "the design's own words")
    (is (str/includes? s "60%"))
    (is (str/includes? s "8.4 GB of 14.0 GB"))))

(deftest reading-the-install-shows-a-rate-and-a-clock
  (testing "a 15 GB read at a gigabyte a second showed 0.0 MB/s and --:-- on the
            old screen, which reads as hung on the one operation that rewrites a
            game in place"
    (let [s (pr-str (progress :hashing {}))]
      (is (not (str/includes? s "0.0 MB/s")))
      (is (not (str/includes? s "--:--"))))))

(deftest each-phase-says-what-it-is-doing
  (is (str/includes? (pr-str (progress :hashing {})) "Hashing local files"))
  (is (str/includes? (pr-str (progress :staging {})) "Preparing"))
  (is (str/includes? (pr-str (progress :switching {})) "Switching")))

(deftest the-action-button-is-gone-while-it-runs
  (testing "not merely disabled: a second press must not be able to start a
            second pass over the same install"
    (is (not (str/includes? (pr-str (progress :hashing {})) "Switch to 1.6.1130")))))

(deftest a-run-in-progress-can-be-cancelled
  (let [fired (atom false)
        v (progress :switching {})
        btn (first (filter #(= "Cancel" (:text %)) (switch-screen/buttons v)))]
    (is (some? btn) "reading fifteen gigabytes must be stoppable")
    (is (fn? (:on-action btn)))))

(deftest cancel-is-wired-to-the-callers-handler
  (let [fired (atom false)
        v (view {:snapshot {:stage :hashing :bytes-done 1 :bytes-total 2}
                 :on-cancel (fn [_] (reset! fired true))})
        btn (first (filter #(= "Cancel" (:text %)) (switch-screen/buttons v)))]
    ((:on-action btn) nil)
    (is @fired)))

;; ---------------------------------------------------------------------------
;; failure and completion

(deftest a-failure-shows-the-reason-and-offers-to-carry-on
  (let [s (pr-str (view {:snapshot {:stage :failed :bytes-done 160000000
                                    :error {:category :unavailable
                                            :message "cdn rejected the request"}}}))]
    (is (str/includes? s "cdn rejected the request") "the reason, not just a category")
    (is (str/includes? s "UNAVAILABLE"))
    (is (str/includes? s "Resume switch"))))

(deftest a-failure-does-not-promise-nothing-needs-re-fetching
  (testing "true of a download, whose progress file records what landed. A switch
            keeps no such record: re-running re-hashes and works out what is
            still needed, which may include a file it had half written."
    (let [s (pr-str (view {:snapshot {:stage :failed :bytes-done 1
                                      :error {:category :io :message "x"}}}))]
      (is (not (str/includes? s "nothing needs to be re-fetched")))
      (is (str/includes? s "re-check")))))

(deftest a-finished-switch-says-so-and-offers-the-folder
  (let [s (pr-str (view {:snapshot {:stage :done}}))]
    (is (str/includes? s "1.6.1130"))
    (is (str/includes? s "Open folder"))))

(deftest a-finished-switch-does-not-still-call-the-install-the-old-version
  (testing "the whole point of the screen is that those bytes are now the target.
            Leaving `Latest` under `Installed at` is not a stale label, it is the
            screen contradicting what it just did -- and the next thing the user
            does with it is decide whether to switch again."
    (let [s (pr-str (view {:snapshot {:stage :done}}))]
      (is (not (str/includes? s "Latest")))
      (is (str/includes? s "1.6.1130 · ") "the install block names the new version"))))

(deftest a-finished-switch-does-not-show-an-arrow-from-a-version-to-itself
  (let [s (pr-str (view {:snapshot {:stage :done}}))]
    (is (not (str/includes? s "→")))))

(defn- untracked
  "Letter-spaced captions are joined with U+2009 THIN SPACE, which looks exactly
   like an ordinary space in a terminal. Strip it rather than trying to write it
   into a literal -- a first attempt matched on ASCII spaces and quietly found
   zero occurrences of a heading that was right there on the screen."
  [s]
  (str/replace s "\u2009" ""))

(deftest a-failure-states-its-heading-once
  (testing "the kicker at the top already says it; a second copy directly below
            reads as two separate failures"
    (let [s (untracked (pr-str (view {:snapshot {:stage :failed :bytes-done 1
                                                 :error {:category :io :message "x"}}})))]
      (is (= 1 (count (re-seq #"SWITCH INTERRUPTED" s)))))))

(deftest every-state-can-get-back-to-the-library
  (testing "including mid-run: leaving the screen is not the same as cancelling,
            and a user must never be trapped on it"
    (doseq [snap [nil {:stage :hashing :bytes-done 1 :bytes-total 2}
                  {:stage :failed :error {:category :io :message "x"}}
                  {:stage :done}]]
      (is (some #(= "Back to library" (:text %))
                (switch-screen/buttons (view {:snapshot snap})))
          (str "no way back from " (pr-str (:stage snap)))))))

(deftest handlers-default-to-no-ops-rather-than-crashing-the-renderer
  (testing "cljfx cannot coerce a nil :on-action -- a missing handler is not an
            inert button, it is a renderer that dies on first paint"
    (doseq [b (switch-screen/buttons (view {:snapshot {:stage :failed
                                                       :error {:category :io :message "x"}}}))]
      (is (fn? (:on-action b)) (str (:text b) " must be pressable")))
    (doseq [b (switch-screen/buttons (view {}))]
      (is (fn? (:on-action b))))))
