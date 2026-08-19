;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns exe-hashes
  "Resolve each catalog version's executable hashes, once.

   Which version an install IS is decided by hashing its executable and looking
   the hash up -- not by asking Steam, whose appmanifest is bookkeeping that goes
   stale the moment files are swapped by hand and says nothing at all after a
   half-applied switch.

   For that lookup to be free, the hashes have to be in the catalog. They can be:
   a depot manifest is immutable, so a version's executable hash never changes.
   One authenticated fetch per depot, ONCE, and every installation afterwards
   settles which version it has by reading two files -- about 40 ms on a real
   Skyrim install -- with no session, no manifest fetch and no network.

   Same shape as resolve-sizes: run by hand, write the answer back into the
   per-domain JSON, and let assemble.py fold it into resources/catalog.edn.

   A manifest's `sha-content` IS the sha1 of the whole file as it sits on disk.
   That was verified against a real install rather than assumed -- both Skyrim
   executables matched digit for digit -- and everything here rests on it.

   Run:  clojure -M:catalog-tool -m exe-hashes skyrimspecialedition:489830 ..."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.session :as session]
            [reliquary.steam.cm.content :as content]
            [reliquary.steam.manifest :as manifest]))

(def ^:private tool-dir (str (io/file (System/getProperty "user.dir") "tool" "catalog")))

(defn- exe? [name] (str/ends-with? (str/lower-case (str name)) ".exe"))

(defn- executables-of
  "{path sha} for every executable across this version's depots.

   The filename key is :name, not :path -- reading :path returns nil for every
   entry without throwing and quietly reports that a 16 GB game contains no
   executables, which is exactly the debugging cycle it cost once already."
  [conn hosts appid version]
  (into {}
        (for [{:keys [depot-id manifest-gid]} (:depots version)
              :let [key-hex (content/depot-key conn appid depot-id)
                    code    (content/manifest-request-code conn appid depot-id
                                                           manifest-gid (:branch version))
                    m       (manifest/parse (manifest/fetch hosts depot-id manifest-gid code)
                                            key-hex)]
              f (:files m)
              :when (and (exe? (:name f)) (seq (:sha-content f)))]
          [(:name f) (:sha-content f)])))

(defn- update-file!
  "Add `:executables` to every version in one versions JSON file."
  [path conn hosts appid]
  (when (.isFile (io/file path))
    (let [doc (json/read-str (slurp path) :key-fn keyword)
          versions
          (mapv (fn [v]
                  (let [exes (try (executables-of conn hosts appid v)
                                  (catch Exception e
                                    (println "   " (:id v) "FAILED" (ex-message e))
                                    nil))]
                    (println (format "    %-14s %s" (:id v)
                                     (if (seq exes)
                                       (str/join ", " (map (fn [[p _]] p) exes))
                                       "(none)")))
                    (cond-> v (seq exes) (assoc :executables exes))))
                (:versions doc))]
      (spit path (json/write-str (assoc doc :versions versions) :indent true))
      (count (filter :executables versions)))))

(defn -main [& args]
  (when (empty? args)
    (println "usage: clojure -M:catalog-tool -m exe-hashes <domain>:<appid> ...")
    (System/exit 1))
  (let [s (session/open!)]
    (try
      (let [conn  (:conn s)
            hosts (content/cdn-servers conn)]
        (doseq [arg args]
          (let [[domain appid-str] (str/split arg #":")
                appid (Long/parseLong appid-str)]
            (println domain appid)
            (doseq [src ["versions" "versions-historical"]]
              (let [p (str tool-dir "/" src "/" domain ".json")]
                (when (.isFile (io/file p))
                  (println "  " src)
                  (update-file! p conn hosts appid)))))))
      (finally (session/close! s))))
  (System/exit 0))
