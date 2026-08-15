;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.manifest-test
  "Against the CAPTURED manifest -- real framing, real protobuf, real Steam.
   test/resources/steam/README.md says why it is an encrypted one."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cdn :as cdn]
            [reliquary.steam.manifest :as manifest]
            [reliquary.steam.vzip :as vzip]))

(defn- blob ^bytes []
  (vzip/decompress (with-open [in (io/input-stream (io/resource "steam/manifest.zip"))]
                     (.readAllBytes in))))

(defn- raise [category msg data] (throw (ex-info msg (assoc data :reliquary/error category))))

(def ^:private blocks  (delay (manifest/parse-blocks (blob))))
(def ^:private entries (delay (manifest/entries @blocks nil)))

(deftest the-captured-manifest-is-encrypted-and-parse-refuses-it-without-a-key
  (testing "AMENDED AFTER TASK 5. Steam encrypts manifest filenames
            universally -- 37 depots across appids 489830, 440, 570 and 730,
            every one filenames_encrypted=true. The plan originally assumed an
            unencrypted fixture could be found; it cannot. So the fixture is
            encrypted, no key is committed, and the production entry point must
            REFUSE rather than emit ciphertext as filesystem paths."
    (is (true? (boolean (:filenames-encrypted (:metadata @blocks)))))
    (let [e (try (manifest/parse (blob) nil) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :incorrect (:reliquary/error (ex-data e)))))))

(deftest parse-blocks-reads-the-metadata-block
  (is (= 489831 (:depot-id (:metadata @blocks)))
      "the committed fixture is appid 489830 depot 489831"))

(deftest parse-blocks-reads-every-file-mapping
  (is (= 19 (count (:mappings (:payload @blocks))))))

