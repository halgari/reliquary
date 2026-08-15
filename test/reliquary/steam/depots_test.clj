;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.depots-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.depots :as depots]
            [reliquary.steam.kv :as kv]))

(def ^:private skyrim
  (delay (kv/parse-text (slurp (io/resource "steam/pics-app.vdf")))))

(defn- synthetic
  "A hand-built app-info map, so each exclusion rule can be tested in isolation
   -- the real fixture proves the reader, this proves the policy."
  []
  {"appinfo"
   {"appid" "100"
    "depots"
    {"branches" {"public" {"buildid" "999"}}
     "200" {"manifests" {"public" {"gid" "2000" "size" "10"}}}
     "201" {"dlcappid" "555" "manifests" {"public" {"gid" "2001" "size" "10"}}}
     "202" {"optional" "1"  "manifests" {"public" {"gid" "2002" "size" "10"}}}
     "203" {"config" {"oslist" "linux"} "manifests" {"public" {"gid" "2003" "size" "10"}}}
     "204" {"config" {"oslist" "windows"} "manifests" {"public" {"gid" "2004" "size" "7"}}}
     "205" {"config" {"oslist" "macos,windows"} "manifests" {"public" {"gid" "2005" "size" "3"}}}
     "206" {"depotfromapp" "42" "manifests" {"public" {"gid" "2006" "size" "0"}}}
     "207" {}}}})

(defn- ids [sel] (set (map :depot-id sel)))

(deftest keeps-a-plain-windows-depot
  (is (contains? (ids (depots/select-windows-depots (synthetic))) 200)))

(deftest excludes-dlc-optional-and-non-windows-depots
  (let [got (ids (depots/select-windows-depots (synthetic)))]
    (is (not (contains? got 201)) "dlcappid")
    (is (not (contains? got 202)) "optional")
    (is (not (contains? got 203)) "oslist without windows")))

(deftest keeps-a-depot-whose-oslist-merely-includes-windows
  (is (contains? (ids (depots/select-windows-depots (synthetic))) 205)))

(deftest excludes-non-numeric-keys-and-depots-with-no-public-manifest
  (let [got (ids (depots/select-windows-depots (synthetic)))]
    (is (not (contains? got 207)) "no manifests/public/gid")
    (is (every? number? got) "\"branches\" must never be read as a depot")))

(deftest depotfromapp-reattributes-a-shared-depot
  (testing "a shared depot's bytes belong to another app; requesting its key
            under the wrong app-id is denied"
    (let [d (first (filter #(= 206 (:depot-id %)) (depots/select-windows-depots (synthetic))))]
      (is (= 42 (:app-id d)))))
  (let [d (first (filter #(= 200 (:depot-id %)) (depots/select-windows-depots (synthetic))))]
    (is (= 100 (:app-id d)) "an unshared depot belongs to the app itself")))

(deftest manifest-gids-stay-strings
  (is (every? string? (map :manifest-gid (depots/select-windows-depots (synthetic))))))

(deftest install-size-sums-only-the-selected-depots
  (testing "10 + 7 + 3 + 0 -- the dlc, optional and linux depots do not count"
    (is (= 20 (depots/windows-install-size (synthetic))))))

(deftest reads-the-public-buildid
  (is (= "999" (depots/public-buildid (synthetic)))))

;; ---- against the real captured Skyrim app info ----

(deftest selects-real-skyrim-depots-from-the-fixture
  (testing "pics-app.vdf is a static committed fixture, not a live call -- it
            changes only on a deliberate re-capture, which warrants updating
            this test anyway. So exact counts belong here, not just seq/pos?:
            a regression that collapsed selection from the real 11 depots down
            to 1 would still pass every loose assertion."
    (let [sel (depots/select-windows-depots @skyrim)]
      (is (= 11 (count sel)) "appid 489830 (Skyrim SE) has 11 selected depots")
      (is (every? #(pos? (:depot-id %)) sel))
      (is (every? #(re-matches #"\d+" (:manifest-gid %)) sel))
      (is (= "13189953" (depots/public-buildid @skyrim)))
      (is (pos? (depots/windows-install-size @skyrim))
          "Skyrim SE is several GB; a zero here means the size key moved"))))
