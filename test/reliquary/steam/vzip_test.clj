;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.vzip-test
  "The zip and zlib branches only. The LZMA branch is exercised by spec 2c
   against a CAPTURED chunk -- deliberately not by a synthetic round-trip here,
   because a synthetic round-trip is exactly what passed while the reference
   decoder was broken (it patched the size field, and Steam's LZMA payloads are
   size-terminated with no EOS marker, so only a real chunk catches it)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.vzip :as vzip])
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip Deflater DeflaterOutputStream ZipEntry ZipOutputStream)))

(defn- sha1-hex ^String [^bytes b]
  (let [d  (.digest (java.security.MessageDigest/getInstance "SHA-1") b)
        sb (StringBuilder. (* 2 (alength d)))]
    (dotimes [i (alength d)]
      (.append sb (format "%02x" (bit-and 0xFF (long (aget d i))))))
    (.toString sb)))

(def ^:private payload
  (.getBytes (apply str (repeat 200 "Data/Skyrim.esm\n")) "UTF-8"))

(defn- single-entry-zip ^bytes [^bytes body deflated?]
  (let [out (ByteArrayOutputStream.)]
    (with-open [z (ZipOutputStream. out)]
      (.setMethod z (if deflated? ZipOutputStream/DEFLATED ZipOutputStream/STORED))
      (let [e (ZipEntry. "z")]
        (when-not deflated?
          (.setSize e (alength body))
          (.setCompressedSize e (alength body))
          (.setCrc e (.getValue (doto (java.util.zip.CRC32.) (.update body)))))
        (.putNextEntry z e)
        (.write z body)
        (.closeEntry z)))
    (.toByteArray out)))

(defn- zlib ^bytes [^bytes body]
  (let [out (ByteArrayOutputStream.)]
    (with-open [d (DeflaterOutputStream. out (Deflater.))]
      (.write d body))
    (.toByteArray out)))

(deftest unwraps-a-deflated-single-entry-zip
  (testing "this is exactly how the CDN serves a depot manifest"
    (is (= (seq payload) (seq (vzip/decompress (single-entry-zip payload true)))))))

(deftest unwraps-a-stored-single-entry-zip
  (testing "method 0 means the entry is already raw -- inflating it would fail"
    (is (= (seq payload) (seq (vzip/decompress (single-entry-zip payload false)))))))

(deftest inflates-a-bare-zlib-stream
  (is (= (seq payload) (seq (vzip/decompress (zlib payload))))))

