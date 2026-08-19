;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.switch
  "Change an existing Steam install from one version to another, in place.

   The pieces are all proven elsewhere -- `reliquary.steam.local` indexes,
   plans, stages and applies, and every one of those is verified against live
   Steam. What lives here is the ORDER, which is the part that can corrupt a
   game if it is wrong:

     1. hash    the install, using the version it currently is as the chunk
                boundary map. Steam's chunk boundaries come from a manifest and
                cannot be recomputed from local bytes.
     2. plan    against the target manifest: in-place, copy, fetch.
     3. stage   every moving chunk, BEFORE anything is written. 89% of reuse
                comes from the install being rewritten, so a moving chunk's
                source is usually a region the writes are about to overwrite.
     4. apply   in place, reading moved chunks from staging and the rest from
                Steam.
     5. clear   the staging area; it is derived data.

   Destructive and deliberately not transactional. An interrupted switch leaves
   a half-switched install, which is simply another state to hash and diff from
   -- run it again and it converges from wherever it actually is. There is no
   backup because nothing is recorded that could be wrong, and no rollback
   because there is nothing to roll back to that hashing cannot rediscover.

   Measured on the real Skyrim SE public -> 1.6.1130 switch: 13.81 GB left
   untouched, 0.97 GB staged and moved, 0.21 GB fetched."
  (:require [reliquary.download :as download]
            [reliquary.error :as error]
            [reliquary.steam.chunk :as chunk]
            [reliquary.steam.local :as local]))

(defn- files-of [{:keys [manifests]}] (mapcat :files manifests))

(defn- chunk-sources
  "{chunk-id {:depot-id d :key-hex k}} for every chunk in these manifests.

   A chunk is fetched from the depot it belongs to, under that depot's key, and
   the plan carries neither -- it is a list of content, not of provenance. This
   is the lookup that puts them back together."
  [{:keys [manifests]}]
  (into {}
        (for [m manifests
              f (:files m)
              ch (:chunks f)]
          [(:id ch) {:depot-id (:depot-id m) :key-hex (:key-hex m)}])))

(defn run!
  "Switch `install` from version `from` to version `to`. Blocking.

   `opts`:
     :session      an open CM session, for manifests and depot keys
     :game         the catalog game, for the appid
     :install      an entry from reliquary.steam.installs (its :path is used)
     :from :to     catalog versions. `from` must be what is actually on disk:
                   it supplies the chunk boundaries the index is built with, so
                   identifying it (reliquary.steam.local/identify) comes first.
     :on-progress  {:phase :hashing|:staging|:applying :done :total :path}
     :on-plan      called once with the plan, before anything is written -- the
                   panel shows what a switch will cost before it starts costing
     :abort?       0-arg predicate, checked throughout

   Returns the plan. Raises if a chunk cannot be sourced; a half-written install
   is left as it is, to be converged by the next run."
  [{:keys [session game install from to on-progress on-plan abort?]}]
  (let [root     (:path install)
        progress (fn [phase] (when on-progress
                               (fn [p] (on-progress (assoc p :phase phase)))))
        src      (download/version-manifests session game from)
        ;; the version on disk supplies the boundaries; see the ns docstring
        index    (local/chunk-index root (files-of src)
                                    {:on-progress (progress :hashing) :abort? abort?})]
    (when-not (and abort? (abort?))
      (let [tgt   (download/version-manifests session game to)
            plan  (local/plan-switch index (files-of tgt))
            _     (when on-plan (on-plan plan))
            srcs  (chunk-sources tgt)
            hosts (:hosts tgt)]
        (when-not (and abort? (abort?))
          (let [staged (local/stage! root plan {:on-progress (progress :staging)
                                                :abort? abort?})]
            (when-not (and abort? (abort?))
              (local/apply!
               root plan staged
               {:on-progress (progress :applying)
                :abort? abort?
                :fetch (fn [{:keys [id size to]}]
                         (let [{:keys [depot-id key-hex]} (get srcs id)]
                           (when-not depot-id
                             (error/raise :incorrect
                                          (str "chunk " id " belongs to no depot in the target")
                                          {:chunk-id id :path (:path to)}))
                           (chunk/fetch-decoded {:hosts hosts
                                                 :depot-id depot-id
                                                 :key-hex key-hex
                                                 :chunk {:id id :cb-original size}})))})
              (local/clear-staging! root))))
        plan))))
