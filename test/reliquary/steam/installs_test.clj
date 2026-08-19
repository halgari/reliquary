;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.steam.installs-test
  "Fixtures are written to temp directories rather than committed, for two
   reasons. A real appmanifest carries `LastOwner` -- the owning account's
   steamID64 -- and that is not something to commit to a public repo. And this
   namespace's whole job is reading a directory tree, so the tests need real
   directories anyway; a committed .acf would still have to be copied somewhere
   to exercise the paths that matter."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.installs :as installs])
  (:import (java.io File)
           (java.nio.file Files FileVisitOption)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "reliquary-installs" (make-array FileAttribute 0))))

(defn- rm-rf
  "Delete a temp tree WITHOUT following symlinks.

   `file-seq` follows them, so the symlink test's cleanup walked back through
   the alias into the very tree it was deleting and then failed on a path it had
   already removed. `Files/walk` does not follow links by default, so the link
   itself is deleted as a leaf."
  [^File d]
  (when (.exists d)
    (with-open [s (Files/walk (.toPath d) (make-array FileVisitOption 0))]
      (doseq [p (reverse (vec (.toArray (.toList s))))]
        (Files/deleteIfExists ^java.nio.file.Path p)))))

;; Real shapes, taken from an actual Steam install and then scrubbed. The tabs
;; and quoting are Valve's, because that is what the parser has to survive.

(defn- libraryfolders [paths]
  (str "\"libraryfolders\"\n{\n"
       (str/join
        (map-indexed
         (fn [i p]
           (str "\t\"" i "\"\n\t{\n"
                "\t\t\"path\"\t\t\"" p "\"\n"
                "\t\t\"label\"\t\t\"\"\n"
                "\t\t\"totalsize\"\t\t\"0\"\n"
                "\t\t\"apps\"\n\t\t{\n\t\t\t\"489830\"\t\t\"16095731388\"\n\t\t}\n"
                "\t}\n"))
         paths))
       "}\n"))

(defn- appmanifest
  [{:keys [appid name installdir buildid state-flags depots bytes]
    :or   {state-flags 4 bytes 16095731388}}]
  (str "\"AppState\"\n{\n"
       "\t\"appid\"\t\t\"" appid "\"\n"
       "\t\"universe\"\t\t\"1\"\n"
       "\t\"name\"\t\t\"" name "\"\n"
       "\t\"StateFlags\"\t\t\"" state-flags "\"\n"
       "\t\"installdir\"\t\t\"" installdir "\"\n"
       "\t\"LastUpdated\"\t\t\"1782516085\"\n"
       "\t\"SizeOnDisk\"\t\t\"" bytes "\"\n"
       "\t\"buildid\"\t\t\"" buildid "\"\n"
       ;; present in the real file, and deliberately never surfaced
       "\t\"LastOwner\"\t\t\"76561190000000000\"\n"
       "\t\"InstalledDepots\"\n\t{\n"
       (str/join (map (fn [[d gid]]
                        (str "\t\t\"" d "\"\n\t\t{\n"
                             "\t\t\t\"manifest\"\t\t\"" gid "\"\n"
                             "\t\t\t\"size\"\t\t\"123\"\n\t\t}\n"))
                      depots))
       "\t}\n}\n"))

(defn- make-library!
  "A steamapps tree under `root`: the manifests given, and a common/ directory
   for each unless :skip-dir? says otherwise."
  [root apps]
  (let [steamapps (io/file root "steamapps")]
    (.mkdirs (io/file steamapps "common"))
    (doseq [{:keys [appid installdir skip-dir?] :as app} apps]
      (spit (io/file steamapps (str "appmanifest_" appid ".acf")) (appmanifest app))
      (when-not skip-dir?
        (.mkdirs (io/file steamapps "common" installdir))))
    steamapps))

(def ^:private skyrim
  {:appid 489830 :name "The Elder Scrolls V: Skyrim Special Edition"
   :installdir "Skyrim Special Edition" :buildid "13189953"
   :depots {489831 "8442952117333549665"
            489832 "8042843504692938467"
            489833 "1914580699073641964"}})

;; ---------------------------------------------------------------------------
;; libraries

