;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.steam.installs
  "What Steam has already installed on this machine, and where.

   This is what makes changing an existing install possible rather than only
   downloading a fresh copy: to offer a user 1.5.97 in place of what they have,
   the app first has to know they have Skyrim at all, which directory it is in,
   and which version those bytes are.

   Three files answer that, all of them Valve's text KeyValues format, which
   `reliquary.steam.kv/parse-text` already reads:

     <root>/steamapps/libraryfolders.vdf   every library, on every drive
     <library>/appmanifest_<appid>.acf     one per installed app
     <library>/common/<installdir>/        the files themselves

   READ ONLY, and deliberately so. Nothing here writes to a Steam directory or
   asks Steam anything; it reads three files Steam maintains for its own
   purposes. Whether Reliquary should ever write into a Steam library is a
   separate decision, and not one this namespace makes.

   Two things learned from a real machine rather than from the format's
   documentation, both encoded below:

   1. A manifest can claim `StateFlags 4`, fully installed, for a directory that
      does not exist. Remnant II did exactly that here. So the directory is
      checked, every time; the manifest alone is not evidence.

   2. `LastOwner` in every manifest is the owning account's steamID64. It is
      never put in the map this namespace returns -- these maps get rendered
      into a UI, printed by tests and pasted into bug reports."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.steam.kv :as kv])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; where Steam might be

(defn candidate-roots
  "Directories Steam may be installed in, for the given environment.

   `env` is `{:os :home :program-files-x86}` rather than read from the JVM
   directly, so both platforms are reachable from a test on either one.

   Every plausible location, existing or not -- `libraries` filters. Listing a
   path that is absent costs one `.isDirectory` call; missing one costs the user
   a library the app then claims they do not have."
  [{:keys [os home program-files-x86]}]
  (let [win? (str/includes? (str/lower-case (or os "")) "win")]
    (if win?
      (->> [(when program-files-x86 (str program-files-x86 "\\Steam"))
            "C:\\Program Files (x86)\\Steam"
            "C:\\Program Files\\Steam"
            (when home (str home "\\scoop\\apps\\steam\\current"))]
           (filter some?)
           (distinct)
           (vec))
      (->> [(when home (str home "/.local/share/Steam"))
            ;; usually symlinks to the above; `libraries` canonicalises, so
            ;; listing them costs nothing and covers the layouts where they
            ;; are not links
            (when home (str home "/.steam/steam"))
            (when home (str home "/.steam/root"))
            ;; the Flatpak build keeps everything under its own sandbox
            (when home (str home "/.var/app/com.valvesoftware.Steam/.local/share/Steam"))
            "/usr/share/steam"]
           (filter some?)
           (distinct)
           (vec)))))

(defn default-roots
  "`candidate-roots` for the JVM we are actually running in."
  []
  (candidate-roots {:os (System/getProperty "os.name")
                    :home (System/getProperty "user.home")
                    :program-files-x86 (System/getenv "ProgramFiles(x86)")}))

;; ---------------------------------------------------------------------------
;; libraries

(defn- read-kv
  "Parse a text-VDF file, or nil if it is absent or unreadable.

   nil rather than a throw: one corrupt file among twenty must not cost the user
   the other nineteen, and these files belong to another application that is
   very likely writing them while we read."
  [^File f]
  (when (and f (.isFile f))
    (try (kv/parse-text (slurp f)) (catch Exception _ nil))))

