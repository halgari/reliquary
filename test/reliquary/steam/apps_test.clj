;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.apps-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.apps :as apps]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.proto :as proto])
  (:import (java.util Base64)))

(defn- b64 ^String [^bytes b] (.encodeToString (Base64/getEncoder) b))

(defn- package-buffer
  "A PICS package buffer, exactly as captured: the file already carries its
   4-byte prefix (verified by inspection -- first four bytes are 01 00 00 00,
   not zero, and the KV object marker 0x00 sits at offset 4), so this must NOT
   prepend another one."
  ^String []
  (b64 (with-open [in (io/input-stream (io/resource "steam/pics-package.bin"))]
         (.readAllBytes in))))

(defn- app-buffer ^String []
  (b64 (.getBytes (slurp (io/resource "steam/pics-app.vdf")) "UTF-8")))

(defn- reply! [c sent emsg-kw resp-type body]
  (let [jid (:jobid-source (:header (env/decode-message (last @sent))))]
    (conn/deliver-frame! c (env/encode-message (get env/e emsg-kw)
                                               {:jobid-target jid}
                                               (proto/encode resp-type body)))))

(deftest a-multi-part-pics-response-is-collected-until-the-last-part
  (testing "Steam splits a large product-info response and flags every non-final
            part with response_pending -- taking only the first loses depots"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))]
      (future (Thread/sleep 50)
              (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                      {:apps [] :response-pending true})
              (Thread/sleep 20)
              (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                      {:apps [{:appid 489830 :buffer (app-buffer)}] :response-pending false}))
      (let [m (apps/app-info c 489830)]
        (is (contains? m "appinfo"))
        (is (= "489830" (get-in m ["appinfo" "appid"])))))))

(deftest owned-apps-reads-appids-out-of-the-package-blob
  (let [sent (atom [])
        c    (conn/conn-with-send-fn #(swap! sent conj %))]
    (future (Thread/sleep 50)
            ;; 1. packages
            (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                    {:packages [{:packageid 1 :buffer (package-buffer)}]
                     :response-pending false})
            (Thread/sleep 20)
            ;; 2. access tokens
            (reply! c sent :pics-token-response "CMsgClientPICSAccessTokenResponse"
                    {:app-access-tokens [{:id 489830 :access-token 7}]})
            (Thread/sleep 20)
            ;; 3. apps
            (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                    {:apps [{:appid 489830 :buffer (app-buffer)}] :response-pending false}))
    (let [r (apps/owned-apps c [{:package-id 1 :access-token 0}])]
      (is (= 0 (:skipped r)))
      (is (some #(= 489830 (:appid %)) (:apps r)))
      (is (every? #(seq (:name %)) (:apps r))))))

(deftest a-corrupt-blob-is-skipped-and-counted-not-fatal
  (testing "one unparseable package must not blank a 200-game library -- but
            the caller has to be able to see that the answer is incomplete"
    (let [sent (atom [])
          c    (conn/conn-with-send-fn #(swap! sent conj %))]
      (future (Thread/sleep 50)
              (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                      {:packages [{:packageid 1 :buffer (b64 (.getBytes "junk" "UTF-8"))}
                                  {:packageid 2 :buffer (package-buffer)}]
                       :response-pending false})
              (Thread/sleep 20)
              (reply! c sent :pics-token-response "CMsgClientPICSAccessTokenResponse"
                      {:app-access-tokens [{:id 489830 :access-token 7}]})
              (Thread/sleep 20)
              (reply! c sent :pics-product-response "CMsgClientPICSProductInfoResponse"
                      {:apps [{:appid 489830 :buffer (app-buffer)}] :response-pending false}))
      (let [r (apps/owned-apps c [{:package-id 1 :access-token 0}
                                  {:package-id 2 :access-token 0}])]
        (is (= 1 (:skipped r)))
        (is (seq (:apps r)) "the good package still yielded its apps")))))

(deftest no-licenses-means-no-apps-and-no-calls
  (let [sent (atom [])
        c    (conn/conn-with-send-fn #(swap! sent conj %))]
    (is (= {:apps [] :skipped 0} (apps/owned-apps c [])))
    (is (empty? @sent) "an account with no licenses must not hit the wire")))