(deftest a-library-list-comes-from-libraryfolders-vdf
  (let [root (tmp-dir)]
    (try
      (let [other (io/file root "other-drive")]
        (.mkdirs other)
        (make-library! root [skyrim])
        (make-library! other [])
        (spit (io/file root "steamapps" "libraryfolders.vdf")
              (libraryfolders [(.getPath root) (.getPath other)]))
        (let [libs (mapv #(.getPath ^java.io.File %) (installs/libraries [root]))]
          (is (= 2 (count libs)) "both libraries must be found, not just the root one")
          (is (every? #(str/ends-with? % "steamapps") libs)
              "a library is its steamapps directory -- that is where manifests live")))
      (finally (rm-rf root)))))

(deftest a-root-with-no-libraryfolders-still-offers-its-own-steamapps
  (testing "a fresh install, or one whose libraryfolders.vdf has not been written
            yet, still has games under the root's own steamapps"
    (let [root (tmp-dir)]
      (try
        (make-library! root [skyrim])
        (is (= 1 (count (installs/libraries [root]))))
        (finally (rm-rf root))))))

(deftest libraries-are-deduplicated-by-canonical-path
  (testing "on Linux ~/.steam/steam, ~/.steam/root and ~/.local/share/Steam are
            commonly symlinks to ONE directory -- reporting the same install
            three times would put three identical rows in front of the user"
    (let [root (tmp-dir)]
      (try
        (make-library! root [skyrim])
        (let [link (io/file root "alias")]
          (Files/createSymbolicLink (.toPath link) (.toPath root)
                                    (make-array FileAttribute 0))
          (is (= 1 (count (installs/libraries [root link])))))
        (finally (rm-rf root))))))

;; ---------------------------------------------------------------------------
;; manifests

(deftest an-install-reports-its-path-build-and-manifests
  (let [root (tmp-dir)]
    (try
      (make-library! root [skyrim])
      (let [found (installs/find-install [root] 489830)]
        (is (some? found))
        (is (= 489830 (:appid found)))
        (is (= "The Elder Scrolls V: Skyrim Special Edition" (:name found)))
        (is (= "13189953" (:build found)) "a build id is a uint64: it stays a string")
        (is (= 16095731388 (:bytes found)))
        (is (true? (:fully-installed? found)))
        (is (= {489831 "8442952117333549665"
                489832 "8042843504692938467"
                489833 "1914580699073641964"}
               (:manifests found))
            "the per-depot manifest ids are what identify the installed VERSION")
        (is (str/ends-with? (:path found) "steamapps/common/Skyrim Special Edition")
            "the install path is common/<installdir> under the library"))
      (finally (rm-rf root)))))

(deftest a-manifest-whose-directory-is-gone-is-not-an-install
  (testing "observed on a real machine: Remnant II's appmanifest said StateFlags 4,
            fully installed, and steamapps/common held no such directory. Trusting
            the manifest alone would offer to change files that are not there."
    (let [root (tmp-dir)]
      (try
        (make-library! root [(assoc skyrim :skip-dir? true)])
        (is (nil? (installs/find-install [root] 489830))
            "no directory means no install, whatever the manifest claims")
        (finally (rm-rf root))))))

(deftest an-update-required-install-is-found-but-flagged
  (testing "StateFlags 6 is 4|2 -- fully installed AND update required, which is
            ordinary on a real machine. It is still an install, but a caller that
            is about to rewrite its files deserves to know Steam disagrees about
            its contents."
    (let [root (tmp-dir)]
      (try
        (make-library! root [(assoc skyrim :state-flags 6)])
        (let [found (installs/find-install [root] 489830)]
          (is (some? found))
          (is (true? (:fully-installed? found)))
          (is (true? (:update-required? found))))
        (finally (rm-rf root))))))

(deftest an-uninstalled-manifest-is-not-an-install
  (let [root (tmp-dir)]
    (try
      (make-library! root [(assoc skyrim :state-flags 1)])
      (is (nil? (installs/find-install [root] 489830))
          "StateFlags without the fully-installed bit is not something to modify")
      (finally (rm-rf root)))))

(deftest a-corrupt-manifest-is-skipped-not-fatal
  (testing "one unreadable file among twenty must not cost the user the other
            nineteen"
    (let [root (tmp-dir)]
      (try
        (let [steamapps (make-library! root [skyrim])]
          (spit (io/file steamapps "appmanifest_999999.acf") "\"AppState\" { broken")
          (is (some? (installs/find-install [root] 489830))
              "the good manifest must still be found"))
        (finally (rm-rf root))))))

(deftest the-owning-account-id-is-never-surfaced
  (testing "a real appmanifest carries LastOwner, the owner's steamID64. It has no
            business in a map that gets rendered, logged or pasted into an issue."
    (let [root (tmp-dir)]
      (try
        (make-library! root [skyrim])
        (let [found (installs/find-install [root] 489830)]
          (is (not (str/includes? (pr-str found) "76561190000000000"))
              "no steamID64 anywhere in the returned install"))
        (finally (rm-rf root))))))

;; ---------------------------------------------------------------------------
;; which catalog version is installed

(def ^:private game
  {:appid 489830
   :title "The Elder Scrolls V: Skyrim Special Edition"
   :versions [{:id "public" :label "Latest" :branch "public" :build "13189953"
               :depots [{:depot-id 489831 :manifest-gid "8442952117333549665"}
                        {:depot-id 489832 :manifest-gid "8042843504692938467"}
                        {:depot-id 489833 :manifest-gid "1914580699073641964"}]}
              {:id "1_6_1130" :label "1.6.1130" :branch "public" :build ""
               :depots [{:depot-id 489831 :manifest-gid "3737743381894105176"}
                        {:depot-id 489832 :manifest-gid "4341968404481569190"}
                        {:depot-id 489833 :manifest-gid "2442187225363891157"}]}]})

(deftest the-installed-version-is-matched-by-manifest-ids
  (testing "manifest ids, not the build id. A build id is Valve's ordinal for a
            build and half the catalog's historical entries have none at all --
            the manifests are what actually say which bytes are on disk. Checked
            against a real install: Skyrim SE's InstalledDepots matched the
            catalog's Latest exactly."
    (let [install {:appid 489830 :build "13189953"
                   :manifests {489831 "8442952117333549665"
                               489832 "8042843504692938467"
                               489833 "1914580699073641964"}}]
      (is (= "public" (:id (installs/installed-version game install)))))))

(deftest an-older-installed-version-is-recognised-too
  (let [install {:appid 489830 :build ""
                 :manifests {489831 "3737743381894105176"
                             489832 "4341968404481569190"
                             489833 "2442187225363891157"}}]
    (is (= "1_6_1130" (:id (installs/installed-version game install))))))

(deftest an-install-matching-no-catalog-version-is-nil-not-a-guess
  (testing "a build the catalog does not carry is the normal case for a game
            Steam has just updated. Saying 'unknown' is honest; picking the
            closest version would tell the user they have something they do not."
    (let [install {:appid 489830 :build "99999999"
                   :manifests {489831 "0000000000000000001"}}]
      (is (nil? (installs/installed-version game install))))))

(deftest a-partial-manifest-match-does-not-count
  (testing "every depot the version names has to match. One matching depot out of
            three is a half-switched install, not that version."
    (let [install {:appid 489830
                   :manifests {489831 "8442952117333549665"}}]
      (is (nil? (installs/installed-version game install))))))

;; ---------------------------------------------------------------------------
;; the real machine

(deftest candidate-roots-cover-both-platforms
  (testing "Windows first, since that is what ships"
    (let [win (installs/candidate-roots
               {:os "Windows 11" :home "C:\\Users\\me"
                :program-files-x86 "C:\\Program Files (x86)"})]
      (is (seq win))
      (is (some #(str/includes? % "Steam") win))))
  (testing "and Linux, where the paths are several and usually symlinked together"
    (let [lin (installs/candidate-roots {:os "Linux" :home "/home/me"})]
      (is (some #(= "/home/me/.local/share/Steam" %) lin))
      (is (some #(= "/home/me/.steam/steam" %) lin))
      (is (some #(str/includes? % "com.valvesoftware.Steam") lin)
          "the Flatpak Steam keeps its library somewhere else entirely"))))
