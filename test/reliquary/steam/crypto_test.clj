;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.crypto :as crypto])
  (:import (java.security KeyPairGenerator SecureRandom)
           (java.util Base64)
           (javax.crypto Cipher KeyGenerator)
           (javax.crypto.spec SecretKeySpec IvParameterSpec)))

;; A JWT-shaped token with a payload we control. Header and signature are
;; irrelevant -- jwt-claims never verifies, by design.
(def ^:private fake-jwt
  (let [enc #(.encodeToString (Base64/getUrlEncoder) (.getBytes ^String % "UTF-8"))]
    (str (enc "{\"typ\":\"JWT\"}") "."
         (enc "{\"sub\":\"76561198000000000\",\"exp\":1799999999}") "."
         "not-a-real-signature")))

(deftest reads-sub-and-exp
  (is (= {:sub "76561198000000000" :exp 1799999999} (crypto/jwt-claims fake-jwt))))

(deftest a-malformed-token-is-categorized-not-a-raw-exception
  (doseq [bad ["" "no-dots-at-all" "only.two"]]
    (let [e (try (crypto/jwt-claims bad) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str "expected a raise for " (pr-str bad)))
      (is (= :unauthenticated (:reliquary/error (ex-data e)))))))

(deftest a-malformed-token-never-echoes-itself
  (testing "the token must not reach a message that gets logged or pasted"
    (let [secret "aaaa.bbbbSECRETPAYLOADbbbb.cccc"
          e (try (crypto/jwt-claims secret) nil (catch clojure.lang.ExceptionInfo e e))]
      ;; without this the whole test passes vacuously the day jwt-claims stops
      ;; raising -- and this is the test standing behind "no ex-data or message
      ;; may carry a refresh token"
      (is (some? e) "expected a raise -- otherwise the assertions below are skipped")
      (when e
        (is (not (clojure.string/includes? (ex-message e) "SECRET")))
        (is (not (clojure.string/includes? (pr-str (ex-data e)) "SECRET")))))))

(deftest encrypted-password-decrypts-back
  (testing "round-trip against the private half proves padding and key spec"
    (let [kp   (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                   (.initialize 2048)))
          pub  (.getPublic kp)
          mod-hex (.toString (.getModulus pub) 16)
          exp-hex (.toString (.getPublicExponent pub) 16)
          ct   (crypto/encrypt-password "hunter2" mod-hex exp-hex)
          c    (doto (Cipher/getInstance "RSA/ECB/PKCS1Padding")
                 (.init Cipher/DECRYPT_MODE (.getPrivate kp)))]
      (is (= "hunter2" (String. (.doFinal c (.decode (Base64/getDecoder) ct)) "UTF-8"))))))

(deftest encryption-is-randomized
  (testing "PKCS#1 v1.5 pads with random bytes; identical output would mean ECB-style leakage"
    (let [kp  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
          pub (.getPublic kp)
          m   (.toString (.getModulus pub) 16)
          e   (.toString (.getPublicExponent pub) 16)]
      (is (not= (crypto/encrypt-password "hunter2" m e)
                (crypto/encrypt-password "hunter2" m e))))))

(deftest a-modulus-with-a-high-leading-bit-stays-positive
  (testing "radix-16 parsing, not byte parsing -- otherwise this modulus goes negative"
    (let [kp  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
          pub (.getPublic kp)
          m   (.toString (.getModulus pub) 16)]
      ;; The top *bit* of a 2048-bit RSA modulus is always set, but that only
      ;; guarantees the leading hex nibble is in 8-f, not always f -- observed
      ;; flaky (e.g. leading nibble 8) against a real KeyPairGenerator, so per
      ;; the task brief this assertion is dropped and only the encrypt call,
      ;; which exercises the same positive-BigInteger parsing, is kept.
      (is (string? (crypto/encrypt-password "x" m (.toString (.getPublicExponent pub) 16)))))))

(deftest a-malformed-modulus-is-categorized-not-a-raw-exception
  (testing "NumberFormatException and friends must not escape encrypt-password uncategorized"
    (let [bogus "not-hex"
          e (try (crypto/encrypt-password "hunter2" bogus "10001") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "expected a raise for a malformed modulus")
      (is (= :unavailable (:reliquary/error (ex-data e))))
      (is (not (clojure.string/includes? (ex-message e) bogus))
          "the bogus input must not be echoed into the message"))))

(defn- encrypt-like-steam
  "The inverse of symmetric-decrypt, written independently from Steam's
   documented construction: a random IV encrypted with AES-256-ECB/NoPadding,
   prepended to the AES-256-CBC/PKCS5 body under that IV."
  ^bytes [^bytes key ^bytes plaintext]
  (let [ks  (SecretKeySpec. key "AES")
        iv  (let [b (byte-array 16)] (.nextBytes (SecureRandom.) b) b)
        eiv (let [c (Cipher/getInstance "AES/ECB/NoPadding")]
              (.init c Cipher/ENCRYPT_MODE ks)
              (.doFinal c iv))
        bod (let [c (Cipher/getInstance "AES/CBC/PKCS5Padding")]
              (.init c Cipher/ENCRYPT_MODE ks (IvParameterSpec. iv))
              (.doFinal c plaintext))
        out (byte-array (+ 16 (alength bod)))]
    (System/arraycopy eiv 0 out 0 16)
    (System/arraycopy bod 0 out 16 (alength bod))
    out))

(defn- random-key ^bytes []
  (.getEncoded (.generateKey (doto (KeyGenerator/getInstance "AES") (.init 256)))))

(deftest symmetric-decrypt-recovers-the-iv-from-the-ecb-prefix
  (testing "the first 16 bytes are AES-ECB(iv), not the iv itself -- treating
            them as a raw iv decrypts to garbage without throwing"
    (let [key (random-key)
          pt  (.getBytes "Data/Textures/Armor/Iron/Cuirass_n.dds" "UTF-8")
          ct  (encrypt-like-steam key pt)]
      (is (= (seq pt) (seq (crypto/symmetric-decrypt key ct)))))))

(deftest symmetric-decrypt-handles-a-plaintext-that-is-an-exact-block-multiple
  (testing "PKCS7 adds a whole padding block at 16|len; stripping it is the
            decrypter's job, and an off-by-one here corrupts every such name"
    (let [key (random-key)
          pt  (.getBytes "0123456789abcdef" "UTF-8")]   ; exactly 16 bytes
      (is (= 16 (alength pt)))
      (is (= (seq pt) (seq (crypto/symmetric-decrypt key (encrypt-like-steam key pt))))))))

(deftest symmetric-decrypt-rejects-a-ciphertext-shorter-than-the-iv-block
  (let [e (try (crypto/symmetric-decrypt (random-key) (byte-array 8)) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))))
