;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns resolve-sizes
  "Fill in the install size of catalog versions that do not know their own.

   Versions recovered from community downgrade guides arrive with a manifest
   GID and nothing else -- those guides publish `download_depot` commands, not
   byte counts. That left the UI rendering `size unknown`, which is honest but
   useless: you cannot tell whether a version is a 3 GB download or a 60 GB one
   at the moment you choose it.

   Steam knows. A depot manifest lists every file and its size, so summing the
   manifest's file table gives the exact uncompressed install size. That costs
   one authenticated fetch per depot, ONCE, and the answer never changes -- a
   manifest is immutable. So this runs by hand, writes the number back into
   tool/catalog/versions-historical/<domain>.json, and the app never pays for
   it again.

   Sizes resolved this way are also MORE accurate than the ones PICS gives for
   current builds: PICS reports every Windows depot including all eleven
   language packs, so Skyrim SE's current build reads 27.7 GB against a real
   English install nearer 12.7 GB. A version resolved here sums only the depots
   the catalog actually names.

   Run:  clojure -M:catalog-tool -m resolve-sizes skyrimspecialedition:489830 ..."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [reliquary.session :as session]
            [reliquary.steam.cm.content :as content]
            [reliquary.steam.manifest :as manifest]))

(defn depot-size
  "Uncompressed bytes of one depot manifest.

   `chunk-table` needs no depot key -- only filenames are ciphertext, sizes are
   plaintext -- but the KEY is still required to get here: Steam will not issue
   a manifest request code for a depot this account cannot decrypt."
  [conn hosts app-id {:keys [depot-id manifest-gid]} branch]
  (content/depot-key conn app-id depot-id)   ; proves entitlement; value unused
  (let [code (content/manifest-request-code conn app-id depot-id manifest-gid branch)
        blob (manifest/fetch hosts depot-id manifest-gid code)]
    (reduce + 0 (map :size (vals (manifest/chunk-table blob))))))

(defn resolve-version!
  "The install size of one version, or nil if Steam refuses any of its depots."
  [conn hosts app-id version]
  (try
    (reduce + 0 (map #(depot-size conn hosts app-id % (or (:branch version) "public"))
                     (:depots version)))
    (catch clojure.lang.ExceptionInfo e
      (println (format "    ! %s: %s" (:id version) (ex-message e)))
      nil)))

(defn -main [& args]
  (let [s (session/open!)]
    (try
      (let [hosts (content/cdn-servers (:conn s))]
        (doseq [arg args]
          (let [[domain appid] (clojure.string/split arg #":")
                appid (Long/parseLong appid)
                path  (str "tool/catalog/versions-historical/" domain ".json")
                f     (io/file path)]
            (if-not (.isFile f)
              (println domain "-- no historical versions file, skipping")
              (let [doc (json/read-str (slurp f) :key-fn keyword)
                    _   (println domain "(" appid ")")
                    vs  (mapv (fn [v]
                                (if (pos? (long (or (:bytes v) 0)))
                                  (do (println (format "    = %s already %.2f GB"
                                                       (:id v) (/ (:bytes v) 1073741824.0)))
                                      v)
                                  (if-let [b (resolve-version! (:conn s) hosts appid v)]
                                    (do (println (format "    + %s -> %.2f GB (%d bytes)"
                                                         (:id v) (/ b 1073741824.0) b))
                                        (assoc v :bytes b))
                                    v)))
                              (:versions doc))]
                ;; with-open, not a bare io/writer: an unflushed writer
                ;; silently truncates the file to nothing, which is exactly
                ;; what happened the first time this ran.
                (with-open [w (io/writer f)]
                  (json/write (assoc doc :versions vs) w :indent true))
                (println "  wrote" path))))))
      (finally (session/close! s)))))
