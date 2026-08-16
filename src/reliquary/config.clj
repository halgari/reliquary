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

(defn- prop-dir
  "The directory named by JVM property `prop-name`, or nil.

   This is the safety net a `binding` cannot be: `catalog/refresh!` writes
   the cache from a raw `Thread`, and dynamic bindings do not cross a raw
   thread. A JVM system property is not thread-local -- it is visible to
   every thread in the process, including one this namespace never sees
   started -- so it is the one redirect mechanism that actually reaches
   there. The :test alias sets reliquary.config-dir / reliquary.data-dir to
   somewhere under target/test-state, which makes the real paths
   unreachable from a test JVM regardless of which namespace's fixture
   discipline does or doesn't bind *config-dir*/*data-dir*."
  [prop-name]
  (some-> (System/getProperty prop-name) not-empty io/file))

(defn- guard-against-the-real-path!
  "The loud backstop for `prop-dir`: refuse to resolve onto (or into, or
   out from) the REAL default directory this OS would hand back with no
   override at all -- the one place an actual Steam refresh token can
   live. A misconfigured property is a mistake in test setup, not a
   licence to risk the user's credential; this makes that mistake fail the
   build instead of silently overwriting one.

   Deliberately scoped to landing on the real default path, not merely
   living under $HOME: this checkout itself lives under $HOME on plenty of
   real machines (this one included), and target/test-state -- exactly
   where the :test alias points `resolved` -- is unavoidably a descendant
   of $HOME too. A literal 'nothing under $HOME' rule would make the suite
   unrunnable from a checkout like this one; the actual hazard is landing
   on the specific path the real credentials live in, and that is what
   this checks."
  [^File resolved ^File real-default]
  (let [resolved-path (.getCanonicalPath resolved)
        real-path     (.getCanonicalPath real-default)]
    (when (or (= resolved-path real-path)
              (.startsWith resolved-path (str real-path File/separator))
              (.startsWith real-path (str resolved-path File/separator)))
      (throw (ex-info
              (str "reliquary.config-dir/data-dir test override resolves onto the real "
                   "config/data path -- refusing to risk the stored credential")
              {:resolved resolved-path :real real-path}))))
  resolved)

(defn config-dir ^File []
  (or *config-dir*
      (when-let [p (prop-dir "reliquary.config-dir")]
        (guard-against-the-real-path! p (xdg "XDG_CONFIG_HOME" "/.config")))
      (xdg "XDG_CONFIG_HOME" "/.config")))

(defn data-dir ^File []
  (or *data-dir*
      (when-let [p (prop-dir "reliquary.data-dir")]
        (guard-against-the-real-path! p (xdg "XDG_DATA_HOME" "/.local/share")))
      (xdg "XDG_DATA_HOME" "/.local/share")))

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
      (try (let [v (edn/read-string (slurp f))]
             (if (map? v) v {}))
           (catch Exception _ {}))
      {})))

(defn write-config!
  "Write `m` atomically at mode 0600, creating the directory if needed and
   hardening it to 0700.

   Atomic because a half-written config that loses a refresh token costs the
   user a re-login for no reason. Temp file in the SAME directory, so the
   rename cannot cross a filesystem boundary and silently degrade to a copy.
   The directory is hardened the same way ~/.ssh is: the file being 0600 is
   not a reason to leave its parent world-traversable."
  [m]
  (let [dir (doto (config-dir) .mkdirs)
        _   (when (posix?)
              (Files/setPosixFilePermissions (.toPath dir)
                                             (PosixFilePermissions/fromString "rwx------")))
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