(defn- library-paths-from-vdf
  "The `path` of every entry in a parsed libraryfolders.vdf.

   Valve has used two shapes here over the years -- numbered entries whose value
   is a map with a `path`, and older versions where the value was the path
   string itself. Both are accepted, because a user's Steam may predate either."
  [kv]
  (when-let [folders (get kv "libraryfolders")]
    (keep (fn [[k v]]
            (when (re-matches #"\d+" (str k))
              (cond (map? v) (get v "path")
                    (string? v) v)))
          folders)))

(defn libraries
  "Every Steam library's `steamapps` directory reachable from `roots`.

   Deduplicated by CANONICAL path. On Linux ~/.steam/steam, ~/.steam/root and
   ~/.local/share/Steam are commonly three symlinks to one directory; without
   this the same install is reported three times, and the user is offered three
   identical rows."
  ([] (libraries (default-roots)))
  ([roots]
   (let [;; a root's own steamapps counts even when libraryfolders.vdf is absent
         ;; or unwritten, which is the case on a fresh install
         from-roots (map #(io/file (io/as-file %) "steamapps") roots)
         from-vdf   (mapcat (fn [root]
                              (->> (read-kv (io/file (io/as-file root) "steamapps" "libraryfolders.vdf"))
                                   (library-paths-from-vdf)
                                   (map #(io/file % "steamapps"))))
                            roots)]
     (->> (concat from-roots from-vdf)
          (filter #(.isDirectory ^File %))
          (reduce (fn [{:keys [seen out]} ^File d]
                    (let [c (.getCanonicalPath d)]
                      (if (contains? seen c)
                        {:seen seen :out out}
                        {:seen (conj seen c) :out (conj out d)})))
                  {:seen #{} :out []})
          :out))))

;; ---------------------------------------------------------------------------
;; installs

(def ^:private state-fully-installed
  "StateFlags is a bitfield; 4 is StateFullyInstalled. 6 -- 4|2, installed and
   update-required -- is ordinary on a real machine, so the bit is tested rather
   than the whole value compared."
  4)

(def ^:private state-update-required 2)

(defn- ->long [v] (try (Long/parseLong (str v)) (catch Exception _ nil)))

(defn- installed-manifests
  "{depot-id manifest-gid} from an AppState's InstalledDepots.

   A manifest gid is a uint64 and stays a STRING; parsing it to a long would
   round the large ones, and it is only ever compared and sent back to Steam."
  [app-state]
  (into {}
        (keep (fn [[depot v]]
                (let [id (->long depot)
                      gid (when (map? v) (get v "manifest"))]
                  (when (and id (string? gid) (seq gid))
                    [id gid]))))
        (get app-state "InstalledDepots")))

(defn- ->install
  "One appmanifest as an install, or nil if it does not describe usable files.

   nil for two distinct reasons, and both matter: StateFlags without the
   fully-installed bit means Steam does not consider these files complete, and a
   missing directory means they are not there at all whatever the manifest says.

   Note what is NOT in the returned map: no LastOwner. See the namespace
   docstring."
  [^File library app-state]
  (let [appid      (->long (get app-state "appid"))
        installdir (get app-state "installdir")
        flags      (or (->long (get app-state "StateFlags")) 0)]
    (when (and appid (seq installdir)
               (pos? (bit-and flags state-fully-installed)))
      (let [dir (io/file library "common" installdir)]
        (when (.isDirectory dir)
          {:appid            appid
           :name             (get app-state "name")
           ;; a build id is a uint64 and half the catalog's historical versions
           ;; have none, so it is a string and never the thing matched on
           :build            (str (or (get app-state "buildid") ""))
           :bytes            (or (->long (get app-state "SizeOnDisk")) 0)
           :path             (.getPath dir)
           :library          (.getPath library)
           :manifests        (installed-manifests app-state)
           :fully-installed? true
           :update-required? (pos? (bit-and flags state-update-required))})))))

(defn- apps-in-libraries
  "Every usable install in the given `steamapps` directories.

   Skips whatever it cannot read: these files belong to another application, and
   one it may be rewriting as we look."
  [libs]
  (into []
        (comp (mapcat (fn [^File lib]
                        (->> (.listFiles lib)
                             (filter #(re-matches #"appmanifest_\d+\.acf" (.getName ^File %)))
                             (keep (fn [f]
                                     (some-> (read-kv f)
                                             (get "AppState")
                                             (->> (->install lib))))))))
              (filter some?))
        libs))

(defn installed-apps
  "Every usable install Steam has under `roots`.

   ROOTS, not libraries -- the same thing `candidate-roots` returns, and the only
   thing a caller ever has to hand. Taking libraries here read fine and misled
   every caller into passing roots to it, which then found nothing at all,
   because a root holds `steamapps/` and a library IS that directory. Resolving
   roots to libraries is this namespace's job, not its callers'."
  ([] (installed-apps (default-roots)))
  ([roots] (apps-in-libraries (libraries roots))))

(defn find-install
  "The install for `appid` under `roots`, or nil.

   The first match wins: an app can only be installed once per library, and a
   user with the same appid in two libraries has a Steam problem this namespace
   is not going to resolve for them."
  ([appid] (find-install (default-roots) appid))
  ([roots appid]
   (first (filter #(= (long appid) (:appid %)) (installed-apps roots)))))

;; ---------------------------------------------------------------------------
;; which version is on disk

(defn installed-version
  "The catalog version whose depots match `install`'s manifests, or nil.

   Matched on MANIFEST IDS, not the build id. A build id is Valve's ordinal for
   a build, and most of the catalog's historical entries have none at all --
   whereas a manifest id names the exact bytes of one depot, which is precisely
   the question being asked. Verified against a real machine: Skyrim SE's
   InstalledDepots matched the catalog's Latest exactly, gid for gid.

   Every depot the version names must match. A partial match is a half-switched
   install, not that version, and calling it that would tell the user they have
   something they do not. nil is the honest answer for a build the catalog does
   not carry, which is the normal case right after Steam updates a game."
  [game install]
  (let [have (:manifests install)]
    (when (seq have)
      (first
       (filter (fn [v]
                 (let [want (:depots v)]
                   (and (seq want)
                        (every? (fn [{:keys [depot-id manifest-gid]}]
                                  (= manifest-gid (get have depot-id)))
                                want))))
               (:versions game))))))
