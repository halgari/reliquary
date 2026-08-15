;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.session
  "A logged-on Steam CM session, opened from the stored refresh token.

   mauvi's version of this namespace read the token out of a RocksDB-backed
   store. Reliquary has no store, so this reads reliquary.config instead and is
   otherwise the same shape: the caller owns the session's lifetime."
  (:require [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.steam.apps :as apps]
            [reliquary.steam.cm.client :as client]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.crypto :as crypto]))

(defn expired?
  "Is this refresh token past its expiry at `now-secs`?

   A token we cannot parse counts as expired. The alternative is presenting an
   unreadable credential to Steam and reporting its refusal as a network fault."
  [refresh-token now-secs]
  (try
    (let [{:keys [exp]} (crypto/jwt-claims refresh-token)]
      (or (nil? exp) (<= (long exp) (long now-secs))))
    (catch Exception _ true)))

(defn open!
  "Log on with the stored token. Raises :unauthenticated when there is no
   usable one -- the UI turns that into the login screen.

   The raised message names neither the token nor any part of it."
  []
  (let [{:keys [refresh-token account]} (config/token)]
    (when-not refresh-token
      (error/raise :unauthenticated "not signed in to steam"))
    (when (expired? refresh-token (quot (System/currentTimeMillis) 1000))
      (error/raise :unauthenticated "the stored steam session has expired"))
    (let [{:keys [conn steamid heartbeat]} (client/logon! refresh-token account)]
      {:conn conn :steamid steamid :account account :heartbeat heartbeat})))

(defn close!
  "Stop the heartbeat and drop the connection. The heartbeat is a daemon thread
   and cannot hold the process open, but a long-lived app must not accumulate
   one per session."
  [session]
  (when-let [^Thread hb (:heartbeat session)] (.interrupt hb))
  (when-let [c (:conn session)] (conn/close! c))
  nil)

(defn status [session]
  {:status   :online
   :steamid  (:steamid session)
   :account  (:account session)
   :licenses (count (client/licenses (:conn session)))})

(defn owned-appids
  "The set of appids this account licenses, for the library's ownership
   marking. A courtesy only -- the authority is Steam's answer to the
   depot-key request, which the download engine still handles."
  [session]
  (let [c (:conn session)]
    (into #{} (map :appid) (:apps (apps/owned-apps c (client/licenses c))))))
