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


;; ---------------------------------------------------------------------------
;; a Guard code Steam refuses -- or has already accepted

(def ^:private ^:const max-guard-attempts
  "Submissions of a typed code per login, including the first."
  3)

(def ^:private retypable-eresults
  "The EResults that mean the human mistyped the code -- 65 InvalidLoginAuthCode
   (emailed) and 88 TwoFactorCodeMismatch (authenticator). Deliberately not
   \"any :incorrect\": a rate limit (84) or an expired session is not fixed by
   typing again, and re-prompting on one of those spends the user's next attempt
   on the same refusal while telling them their code was wrong when it was not."
  #{"65" "88"})

(defn- retypable?
  "Is this the kind of refusal a second attempt at the code could fix?"
  [e]
  (contains? retypable-eresults (str (:eresult (ex-data e)))))

(defn- already-accepted?
  "EResult 29 DuplicateRequest: Steam has this confirmation already. It is what
   comes back when the user approved the prompt in the mobile app and then typed
   the code as well, and the reference client is explicit that it must not be
   treated as a failure -- \"authentication will succeed on the next poll\".
   api/call raises on every non-OK eresult, so this is the one that has to be
   caught and dropped, or the login dies one poll short of its token."
  [e]
  (= "29" (str (:eresult (ex-data e)))))

(defn- submit-guard!
  "Submit the typed Guard code, re-prompting when Steam says it was wrong.

   Without this a single mistyped character ended the whole login -- api/call
   raises on the non-OK eresult -- and sent the user back to the password field.
   Nothing about the session is spent at that point: the client id and steamid
   are still valid, so only the code needs asking for again. Re-fired events
   carry :retry? true so the ops layer can say the last one was refused."
  [client-id steamid code-type on-event first-code]
  (loop [code first-code attempt 1]
    (when-not (seq code)
      (error/raise :incorrect "no steam guard code supplied"))
    (let [r (try
              (auth-api/submit-guard client-id steamid code code-type)
              (catch clojure.lang.ExceptionInfo e
                (cond
                  (already-accepted? e) nil
                  (and (retypable? e) (< attempt max-guard-attempts)) ::refused
                  :else (throw e))))]
      (if (= ::refused r)
        (recur (on-event {:type :guard-needed :code-type code-type :retry? true})
               (inc attempt))
        r))))

(defn- poll-until-token
  "Poll until a non-blank refresh token arrives. Re-fires :qr when Steam rotates
   the challenge, and follows :new-client-id when it does.

   `abort?` is a 0-arg predicate or nil, and it is checked BEFORE each poll
   rather than after: an abandoned login must make no further request to Steam.
   Aborting returns nil, which is neither a token nor an error -- the caller
   asked for this, so there is nothing to report."
  [client-id request-id interval on-event abort?]
  (loop [client-id client-id]
    (if (and abort? (abort?))
      nil
      (let [r (auth-api/poll client-id request-id)]
        (if (seq (:refresh-token r))
          (finish r)
          (do
            (when-let [u (:new-challenge-url r)]
              (on-event {:type :qr :challenge-url u}))
            (sleep-for interval)
            (recur (or (:new-client-id r) client-id))))))))

(defn login-qr!
  "BLOCKING QR login. Fires {:type :qr :challenge-url url} so the caller can
   render it, then polls until the user approves.

   `opts` carries :abort? -- see `poll-until-token`. A nil RETURN means the
   login was abandoned, not that it failed."
  ([on-event] (login-qr! on-event nil))
  ([on-event {:keys [abort?]}]
   (let [b (auth-api/begin-qr)]
     (on-event {:type :qr :challenge-url (:challenge-url b)})
     (poll-until-token (:client-id b) (:request-id b) (:interval b) on-event abort?))))

(defn login-credentials!
  "BLOCKING credential login. Two events can fire, and which one depends on
   `needs-code?` of the confirmation Steam chose:

     {:type :guard-needed :code-type n}          -- MUST RETURN the typed code
     {:type :confirmation-pending :confirmation-type n} -- purely informational

   The second exists so the ops layer can say `approve this in your Steam mobile
   app` instead of leaving the user watching a silent poll loop. When there is
   nothing to confirm at all, neither fires.

   `opts` carries :abort? -- see `poll-until-token`. A nil RETURN means the
   login was abandoned, not that it failed."
  ([username password on-event] (login-credentials! username password on-event nil))
  ([username password on-event {:keys [abort?]}]
   (let [{:keys [mod exp timestamp]} (auth-api/rsa-key username)
         _ (when-not mod
             (error/raise :incorrect (str "steam has no account named " username)))
         encrypted (crypto/encrypt-password password mod exp)
         b (auth-api/begin-credentials username encrypted timestamp)
         want (preferred-confirmation (:confirmations b))]
     (cond
       (needs-code? want)
       (submit-guard! (:client-id b) (:steamid b) want on-event
                      (on-event {:type :guard-needed :code-type want}))

       ;; 4 and 5: nothing to submit, but the user must be told to go approve it
       want
       (on-event {:type :confirmation-pending :confirmation-type want}))
     (poll-until-token (:client-id b) (:request-id b) (:interval b) on-event abort?))))
