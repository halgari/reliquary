;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.kv
  "Valve KeyValues, in both of the shapes PICS uses.

   `parse` reads the BINARY form: a tree of typed nodes, names as NUL-terminated
   C strings, leaves as strings, int32s or uint64s. PICS *package* blobs are
   this. `parse-text` reads the TEXT VDF form -- quoted key/value pairs and
   braced objects. PICS *app* blobs are this. Same concept, two encodings, one
   namespace.

   Neither is protobuf, which is why spec 1 SS8's namespace list could not reach
   a depot id: nothing in it could read either format."
  (:require [reliquary.error :as error])
  (:import (java.nio.charset StandardCharsets)))

;; ---- binary --------------------------------------------------------------

(defn- read-cstr
  "The UTF-8 C string at `off`; returns [s next-offset] past the NUL."
  [^bytes buf ^long off]
  (let [n (alength buf)]
    (loop [i off]
      (cond
        (>= i n) (error/raise :incorrect "unterminated kv string")
        (zero? (aget buf i)) [(String. buf (int off) (int (- i off)) StandardCharsets/UTF_8)
                              (inc i)]
        :else (recur (inc i))))))

(defn- read-i32 ^long [^bytes buf ^long off]
  (when (> (+ off 4) (alength buf))
    (error/raise :incorrect "truncated kv int32"))
  (unchecked-int
   (bit-or (bit-and 0xFF (long (aget buf off)))
           (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 1)))) 8)
           (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 2)))) 16)
           (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 3)))) 24))))

(defn- read-u64 ^long [^bytes buf ^long off]
  (when (> (+ off 8) (alength buf))
    (error/raise :incorrect "truncated kv uint64"))
  (bit-or (bit-and 0xFF (long (aget buf off)))
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 1)))) 8)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 2)))) 16)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 3)))) 24)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 4)))) 32)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 5)))) 40)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 6)))) 48)
          (bit-shift-left (bit-and 0xFF (long (aget buf (+ off 7)))) 56)))

(defn- parse-object
  "Children until the 0x08 end byte; returns [map next-offset]."
  [^bytes buf ^long off]
  (loop [o off acc {}]
    (when (>= o (alength buf))
      (error/raise :incorrect "unterminated kv object"))
    (let [type (bit-and 0xFF (long (aget buf o)))]
      (cond
        (= type 0x08) [acc (inc o)]
        (= type 0x00) (let [[nm o1]   (read-cstr buf (inc o))
                            [child o2] (parse-object buf (long o1))]
                        (recur (long o2) (assoc acc nm child)))
        (= type 0x01) (let [[nm o1] (read-cstr buf (inc o))
                            [v o2]  (read-cstr buf (long o1))]
                        (recur (long o2) (assoc acc nm v)))
        (= type 0x02) (let [[nm o1] (read-cstr buf (inc o))
                            o1 (long o1)]
                        (recur (+ o1 4) (assoc acc nm (read-i32 buf o1))))
        ;; uint64 stays a STRING: gids and package ids run past 2^63, and a
        ;; signed long would silently go negative.
        (= type 0x07) (let [[nm o1] (read-cstr buf (inc o))
                            o1 (long o1)]
                        (recur (+ o1 8)
                               (assoc acc nm (Long/toUnsignedString (read-u64 buf o1)))))
        :else (error/raise :incorrect (str "unknown kv type " type " at offset " o)
                           {:type type :offset o})))))

(defn parse
  "A binary KV blob -> a nested map of string names. The document is one named
   root object: 0x00, name, children, 0x08."
  [^bytes buf]
  (when (zero? (alength buf))
    (error/raise :incorrect "empty kv buffer"))
  (let [[nm o]    (read-cstr buf 1)
        [child _] (parse-object buf (long o))]
    {nm child}))

;; ---- text VDF ------------------------------------------------------------

(defn- ws? [c]
  (or (= c \space) (= c \tab) (= c \newline) (= c \return)))

(defn- skip-ws ^long [^String s ^long n ^long i]
  (loop [i i] (if (and (< i n) (ws? (.charAt s (int i)))) (recur (inc i)) i)))

(defn- read-tstr
  "`i` at the opening quote; returns [s next-index] past the closing quote.
   A backslash escapes the next character."
  [^String s ^long n ^long i]
  (loop [j (inc i) acc (StringBuilder.)]
    (when (>= j n) (error/raise :incorrect "unterminated vdf string"))
    (let [c (.charAt s (int j))]
      (cond
        (= c \") [(.toString ^StringBuilder acc) (inc j)]
        (= c \\) (do (when (>= (inc j) n) (error/raise :incorrect "vdf escape at end of input"))
                     (recur (+ j 2) (.append ^StringBuilder acc (.charAt s (int (inc j))))))
        :else (recur (inc j) (.append ^StringBuilder acc c))))))

(defn- parse-tobj
  "`i` just past '{'; returns [map next-index] past the matching '}'."
  [^String s ^long n ^long i]
  (loop [i (skip-ws s n i) acc {}]
    (when (>= i n) (error/raise :incorrect "unterminated vdf object"))
    (let [c (.charAt s (int i))]
      (cond
        (= c \}) [acc (inc i)]
        (= c \") (let [[k i1] (read-tstr s n i)
                       i2     (skip-ws s n (long i1))]
                   (when (>= i2 n) (error/raise :incorrect "vdf key with no value"))
                   (if (= (.charAt s (int i2)) \{)
                     (let [[child i3] (parse-tobj s n (inc i2))]
                       (recur (skip-ws s n (long i3)) (assoc acc k child)))
                     (let [[v i3] (read-tstr s n i2)]
                       (recur (skip-ws s n (long i3)) (assoc acc k v)))))
        :else [acc i]))))

(defn parse-text
  "A text-VDF document -> a nested map of string keys. One root key wrapping an
   object; every leaf is a string."
  [^String s]
  (let [n (.length s)
        i (skip-ws s n 0)]
    (when (>= i n) (error/raise :incorrect "empty vdf document"))
    (let [[k i1]    (read-tstr s n i)
          i2        (skip-ws s n (long i1))
          [child _] (parse-tobj s n (inc i2))]
      {k child})))
