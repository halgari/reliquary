;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.client
  "The CM logon handshake: discover a server, connect, log on with the refresh
  token, wait for the pushed license list, and keep the connection alive with a
  heartbeat. Ported from the original cm/client.cljs; the async waits become
  CompletableFutures completed by the connection's receive thread."
  (:require [reliquary.steam.cm.discovery :as disc]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.crypto :as crypto]
            [reliquary.error :as error])
  (:import (java.util.concurrent CompletableFuture TimeUnit TimeoutException)))

;; Steam client protocol version sent in CMsgClientLogon.
(def ^:private ^:const protocol-version 65580)
;; client_os_type 16 = Linux (matches the original).
(def ^:private ^:const os-linux 16)
;; how many CM servers to try before giving up.
(def ^:private ^:const max-server-attempts 3)

(def ^:dynamic *logon-timeout-ms*
  "How long logon-on! waits for CMsgClientLogonResponse before raising
  :unavailable. A var so tests don't have to eat the real 15s wait."
  15000)

(def ^:dynamic *license-timeout-ms*
  "How long logon-on! waits for the license list, which Steam pushes unsolicited
   right after the logon response. An account with no licenses never gets one,
   so this wait must expire rather than hang. Dynamic so tests need not pay it."
  8000)

(defn licenses [c] (or @(:licenses c) []))

(defn- start-heartbeat!
  "Start the keepalive and RETURN the thread, so session/close! can stop it. A
   daemon cannot hold the JVM open, but a long-lived process (mount start) would
   otherwise accumulate one thread per session, each lingering until its next
   send happened to fail. The interrupt lands in Thread/sleep, so the catch has
   to wrap the whole loop, not just the send."
  ^Thread [c secs]
  (doto (Thread.
         ^Runnable (fn []
                     (try
                       (loop []
                         (Thread/sleep (* 1000 (max 1 (long secs))))
                         (when (try (conn/send! c :heartbeat "CMsgClientHeartBeat" {}) true
                                    (catch InterruptedException e (throw e))
                                    (catch Throwable _ false))
                           (recur)))
                       (catch InterruptedException _ nil)))
         "steam-cm-heartbeat")
    (.setDaemon true)
    (.start)))

(defn logon-on!
  "Log on over an already-open connection. Separated from logon! so the whole
   handshake is testable against an injected transport."
  [c refresh-token account]
  (let [steamid (:sub (crypto/jwt-claims refresh-token))
        lic-ready (CompletableFuture.)
        logon-cf (CompletableFuture.)]
    (reset! (:licenses c) [])
    ;; ClientLicenseList is PUSHED right after the logon response, and
    ;; ClientLogOnResponse is NOT job-correlated -- Steam leaves jobid_target
    ;; unset on both -- so each is awaited via an unsolicited EMsg handler.
    (conn/on-emsg! c :license-list "CMsgClientLicenseList"
                   (fn [m]
                     (reset! (:licenses c)
                             (mapv #(select-keys % [:package-id :access-token])
                                   (:licenses m)))
                     (.complete lic-ready true)))
    (conn/on-emsg! c :logon-response "CMsgClientLogonResponse" #(.complete logon-cf %))
    (conn/set-session! c {:steamid steamid :session-id 0})
    (conn/send! c :logon "CMsgClientLogon"
                {:protocol-version protocol-version
                 :account-name (or account "")
                 :access-token refresh-token
                 :client-language "english"
                 :client-os-type os-linux
                 :supports-rate-limit-response true})
    (let [resp (try (.get logon-cf (long *logon-timeout-ms*) TimeUnit/MILLISECONDS)
                    (catch TimeoutException _
                      (error/raise :unavailable "steam logon timed out")))
          eresult (:eresult resp)]
      (when (not= 1 eresult)
        ;; the token is the thing Steam rejected, so this is :unauthenticated,
        ;; not :unavailable -- the user must log in again, not retry.
        (error/raise :unauthenticated
                     (str "steam logon failed, eresult " eresult) {:eresult eresult}))
      (conn/set-session! c {:steamid (or (:client-supplied-steamid resp) steamid)})
      ;; don't hang if the account genuinely has no licenses
      (try (.get lic-ready (long *license-timeout-ms*) TimeUnit/MILLISECONDS) (catch Exception _ nil))
      ;; the heartbeat thread rides along on the session map -- session/close!
      ;; needs a handle on it or it cannot be stopped
      {:conn c
       :steamid (or (:client-supplied-steamid resp) steamid)
       :heartbeat (start-heartbeat! c (or (:heartbeat-seconds resp) 9))})))

(defn logon!
  "Discover CM servers, connect to one, and log on. Tries servers in turn --
   the reference used only the first, so one unreachable CM failed the login."
  [refresh-token account]
  (let [servers (disc/cm-servers)]
    (when (empty? servers)
      (error/raise :unavailable "steam returned no CM servers"))
    (loop [[url & more] (take max-server-attempts servers)
           failures []]
      (if (nil? url)
        (error/raise :unavailable
                     (str "could not reach any steam CM server (tried "
                          (count failures) ")")
                     {:failures failures})
        (let [c (try (conn/connect! url) (catch Exception e {:failed (ex-message e)}))]
          (if (:failed c)
            (recur more (conj failures url))
            ;; recur can't appear inside a catch clause (it can't cross a
            ;; try/catch boundary), so the retry decision is captured here and
            ;; the recur itself happens after the try, back at loop tail.
            (let [outcome (try
                            {:ok (logon-on! c refresh-token account)}
                            ;; Throwable, not ExceptionInfo: conn/send! bottoms
                            ;; out in (.get cf 20 SECONDS), which throws a raw
                            ;; ExecutionException / TimeoutException /
                            ;; InterruptedException when the socket dies
                            ;; mid-logon. Catching only ExceptionInfo leaked the
                            ;; WebSocket and its scheduler thread AND skipped
                            ;; the fallback this loop exists for.
                            (catch Throwable t
                              (try (conn/close! c) (catch Throwable _ nil))
                              ;; a rejected TOKEN will be rejected by every
                              ;; server -- only a transport failure is worth
                              ;; another host.
                              (if (and (instance? clojure.lang.ExceptionInfo t)
                                       (= :unauthenticated (:reliquary/error (ex-data t))))
                                (throw t)
                                {:retry true})))]
              (if (:retry outcome)
                (recur more (conj failures url))
                (:ok outcome)))))))))
