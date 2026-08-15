;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.crypto
  "Steam crypto for the session layer: reading the refresh token's claims, and
   encrypting a password for credential login.

   AES manifest/chunk decryption lands with spec 2b, in this same namespace."
  (:require [clojure.data.json :as json]
            [reliquary.error :as error])
  (:import (java.math BigInteger)
           (java.security KeyFactory)
           (java.security.spec RSAPublicKeySpec)
           (java.util Arrays Base64)
           (javax.crypto Cipher)
           (javax.crypto.spec IvParameterSpec SecretKeySpec)))

(defn jwt-claims
  "The `sub` (steamid) and `exp` (unix expiry) claims from a JWT, WITHOUT
   verifying the signature -- Steam issued it and we only read it. The payload
   is base64url, not base64."
  [^String jwt]
  (let [parts (.split jwt "\\.")]
    (when (< (alength parts) 2)
      (error/raise :unauthenticated "malformed steam token"))
    (try
      (let [json-str (String. (.decode (Base64/getUrlDecoder) ^String (aget parts 1)) "UTF-8")
            m (json/read-str json-str :key-fn keyword)]
        {:sub (:sub m) :exp (:exp m)})
      (catch Exception _
        ;; deliberately does not echo the token into the message
        (error/raise :unauthenticated "steam token payload is unreadable")))))

(defn encrypt-password
  "Encrypt `password` under Steam's RSA public key for BeginAuthSessionViaCredentials.
   `mod-hex` and `exp-hex` are the hex strings GetPasswordRSAPublicKey returns.
   Steam expects PKCS#1 v1.5, base64-encoded.

   The radix-16 BigInteger ctor with a positive signum is deliberate: the modulus
   is unsigned, and (BigInteger. hex-bytes) would read a leading high bit as a
   negative number."
  ^String [^String password ^String mod-hex ^String exp-hex]
  (try
    (let [spec (RSAPublicKeySpec. (BigInteger. mod-hex 16) (BigInteger. exp-hex 16))
          key  (.generatePublic (KeyFactory/getInstance "RSA") spec)
          c    (doto (Cipher/getInstance "RSA/ECB/PKCS1Padding")
                 (.init Cipher/ENCRYPT_MODE key))]
      (.encodeToString (Base64/getEncoder)
                       (.doFinal c (.getBytes password "UTF-8"))))
    (catch Exception _
      ;; message deliberately omits the exception text --
      ;; it could echo key material
      (error/raise :unavailable "steam returned an unusable RSA public key"))))

(defn symmetric-decrypt
  "Steam's symmetric decryption for manifest filenames and depot chunks.

   NOT plain CBC. The first 16 bytes of `ct` are the IV encrypted with
   AES-256-ECB/NoPadding -- decrypt them to recover the real IV, then the
   remainder is AES-256-CBC/PKCS7 under it. Treating the prefix as a raw IV
   decrypts to plausible garbage rather than throwing, so this is a mistake
   that surfaces as corrupt filenames much later.

   `key` is the 32-byte depot key."
  ^bytes [^bytes key ^bytes ct]
  (when (< (alength ct) 17)
    (error/raise :incorrect "steam ciphertext shorter than its iv block"
                 {:length (alength ct)}))
  (let [ks (SecretKeySpec. key "AES")
        iv (let [c (Cipher/getInstance "AES/ECB/NoPadding")]
             (.init c Cipher/DECRYPT_MODE ks)
             (.doFinal c (Arrays/copyOfRange ct 0 16)))
        c2 (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init c2 Cipher/DECRYPT_MODE ks (IvParameterSpec. iv))
    (.doFinal c2 (Arrays/copyOfRange ct 16 (alength ct)))))
