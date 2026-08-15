;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.envelope
  "CM message framing: a 4-byte LE EMsg (high bit = protobuf), a 4-byte LE
  header length, the CMsgProtoBufHeader, then the body. Ported from
  cm/envelope.cljs, with cm/emsg.cljs's EMsg table folded in."
  (:require [reliquary.steam.proto :as proto]
            [reliquary.error :as error])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.util Arrays)))

;; High bit of the 32-bit EMsg marks a protobuf-encoded message.
(def ^:const proto-mask 0x80000000)

(def e
  {:multi 1 :heartbeat 703 :logon-response 751 :logged-off 757
   :license-list 780 :logon 5514
   :pics-product-request 8903 :pics-product-response 8904
   :pics-token-request 8905 :pics-token-response 8906
   :depot-key-request 5438 :depot-key-response 5439
   :service-call 151 :service-response 147})

(def by-int (into {} (map (fn [[k v]] [v k])) e))

(defn encode-message
  "Frame a protobuf CM message: [LE uint32 (emsg | proto-mask)][LE uint32
  header-len][header bytes][body bytes]."
  ^bytes [^long emsg-int header-map ^bytes body]
  (let [^bytes head (proto/encode "CMsgProtoBufHeader" header-map)
        head-len    (alength head)
        body-len    (alength body)
        bb (doto (ByteBuffer/allocate (+ 8 head-len body-len))
             (.order ByteOrder/LITTLE_ENDIAN)
             ;; high bit marks a protobuf message; write unsigned
             (.putInt (unchecked-int (bit-or emsg-int (long proto-mask))))
             (.putInt (int head-len))
             (.put head)
             (.put body))]
    (.array bb)))

(defn decode-message
  "Read the LE uint32 EMsg; the high bit means protobuf. For protobuf messages
  decode the CMsgProtoBufHeader and return the trailing body as a byte[]."
  [^bytes packet]
  (let [n (alength packet)
        _ (when (< n 4)
            (error/raise :unavailable
                         (str "truncated CM packet: " n " bytes, need 4 for the EMsg word")))
        bb  (doto (ByteBuffer/wrap packet) (.order ByteOrder/LITTLE_ENDIAN))
        raw (bit-and (long (.getInt bb)) 0xFFFFFFFF)   ;; unsigned emsg word
        proto?   (not (zero? (bit-and raw (long proto-mask)))) ;; high bit = protobuf
        emsg-int (bit-and raw 0x7fffffff)]             ;; mask off the protobuf flag
    (if proto?
      (let [_ (when (< n 8)
                (error/raise :unavailable
                             (str "truncated CM packet: " n
                                  " bytes, need 8 for the header length")))
            head-len (bit-and (long (.getInt bb)) 0xFFFFFFFF)
            head-end (+ 8 head-len)
            ;; the declared header length is unsigned 32-bit and unvalidated on
            ;; the wire -- same reason multi/expand checks its frames
            _ (when (> head-end n)
                (error/raise :unavailable
                             (str "truncated CM packet: header declares " head-len
                                  " bytes but only " (- n 8) " remain")))
            header   (proto/decode "CMsgProtoBufHeader" (Arrays/copyOfRange packet 8 (int head-end)))
            body     (Arrays/copyOfRange packet (int head-end) (alength packet))]
        {:emsg emsg-int :proto? true :header header :body body})
      ;; legacy non-protobuf "struct" message — we don't need any of these; skip.
      {:emsg emsg-int :proto? false})))
