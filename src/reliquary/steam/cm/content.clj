;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.content
  "The content-system CM calls: depot decryption keys, manifest request codes,
   and the CDN host list. Each blocks on its job future."
  (:require [reliquary.error :as error]
            [reliquary.steam.cm.connection :as conn])
  (:import (java.util Base64)))

(defn- bytes->hex ^String [^bytes b]
  (let [sb (StringBuilder. (* 2 (alength b)))]
    (dotimes [i (alength b)]
      (.append sb (format "%02x" (bit-and 0xFF (long (aget b i))))))
    (.toString sb)))

(defn depot-key
  "The depot decryption key, as a lowercase hex string (what manifest/parse
   takes). Raises :incorrect carrying :eresult and :depot-id when Steam denies
   it -- a denial is an EXPECTED outcome for a selected-but-unowned depot, and
   ingest skips just that depot rather than failing the sync.

   The raised message never quotes the key: this value is a secret.

   app-id and depot-id are NOT primitive-hinted, even though they are always
   longs in practice -- see reliquary.steam.apps/app-info's docstring for why: a
   ^long arg here would make with-redefs-installed test doubles (as
   mauvi.ops.steam-test installs for the denied-depot path) fail a
   ClassCastException at the call site instead of running."
  ^String [c app-id depot-id]
  (let [r (conn/join
           (conn/send-job! c :depot-key-request
                           "CMsgClientGetDepotDecryptionKey"
                           {:app-id app-id :depot-id depot-id}
                           "CMsgClientGetDepotDecryptionKeyResponse"))]
    (when (not= 1 (:eresult r))
      (error/raise :incorrect (str "steam denied depot " depot-id)
                   {:eresult (:eresult r) :depot-id depot-id}))
    (when (nil? (:depot-encryption-key r))
      (error/raise :incorrect
                   (str "steam approved depot " depot-id " but sent no key")
                   {:depot-id depot-id}))
    (bytes->hex (.decode (Base64/getDecoder) ^String (:depot-encryption-key r)))))

(defn manifest-request-code
  "The request code for a public-branch manifest. A uint64 that stays a STRING.

   Raises :incorrect carrying :depot-id when the field is absent -- the proto
   bridge fills no defaults, so a request Steam declines decodes to nil rather
   than an eresult ops.steam/sync could branch on. A nil code would otherwise
   build a VALID manifest url with an empty request-code segment, so the CDN's
   4xx would blame the CDN for what the CM actually declined.

   Like a denied depot key, ops.steam/sync treats this as skip-and-count, not
   fatal -- see its docstring for why. That means it can end up wrapped in a
   with-redefs test double the same way depot-key's denied-depot tests do, so
   app-id and depot-id are NOT primitive-hinted here either -- see depot-key's
   docstring just above for the ClassCastException a ^long arg would cause."
  ^String
  ([c app-id depot-id ^String manifest-gid]
   (manifest-request-code c app-id depot-id manifest-gid "public"))
  ([c app-id depot-id ^String manifest-gid ^String branch]
   (let [r    (conn/join
               (conn/send-service! c "ContentServerDirectory.GetManifestRequestCode#1"
                                   "CContentServerDirectory_GetManifestRequestCode_Request"
                                   {:app-id app-id :depot-id depot-id
                                    :manifest-id manifest-gid :app-branch branch}
                                   "CContentServerDirectory_GetManifestRequestCode_Response"))
         code (:manifest-request-code r)]
     (when (nil? code)
       (error/raise :incorrect
                    (str "steam granted no manifest request code for depot " depot-id)
                    {:depot-id depot-id}))
     code)))

(defn cdn-servers
  "SteamPipe CDN hosts that support HTTPS. Ordered as Steam returned them;
   manifest/fetch rotates through this list on repeated failure."
  [c]
  (let [r (conn/join
           (conn/send-service! c "ContentServerDirectory.GetServersForSteamPipe#1"
                               "CContentServerDirectory_GetServersForSteamPipe_Request"
                               {:max-servers 20}
                               "CContentServerDirectory_GetServersForSteamPipe_Response"))]
    (into [] (comp (filter #(#{"mandatory" "optional"} (:https-support %)))
                   (keep :host))
          (:servers r))))
