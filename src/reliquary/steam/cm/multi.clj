;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.multi
  "Expand a batched CMsgMulti into its constituent inner packets. The
  message-body (base64 from the proto layer) is raw when size-unzipped is 0/nil,
  else gzip-compressed and inflated first. The payload is a run of framed
  packets: a little-endian uint32 length followed by that many bytes."
  (:require [reliquary.error :as error])
  (:import (java.io ByteArrayInputStream)
           (java.util Arrays Base64)
           (java.util.zip GZIPInputStream)))

(defn- inflate ^bytes [^bytes raw]
  (with-open [in (GZIPInputStream. (ByteArrayInputStream. raw))]
    (.readAllBytes in)))

(defn expand
  "Given a decoded CMsgMulti map (`:message-body` base64 string, optional
  `:size-unzipped`), return a vector of inner packet byte[]s, each ready to feed
  back into decode-message."
  [body]
  (let [raw (.decode (Base64/getDecoder) ^String (:message-body body))
        payload (if (pos? (long (or (:size-unzipped body) 0)))
                  (inflate raw)
                  raw)
        n (alength ^bytes payload)]
    (loop [off 0, acc []]
      (if (>= off n)
        acc
        ;; Arrays/copyOfRange SILENTLY ZERO-PADS past the end of the array, so a
        ;; truncated batch would otherwise yield a corrupted packet and surface
        ;; downstream as an unrelated protobuf "input ended unexpectedly". The
        ;; declared length is a full unsigned 32 bits, so an unchecked slice is
        ;; also a ~2 GB allocation waiting for a corrupt or hostile frame.
        (do
          (when (> (+ off 4) n)
            (error/raise :unavailable
                         (str "truncated multi batch: " (- n off)
                              " bytes left, need 4 for a length header")))
          (let [len (bit-or (bit-and 0xFF (long (aget ^bytes payload off)))
                            (bit-shift-left (bit-and 0xFF (long (aget ^bytes payload (+ off 1)))) 8)
                            (bit-shift-left (bit-and 0xFF (long (aget ^bytes payload (+ off 2)))) 16)
                            (bit-shift-left (bit-and 0xFF (long (aget ^bytes payload (+ off 3)))) 24))
                start (+ off 4)
                end (+ start len)]
            (when (> end n)
              (error/raise :unavailable
                           (str "truncated multi batch: packet declares " len
                                " bytes but only " (- n start) " remain")))
            (recur end (conj acc (Arrays/copyOfRange ^bytes payload (int start) (int end))))))))))
