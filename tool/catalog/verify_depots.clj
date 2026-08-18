;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns verify-depots
  "Find the depots in the catalog that are not actually part of the game, by
   asking Steam for their decryption keys and recording which ones it refuses.

   PICS marks most non-game depots plainly and `fetch_versions.py` already
   drops those: real DLC carries `dlcappid` + `optional`, shared
   redistributables carry `depotfromapp` + `sharedinstall`, localizations
   carry `config.language`. What is left over is a small set of depots that
   are listed in a game's depot table, carry no marker of any kind, and still
   belong to something the player did not buy:

     app 22320 depot 451410 -- The Elder Scrolls III: Morrowind Soundtrack,
       a separate `type=Music` application parented to the game. Detectable
       statically, since the depot id is also its appid.

     app 22380 depot 22493 -- a depot of app 22490, `Fallout: New Vegas
       PCR`, a separate SKU. NOT detectable statically: its PICS record is
       `{config: {language: english}, manifests: {...}}`, byte for byte the
       same shape as depots 22382, 72732 and 72742, which are ordinary base
       game content that works. Valve simply left it unmarked.

   Since one of the two cases has no static tell, no amount of reading PICS
   is enough and the only authority is Steam itself: request the key, and a
   refusal (EResult 15, AccessDenied) means this is not content the game's
   own license grants. Doing it HERE rather than at download time is what
   keeps a user from picking a version and watching it fail -- the answer is
   the same every time, so it belongs in the catalog.

   The account this runs under decides the answer, which is the one real
   limitation. An account that owns the Morrowind soundtrack would find 451410
   allowed and keep it in the catalog for everybody. Run it under a plain
   account that owns the games and none of their extras.

   An app whose depots are refused ENTIRELY is a different thing and is never
   recorded: it means the account does not own the game, and the probe has
   learned nothing about which of its depots are extras. Baldur's Gate 3
   returned AccessDenied on all four, and recording that would have deleted
   the game from the catalog for everyone. Refusals are only meaningful
   alongside at least one grant on the same app.

   Writes tool/catalog/license-denied-depots.json, which `fetch_versions.py`
   and `assemble.py` both read. The file persists, so a later regeneration
   does not need Steam to stay correct.

   Run:  clojure -M:catalog-tool -m verify-depots newvegas:22380 morrowind:22320 ..."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reliquary.session :as session]
            [reliquary.steam.cm.content :as content]))

(def ^:private denied-file "tool/catalog/license-denied-depots.json")

(defn depot-ids
  "Every depot id this domain's versions reference, from BOTH sources.

   versions-historical/ matters as much as versions/: a historical build
   names the same depots, so a depot refused today is refused for those too."
  [domain]
  (into (sorted-set)
        (for [src   ["versions" "versions-historical"]
              :let  [f (io/file (str "tool/catalog/" src "/" domain ".json"))]
              :when (.isFile f)
              v     (:versions (json/read-str (slurp f) :key-fn keyword))
              d     (:depots v)]
          (long (:depot-id d)))))

(def ^:private access-denied
  "EResult 15, AccessDenied -- the ONE answer that means the game's licence does
   not cover this depot."
  15)

(defn- probe
  "nil if Steam grants the key, or the EResult if it refuses on licence grounds.

   Only a refusal is recorded. A network blip or a dead connection would
   otherwise be written into the catalog as `this depot is not part of the
   game`, which is a lie that persists until someone re-runs this by hand --
   so anything that is not a categorized refusal is rethrown.

   The gate is on 15 SPECIFICALLY, and that is the whole point:
   `content/depot-key` raises with :eresult for EVERY non-1 result, so testing
   only for the key's PRESENCE accepted 20 ServiceUnavailable, 21 NotLoggedOn,
   16 Timeout and 84 RateLimitExceeded as licence answers too. A session that
   dropped or got rate-limited partway through a sweep therefore recorded every
   remaining real game depot as unowned -- and since `fetch_versions.py` and
   `assemble.py` both read that file from then on, the catalog shipped versions
   missing base game content, with nothing failing until a user downloaded one."
  [conn app-id depot-id]
  (try
    (content/depot-key conn app-id depot-id)
    nil
    (catch clojure.lang.ExceptionInfo e
      (if (= access-denied (:eresult (ex-data e)))
        access-denied
        (throw e)))))

(defn -main [& args]
  (let [s   (session/open!)
        out (io/file denied-file)
        cur (if (.isFile out) (json/read-str (slurp out)) {})]
    (try
      (let [result
            (reduce
             (fn [acc arg]
               (let [[domain appid] (str/split arg #":")
                     appid  (Long/parseLong appid)
                     ids    (depot-ids domain)
                     denied (vec (for [d ids
                                       :let [er (probe (:conn s) appid d)]
                                       :when er]
                                   d))
                     all?   (and (seq ids) (= (count denied) (count ids)))]
                 (doseq [d denied] (println (format "    x %d refused" d)))
                 (println (format "%-24s app %-8d %d depot(s), %d refused%s"
                                  domain appid (count ids) (count denied)
                                  (if all? "  -- GAME NOT OWNED, recording nothing" "")))
                 (cond-> acc
                   ;; Refusals only mean "not part of the game" when something
                   ;; else on the same app was granted. All-refused means the
                   ;; account does not own the game and this run knows nothing.
                   (and (seq denied) (not all?)) (assoc (str appid) denied))))
             cur args)]
        ;; with-open, not a bare io/writer: an unflushed writer silently
        ;; truncates the file to nothing, which is how two version files were
        ;; lost the first time resolve_sizes ran.
        (with-open [w (io/writer out)]
          (json/write result w :indent true))
        (println)
        (println "wrote" denied-file))
      (finally (session/close! s)))))
