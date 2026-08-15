;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.envelope-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.proto :as proto])
  (:import (java.nio ByteBuffer ByteOrder)))

(deftest frames-round-trip
  (let [body (proto/encode "CMsgClientLogon" {:protocol-version 65580})
        packet (env/encode-message (:logon env/e) {:steamid "76561198000000000"} body)
        out (env/decode-message packet)]
    (is (= (:logon env/e) (:emsg out)))
    (is (:proto? out))
    (is (= "76561198000000000" (:steamid (:header out))))
    (is (= 65580 (:protocol-version (proto/decode "CMsgClientLogon" (:body out)))))))

(deftest the-protobuf-flag-is-set-and-then-masked-off
  (testing "the high bit marks protobuf; the emsg the caller sees must not carry it"
    (let [packet (env/encode-message (:logon env/e) {} (byte-array 0))
          word (-> (ByteBuffer/wrap packet) (.order ByteOrder/LITTLE_ENDIAN) (.getInt))]
      (is (neg? word) "high bit set means the signed int reads negative")
      (is (= (:logon env/e) (:emsg (env/decode-message packet)))))))

(deftest a-struct-message-is-reported-not-parsed
  (testing "legacy non-protobuf messages exist on the wire and must not crash the decoder"
    (let [bb (doto (ByteBuffer/allocate 8)
               (.order ByteOrder/LITTLE_ENDIAN)
               (.putInt 703)          ; heartbeat, WITHOUT the proto flag
               (.putInt 0))
          out (env/decode-message (.array bb))]
      (is (= 703 (:emsg out)))
      (is (not (:proto? out)))
      (is (nil? (:body out))))))

(deftest an-empty-body-frames-cleanly
  (let [out (env/decode-message (env/encode-message (:heartbeat env/e) {} (byte-array 0)))]
    (is (= 0 (alength ^bytes (:body out))))))

(deftest a-truncated-packet-is-a-categorized-frame-error
  (testing "the declared header length is unsigned and unvalidated on the wire"
    (let [err (fn [^bytes b] (try (env/decode-message b) nil
                                  (catch clojure.lang.ExceptionInfo e e)))
          overrun (doto (ByteBuffer/allocate 12)
                    (.order ByteOrder/LITTLE_ENDIAN)
                    (.putInt (unchecked-int (bit-or (:logon env/e) (long env/proto-mask))))
                    (.putInt 9999)      ; header claims 9999 bytes; 4 follow
                    (.putInt 0))]
      (doseq [[what b] [["a 2-byte packet" (byte-array 2)]
                        ["a proto packet with no header length" (.array (doto (ByteBuffer/allocate 4)
                                                                          (.order ByteOrder/LITTLE_ENDIAN)
                                                                          (.putInt (unchecked-int (bit-or (:logon env/e) (long env/proto-mask))))))]
                        ["a header length past the end" (.array overrun)]]]
        (let [e (err b)]
          (is (some? e) (str what " must not decode"))
          (is (= :unavailable (:reliquary/error (ex-data e))) what)
          (is (clojure.string/includes? (ex-message e) "truncated CM packet") what))))))

(deftest emsg-table-is-a-bijection
  (is (= (count env/e) (count env/by-int)))
  (doseq [[k v] env/e]
    (is (= k (get env/by-int v)))))

(deftest the-emsgs-the-session-needs-are-present
  (doseq [k [:multi :heartbeat :logon :logon-response :logged-off :license-list]]
    (is (contains? env/e k) (str "missing EMsg " k))))
