;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.multi-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.multi :as multi])
  (:import (java.io ByteArrayOutputStream)
           (java.nio ByteBuffer ByteOrder)
           (java.util Base64)
           (java.util.zip GZIPOutputStream)))

(defn- framed
  "The payload shape Steam uses inside a Multi: LE uint32 length, then bytes."
  ^bytes [packets]
  (let [total (reduce + (map #(+ 4 (alength ^bytes %)) packets))
        bb (doto (ByteBuffer/allocate total) (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [^bytes p packets]
      (.putInt bb (alength p))
      (.put bb p))
    (.array bb)))

(defn- gzipped ^bytes [^bytes raw]
  (let [out (ByteArrayOutputStream.)]
    (with-open [g (GZIPOutputStream. out)] (.write g raw))
    (.toByteArray out)))

(defn- b64 [^bytes b] (.encodeToString (Base64/getEncoder) b))

(def ^:private one (byte-array [1 2 3]))
(def ^:private two (byte-array [9 8 7 6 5]))

(deftest expands-an-uncompressed-batch
  (testing "size-unzipped 0 means the body is raw"
    (let [out (multi/expand {:message-body (b64 (framed [one two])) :size-unzipped 0})]
      (is (= 2 (count out)))
      (is (= [1 2 3] (vec (first out))))
      (is (= [9 8 7 6 5] (vec (second out)))))))

(deftest expands-a-gzipped-batch
  (let [raw (framed [one two])
        out (multi/expand {:message-body (b64 (gzipped raw)) :size-unzipped (alength raw)})]
    (is (= 2 (count out)))
    (is (= [1 2 3] (vec (first out))))))

(deftest a-missing-size-unzipped-is-treated-as-uncompressed
  (testing "the field is absent, not zero, when the proto layer omits it"
    (let [out (multi/expand {:message-body (b64 (framed [one]))})]
      (is (= 1 (count out)))
      (is (= [1 2 3] (vec (first out)))))))

(deftest an-empty-batch-yields-nothing
  (is (= [] (multi/expand {:message-body (b64 (byte-array 0)) :size-unzipped 0}))))

(deftest a-single-packet-batch-works
  (is (= 1 (count (multi/expand {:message-body (b64 (framed [two])) :size-unzipped 0})))))

;; ---- truncation -------------------------------------------------------------
;;
;; Arrays/copyOfRange zero-pads rather than throwing, so an unvalidated slice
;; turns a cut-short batch into a silently corrupt packet.

(defn- expand-err [body]
  (try (multi/expand body) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest a-cut-short-length-header-is-a-frame-error
  (testing "fewer than 4 trailing bytes cannot be a length"
    (let [short-header (byte-array [1 0 0])   ;; 3 bytes where a 4-byte length goes
          e (expand-err {:message-body (b64 short-header) :size-unzipped 0})]
      (is (some? e) "a 3-byte payload must not be read as a length")
      (is (= :unavailable (:reliquary/error (ex-data e))))
      (is (clojure.string/includes? (ex-message e) "truncated multi batch")))))

(deftest a-declared-length-that-overruns-the-body-is-a-frame-error
  (testing "without this the operator sees a protobuf decode error instead"
    (let [bb (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN))
          _ (doto bb (.putInt 99) (.putInt 0))    ;; claims 99 bytes, supplies 4
          e (expand-err {:message-body (b64 (.array bb)) :size-unzipped 0})]
      (is (some? e))
      (is (= :unavailable (:reliquary/error (ex-data e))))
      (is (clojure.string/includes? (ex-message e) "truncated multi batch")))))

(deftest a-hostile-length-does-not-attempt-a-two-gigabyte-allocation
  (testing "0x77359400 is ~2 GB; the guard must reject it on the declared size"
    (let [bb (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN))
          _ (doto bb (.putInt (unchecked-int 0x77359400)) (.putInt 0))
          e (expand-err {:message-body (b64 (.array bb)) :size-unzipped 0})]
      (is (some? e))
      (is (= :unavailable (:reliquary/error (ex-data e))))
      (is (clojure.string/includes? (ex-message e) "2000000000")
          "the declared length is read unsigned, not as a negative int"))))

(deftest lengths-are-read-little-endian
  (testing "a 300-byte packet needs two length bytes; big-endian would misread it"
    (let [big (byte-array 300 (byte 7))
          out (multi/expand {:message-body (b64 (framed [big])) :size-unzipped 0})]
      (is (= 300 (alength ^bytes (first out)))))))
