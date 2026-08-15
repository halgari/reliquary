;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.chunk-test
  "The CDN chunk path with the socket stubbed. The real fetch/decrypt/decompress
   combination against live Steam is the job of reliquary.steam.cdn-e2e-test."
  (:require [clojure.test :refer [deftest is]]
            [reliquary.steam.cdn :as cdn]
            [reliquary.steam.chunk :as chunk]))

(def ^:private key-hex (apply str (repeat 64 "a")))   ; 32 bytes of 0xaa

(defn- with-wire
  "Run f with the CDN returning `body` and decrypt/decompress stubbed to the
   identity, so only chunk.clj's own logic is under test."
  [body f]
  (with-redefs [cdn/fetch-with-rotation (fn [_ _ _] body)
                reliquary.steam.crypto/symmetric-decrypt (fn [_ ct] ct)
                reliquary.steam.vzip/decompress (fn [b] b)]
    (f)))

(deftest returns-the-decoded-bytes-when-everything-agrees
  (let [payload (.getBytes "hello steam" "UTF-8")
        id      (chunk/sha1-hex payload)]
    (with-wire payload
      (fn []
        (let [got (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex
                                        :chunk {:id id :cb-original (alength payload)}})]
          (is (= (vec payload) (vec got))))))))

(deftest a-sha1-mismatch-is-incorrect-and-names-both-causes
  (let [payload (.getBytes "hello steam" "UTF-8")]
    (with-wire payload
      (fn []
        (let [e (try (chunk/fetch-decoded {:hosts ["h"] :depot-id 77 :key-hex key-hex
                                           :chunk {:id "00" :cb-original (alength payload)}})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :incorrect (:reliquary/error (ex-data e))))
          (is (= 77 (:depot-id (ex-data e)))
              "the depot id is needed to clear a stale key")
          (is (re-find #"(?i)wrong depot key" (ex-message e))
              "the hash covers the plaintext, so it cannot gate decryption -- a
               stale key and a corrupt download are indistinguishable here")
          (is (re-find #"(?i)corrupt" (ex-message e))))))))

(deftest a-length-mismatch-is-incorrect
  (let [payload (.getBytes "hello steam" "UTF-8")
        id      (chunk/sha1-hex payload)]
    (with-wire payload
      (fn []
        (let [e (try (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex
                                           :chunk {:id id :cb-original 999}})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :incorrect (:reliquary/error (ex-data e)))))))))

(deftest no-secret-reaches-the-error
  (let [payload (.getBytes "hello steam" "UTF-8")]
    (with-wire payload
      (fn []
        (let [e (try (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex
                                           :chunk {:id "00" :cb-original (alength payload)}})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (not (re-find (re-pattern key-hex) (str (ex-message e) (pr-str (ex-data e))))
              )
              "a depot key must never be printed, logged, or pasted into a bug report"))))))

(deftest a-raw-decode-exception-is-wrapped-without-leaking-its-message
  ;; chunk.clj:188-197's catch-all is exactly the code the FUSE-callback
  ;; constraint is about: an uncategorized exception here tears down the
  ;; mount. Neither symmetric-decrypt nor vzip/decompress needs to run for
  ;; real -- a plain RuntimeException from either stands in for a
  ;; BadPaddingException or an LZMA decoder failure.
  (let [marker "SECRET_MARKER_9f3a7c"]
    (with-redefs [cdn/fetch-with-rotation (fn [_ _ _] (byte-array 0))
                  reliquary.steam.crypto/symmetric-decrypt (fn [_ ct] ct)
                  reliquary.steam.vzip/decompress (fn [_] (throw (RuntimeException. marker)))]
      (let [e (try (chunk/fetch-decoded {:hosts ["h"] :depot-id 42 :key-hex key-hex
                                         :chunk {:id "00" :cb-original 0}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (= 42 (:depot-id (ex-data e)))
            "the depot id is needed to clear a stale key")
        (is (re-find #"RuntimeException" (ex-message e))
            "the class name is enough to triage")
        (is (not (re-find (re-pattern marker) (str (ex-message e) (pr-str (ex-data e)))))
            "the raw exception's own message can echo cipher state and must
             never be folded in")))))

(deftest an-already-categorized-decode-exception-passes-through-unchanged
  ;; vzip/decompress and crypto/symmetric-decrypt both raise their own
  ;; already-categorized ex-info. A regression that swapped the catch order
  ;; (or dropped the ExceptionInfo branch) would re-wrap it here instead of
  ;; letting it through -- this pins the ordering.
  (let [inner (ex-info "vzip: not a recognized header" {:reliquary/error :incorrect})]
    (with-redefs [cdn/fetch-with-rotation (fn [_ _ _] (byte-array 0))
                  reliquary.steam.crypto/symmetric-decrypt (fn [_ ct] ct)
                  reliquary.steam.vzip/decompress (fn [_] (throw inner))]
      (let [e (try (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex
                                         :chunk {:id "00" :cb-original 0}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (identical? inner e)
            "passed through, not re-wrapped")))))

(deftest stats-count-the-wire-and-the-decode
  ;; The wire (enc) and decoded lengths must differ, or a swap of the two
  ;; alength calls in chunk.clj's stats update would pass unnoticed.
  (let [enc-bytes (.getBytes "steam" "UTF-8")                      ; 5 bytes
        decoded   (byte-array (concat (seq enc-bytes) (repeat 7 0))) ; 12 bytes
        id        (chunk/sha1-hex decoded)
        stats     (atom {:chunks-fetched 0 :bytes-downloaded 0 :bytes-decoded 0})]
    (with-redefs [cdn/fetch-with-rotation (fn [_ _ _] enc-bytes)
                  reliquary.steam.crypto/symmetric-decrypt (fn [_ ct] ct)
                  reliquary.steam.vzip/decompress (fn [_] decoded)]
      (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex :stats stats
                            :chunk {:id id :cb-original (alength decoded)}})
      (is (not= (alength enc-bytes) (alength decoded))
          "the two lengths must differ for this test to distinguish the counters")
      (is (= 1 (:chunks-fetched @stats)))
      (is (= (alength enc-bytes) (:bytes-downloaded @stats)))
      (is (= (alength decoded) (:bytes-decoded @stats))))))

(deftest a-nil-stats-atom-is-fine
  (let [payload (.getBytes "hello steam" "UTF-8")
        id      (chunk/sha1-hex payload)]
    (with-wire payload
      (fn []
        (is (some? (chunk/fetch-decoded {:hosts ["h"] :depot-id 1 :key-hex key-hex
                                         :stats nil
                                         :chunk {:id id :cb-original (alength payload)}})))))))

(deftest the-url-is-the-depot-chunk-path
  (let [payload (.getBytes "x" "UTF-8")
        id      (chunk/sha1-hex payload)
        seen    (atom nil)]
    (with-redefs [cdn/fetch-with-rotation (fn [_ url-fn _] (reset! seen (url-fn "cdn.example")) payload)
                  reliquary.steam.crypto/symmetric-decrypt (fn [_ ct] ct)
                  reliquary.steam.vzip/decompress (fn [b] b)]
      (chunk/fetch-decoded {:hosts ["h"] :depot-id 489831 :key-hex key-hex
                            :chunk {:id id :cb-original 1}})
      (is (= (str "https://cdn.example/depot/489831/chunk/" id) @seen)))))
