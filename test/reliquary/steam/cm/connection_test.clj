;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.connection-test
  "No socket. The transport is injected as send-fn/close-fn and inbound frames
   go through deliver-frame!, so the whole receive path is reachable directly."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.proto :as proto])
  (:import (java.io ByteArrayOutputStream)
           (java.nio ByteBuffer ByteOrder)
           (java.util Base64)
           (java.util.concurrent TimeUnit TimeoutException)
           (java.util.zip GZIPOutputStream)))

(defn- capturing []
  (let [sent (atom [])]
    [sent (conn/conn-with-send-fn (fn [^bytes b] (swap! sent conj b)))]))

(defn- response-frame
  "A frame as Steam would send it: emsg, a header naming the job it answers
   (nil for unsolicited), and a protobuf body."
  ^bytes [emsg-kw jobid-target type body-map]
  (env/encode-message (get env/e emsg-kw)
                      (cond-> {} jobid-target (assoc :jobid-target jobid-target))
                      (proto/encode type body-map)))

(deftest send!-frames-the-message-onto-the-transport
  (let [[sent c] (capturing)]
    (conn/set-session! c {:steamid "76561198000000000"})
    (conn/send! c :logon "CMsgClientLogon" {:protocol-version 65580})
    (is (= 1 (count @sent)))
    (let [out (env/decode-message (first @sent))]
      (is (= (:logon env/e) (:emsg out)))
      (is (= "76561198000000000" (:steamid (:header out))))
      (is (= 65580 (:protocol-version (proto/decode "CMsgClientLogon" (:body out))))))))

