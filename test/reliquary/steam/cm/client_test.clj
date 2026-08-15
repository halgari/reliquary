;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.client-test
  "The handshake, driven against an injected transport. logon! itself (discovery
   plus connect) is covered by the live gate."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.client :as client]
            [reliquary.steam.cm.connection :as conn]
            [reliquary.steam.cm.discovery :as disc]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.proto :as proto])
  (:import (java.util Base64)
           (java.util.concurrent TimeoutException)))

(def ^:private token
  (let [enc #(.encodeToString (Base64/getUrlEncoder) (.getBytes ^String % "UTF-8"))]
    (str (enc "{}") "." (enc "{\"sub\":\"76561198000000000\",\"exp\":1799999999}") ".sig")))

(defn- unsolicited ^bytes [emsg-kw type body-map]
  (env/encode-message (get env/e emsg-kw) {} (proto/encode type body-map)))

(deftest a-successful-logon-returns-the-steamid-steam-supplies
  (let [c (conn/conn-with-send-fn (fn [_]))]
    ;; reply on a separate thread once logon-on! is blocked awaiting the response
    (future (Thread/sleep 50)
            (conn/deliver-frame! c (unsolicited :logon-response "CMsgClientLogonResponse"
                                                {:eresult 1
                                                 :client-supplied-steamid "76561198000000001"
                                                 :heartbeat-seconds 9}))
            (conn/deliver-frame! c (unsolicited :license-list "CMsgClientLicenseList"
                                                {:licenses [{:package-id 1}
                                                            {:package-id 2}]})))
    (let [r (client/logon-on! c token "me")]
      (is (= "76561198000000001" (:steamid r)))
      (is (= 2 (count (client/licenses c)))))))

(deftest a-rejected-token-is-unauthenticated-not-unavailable
  (testing "the user must log in again; retrying another server would not help"
    (let [c (conn/conn-with-send-fn (fn [_]))]
      (future (Thread/sleep 50)
              (conn/deliver-frame! c (unsolicited :logon-response "CMsgClientLogonResponse"
                                                  {:eresult 5})))
      (let [e (try (client/logon-on! c token "me") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :unauthenticated (:reliquary/error (ex-data e))))
        (is (= 5 (:eresult (ex-data e))))))))

(deftest a-logon-with-no-response-times-out-as-unavailable
  (testing "transient -- worth a retry, unlike a rejected token"
    (let [c (conn/conn-with-send-fn (fn [_]))
          e (try (binding [client/*logon-timeout-ms* 200] (client/logon-on! c token "me"))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :unavailable (:reliquary/error (ex-data e)))))))

(deftest an-account-with-no-licenses-still-completes
  (testing "the license list is pushed unsolicited and may never arrive"
    (let [c (conn/conn-with-send-fn (fn [_]))]
      (future (Thread/sleep 50)
              (conn/deliver-frame! c (unsolicited :logon-response "CMsgClientLogonResponse"
                                                  {:eresult 1 :heartbeat-seconds 9})))
      (let [r (binding [client/*license-timeout-ms* 100]
                (client/logon-on! c token "me"))]
        (is (= "76561198000000000" (:steamid r)) "falls back to the token's sub claim")
        (is (= [] (client/licenses c)))))))

(deftest the-heartbeat-thread-comes-back-on-the-session-map
  (testing "session/close! cannot stop a thread it has no handle on"
    (let [c (conn/conn-with-send-fn (fn [_]))]
      (future (Thread/sleep 50)
              (conn/deliver-frame! c (unsolicited :logon-response "CMsgClientLogonResponse"
                                                  {:eresult 1 :heartbeat-seconds 9})))
      (let [r (binding [client/*license-timeout-ms* 100] (client/logon-on! c token "me"))
            ^Thread hb (:heartbeat r)]
        (is (instance? Thread hb))
        (is (.isAlive hb))
        (.interrupt hb)
        (.join hb 2000)
        (is (not (.isAlive hb)) "the interrupt must land in the sleep, not wait ~9s")))))

;; ---- the server-fallback loop -----------------------------------------------

(deftest a-raw-transport-failure-closes-the-socket-and-tries-the-next-server
  (testing "conn/send! bottoms out in a .get that throws raw Timeout/Execution
            exceptions -- catching only ExceptionInfo leaked the socket and
            skipped the fallback"
    (let [tried (atom [])
          closed (atom [])]
      (with-redefs [disc/cm-servers (fn [] ["wss://a" "wss://b" "wss://c"])
                    conn/connect! (fn [url] (swap! tried conj url) {:url url})
                    conn/close! (fn [c] (swap! closed conj (:url c)))
                    client/logon-on! (fn [c _ _]
                                       (if (= "wss://a" (:url c))
                                         (throw (TimeoutException. "socket died mid-logon"))
                                         {:conn c :steamid "76561198000000000" :heartbeat nil}))]
        (is (= "76561198000000000" (:steamid (client/logon! token "me"))))
        (is (= ["wss://a" "wss://b"] @tried) "the next CM server must be tried")
        (is (= ["wss://a"] @closed) "the dead connection must be closed, not leaked")))))

(deftest a-rejected-token-still-refuses-to-try-another-server
  (testing "every CM will reject the same token; retrying only wastes the user's time"
    (let [tried (atom [])]
      (with-redefs [disc/cm-servers (fn [] ["wss://a" "wss://b" "wss://c"])
                    conn/connect! (fn [url] (swap! tried conj url) {:url url})
                    conn/close! (fn [_])
                    client/logon-on! (fn [_ _ _]
                                       (throw (ex-info "steam logon failed, eresult 5"
                                                       {:reliquary/error :unauthenticated :eresult 5})))]
        (let [e (try (client/logon! token "me") nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :unauthenticated (:reliquary/error (ex-data e))))
          (is (= ["wss://a"] @tried)))))))

(deftest logon-sends-linux-as-the-os-type
  (let [sent (atom nil)
        c (conn/conn-with-send-fn (fn [^bytes b] (reset! sent b)))]
    (future (Thread/sleep 50)
            (conn/deliver-frame! c (unsolicited :logon-response "CMsgClientLogonResponse"
                                                {:eresult 1})))
    (binding [client/*license-timeout-ms* 100]
      (client/logon-on! c token "me"))
    (let [body (proto/decode "CMsgClientLogon" (:body (env/decode-message @sent)))]
      (is (= 16 (:client-os-type body)))
      (is (= 65580 (:protocol-version body)))
      (is (= token (:access-token body)) "the REFRESH token goes in access_token"))))