(deftest entries-carry-a-sha1-content-digest-as-hex
  (let [with-sha (filter :sha-content @entries)]
    (is (seq with-sha))
    (is (every? #(re-matches #"[0-9a-f]{40}" (:sha-content %)) with-sha)
        "SHA-1 is 20 bytes, so 40 hex characters")))

(deftest entries-have-no-name-without-a-key
  (testing "nil, never the raw ciphertext -- a ciphertext string reaching the
            path interner would create garbage path entities"
    (is (every? nil? (map :name @entries)))))

(deftest chunk-ids-are-hex-and-sizes-are-present
  (testing "spec 2c consumes these; if they are wrong nothing downloads"
    (let [chunks (mapcat :chunks @entries)]
      (is (seq chunks))
      (is (every? #(re-matches #"[0-9a-f]{40}" (:id %)) chunks))
      (is (every? #(pos? (:cb-compressed %)) chunks))
      (is (every? #(pos? (:cb-original %)) chunks)))))

(deftest every-entry-in-this-depot-has-content
  (testing "AMENDED AFTER TASK 5. The plan assumed a real manifest contains
            directory entries so the EDepotFileFlag bits could be pinned from
            Steam's own bytes. It does not: this depot's flags are only
            #{0 256} and NO entry has zero chunks -- and four further Skyrim
            depots showed no Directory- or Symlink-flagged entry either.

            So the flag constants are NOT pinned and NOT used. Ingest filters
            on observable structure instead -- an entry with no content sha or
            no chunks is not a file we can serve, whatever its flags say --
            which is correct regardless of what any bit turns out to mean.
            This test records what the fixture actually contains, so a future
            fixture that DOES carry directory entries makes it fail loudly
            rather than passing unnoticed."
    (is (= #{0 256} (into #{} (map :flags) @entries)))
    (is (every? #(seq (:chunks %)) @entries))
    (is (every? :sha-content @entries))))

(deftest a-blob-with-no-recognisable-magic-is-incorrect
  (let [e (try (manifest/parse (byte-array 64) nil) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :incorrect (:reliquary/error (ex-data e))))))

(deftest a-blob-truncated-just-past-the-magic-is-incorrect-not-a-raw-exception
  (testing "4 bytes of magic plus 1-3 more bytes leaves no room for the u32-LE
            LENGTH field. Reading it anyway overruns the buffer with a raw
            IndexOutOfBoundsException instead of the :incorrect every other
            malformed-manifest case raises."
    (doseq [n [5 6 7]]
      (let [e (try (manifest/parse-blocks (byte-array n (byte 1))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) (str n " bytes should raise, not throw silently"))
        (is (= :incorrect (:reliquary/error (ex-data e))) (str n " bytes"))))))

(deftest hex-round-trips
  (let [b (byte-array (map unchecked-byte [0x00 0x7f 0x80 0xff]))]
    (is (= "007f80ff" (manifest/bytes->hex b)))
    (is (= (seq b) (seq (manifest/hex->bytes "007f80ff"))))))

;; ---- fetch --------------------------------------------------------------
;;
;; No local HTTP server and no HttpClient mock -- this repo's established
;; convention (api_test.clj, auth_api_test.clj) is real network only under the
;; MAUVI_STEAM_LIVE gate, and pure logic tested by redefining a narrow
;; internal seam instead of a socket. `get-once` is that seam here: it is the
;; one function in `fetch` that touches the network, so redefining it proves
;; the retry / host-rotation / error-mapping logic around it without ever
;; opening a port.

(defn- zlib-bytes
  "A real zlib stream `vzip/decompress`'s default branch will inflate back to
   `raw` -- so a faked get-once can return something fetch's own decompress
   step accepts, exercising the full function, not just the retry loop."
  ^bytes [^bytes raw]
  (let [deflater (java.util.zip.Deflater.)
        out (java.io.ByteArrayOutputStream.)
        buf (byte-array 256)]
    (.setInput deflater raw)
    (.finish deflater)
    (while (not (.finished deflater))
      (let [n (.deflate deflater buf)]
        (.write out buf 0 n)))
    (.toByteArray out)))

(deftest fetch-does-not-retry-or-rotate-on-a-4xx
  (testing "a bad request code is not a transport problem -- no other host
            would answer differently, so retrying only wastes time"
    (let [calls (atom [])]
      (with-redefs [cdn/get-once (fn [url] (swap! calls conj url)
                                  (raise :incorrect "cdn rejected the request, HTTP 400"
                                         {:status 400}))]
        (let [e (try (manifest/fetch ["host-a" "host-b"] 1 "2" "3") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :incorrect (:reliquary/error (ex-data e))))
          (is (= 1 (count @calls)) "exactly one attempt, no retry, no rotation"))))))

(deftest fetch-retries-a-host-then-rotates-on-5xx
  (testing "5xx and transport failures are worth retrying, and worth trying
            the next host once the current one is exhausted"
    (let [calls (atom [])
          content (.getBytes "the manifest bytes" "UTF-8")]
      (with-redefs [cdn/get-once
                    (fn [uri]
                      (swap! calls conj (str uri))
                      (if (re-find #"host-a" (str uri))
                        (raise :unavailable "cdn error, HTTP 503" {:status 503})
                        (zlib-bytes content)))]
        (binding [cdn/*fetch-attempts* 2]
          (let [got (manifest/fetch ["host-a" "host-b"] 1 "2" "3")]
            (is (= (seq content) (seq got)))
            (is (= 2 (count (filter #(re-find #"host-a" %) @calls)))
                "host-a exhausted its attempts before rotating")
            (is (= 1 (count (filter #(re-find #"host-b" %) @calls)))
                "host-b answered on the first try")))))))

(deftest fetch-exhausting-every-host-is-unavailable
  (let [calls (atom [])]
    (with-redefs [cdn/get-once (fn [url] (swap! calls conj url)
                                (raise :unavailable "cdn unreachable" {}))]
      (binding [cdn/*fetch-attempts* 1]
        (let [e (try (manifest/fetch ["host-a" "host-b"] 1 "2" "3") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :unavailable (:reliquary/error (ex-data e))))
          (is (= 2 (count @calls)) "both hosts were tried, one attempt each"))))))

(deftest a-malformed-manifest-gid-raises-incorrect-once-and-never-leaks-the-request-code
  (testing "manifest-gid comes straight off the PICS text VDF, never checked for
            digits. A space in it makes java.net.URI/create throw with the
            WHOLE url in its message -- and the url embeds request-code, an
            unauthenticated capability (depot-id, gid, code) is enough to pull
            the manifest. :incorrect matters too: :unavailable would send this
            through fetch's retry-then-rotate path for a failure no retry can
            fix."
    (let [calls (atom 0)]
      (with-redefs [cdn/get-once (fn [_] (swap! calls inc)
                                  (raise :unavailable "cdn error, HTTP 503" {}))]
        (let [e (try (manifest/fetch ["host-a" "host-b"] 1 "2 evil" "SUPERSECRETCODE") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :incorrect (:reliquary/error (ex-data e))))
          (is (not (re-find #"SUPERSECRETCODE" (ex-message e)))
              "the request code must never appear in a raised message")
          (is (not (re-find #"(?i)illegal character" (ex-message e)))
              "java.net's own message quotes the whole url; it must not ride along")
          (is (zero? @calls)
              "the malformed url must fail before any network attempt -- no retry, no rotation"))))))

(deftest a-malformed-cdn-host-also-raises-incorrect-once-without-leaking-the-code
  (testing "host comes from the CDN directory response, also Steam-supplied and
            unvalidated -- a bad host must fail exactly like a bad gid"
    (let [calls (atom 0)]
      (with-redefs [cdn/get-once (fn [_] (swap! calls inc)
                                  (raise :unavailable "cdn error, HTTP 503" {}))]
        (let [e (try (manifest/fetch ["bad host with spaces"] 1 "2" "SUPERSECRETCODE") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :incorrect (:reliquary/error (ex-data e))))
          (is (not (re-find #"SUPERSECRETCODE" (ex-message e))))
          (is (zero? @calls)))))))

(deftest fetch-with-no-hosts-is-unavailable-and-touches-nothing
  (let [calls (atom [])]
    (with-redefs [cdn/get-once (fn [url] (swap! calls conj url))]
      (let [e (try (manifest/fetch [] 1 "2" "3") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :unavailable (:reliquary/error (ex-data e))))
        (is (empty? @calls))))))
