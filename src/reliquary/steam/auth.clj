;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.auth
  "Drives a login to a refresh token. Both flows share one poll loop.

   NO TERMINAL I/O HAPPENS HERE. Rendering a QR and prompting for a Guard code
   are the ops layer's job; this namespace fires events. That is what keeps the
   flows unit-testable and what keeps the rendering decision out of the
   protocol layer."
  (:require [reliquary.steam.auth-api :as auth-api]
            [reliquary.steam.crypto :as crypto]
            [reliquary.error :as error]))

(def ^:dynamic *poll-sleep-ms*
  "Overridden by tests so the poll loop does not actually sleep."
  nil)

(defn- sleep-for [interval]
  (Thread/sleep (long (or *poll-sleep-ms* (* 1000 (max 1 (long (or interval 5))))))))

(defn- finish
  "Shape the poll result into what model.steam/save-token! consumes."
  [{:keys [refresh-token account]}]
  {:refresh-token refresh-token
   :account account
   :steam-id (:sub (crypto/jwt-claims refresh-token))})

;; EAuthSessionGuardType. The AUTHORITATIVE numbering lives in
;; resources/steam/protos/steam_auth.proto -- check it there, never from memory:
;;
;;   0 Unknown              4 DeviceConfirmation (approve in the mobile app)
;;   1 None                 5 EmailConfirmation  (click a link in an email)
;;   2 EmailCode            6 MachineToken
;;   3 DeviceCode (TOTP)    7 LegacyMachineAuth
;;
;; Two independent questions hide in that list, and collapsing them into one
;; number is how this got mis-implemented once already: *which* confirmation do
;; we pursue, and does the one we picked require a typed code?
(def ^:private code-types
  "The confirmation types the user must type a code for and we then submit."
  #{2 3})

(defn needs-code?
  "Does this confirmation type require a code from the user? Only 2 (emailed
   code) and 3 (authenticator TOTP) do -- 4 and 5 are approved out of band, so
   they need polling and nothing else."
  [confirmation]
  (contains? code-types confirmation))

(defn preferred-confirmation
  "Steam may allow several confirmation types at once; only one is ever pursued.
   Prefer one that needs no typing -- 4 (device confirmation) first, then 5
   (email confirmation) -- and otherwise take the lowest-numbered code type.
   Everything else (0 Unknown, 1 None, 6 MachineToken, 7 LegacyMachineAuth, and
   an empty list) means there is nothing to confirm, so: nil."
  [confirmations]
  (let [s (set confirmations)]
    (cond (contains? s 4) 4
          (contains? s 5) 5
          :else (first (sort (filter code-types s))))))

(defn- poll-until-token
  "Poll until a non-blank refresh token arrives. Re-fires :qr when Steam rotates
   the challenge, and follows :new-client-id when it does."
  [client-id request-id interval on-event]
  (loop [client-id client-id]
    (let [r (auth-api/poll client-id request-id)]
      (if (seq (:refresh-token r))
        (finish r)
        (do
          (when-let [u (:new-challenge-url r)]
            (on-event {:type :qr :challenge-url u}))
          (sleep-for interval)
          (recur (or (:new-client-id r) client-id)))))))

(defn login-qr!
  "BLOCKING QR login. Fires {:type :qr :challenge-url url} so the caller can
   render it, then polls until the user approves."
  [on-event]
  (let [b (auth-api/begin-qr)]
    (on-event {:type :qr :challenge-url (:challenge-url b)})
    (poll-until-token (:client-id b) (:request-id b) (:interval b) on-event)))

(defn login-credentials!
  "BLOCKING credential login. Two events can fire, and which one depends on
   `needs-code?` of the confirmation Steam chose:

     {:type :guard-needed :code-type n}          -- MUST RETURN the typed code
     {:type :confirmation-pending :confirmation-type n} -- purely informational

   The second exists so the ops layer can say `approve this in your Steam mobile
   app` instead of leaving the user watching a silent poll loop. When there is
   nothing to confirm at all, neither fires."
  [username password on-event]
  (let [{:keys [mod exp timestamp]} (auth-api/rsa-key username)
        _ (when-not mod
            (error/raise :incorrect (str "steam has no account named " username)))
        encrypted (crypto/encrypt-password password mod exp)
        b (auth-api/begin-credentials username encrypted timestamp)
        want (preferred-confirmation (:confirmations b))]
    (cond
      (needs-code? want)
      (let [code (on-event {:type :guard-needed :code-type want})]
        (when-not (seq code)
          (error/raise :incorrect "no steam guard code supplied"))
        (auth-api/submit-guard (:client-id b) (:steamid b) code want))

      ;; 4 and 5: nothing to submit, but the user must be told to go approve it
      want
      (on-event {:type :confirmation-pending :confirmation-type want}))
    (poll-until-token (:client-id b) (:request-id b) (:interval b) on-event)))
