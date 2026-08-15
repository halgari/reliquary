;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.kv-test
  "Hand-built byte arrays, not a round-trip against our own writer: there is no
   writer, and a round-trip against one would only prove the reader agrees with
   itself. These bytes are laid out from the format's definition. The real PICS
   blobs in test/resources/steam/ are the external oracle, exercised in
   reliquary.steam.depots-test."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.kv :as kv]))

(defn- bs ^bytes [& xs]
  ;; xs: ints, or strings (appended as UTF-8 + NUL)
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [x xs]
      (if (string? x)
        (do (.write out (.getBytes ^String x "UTF-8")) (.write out 0))
        (.write out (int x))))
    (.toByteArray out)))

(deftest parses-a-nested-binary-kv-tree
  (let [buf (bs 0x00 "root"
                0x01 "name" "Skyrim"
                0x02 "count" 7 0 0 0
                0x00 "appids"
                     0x07 "0" 0x66 0x79 0x07 0x00 0x00 0x00 0x00 0x00
                0x08
                0x08)]
    (is (= {"root" {"name"   "Skyrim"
                    "count"  7
                    "appids" {"0" "489830"}}}
           (kv/parse buf)))))

(deftest a-uint64-leaf-stays-a-string-and-survives-the-high-bit
  (testing "manifest gids and package ids are uint64; a signed long goes
            negative above 2^63 and silently corrupts the id"
    (let [buf (bs 0x00 "root"
                  0x07 "gid" 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF
                  0x08)]
      (is (= {"root" {"gid" "18446744073709551615"}} (kv/parse buf))))))

(deftest an-unterminated-object-is-incorrect-not-an-index-error
  (let [e (try (kv/parse (bs 0x00 "root" 0x01 "k" "v")) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest an-unknown-type-byte-is-reported-with-its-offset
  (let [e (try (kv/parse (bs 0x00 "root" 0x63 "k" 0x08)) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))
    (is (re-find #"99" (ex-message e)) "the type byte, so a bad blob is diagnosable")))

(deftest an-empty-buffer-is-incorrect
  (let [e (try (kv/parse (byte-array 0)) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest parses-nested-text-vdf
  (is (= {"appinfo" {"appid" "489830"
                     "common" {"name" "Skyrim Special Edition" "type" "game"}
                     "depots" {"489831" {"manifests" {"public" {"gid" "845123"}}}}}}
         (kv/parse-text
          "\"appinfo\"\n{\n\t\"appid\"\t\"489830\"\n\t\"common\"\n\t{\n\t\t\"name\"\t\"Skyrim Special Edition\"\n\t\t\"type\"\t\"game\"\n\t}\n\t\"depots\"\n\t{\n\t\t\"489831\"\n\t\t{\n\t\t\t\"manifests\"\n\t\t\t{\n\t\t\t\t\"public\"\n\t\t\t\t{\n\t\t\t\t\t\"gid\"\t\"845123\"\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n"))))

(deftest text-vdf-honours-backslash-escapes
  (testing "Windows paths appear in launch config as escaped backslashes"
    (is (= {"r" {"path" "Data\\Skyrim.esm" "q" "a\"b"}}
           (kv/parse-text "\"r\" { \"path\" \"Data\\\\Skyrim.esm\" \"q\" \"a\\\"b\" }")))))