(deftest a-truncated-zlib-stream-is-incorrect-not-a-silent-short-read
  (let [z (zlib payload)
        e (try (vzip/decompress (java.util.Arrays/copyOfRange z 0 (quot (alength z) 2))) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest vzip?-recognises-only-the-vzip-magic
  (is (false? (vzip/vzip? (single-entry-zip payload true))))
  (is (false? (vzip/vzip? (byte-array 4))))
  (is (true? (vzip/vzip? (let [b (byte-array 32)]
                           (aset b 0 (unchecked-byte 0x56))
                           (aset b 1 (unchecked-byte 0x5A))
                           b)))))

(deftest a-truncated-zip-header-is-incorrect-not-an-index-error
  (testing "unzip-pk bounds-checks BEFORE reading offsets 8/18/26/28"
    ;; A valid PK\x03\x04 magic and nothing else. decompress dispatches to
    ;; unzip-pk on the magic, which then read offsets 8, 18, 26 and 28 of a
    ;; 4-byte array. On the live CDN path a truncated response must raise
    ;; :incorrect, never ArrayIndexOutOfBoundsException.
    (let [buf (byte-array [0x50 0x4B 0x03 0x04])
          e   (try (vzip/decompress buf) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "a 4-byte zip must not decode")
      (is (= :incorrect (:reliquary/error (ex-data e))))))

  (testing "a header truncated mid-way is also :incorrect"
    ;; 20 bytes: past offset 8 and 18, but short of 26/28.
    (let [buf (byte-array 20)
          _   (do (aset buf 0 (unchecked-byte 0x50)) (aset buf 1 (unchecked-byte 0x4B))
                  (aset buf 2 (unchecked-byte 0x03)) (aset buf 3 (unchecked-byte 0x04)))
          e   (try (vzip/decompress buf) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :incorrect (:reliquary/error (ex-data e)))))))

(deftest vzip?-requires-the-full-fixed-overhead
  (testing "a buffer too short for to-lzma-stream is not claimed as vzip"
    ;; to-lzma-stream reads props at 7..12 and a 10-byte footer, so its real
    ;; fixed overhead is 22 bytes, not the 17 vzip? used to accept. A 17..21
    ;; byte buffer with a VZ header would be routed to the LZMA branch and
    ;; then blow up inside it on a negative-length copyOfRange.
    (doseq [n (range 17 22)]
      (let [buf (byte-array n)]
        (aset buf 0 (unchecked-byte 0x56))   ; 'V'
        (aset buf 1 (unchecked-byte 0x5A))   ; 'Z'
        (is (not (vzip/vzip? buf))
            (str n " bytes is below to-lzma-stream's 22-byte overhead")))))

  (testing "22 bytes with a VZ header still is"
    (let [buf (byte-array 22)]
      (aset buf 0 (unchecked-byte 0x56))
      (aset buf 1 (unchecked-byte 0x5A))
      (is (vzip/vzip? buf)))))

(deftest the-lzma-branch-decodes-a-real-captured-chunk
  (testing "against a chunk captured from the CDN, not a synthetic round-trip"
    ;; 2b wrote this branch and deliberately left it unproven, noting that a
    ;; compressor and decompressor built on the same wrong assumption agree
    ;; with each other. This is the only test that decodes real Steam LZMA
    ;; output. The fixture is committed DECRYPTED but still compressed, so no
    ;; depot key is needed and this never skips (spec 2c §8).
    (let [meta    (edn/read-string (slurp (io/resource "steam/chunk.edn")))
          ^bytes vz (with-open [in (io/input-stream (io/resource "steam/chunk.vz"))]
                      (.readAllBytes in))]
      (is (vzip/vzip? vz) "the fixture must exercise the LZMA branch, not zlib")
      (let [decoded (vzip/decompress vz)]
        (is (= (:cb-original meta) (alength decoded))
            "decoded length matches what the manifest declared")
        (is (= (:id meta) (sha1-hex decoded))
            "the chunk id is the SHA-1 of the DECODED plaintext")))))

;; ---------------------------------------------------------------------------
;; VSZa -- Valve's Zstandard container
;;
;; Steam began serving these and the decoder did not know them, so they fell
;; through to `inflate` and died with "malformed deflate stream". Found against
;; the live CDN: two of the six chunks of Skyrim SE's launcher on the build
;; shipped 2026-08-20 are VSZa, and every download or switch touching one of
;; them failed.

(defn- vsza
  "A VSZa buffer around `payload`, in the layout the CDN serves: 8-byte header
   ('VSZa' + 4 bytes), the zstd frame, then a 15-byte footer."
  [^bytes raw]
  (let [comp   (io.airlift.compress.zstd.ZstdCompressor.)
        buf    (byte-array (.maxCompressedLength comp (alength raw)))
        n      (.compress comp raw 0 (alength raw) buf 0 (alength buf))
        out    (java.io.ByteArrayOutputStream.)]
    (.write out (byte-array (map unchecked-byte [0x56 0x53 0x5A 0x61  0xA3 0x9A 0x6D 0x84])))
    (.write out buf 0 n)
    ;; crc(4) + size(4) + 4 + magic "zsv"(3)
    (.write out (byte-array (map unchecked-byte [0xA3 0x9A 0x6D 0x84])))
    (.write out (byte-array [(unchecked-byte (bit-and (alength raw) 0xFF))
                             (unchecked-byte (bit-and (bit-shift-right (alength raw) 8) 0xFF))
                             (unchecked-byte (bit-and (bit-shift-right (alength raw) 16) 0xFF))
                             (unchecked-byte (bit-and (bit-shift-right (alength raw) 24) 0xFF))]))
    (.write out (byte-array 4))
    (.write out (byte-array (map unchecked-byte [0x7A 0x73 0x76])))
    (.toByteArray out)))

(deftest unwraps-a-vszip-chunk
  (let [raw (.getBytes (apply str (repeat 500 "reliquary ")) "UTF-8")]
    (is (= (seq raw) (seq (vzip/decompress (vsza raw)))))))

(deftest vszip?-recognises-only-the-vszip-magic
  (is (true? (vzip/vszip? (vsza (.getBytes "x" "UTF-8")))))
  (is (false? (vzip/vszip? (byte-array (repeat 40 0)))))
  (testing "VZ is not VSZ: the two differ in one byte of magic and entirely in
            payload format"
    (is (false? (vzip/vszip? (byte-array (map unchecked-byte
                                              (concat [0x56 0x5A 0x61] (repeat 30 0)))))))))

(deftest a-vszip-buffer-too-short-to-hold-a-frame-is-incorrect-not-a-crash
  (is (thrown? clojure.lang.ExceptionInfo
               (vzip/decompress (byte-array (map unchecked-byte
                                                 [0x56 0x53 0x5A 0x61 0 0 0 0
                                                  0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]))))))
