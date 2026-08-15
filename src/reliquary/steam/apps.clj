;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.apps
  "PICS product info: which apps we own, and their app-info blobs.

   Three calls. Packages (binary KeyValues behind a 4-byte prefix) give the
   appids we own; access tokens unlock the app blobs; the app blobs (text VDF)
   give names, types and depots.

   Every product-info call uses send-job-collect! -- Steam splits a large
   response across parts and flags every non-final one with response_pending,
   so taking the first part silently loses depots."
  (:require [clojure.string :as str]
            [reliquary.error :as error]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.kv :as kv])
  (:import (java.util Arrays Base64)))

(defn- b64->bytes ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn- parse-package
  "A package buffer: a 4-byte prefix, then binary KeyValues. One root key (the
   package id), so callers take the single value."
  [^String b64]
  (let [b (b64->bytes b64)]
    (when (< (alength b) 5)
      (error/raise :incorrect "pics package buffer shorter than its prefix"))
    (kv/parse (Arrays/copyOfRange b 4 (alength b)))))

(defn- parse-app
  "An app buffer: text VDF, unlike packages."
  [^String b64]
  (kv/parse-text (String. (b64->bytes b64) "UTF-8")))

(defn- product-info
  "One PICS product-info request, collected across all its parts."
  [c req-map]
  (conn/join
   (conn/send-job-collect! c :pics-product-request
                           "CMsgClientPICSProductInfoRequest"
                           req-map
                           "CMsgClientPICSProductInfoResponse"
                           (complement :response-pending))))

(defn- access-tokens
  "{appid -> token} for the given appids."
  [c appids]
  (let [r (conn/join
           (conn/send-job! c :pics-token-request
                           "CMsgClientPICSAccessTokenRequest"
                           {:appids appids}
                           "CMsgClientPICSAccessTokenResponse"))]
    (into {} (map (juxt :id :access-token)) (:app-access-tokens r))))

(defn app-info
  "The full app-info map for one appid, e.g. {\"appinfo\" {...}}.

   `appid` is NOT primitive-hinted, even though it is always a long in
   practice: a ^long arg makes this fn's compiled class implement
   clojure.lang.IFn$OLO, and a call site compiled against that shape then
   invokes any with-redefs replacement through invokePrim too -- a plain
   test-double fn (as mauvi.ops.steam-test installs) does not implement that
   interface and the call throws ClassCastException instead of running the
   stub. Verified empirically against this exact function."
  [c appid]
  (let [token (get (access-tokens c [appid]) appid 0)
        app   (first (mapcat :apps (product-info c {:apps [{:appid appid :access-token token}]})))]
    (if app
      (parse-app (:buffer app))
      (error/raise :incorrect "steam returned no product info for this app" {:appid appid}))))

(defn owned-apps
  "{:apps [{:appid :name}] :skipped n} for the games these licenses grant.

   A corrupt blob is SKIPPED, not fatal -- one unparseable package must not
   blank a whole library -- but the count comes back so a caller can tell that
   the answer is incomplete."
  [c licenses]
  (if (empty? licenses)
    {:apps [] :skipped 0}
    (let [skipped  (volatile! 0)
          pkgs     (mapcat :packages
                           (product-info c {:packages (mapv #(hash-map :packageid    (:package-id %)
                                                                       :access-token (:access-token %))
                                                            licenses)}))
          appids   (into []
                         (comp (mapcat (fn [pkg]
                                         (try
                                           (vals (get (first (vals (parse-package (:buffer pkg)))) "appids"))
                                           (catch Exception _ (vswap! skipped inc) nil))))
                               (distinct))
                         pkgs)]
      (if (empty? appids)
        {:apps [] :skipped @skipped}
        (let [tokens (access-tokens c appids)
              rows   (mapcat :apps
                             (product-info c {:apps (mapv #(hash-map :appid % :access-token (get tokens % 0))
                                                          appids)}))
              games  (into []
                           (keep (fn [app]
                                   (try
                                     (let [common (get (first (vals (parse-app (:buffer app)))) "common")
                                           nm     (get common "name")]
                                       ;; PICS capitalizes this ("Game", not "game") -- confirmed
                                       ;; against the captured fixture, not assumed.
                                       (when (and (= "game" (str/lower-case (or (get common "type") "")))
                                                  (seq nm))
                                         {:appid (:appid app) :name nm}))
                                     (catch Exception _ (vswap! skipped inc) nil))))
                           rows)]
          {:apps games :skipped @skipped})))))
