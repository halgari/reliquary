;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.progress
  "Which chunks already landed, so a resumed download re-fetches nothing.

   The interrupted screen tells the user that nothing needs to be re-fetched.
   That claim is only honest if this file is written AFTER the bytes it
   describes are acknowledged as done on disk -- never before. A progress
   file that runs ahead of the disk silently skips chunks that were never
   written.

   A corrupt or missing file reads as {} -- an empty progress map costs a
   re-download, while a raise costs the user the whole install.

   ## Why the manifest identity is part of the file

   A recorded chunk is an INDEX -- a position in one file's chunk list in one
   specific manifest. It means nothing except against the manifest that
   produced it. appid and version-id do not pin that manifest: a version id is
   a moving target (`public` is the obvious one, but `catalog/refresh!` can
   swap any version's manifest-gid between two runs), so the same
   dest/appid/version-id can resolve to a DIFFERENT build tomorrow.

   Applying yesterday's indices to today's manifests mixes two builds into one
   install, and nothing downstream catches it: there is deliberately no
   verification pass, and the per-chunk SHA-1 only ever sees chunks that were
   actually fetched -- the ones skipped as \"already done\" are never checked.
   That is the one failure mode that produces a corrupt install that looks
   clean.

   So the file records the {depot-id manifest-gid} map it was written against,
   and `load` returns {} -- EMPTY, re-download everything -- whenever that map
   differs from the manifests just resolved. Discarding is the only safe
   answer: the bytes on disk may be from either build and there is no way to
   tell which without the verification pass the engine does not have. A
   discarded resume costs bandwidth; an honoured mismatch costs the install.

   A file written before this field existed has no :manifests at all, which
   reads as a mismatch, which is the correct answer for exactly the same
   reason -- nothing identifies the build those bytes came from."
  (:refer-clojure :exclude [load])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

(defn progress-file
  "The progress file for `appid`/`version-id` under install destination
   `dest`: <dest>/.reliquary/<appid>-<version-id>.progress.

   Different versions of the same app never share a file -- resuming a
   different version's download must not be mistaken for resuming this one.
   That separation is necessary but NOT sufficient, since a version id can
   point at a different build over time; see the namespace docstring for the
   manifest identity that closes the rest of the hole."
  ^File [dest appid version-id]
  (io/file dest ".reliquary" (str appid "-" version-id ".progress")))

(defn manifest-fingerprint
  "`manifests` ({depot-id -> manifest-gid}) as the stable, comparable identity
   stored in a progress file.

   Normalized -- depot ids to longs, gids to strings, sorted -- so that a map
   read back from EDN compares equal to one built from a freshly resolved
   version regardless of how either side happened to type its numbers."
  [manifests]
  (into (sorted-map)
        (map (fn [[depot-id gid]] [(long depot-id) (str gid)]))
        manifests))

(defn load
  "{path -> #{chunk-index ...}} already on disk for this dest/appid/version,
   or {} when the file is absent, unparseable, or was recorded against a
   DIFFERENT `manifests` map than the one passed here.

   A corrupt progress file must not brick a resume: the alternative is a
   download that cannot proceed over a file the user never touched by hand.
   A mismatched one must not be honoured: see the namespace docstring."
  [dest appid version-id manifests]
  (let [f (progress-file dest appid version-id)]
    (if (.isFile f)
      (try (let [v (edn/read-string (slurp f))]
             (if (and (map? v)
                      (map? (:done v))
                      (= (manifest-fingerprint manifests) (:manifests v)))
               (:done v)
               {}))
           (catch Exception _ {}))
      {})))

(defn save!
  "Write `done` atomically for this dest/appid/version, stamped with the
   identity of `manifests`, creating the `.reliquary` directory if needed.

   Temp file in the SAME directory as the target, so the rename cannot cross
   a filesystem boundary and silently degrade to a copy. Callers MUST only
   call this after the chunks recorded in `done` are actually on disk --
   never before -- or a resume will believe bytes exist that do not, and MUST
   pass the same `manifests` the indices in `done` were resolved against, or
   the next run will discard work that was perfectly good."
  [dest appid version-id manifests done]
  (let [f   (progress-file dest appid version-id)
        dir (doto (.getParentFile f) .mkdirs)
        tmp (Files/createTempFile (.toPath dir) ".progress" ".tmp"
                                  (make-array FileAttribute 0))]
    (spit (.toFile tmp) (pr-str {:manifests (manifest-fingerprint manifests)
                                 :done      done}))
    (Files/move tmp (.toPath f)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/ATOMIC_MOVE]))
    done))

(defn remaining
  "`plan` with chunks already recorded in `done` removed, and
   :download-bytes / :total-chunks recomputed to match.

   A file whose chunks are all complete KEEPS its entry with an empty
   :chunks vector -- it still needs preallocating and its :copies still need
   making, so dropping the entry would break resume."
  [plan done]
  (let [files' (mapv (fn [{:keys [path chunks] :as file}]
                       (let [done-idxs (get done path #{})]
                         (assoc file :chunks
                                (vec (remove #(contains? done-idxs (:index %)) chunks)))))
                     (:files plan))
        dl-bytes (reduce + 0 (mapcat (fn [f] (map :cb-original (:chunks f))) files'))
        total-chunks (reduce + 0 (map (comp count :chunks) files'))]
    (assoc plan :files files' :download-bytes dl-bytes :total-chunks total-chunks)))

(defn done-bytes
  "Bytes already on disk per `done`, for a resumed progress bar -- and for the
   disk-space check, which must ask for the REMAINDER, not the whole install."
  ^long [plan done]
  (reduce + 0
          (for [{:keys [path chunks]} (:files plan)
                :let [done-idxs (get done path #{})]
                c chunks
                :when (contains? done-idxs (:index c))]
            (:cb-original c))))
