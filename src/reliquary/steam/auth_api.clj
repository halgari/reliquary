;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.auth-api
  "The IAuthenticationService calls the two login flows use.

   platform-type 1 = SteamClient and website-id \"Client\" match the reference
   client; Steam rejects unfamiliar combinations."
  (:require [reliquary.steam.api :as api :refer [defcall]]))

(def ^:private device
  "How this client introduces itself. `device-friendly-name` is not cosmetic: it
   is the name Steam lists on the account's authorized-devices page, where the
   user decides what to revoke. It read \"mauvi\" until this was noticed --
   naming a different application on the user's own security page, which is the
   copy this namespace came from showing through."
  {:device-friendly-name "Reliquary" :platform-type 1})

(def ^:private base-req
  (assoc device :website-id "Client" :device-details device))

(defn- confirmations [resp]
  (mapv :confirmation-type (or (:allowed-confirmations resp) [])))

(defcall begin-qr* "BeginAuthSessionViaQR" :post
  CAuthentication_BeginAuthSessionViaQR_Request
  CAuthentication_BeginAuthSessionViaQR_Response)

(defcall poll* "PollAuthSessionStatus" :post
  CAuthentication_PollAuthSessionStatus_Request
  CAuthentication_PollAuthSessionStatus_Response)

;; The one GET on this service. Steam answers it ONLY to GET -- a POST comes
;; back 405 with an HTML page reading "This API must be called with a HTTP GET
;; request", which is not a protobuf and used to reach the parser as if it were.
(defcall rsa-key* "GetPasswordRSAPublicKey" :get
  CAuthentication_GetPasswordRSAPublicKey_Request
  CAuthentication_GetPasswordRSAPublicKey_Response)

(defcall begin-credentials* "BeginAuthSessionViaCredentials" :post
  CAuthentication_BeginAuthSessionViaCredentials_Request
  CAuthentication_BeginAuthSessionViaCredentials_Response)

(defcall submit-guard* "UpdateAuthSessionWithSteamGuardCode" :post
  CAuthentication_UpdateAuthSessionWithSteamGuardCode_Request
  CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response)

(defn begin-qr []
  (let [r (begin-qr* base-req)]
    {:client-id (:client-id r) :request-id (:request-id r)
     :challenge-url (:challenge-url r) :interval (:interval r)
     :confirmations (confirmations r)}))

(defn poll
  "A non-blank :refresh-token means the login was approved."
  [client-id request-id]
  (let [r (poll* {:client-id client-id :request-id request-id})]
    {:refresh-token (:refresh-token r) :access-token (:access-token r)
     :account (:account-name r) :new-client-id (:new-client-id r)
     :new-challenge-url (:new-challenge-url r)}))

(defn rsa-key
  "Steam's RSA public key for this account, as hex strings plus the timestamp
   that must be echoed back with the encrypted password."
  [username]
  (let [r (rsa-key* {:account-name username})]
    {:mod (:publickey-mod r) :exp (:publickey-exp r) :timestamp (:timestamp r)}))

(defn begin-credentials [username encrypted-b64 timestamp]
  (let [r (begin-credentials* (assoc base-req
                                     :account-name username
                                     :encrypted-password encrypted-b64
                                     :encryption-timestamp timestamp
                                     :persistence 1))]
    {:client-id (:client-id r) :request-id (:request-id r) :interval (:interval r)
     :confirmations (confirmations r) :steamid (:steamid r)}))

(defn submit-guard [client-id steamid code code-type]
  (submit-guard* {:client-id client-id :steamid steamid
                  :code code :code-type code-type}))