(deftest an-unsolicited-emsg-reaches-its-handler
  (testing "logon response and license list both arrive with jobid_target unset"
    (let [[_ c] (capturing)
          got (atom nil)]
      (conn/on-emsg! c :logon-response "CMsgClientLogonResponse" #(reset! got %))
      (conn/deliver-frame! c (response-frame :logon-response nil
                                             "CMsgClientLogonResponse" {:eresult 1}))
      (is (= 1 (:eresult @got))))))

(deftest the-session-id-is-captured-from-an-inbound-header
  (let [[sent c] (capturing)]
    (conn/deliver-frame! c (env/encode-message (:logon-response env/e)
                                               {:client-sessionid 12345}
                                               (proto/encode "CMsgClientLogonResponse" {})))
    (conn/send! c :heartbeat "CMsgClientHeartBeat" {})
    (is (= 12345 (:client-sessionid (:header (env/decode-message (first @sent)))))
        "the session id Steam assigns must appear on every subsequent outbound header")))

(deftest job-responses-route-to-their-own-futures-out-of-order
  (testing "the hard part: two jobs in flight, answered in reverse"
    (let [[sent c] (capturing)
          cf1 (conn/send-job! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                              "CMsgClientPICSProductInfoResponse")
          cf2 (conn/send-job! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                              "CMsgClientPICSProductInfoResponse")
          jid-of (fn [^bytes b] (:jobid-source (:header (env/decode-message b))))
          j1 (jid-of (first @sent))
          j2 (jid-of (second @sent))]
      (is (not= j1 j2) "each job gets its own id")
      ;; answer the SECOND job first
      (conn/deliver-frame! c (response-frame :pics-product-response j2
                                             "CMsgClientPICSProductInfoResponse"
                                             {:response-pending false}))
      (is (= false (:response-pending (conn/join cf2))))
      (is (not (.isDone cf1)) "the first job must still be waiting")
      (conn/deliver-frame! c (response-frame :pics-product-response j1
                                             "CMsgClientPICSProductInfoResponse"
                                             {:response-pending false}))
      (is (map? (conn/join cf1))))))

(deftest collect-job-accumulates-parts-and-resolves-to-the-vector
  (let [[sent c] (capturing)
        cf (conn/send-job-collect! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                                   "CMsgClientPICSProductInfoResponse"
                                   (fn [resp] (not (:response-pending resp))))
        jid (:jobid-source (:header (env/decode-message (first @sent))))]
    (conn/deliver-frame! c (response-frame :pics-product-response jid
                                           "CMsgClientPICSProductInfoResponse"
                                           {:response-pending true}))
    (is (not (.isDone cf)) "still waiting on the final part")
    (conn/deliver-frame! c (response-frame :pics-product-response jid
                                           "CMsgClientPICSProductInfoResponse"
                                           {:response-pending false}))
    (let [result (conn/join cf)]
      (is (= 2 (count result)))
      (is (= true (:response-pending (first result))))
      (is (= false (:response-pending (second result)))))))

(deftest a-non-final-part-re-arms-the-timeout-instead-of-leaving-it-uncancelled
  (binding [conn/*job-timeout-ms* 300]
    (let [[sent c] (capturing)
          cf (conn/send-job-collect! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                                     "CMsgClientPICSProductInfoResponse"
                                     (fn [resp] (not (:response-pending resp))))
          jid (:jobid-source (:header (env/decode-message (first @sent))))]
      ;; Deliver exactly one non-final part and never a final one. The original
      ;; watchdog is cancelled unconditionally on arrival (that alone can't
      ;; distinguish re-arm from no-re-arm), so the only thing that can ever
      ;; complete this future is a freshly armed timeout firing on the
      ;; re-armed schedule and rejecting it. If the re-arm call were missing,
      ;; nothing would ever touch the future again and the .get below would
      ;; hit its own 3s ceiling with a TimeoutException instead.
      (conn/deliver-frame! c (response-frame :pics-product-response jid
                                             "CMsgClientPICSProductInfoResponse"
                                             {:response-pending true}))
      (let [e (try (.get cf 3 TimeUnit/SECONDS) nil
                   (catch java.util.concurrent.ExecutionException e (.getCause e)))]
        (is (some? e))
        (is (= :unavailable (:reliquary/error (ex-data e))))))))

(deftest a-collect-job-with-no-parts-still-times-out
  (binding [conn/*job-timeout-ms* 150]
    (let [[_ c] (capturing)
          cf (conn/send-job-collect! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                                     "CMsgClientPICSProductInfoResponse"
                                     (fn [resp] (not (:response-pending resp))))
          e (try (.get cf 3 TimeUnit/SECONDS) nil
                 (catch java.util.concurrent.ExecutionException e (.getCause e)))]
      (is (some? e))
      (is (= :unavailable (:reliquary/error (ex-data e)))))))

(deftest a-multi-batch-is-expanded-and-each-inner-frame-dispatched
  (let [[_ c] (capturing)
        got (atom [])
        inner (response-frame :logon-response nil "CMsgClientLogonResponse" {:eresult 1})
        payload (let [bb (doto (ByteBuffer/allocate (+ 4 (alength inner)))
                           (.order ByteOrder/LITTLE_ENDIAN)
                           (.putInt (alength inner))
                           (.put inner))]
                  (.array bb))
        multi-frame (env/encode-message (:multi env/e) {}
                                        (proto/encode "CMsgMulti"
                                                      {:message-body payload
                                                       :size-unzipped 0}))]
    (conn/on-emsg! c :logon-response "CMsgClientLogonResponse" #(swap! got conj %))
    (conn/deliver-frame! c multi-frame)
    (is (= 1 (count @got)))
    (is (= 1 (:eresult (first @got))))))

(deftest a-job-that-is-never-answered-times-out
  (binding [conn/*job-timeout-ms* 150]
    (let [[_ c] (capturing)
          cf (conn/send-job! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                             "CMsgClientPICSProductInfoResponse")
          e (try (.get cf 3 TimeUnit/SECONDS) nil
                 (catch java.util.concurrent.ExecutionException e (.getCause e)))]
      (is (some? e))
      (is (= :unavailable (:reliquary/error (ex-data e)))))))

(deftest closing-rejects-every-pending-job
  (let [[_ c] (capturing)
        cf (conn/send-job! c :pics-product-request "CMsgClientPICSProductInfoRequest" {}
                           "CMsgClientPICSProductInfoResponse")]
    (conn/close! c)
    (let [e (try (conn/join cf) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "a caller blocked on join must not hang forever")
      (is (= :unavailable (:reliquary/error (ex-data e)))))))

(deftest close-invokes-the-injected-close-fn
  (let [closed (atom false)
        c (conn/conn-with-send-fn (fn [_]) (fn [] (reset! closed true)))]
    (conn/close! c)
    (is @closed)))

(deftest a-struct-frame-is-ignored-without-throwing
  (let [[_ c] (capturing)
        bb (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN)
             (.putInt 703) (.putInt 0))]
    (is (nil? (conn/deliver-frame! c (.array bb))))))
