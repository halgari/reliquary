;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.vzip
  "Unwrap a Steam CDN payload. Three formats, dispatched by header: VZip/LZMA
   (0x5A56), a single-entry ZIP local header (PK\\x03\\x04), or a bare zlib
   stream.

   Spec 2a assigned this namespace to spec 2c, on the assumption it was only
   for chunks. It is not: the CDN serves a depot MANIFEST as a single-entry
   zip, so 2b needs the zip branch on its critical path. The LZMA branch is
   ported here and proved in 2c, against a captured chunk."
  (:require [reliquary.error :as error])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util Arrays)
           (java.util.zip Inflater)
           (org.tukaani.xz LZMAInputStream)))

(def ^:private VZIP-HEADER 0x5A56)
(def ^:private VZIP-FOOTER 0x767A)

(defn- read-u16-le ^long [^bytes b ^long i]
  (bit-or (bit-and 0xFF (long (aget b i)))
          (bit-shift-left (bit-and 0xFF (long (aget b (+ i 1)))) 8)))

(defn- read-u32-le ^long [^bytes b ^long i]
  (bit-or (bit-and 0xFF (long (aget b i)))
          (bit-shift-left (bit-and 0xFF (long (aget b (+ i 1)))) 8)
          (bit-shift-left (bit-and 0xFF (long (aget b (+ i 2)))) 16)
          (bit-shift-left (bit-and 0xFF (long (aget b (+ i 3)))) 24)))

(defn- read-i32-le ^long [^bytes b ^long i]
  (unchecked-int
   (bit-or (bit-and 0xFF (long (aget b i)))
           (bit-shift-left (bit-and 0xFF (long (aget b (+ i 1)))) 8)
           (bit-shift-left (bit-and 0xFF (long (aget b (+ i 2)))) 16)
           (bit-shift-left (bit-and 0xFF (long (aget b (+ i 3)))) 24))))

;; to-lzma-stream reads the 5 property bytes at 7..12 and a 10-byte footer, so
;; 12 + 10 = 22 is the real fixed overhead. vzip? accepted 17, which let a
;; 17..21 byte buffer with a VZ header reach the LZMA branch and fail inside
;; to-lzma-stream on a negative-length copyOfRange instead of falling through
;; to the other formats. (2b follow-up; 2c is the spec that reaches it.)
(def ^:private VZIP-MIN-LEN 22)

(defn vzip? [^bytes buf]
  (and (>= (alength buf) VZIP-MIN-LEN)
       (= VZIP-HEADER (read-u16-le buf 0))))

(defn to-lzma-stream
  "Build a .lzma-alone stream from a VZip buffer. Layout: 7-byte header, 5
   property bytes (7..12), LZMA payload, 10-byte footer (crc4 + size4 + magic2).
   The 5 property bytes are 1 props byte + 4 LE dict size; appending an 8-byte
   LE uncompressed size completes the 13-byte alone header LZMAInputStream
   wants.

   Steam's payload is SIZE-terminated with no end-of-stream marker, so the REAL
   size must be written here. Writing size-unknown removes the only terminator
   the stream has -- do not 'simplify' this."
  ^bytes [^bytes buf]
  (when (not= 0x61 (bit-and 0xFF (long (aget buf 2))))
    (error/raise :incorrect "unsupported vzip version"))
  (when (not= VZIP-FOOTER (read-u16-le buf (- (alength buf) 2)))
    (error/raise :incorrect "vzip footer magic mismatch"))
  (let [len     (alength buf)
        props   (Arrays/copyOfRange buf 7 12)
        size    (read-i32-le buf (- len 6))
        payload (Arrays/copyOfRange buf 12 (int (- len 10)))
        out     (byte-array (+ 5 8 (alength payload)))]
    (System/arraycopy props 0 out 0 5)
    (dotimes [i 4]                       ; low 32 bits; the high 4 stay zero
      (aset out (+ 5 i) (unchecked-byte (bit-shift-right size (* 8 i)))))
    (System/arraycopy payload 0 out 13 (alength payload))
    out))

(defn- lzma-decode ^bytes [^bytes alone]
  (with-open [in (LZMAInputStream. (ByteArrayInputStream. alone))]
    (.readAllBytes in)))

(defn- inflate
  "Inflate into a byte[]. `nowrap` selects raw deflate (true) over zlib (false)."
  ^bytes [^bytes buf nowrap]
  (let [inf (Inflater. (boolean nowrap))
        out (ByteArrayOutputStream. (alength buf))
        tmp (byte-array 8192)]
    (.setInput inf buf)
    (try
      (loop []
        (when-not (.finished inf)
          (let [n (.inflate inf tmp)]
            (if (zero? n)
              (when (or (.needsInput inf) (.needsDictionary inf))
                (error/raise :incorrect "truncated deflate stream"))
              (.write out tmp 0 n))
            (recur))))
      (catch java.util.zip.DataFormatException e
        (error/raise :incorrect (str "malformed deflate stream: " (.getMessage e))))
      (finally (.end inf)))
    (.toByteArray out)))

(defn- unzip-pk
  "A single-entry ZIP local header: raw-inflate a deflated entry (method 8) or
   return a stored one (method 0) as-is.

   ZipOutputStream in STORED mode writes a data descriptor after the entry
   body, so an unbounded slice to the end of the buffer would also pick up
   trailing central-directory bytes. Bound the slice by the local header's
   compressed-size field (offset 18) when it is known; fall back to the
   unbounded slice when csize is 0, which happens for streamed entries whose
   real size lives only in the (post-body) data descriptor. Guard BEFORE the
   reads, not after — the fixed local header is 30 bytes."
  ^bytes [^bytes buf]
  ;; Guard BEFORE the reads, not after. The fixed local header is 30 bytes and
  ;; this fn reads offsets 8, 18, 26 and 28 of it; on the live CDN path a
  ;; truncated response used to surface as ArrayIndexOutOfBoundsException.
  ;; aether.vfs.fuse/guarded catches that regardless and keeps the mount up,
  ;; but as a bare -EIO instead of the actionable :incorrect raised below --
  ;; and cli/-main's exit-code mapping needs the category too. (2b follow-up,
  ;; flagged there as the highest-value of them because it is on the live path.)
  (when (< (alength buf) 30)
    (error/raise :incorrect "zip local header is truncated"
                 {:length (alength buf)}))
  (let [method (read-u16-le buf 8)
        nlen   (read-u16-le buf 26)
        elen   (read-u16-le buf 28)
        start  (+ 30 nlen elen)]
    (when (> start (alength buf))
      (error/raise :incorrect "zip local header longer than the payload"))
    (let [csize (read-u32-le buf 18)
          end   (if (pos? csize) (min (alength buf) (+ start csize)) (alength buf))
          entry (Arrays/copyOfRange buf (int start) (int end))]
      (if (zero? method) entry (inflate entry true)))))

(defn decompress
  "Unwrap a Steam CDN payload -> the raw bytes."
  ^bytes [^bytes buf]
  (cond
    (vzip? buf)                                          (lzma-decode (to-lzma-stream buf))
    (and (>= (alength buf) 4) (= 0x04034B50 (read-u32-le buf 0))) (unzip-pk buf)
    :else                                                (inflate buf false)))
