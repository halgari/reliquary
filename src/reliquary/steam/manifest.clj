;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.manifest
  "Parse a Steam depot manifest.

   The blob is a sequence of magic-framed blocks -- each a u32-LE magic, a
   u32-LE body length, then the body. The payload block decodes as
   ContentManifestPayload (the file mappings), the metadata block as
   ContentManifestMetadata (the depot id and whether filenames are encrypted)."
  (:require [clojure.string :as str]
            [reliquary.error :as error]
            [reliquary.steam.cdn :as cdn]
            [reliquary.steam.crypto :as crypto]
            [reliquary.steam.proto :as proto]
            [reliquary.steam.vzip :as vzip])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.util Arrays Base64)))

(def ^:private MAGIC-PAYLOAD  0x71F617D0)
(def ^:private MAGIC-METADATA 0x1F4812BE)
(def ^:private MAGIC-END      0x32C415AB)

;; AMENDED AFTER TASK 5. There are deliberately NO EDepotFileFlag constants
;; here. The plan intended to pin Directory=64 / Symlink=128 against the
;; captured fixture rather than trust recollection -- but the fixture, and four
;; further Skyrim depots, contain no directory or symlink entry at all (flags
;; are only #{0 256}, every entry has chunks). Unpinnable constants would be
;; recollection wearing a `def`, so callers filter on observable structure --
;; no content sha, or no chunks, means nothing we can serve -- which holds
;; whatever the bits turn out to mean.

(defn bytes->hex ^String [^bytes b]
  (let [sb (StringBuilder. (* 2 (alength b)))]
    (dotimes [i (alength b)]
      (.append sb (format "%02x" (bit-and 0xFF (long (aget b i))))))
    (.toString sb)))

(defn hex->bytes ^bytes [^String s]
  (let [n   (quot (.length s) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (Integer/parseInt (.substring s (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(defn- b64->bytes ^bytes [^String s]
  ;; The MIME decoder, NOT the basic one: Steam MIME-wraps encrypted filenames,
  ;; so their base64 carries embedded newlines that the strict decoder rejects.
  ;; Harmless for the newline-free shas.
  (.decode (Base64/getMimeDecoder) s))

(defn- b64->hex ^String [^String s] (bytes->hex (b64->bytes s)))

(defn- read-u32-le ^long [^bytes b ^long off]
  (bit-and 0xFFFFFFFF
           (long (.getInt (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))
                          (int off)))))

(defn decrypt-filename
  "Decrypt one MIME-base64 filename with the depot key, strip the trailing NUL
   and normalise backslashes to forward slashes."
  ^String [^bytes key ^String b64]
  (let [^bytes pt (crypto/symmetric-decrypt key (b64->bytes b64))
        nul (loop [i 0] (cond (>= i (alength pt)) (alength pt)
                              (zero? (aget pt i)) i
                              :else (recur (inc i))))]
    (str/replace (String. (Arrays/copyOfRange pt 0 (int nul)) "UTF-8") "\\" "/")))

(defn- file-entry [fm enc? ^bytes key]
  {:name  (let [n (:filename fm)]
            (cond
              (and enc? key) (decrypt-filename key n)
              enc?           nil ; encrypted but no key -- never the ciphertext
              :else          (str/replace n "\\" "/")))
   :size  (:size fm)                     ; uint64 -> string, left alone
   :flags (long (or (:flags fm) 0))
   :sha-content (when-let [s (:sha-content fm)] (b64->hex s))
   :chunks (mapv (fn [ch]
                   {:id            (b64->hex (:sha ch))
                    :offset        (:offset ch)
                    :cb-original   (:cb-original ch)
                    :cb-compressed (:cb-compressed ch)})
                 (:chunks fm))})

(defn parse-blocks
  "A depot manifest blob -> {:metadata <ContentManifestMetadata>
                             :payload  <ContentManifestPayload>}, both as
   decoded protobuf maps, with no filename handling at all.

   Split out from `parse` so the committed fixture can drive the framing, the
   protobuf decode, the chunk list, the per-file SHA-1s and the flags WITHOUT a
   depot key -- every one of those fields is plaintext even in an encrypted
   manifest. Only `filename` is ciphertext."
  [^bytes blob]
  (let [len (alength blob)]
    (loop [off 0 payload nil meta nil]
      (if (>= (+ off 4) len)
        (do
          (when (nil? meta)
            (error/raise :incorrect "manifest has no metadata block"))
          (when (nil? payload)
            (error/raise :incorrect "manifest has no payload block"))
          {:metadata meta :payload payload})
        (let [magic (read-u32-le blob off)]
          (if (= magic MAGIC-END)
            (recur len payload meta)
            (do
              (when (> (+ off 8) len)
                (error/raise :incorrect "manifest is truncated before a block length"
                             {:offset off}))
              (let [blen (read-u32-le blob (+ off 4))
                    end  (+ off 8 blen)]
                (when (> end len)
                  (error/raise :incorrect "manifest block length runs past the blob"
                               {:offset off :declared blen}))
                (let [body (Arrays/copyOfRange blob (int (+ off 8)) (int end))]
                  (cond
                    (= magic MAGIC-PAYLOAD)
                    (recur end (proto/decode "ContentManifestPayload" body) meta)
                    (= magic MAGIC-METADATA)
                    (recur end payload (proto/decode "ContentManifestMetadata" body))
                    :else
                    (recur end payload meta)))))))))))

(defn entries
  "The file entries of a `parse-blocks` result. `depot-key-hex` may be nil, in
   which case `:name` is nil on every entry -- NEVER the raw ciphertext, which
   would create garbage path entities if it ever reached the interner.
   Everything else is present regardless."
  [{:keys [metadata payload]} depot-key-hex]
  (let [enc? (boolean (:filenames-encrypted metadata))
        key  (when depot-key-hex (hex->bytes depot-key-hex))]
    (mapv #(file-entry % enc? key) (:mappings payload))))

(defn chunk-table
  "{sha-content-hex -> {:size long :chunks [{:id :offset :cb-original
   :cb-compressed} ...]}} for every servable file in a manifest blob.

   NO DEPOT KEY REQUIRED, deliberately. Only `filename` is ciphertext in a
   manifest; sizes, content SHA-1s and chunk lists are plaintext even when
   filenames_encrypted is set. Spec 2c's mount path matches members to chunk
   lists by CONTENT DIGEST, so it never needs a name -- and not needing the key
   here means the offline test can use the committed encrypted fixture, and a
   rotated key breaks only the chunk decrypt rather than the whole mount.

   Filters on observable structure -- no content SHA-1, or no chunks, means
   nothing we can serve -- the same rule `parse` uses, and for the same reason
   2b recorded: the flag bits could not be pinned against real data.

   Offsets are returned as LONGS. The manifest decodes uint64 as a String and
   reliquary.steam.slice requires numbers."
  [^bytes blob]
  (let [{:keys [payload]} (parse-blocks blob)]
    (into {}
          (keep (fn [fm]
                  (when (and (:sha-content fm) (seq (:chunks fm)))
                    [(b64->hex (:sha-content fm))
                     {:size   (Long/parseLong (str (:size fm)))
                      :chunks (mapv (fn [ch]
                                      {:id            (b64->hex (:sha ch))
                                       :offset        (Long/parseLong (str (:offset ch)))
                                       :cb-original   (long (:cb-original ch))
                                       :cb-compressed (long (:cb-compressed ch))})
                                    (:chunks fm))}])))
          (:mappings payload))))

(defn parse
  "A depot manifest blob -> {:depot-id :files}. The production entry point.

   Raises when the manifest's filenames are encrypted and no key was supplied.
   That is not pedantry: every depot Steam serves today is encrypted (verified
   across 37 depots on 4 apps), so the alternative is silently interning
   ciphertext as filesystem paths."
  [^bytes blob depot-key-hex]
  (let [{:keys [metadata] :as blocks} (parse-blocks blob)]
    (when (and (:filenames-encrypted metadata) (nil? depot-key-hex))
      (error/raise :incorrect
                   "manifest filenames are encrypted but no depot key was supplied"
                   {:depot-id (:depot-id metadata)}))
    {:depot-id (:depot-id metadata)
     :files    (entries blocks depot-key-hex)}))

;; ---- CDN fetch --------------------------------------------------------------

(defn fetch
  "GET a depot manifest from the first CDN host that answers, unwrap the
   single-entry zip, and return the raw manifest blob.

   Host rotation, retry and the URL-secrecy rule live in reliquary.steam.cdn (spec
   2c extracted them so the chunk path shares one policy). What remains here is
   the manifest URL shape and the zip unwrap.

   depot-id is NOT primitive-hinted, even though it is always a long in
   practice -- same rationale as reliquary.steam.cm.content/depot-key's docstring:
   ops.steam-test installs a with-redefs double over this fn to test the
   skip-and-count path around a denied manifest request code, and a ^long arg
   here would make that double fail a ClassCastException at the call site
   instead of running."
  ^bytes [hosts depot-id ^String manifest-gid ^String request-code]
  (vzip/decompress
   (cdn/fetch-with-rotation
    hosts
    (fn [host]
      (str "https://" host "/depot/" depot-id "/manifest/" manifest-gid
           "/5/" request-code))
    "manifest")))
