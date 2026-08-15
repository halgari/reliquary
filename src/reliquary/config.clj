;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.config
  "Where Reliquary keeps its small amount of durable state: one EDN file.

   There is no database. The whole persistent surface is this file plus a
   progress file per in-flight download, and that is deliberate -- a JNI native
   library inside the native image would cost more than every feature it could
   buy.

   The file holds a Steam refresh token, so it is written at mode 0600 and its
   contents are never logged, never rendered, and never placed in an error map."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.nio.file CopyOption Files LinkOption StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(defn- xdg ^File [env-var fallback]
  (io/file (or (not-empty (System/getenv env-var))
               (str (System/getProperty "user.home") fallback))
           "reliquary"))

(def ^:dynamic *config-dir* nil)
(def ^:dynamic *data-dir* nil)

(defn config-dir ^File [] (or *config-dir* (xdg "XDG_CONFIG_HOME" "/.config")))
(defn data-dir   ^File [] (or *data-dir*   (xdg "XDG_DATA_HOME"   "/.local/share")))

(defn- config-file ^File [] (io/file (config-dir) "config.edn"))

(defn- posix? []
  (.. (java.nio.file.FileSystems/getDefault) supportedFileAttributeViews (contains "posix")))

(defn read-config
  "The config map, or {} when the file is absent or unparseable.

   A corrupt config reads as empty rather than throwing: the alternative is an
   app that cannot start and gives the user no way back, over a file they
   never edited by hand."
  []
  (let [f (config-file)]
    (if (.isFile f)
      (try (or (edn/read-string (slurp f)) {})
           (catch Exception _ {}))
      {})))

(defn write-config!
  "Write `m` atomically at mode 0600, creating the directory if needed.

   Atomic because a half-written config that loses a refresh token costs the
   user a re-login for no reason. Temp file in the SAME directory, so the
   rename cannot cross a filesystem boundary and silently degrade to a copy."
  [m]
  (let [dir (doto (config-dir) .mkdirs)
        tmp (Files/createTempFile (.toPath dir) ".config" ".tmp"
                                  (make-array FileAttribute 0))]
    (spit (.toFile tmp) (pr-str m))
    (when (posix?)
      (Files/setPosixFilePermissions tmp (PosixFilePermissions/fromString "rw-------")))
    (Files/move tmp (.toPath (config-file))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                        StandardCopyOption/ATOMIC_MOVE]))
    m))

(defn token
  "The stored Steam credentials, or nil when nobody has logged in."
  []
  (let [{:keys [refresh-token account]} (read-config)]
    (when (seq refresh-token)
      {:refresh-token refresh-token :account account})))

(defn save-token! [{:keys [refresh-token account]}]
  (write-config! (assoc (read-config) :refresh-token refresh-token :account account))
  {:refresh-token refresh-token :account account})

(defn forget-token!
  "Drop the credentials, keeping every other setting."
  []
  (write-config! (dissoc (read-config) :refresh-token :account))
  nil)
