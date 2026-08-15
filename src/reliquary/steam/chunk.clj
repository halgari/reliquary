;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.chunk
  "One Steam depot chunk, end to end: CDN fetch -> AES decrypt -> vzip
   decompress -> verify. Knows nothing about mauvi's blocks, the store, or
   FUSE -- mauvi.source.steam-depot does that mapping.

   THE CHUNK ID HASHES THE DECODED PLAINTEXT, not the encrypted bytes the CDN
   serves (read off the mauvi-clj reference, not recalled -- spec 2c §1). Two
   consequences, both load-bearing:

     1. The hash CANNOT gate decryption. A stale or wrong depot key is not
        rejected before the work; it produces garbage that fails either vzip
        (no recognizable header) or the SHA-1 check afterwards. So the mismatch
        message names BOTH causes rather than asserting one, and carries the
        depot id so an operator can clear that key.

     2. It is the safety net under reliquary.steam.slice's offset arithmetic, which
        was ported rather than rederived precisely because a mistake there
        yields plausible bytes at the wrong offset instead of an error."
  (:require [reliquary.error :as error]
            [reliquary.steam.cdn :as cdn]
            [reliquary.steam.crypto :as crypto]
            [reliquary.steam.manifest :as manifest]
            [reliquary.steam.vzip :as vzip])
  (:import (java.security MessageDigest)))

(defn sha1-hex
  "Lowercase hex SHA-1. Public because the fixture test and the capture script
   both need to reproduce a chunk id."
  ^String [^bytes b]
  (let [d  (.digest (MessageDigest/getInstance "SHA-1") b)
        sb (StringBuilder. (* 2 (alength d)))]
    (dotimes [i (alength d)]
      (.append sb (format "%02x" (bit-and 0xFF (long (aget d i))))))
    (.toString sb)))

(defn fetch-decoded
  "The decoded original bytes of one depot chunk.

   Every failure is categorized before it returns. This runs inside a FUSE
   read callback, but an uncategorized exception reaching one does NOT tear
   down the mount: aether.vfs.fuse/guarded already catches Throwable and maps
   it to -EIO, so the mount survives regardless. Categorizing still matters
   because cli/-main maps :reliquary/error categories to exit codes, and an
   uncategorized exception here would surface to a user as an opaque stack
   trace instead of an actionable message.

   A verification failure is never cached and never retried: mauvi.source
   already refuses to cache a block of the wrong length on the same reasoning,
   and silently serving a bad chunk produces corrupt game assets.

   `stats`, when non-nil, is an atom bumped with :chunks-fetched,
   :bytes-downloaded (encrypted wire bytes) and :bytes-decoded. It exists so
   the 2c gate can assert on real streaming volume rather than on a DXVK marker
   that fires before much has been read."
  ^bytes [{:keys [hosts depot-id key-hex chunk stats]}]
  (let [id       (:id chunk)
        ^bytes enc (cdn/fetch-with-rotation
                    hosts
                    (fn [host] (str "https://" host "/depot/" depot-id "/chunk/" id))
                    "chunk")
        ^bytes decoded
        (try
          (vzip/decompress (crypto/symmetric-decrypt (manifest/hex->bytes key-hex) enc))
          (catch clojure.lang.ExceptionInfo e
            (throw e))                       ; already categorized, whether by
                                              ; vzip's format check or
                                              ; crypto's short-ciphertext raise
          (catch Exception e
            ;; BadPaddingException and the LZMA decoder's own failures land
            ;; here. Never quote the key or the exception message, which can
            ;; echo cipher state; the class name is enough to triage.
            (error/raise :incorrect
                         (str "chunk " id " failed to decode -- corrupt download or "
                              "wrong depot key (" (.getSimpleName (class e)) ")")
                         {:depot-id depot-id :chunk-id id})))]
    (when (not= id (sha1-hex decoded))
      (error/raise :incorrect
                   (str "chunk " id " failed its sha1 -- corrupt download or "
                        "wrong depot key")
                   {:depot-id depot-id :chunk-id id}))
    (when (not= (long (:cb-original chunk)) (alength decoded))
      (error/raise :incorrect
                   (str "chunk " id " decoded to the wrong length")
                   {:depot-id depot-id :chunk-id id
                    :expected (long (:cb-original chunk)) :actual (alength decoded)}))
    (when stats
      (swap! stats (fn [s] (-> s
                               (update :chunks-fetched (fnil inc 0))
                               (update :bytes-downloaded (fnil + 0) (alength enc))
                               (update :bytes-decoded (fnil + 0) (alength decoded))))))
    decoded))
