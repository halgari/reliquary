;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.cm.content :as content]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.proto :as proto])
  (:import (java.util Base64)))

(defn- reply!
  "Answer the one job the connection has outstanding, correlating on the
   jobid-source it sent."
  [c sent emsg-kw resp-type body]
  (let [jid (:jobid-source (:header (env/decode-message (first @sent))))]
    (conn/deliver-frame! c (env/encode-message (get env/e emsg-kw)
                                               {:jobid-target jid}
                                               (proto/encode resp-type body)))))

(defn- b64 ^String [^bytes b] (.encodeToString (Base64/getEncoder) b))

(deftest depot-key-comes-back-as-lowercase-hex
  (testing "the proto bytes field arrives base64; manifest/parse wants hex"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))
          key  (byte-array (map unchecked-byte [0x00 0x0f 0xa0 0xff]))]
      (future (Thread/sleep 50)
              (reply! c sent :depot-key-response "CMsgClientGetDepotDecryptionKeyResponse"
                      {:eresult 1 :depot-id 489831 :depot-encryption-key (b64 key)}))
      (is (= "000fa0ff" (content/depot-key c 489830 489831))))))

(deftest an-ok-depot-key-response-missing-the-key-field-is-incorrect-not-an-npe
  (testing "eresult 1 with no :depot-encryption-key is possible because the
            proto bridge fills no defaults -- Base64/getDecoder .decode on nil
            NPEs instead of raising a categorized error"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))]
      (future (Thread/sleep 50)
              (reply! c sent :depot-key-response "CMsgClientGetDepotDecryptionKeyResponse"
                      {:eresult 1 :depot-id 489831}))
      (let [e (try (content/depot-key c 489830 489831) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (= 489831 (:depot-id (ex-data e))))))))

(deftest a-denied-depot-key-is-incorrect-and-carries-its-eresult
  (testing "some selected depots are legitimately not ours; ingest must be able
            to tell a denial from a transport failure and skip just that depot"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))]
      (future (Thread/sleep 50)
              (reply! c sent :depot-key-response "CMsgClientGetDepotDecryptionKeyResponse"
                      {:eresult 15 :depot-id 489831}))
      (let [e (try (content/depot-key c 489830 489831) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (= 15 (:eresult (ex-data e))))
        (is (= 489831 (:depot-id (ex-data e))))
        (is (not (re-find #"(?i)key" (ex-message e)))
            "an error message must never look like it is quoting the key")))))

(deftest a-manifest-request-code-stays-a-string
  (let [sent (atom [])
        c    (conn/conn-with-send-fn #(swap! sent conj %))]
    (future (Thread/sleep 50)
            (reply! c sent :service-response
                    "CContentServerDirectory_GetManifestRequestCode_Response"
                    {:manifest-request-code "18446744073709551615"}))
    (is (= "18446744073709551615"
           (content/manifest-request-code c 489830 489831 "845123")))))

(deftest a-declined-manifest-request-code-is-incorrect-not-nil
  (testing "the proto bridge fills no defaults, so a declined request decodes
            to a response with no :manifest-request-code at all -- returning
            that nil silently would let ops.steam/sync build a valid-looking
            url with an empty request-code segment and blame the CDN for a
            4xx the CM actually caused"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))]
      (future (Thread/sleep 50)
              (reply! c sent :service-response
                      "CContentServerDirectory_GetManifestRequestCode_Response"
                      {}))
      (let [e (try (content/manifest-request-code c 489830 489831 "845123") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (= 489831 (:depot-id (ex-data e))))))))

(deftest cdn-servers-keeps-only-https-capable-hosts
  (let [sent (atom [])
        c    (conn/conn-with-send-fn #(swap! sent conj %))]
    (future (Thread/sleep 50)
            (reply! c sent :service-response
                    "CContentServerDirectory_GetServersForSteamPipe_Response"
                    {:servers [{:host "a.steampipe.net" :https-support "mandatory"}
                               {:host "b.steampipe.net" :https-support "optional"}
                               {:host "c.steampipe.net" :https-support "none"}
                               {:https-support "mandatory"}]}))
    (is (= ["a.steampipe.net" "b.steampipe.net"] (content/cdn-servers c)))))

(deftest manifest-request-code-passes-the-branch
  (let [sent (atom nil)]
    (with-redefs [conn/send-service! (fn [_c _m _rt body _resp]
                                       (reset! sent body)
                                       (doto (java.util.concurrent.CompletableFuture.)
                                         (.complete {:manifest-request-code "99"})))
                  conn/join          (fn [f] (.get ^java.util.concurrent.CompletableFuture f))]
      (is (= "99" (content/manifest-request-code nil 1 2 "3" "beta")))
      (is (= "beta" (:app-branch @sent))))))
