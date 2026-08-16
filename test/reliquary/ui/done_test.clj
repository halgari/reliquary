;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.ui.done-test
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.ui.done :as done]))

(def ^:private game {:title "The Elder Scrolls V: Skyrim Special Edition"
                      :studio "Bethesda Game Studios"})

(def ^:private version {:label "Latest — public" :build "13189953"
                         :date "2024-01-17" :bytes 29742606498})

(def ^:private unknown-version {:label "1.5.97" :build "" :date "2019-11-20" :bytes 0})

(deftest the-path-title-and-meta-render
  (let [s (pr-str (done/view {:game game :version version :path "/install/skyrim"}))]
    (is (str/includes? s "The Elder Scrolls V: Skyrim Special Edition is ready"))
    (is (str/includes? s "/install/skyrim"))
    (is (str/includes? s "Latest — public"))
    (is (not (str/includes? s "build"))
        "the build id is Valve's own ordinal, not the version the user chose --
         it was removed from every screen deliberately")
    (is (str/includes? s "27.7 GB verified"))))

(deftest a-version-with-no-known-build-or-size-says-so-rather-than-lying
  (testing "community-sourced catalog versions -- e.g. Skyrim SE's 1.5.97 -- genuinely
            lack these fields; bytes 0 / build \"\" must never render as \"0.0 GB\""
    (let [s (pr-str (done/view {:game game :version unknown-version :path "/install/skyrim"}))]
      (is (not (str/includes? s "build"))
        "no build id, so no 'build unknown' either")
      (is (str/includes? s "size unknown"))
      (is (not (str/includes? s "0.0 GB"))))))

(deftest the-opened-confirmation-line-only-shows-when-opened
  (let [not-opened (pr-str (done/view {:game game :version version :path "/x"}))
        opened     (pr-str (done/view {:game game :version version :path "/x" :opened? true}))]
    (is (not (str/includes? not-opened "Opened in your file browser.")))
    (is (str/includes? opened "Opened in your file browser."))))

(deftest an-error-renders-as-a-gold-line-not-a-banner
  (testing "spec: errors are gold text on surface, never a red banner"
    (let [s (pr-str (done/view {:game game :version version :path "/x"
                                 :error "Could not open /x — no file browser is available"}))]
      (is (str/includes? s "no file browser is available")))
    (is (not (str/includes? (pr-str (done/view {:game game :version version :path "/x"}))
                             "no file browser"))
        "no :error -> no error line at all")))

(deftest open-folder-failing-produces-an-error-string-rather-than-throwing
  (testing "both routes fail -- e.g. a headless box or a minimal container with neither
            java.awt.Desktop nor xdg-open -- must never throw, per the spec"
    (with-redefs [done/desktop-open! (fn [_] false)
                  done/xdg-open! (fn [_] false)]
      (let [result (done/open-folder! "/install/skyrim")]
        (is (string? result))
        (is (str/includes? result "/install/skyrim")))))
  (testing "either route succeeding is a success -- nil, not a string"
    (with-redefs [done/desktop-open! (fn [_] true)
                  done/xdg-open! (fn [_] false)]
      (is (nil? (done/open-folder! "/install/skyrim"))))
    (with-redefs [done/desktop-open! (fn [_] false)
                  done/xdg-open! (fn [_] true)]
      (is (nil? (done/open-folder! "/install/skyrim"))))))

(deftest desktop-open-and-xdg-open-never-throw-on-their-own
  (testing "the real implementations, exercised for real -- on this xvfb box neither
            a real desktop environment nor an `xdg-open` binary is guaranteed, and
            either function returning false for that reason is fine; throwing is not"
    (is (boolean? (done/desktop-open! "/nonexistent/path/for/reliquary/tests")))
    (is (boolean? (done/xdg-open! "/nonexistent/path/for/reliquary/tests")))))

(deftest the-view-actually-instantiates-real-javafx-nodes
  (testing "pr-str only checks description SHAPE and never builds a Node -- this
            has already missed a nil handler or a bad prop twice on this project.
            This builds the real component through the same lifecycle path
            shot/render! uses, for every branch that matters: the base state, the
            unknown-build/size case, opened, and errored"
    (doseq [state [{:game game :version version :path "/install/skyrim"}
                   {:game game :version unknown-version :path "/install/skyrim"}
                   {:game game :version version :path "/install/skyrim" :opened? true}
                   {:game game :version version :path "/install/skyrim"
                    :error "Could not open /install/skyrim — no file browser is available"}
                   {:game game :version version :path "/install/skyrim"
                    :on-open (fn [_]) :on-back (fn [_])}]]
      (let [component @(fx/on-fx-thread (fx/create-component (done/view state)))]
        (is (some? (fx/instance component)) (str "failed to instantiate for " state))))))
