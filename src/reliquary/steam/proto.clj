;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.proto
  "Protobuf ↔ Clojure-map bridge over the committed descriptor set
  (resources/steam/steam.desc, produced by protoc -- see bin/gen-protos.sh).
  One generic encode/decode serves every message via protobuf's
  descriptor-reflection API -- no per-message table, no generated classes.
  Message types are named by their bare proto name, e.g.
  (decode \"CMsgClientLogonResponse\" bytes).

  Three wire quirks are preserved from the original CLJS client because
  downstream code depends on them:
   - 64-bit fields (int64/uint64/fixed64/…) decode to STRINGS -- a JVM long
     can hold them, but keeping strings matches the original normalization and
     sidesteps unsigned-vs-signed rendering for callers (uint64/fixed64 render
     unsigned; sint64/int64/sfixed64 signed). encode accepts string or number.
   - bytes fields decode to BASE64 strings; encode accepts base64 or byte[].
   - NO defaults fill: an absent field decodes to nil (absent from the map),
     never \"\"/0. The QR poll flow distinguishes \"no refresh_token yet\" from
     an empty token -- a defaulted \"\" would read as a completed login."
  (:require [clojure.java.io :as io]
            [reliquary.error :as error])
  (:import (com.google.protobuf ByteString DynamicMessage
                                DescriptorProtos$FileDescriptorSet
                                Descriptors$Descriptor
                                Descriptors$FieldDescriptor
                                Descriptors$FieldDescriptor$Type
                                Descriptors$FileDescriptor)
           (java.util Base64)))

(defn- kebab [^String proto-name]
  (keyword (.replace proto-name \_ \-)))

(defn- snake [k]
  (.replace (name k) \- \_))

(def ^:private file-descriptors
  "Parsed once. Each file in the set has no imports and no package (verified:
   both protos are bare proto2), so every FileDescriptor builds with an empty
   dependency array -- there is no dependency graph to topologically sort."
  (delay
    (with-open [in (io/input-stream (io/resource "steam/steam.desc"))]
      (mapv (fn [fp]
              (Descriptors$FileDescriptor/buildFrom
               fp (make-array Descriptors$FileDescriptor 0)))
            (.getFileList (DescriptorProtos$FileDescriptorSet/parseFrom in))))))

(def ^:private descriptor
  (memoize
    (fn ^Descriptors$Descriptor [type-name]
      (or (some #(.findMessageTypeByName ^Descriptors$FileDescriptor % type-name)
                @file-descriptors)
          (error/raise :incorrect
                       (str "unknown protobuf message type: " type-name)
                       {:type-name type-name})))))

(defn message-type?
  "Whether `type-name` names a message in the descriptor set. Task 4's defcall
   macro calls this at MACROEXPANSION so a typo is a compile error rather than
   a runtime failure on a code path that only runs mid-login."
  [type-name]
  (boolean (some #(.findMessageTypeByName ^Descriptors$FileDescriptor % type-name)
                 @file-descriptors)))

;; ---- decode -----------------------------------------------------------------

(declare decode-msg)

(defn- decode-scalar [^Descriptors$FieldDescriptor fd v]
  (condp = (.getType fd)
    Descriptors$FieldDescriptor$Type/UINT64  (Long/toUnsignedString (long v))
    Descriptors$FieldDescriptor$Type/FIXED64 (Long/toUnsignedString (long v))
    Descriptors$FieldDescriptor$Type/INT64   (Long/toString (long v))
    Descriptors$FieldDescriptor$Type/SINT64  (Long/toString (long v))
    Descriptors$FieldDescriptor$Type/SFIXED64 (Long/toString (long v))
    Descriptors$FieldDescriptor$Type/BYTES   (.encodeToString (Base64/getEncoder)
                                                              (.toByteArray ^ByteString v))
    Descriptors$FieldDescriptor$Type/ENUM    (.getNumber ^com.google.protobuf.Descriptors$EnumValueDescriptor v)
    Descriptors$FieldDescriptor$Type/MESSAGE (decode-msg v)
    Descriptors$FieldDescriptor$Type/GROUP   (decode-msg v)
    v))

(defn- decode-msg [^com.google.protobuf.Message m]
  (persistent!
   (reduce (fn [acc [^Descriptors$FieldDescriptor fd v]]
             (assoc! acc (kebab (.getName fd))
                     (if (.isRepeated fd)
                       (mapv #(decode-scalar fd %) v)
                       (decode-scalar fd v))))
           (transient {})
           (.getAllFields m))))

(defn decode
  "Parse protobuf `data` (byte[]) of message `type-name` into a Clojure map
  containing only the fields actually present on the wire."
  [type-name ^bytes data]
  (decode-msg (DynamicMessage/parseFrom ^Descriptors$Descriptor (descriptor type-name) data)))

;; ---- encode -----------------------------------------------------------------

(declare build-msg)

(defn- coerce-scalar [^Descriptors$FieldDescriptor fd v]
  (condp = (.getType fd)
    Descriptors$FieldDescriptor$Type/UINT64  (if (string? v) (Long/parseUnsignedLong v) (long v))
    Descriptors$FieldDescriptor$Type/FIXED64 (if (string? v) (Long/parseUnsignedLong v) (long v))
    Descriptors$FieldDescriptor$Type/INT64   (if (string? v) (Long/parseLong v) (long v))
    Descriptors$FieldDescriptor$Type/SINT64  (if (string? v) (Long/parseLong v) (long v))
    Descriptors$FieldDescriptor$Type/SFIXED64 (if (string? v) (Long/parseLong v) (long v))
    Descriptors$FieldDescriptor$Type/INT32   (int v)
    Descriptors$FieldDescriptor$Type/UINT32  (int v)
    Descriptors$FieldDescriptor$Type/SINT32  (int v)
    Descriptors$FieldDescriptor$Type/FIXED32 (int v)
    Descriptors$FieldDescriptor$Type/SFIXED32 (int v)
    Descriptors$FieldDescriptor$Type/FLOAT   (float v)
    Descriptors$FieldDescriptor$Type/DOUBLE  (double v)
    Descriptors$FieldDescriptor$Type/BOOL    (boolean v)
    Descriptors$FieldDescriptor$Type/STRING  (str v)
    Descriptors$FieldDescriptor$Type/BYTES   (if (string? v)
                                               (ByteString/copyFrom (.decode (Base64/getDecoder) ^String v))
                                               (ByteString/copyFrom ^bytes v))
    Descriptors$FieldDescriptor$Type/ENUM    (.findValueByNumber (.getEnumType fd) (int v))
    Descriptors$FieldDescriptor$Type/MESSAGE (build-msg (.getMessageType fd) v)
    Descriptors$FieldDescriptor$Type/GROUP   (build-msg (.getMessageType fd) v)))

(defn- build-msg ^com.google.protobuf.Message [^Descriptors$Descriptor desc clj-map]
  (let [b (DynamicMessage/newBuilder desc)]
    (doseq [[k v] clj-map
            :when (some? v)
            :let [fd (.findFieldByName desc (snake k))]
            :when fd]
      (if (.isRepeated fd)
        (doseq [item v] (.addRepeatedField b fd (coerce-scalar fd item)))
        (.setField b fd (coerce-scalar fd v))))
    (.build b)))

(defn encode
  "Encode a Clojure map to protobuf bytes for message `type-name`. Unknown keys
  and nil values are ignored; keys map to fields by kebab→snake name."
  ^bytes [type-name clj-map]
  (.toByteArray (build-msg (descriptor type-name) clj-map)))
