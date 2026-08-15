;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.depots
  "Pure selectors over a parsed app-info map (nested maps with STRING keys, as
   reliquary.steam.kv/parse-text produces).

   This is where the POLICY lives -- which depots are ours -- kept apart from
   reliquary.steam.apps so it can be tested against a committed fixture with no CM
   connection in sight.

   Windows-only is deliberate: mauvi mounts a Windows install and launches it
   under Proton. See the design doc SS3."
  (:require [clojure.string :as str]))

(defn public-buildid
  "The public branch's build id -- the version label a synced file set takes."
  [app-kv]
  (get-in app-kv ["appinfo" "depots" "branches" "public" "buildid"]))

(defn- depot-rows
  "The [key value] pairs under `depots` that are actually depots: a numeric key
   and a map value. Skips \"branches\", \"overridescddb\" and friends."
  [app-kv]
  (filter (fn [[k v]] (and (map? v) (re-matches #"\d+" k)))
          (get-in app-kv ["appinfo" "depots"])))

(defn select-windows-depots
  "The depots this app installs on Windows: no DLC, not optional, has a public
   manifest, and either declares no oslist or includes windows.

   Deliberately over-inclusive -- a shared or legacy depot may be selected here
   and then DENIED when its key is requested. That is expected; ingest skips
   those and counts them."
  [app-kv]
  (let [appid (Long/parseLong (get-in app-kv ["appinfo" "appid"]))]
    (into []
          (keep (fn [[k v]]
                  (when (and (nil? (get v "dlcappid"))
                             (nil? (get v "optional"))
                             (get-in v ["manifests" "public" "gid"])
                             (let [os (get-in v ["config" "oslist"])]
                               (or (nil? os) (str/includes? os "windows"))))
                    {;; a shared depot's bytes belong to another app, and its
                     ;; key must be requested under THAT app-id or be denied
                     :app-id       (if-let [from (get v "depotfromapp")]
                                     (Long/parseLong from)
                                     appid)
                     :depot-id     (Long/parseLong k)
                     :manifest-gid (get-in v ["manifests" "public" "gid"])})))
          (depot-rows app-kv))))

(defn windows-install-size
  "Uncompressed install bytes of the selected depots, from app-info alone. An
   UPPER BOUND: a selected depot whose key is denied contributes nothing in
   reality. Surface it as approximate. 0 when unknown."
  ^long [app-kv]
  (let [selected (set (map :depot-id (select-windows-depots app-kv)))]
    (reduce (fn [^long acc [k v]]
              (if (contains? selected (Long/parseLong k))
                (+ acc (Long/parseLong (or (get-in v ["manifests" "public" "size"]) "0")))
                acc))
            0
            (depot-rows app-kv))))
