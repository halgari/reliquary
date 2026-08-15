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
   re-download, while a raise costs the user the whole install."
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
   different version's download must not be mistaken for resuming this one."
  ^File [dest appid version-id]
  (io/file dest ".reliquary" (str appid "-" version-id ".progress")))

(defn load
  "{path -> #{chunk-index ...}} already on disk for this dest/appid/version,
   or {} when the file is absent or unparseable.

   A corrupt progress file must not brick a resume: the alternative is a
   download that cannot proceed over a file the user never touched by hand."
  [dest appid version-id]
  (let [f (progress-file dest appid version-id)]
    (if (.isFile f)
      (try (let [v (edn/read-string (slurp f))]
             (if (map? v) v {}))
           (catch Exception _ {}))
      {})))

(defn save!
  "Write `done` atomically for this dest/appid/version, creating the
   `.reliquary` directory if needed.

   Temp file in the SAME directory as the target, so the rename cannot cross
   a filesystem boundary and silently degrade to a copy. Callers MUST only
   call this after the chunks recorded in `done` are actually on disk --
   never before -- or a resume will believe bytes exist that do not."
  [dest appid version-id done]
  (let [f   (progress-file dest appid version-id)
        dir (doto (.getParentFile f) .mkdirs)
        tmp (Files/createTempFile (.toPath dir) ".progress" ".tmp"
                                  (make-array FileAttribute 0))]
    (spit (.toFile tmp) (pr-str done))
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
  "Bytes already on disk per `done`, for a resumed progress bar."
  ^long [plan done]
  (reduce + 0
          (for [{:keys [path chunks]} (:files plan)
                :let [done-idxs (get done path #{})]
                c chunks
                :when (contains? done-idxs (:index c))]
            (:cb-original c))))
