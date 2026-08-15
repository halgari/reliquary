;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.download-test
  "Two halves, tested two ways: resolution redefines every network call,
   execution runs against a real HttpServer on an OS-assigned port so the
   concurrency, the positional writes and the resume are genuinely exercised.

   chunk/fetch-decoded is redefined for the execution tests, and only as far
   as the crypto: the double still goes through reliquary.steam.cdn's real
   host rotation and retry policy to a real socket. It cannot do more --
   fetch-decoded verifies that a chunk's id IS the SHA-1 of its decoded
   plaintext, so a fixture served as plaintext under the readable id \"c0\"
   can never satisfy it, and one served under its true digest would make
   every assertion below unreadable."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.download :as download]
            [reliquary.error :as error]
            [reliquary.progress :as progress]
            [reliquary.steam.cdn :as cdn]
            [reliquary.steam.chunk :as chunk]
            [reliquary.steam.cm.content :as content]
            [reliquary.steam.manifest :as manifest])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ---- resolution -------------------------------------------------------------

(deftest a-denied-depot-fails-the-whole-version
  (testing "the catalog named exactly these depots; a denial means this account
            cannot have this build, and a partial install is worse than an error"
    (with-redefs [content/depot-key (fn [_ _ d] (if (= d 2) (error/raise :incorrect "denied") "ab"))
                  content/manifest-request-code (constantly "1")
                  content/cdn-servers (constantly ["h"])
                  manifest/fetch (constantly (byte-array 0))
                  manifest/parse (constantly {:depot-id 1 :files []})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (download/resolve-version {} {:appid 7}
                                             {:branch "public"
                                              :depots [{:depot-id 1 :manifest-gid "a"}
                                                       {:depot-id 2 :manifest-gid "b"}]}))))))

(deftest the-branch-reaches-the-request-code-call
  (let [seen (atom nil)]
    (with-redefs [content/depot-key (constantly "ab")
                  content/manifest-request-code (fn [_ _ _ _ br] (reset! seen br) "1")
                  content/cdn-servers (constantly ["h"])
                  manifest/fetch (constantly (byte-array 0))
                  manifest/parse (constantly {:depot-id 1 :files []})]
      (download/resolve-version {} {:appid 7}
                                {:branch "1_63_legacy_patch"
                                 :depots [{:depot-id 1 :manifest-gid "a"}]})
      (is (= "1_63_legacy_patch" @seen)
          "a historical version on a named branch must not ask for public"))))

(deftest resolution-keeps-the-keys-out-of-the-plan
  (testing "the plan is what gets serialized into progress files and snapshots"
    (with-redefs [content/depot-key (constantly "deadbeef")
                  content/manifest-request-code (fn [_ _ _ _ _] "1")
                  content/cdn-servers (constantly ["h"])
                  manifest/fetch (constantly (byte-array 0))
                  manifest/parse (constantly {:depot-id 1 :files []})]
      (let [{:keys [plan keys hosts]}
            (download/resolve-version {} {:appid 7}
                                      {:branch "public"
                                       :depots [{:depot-id 1 :manifest-gid "a"}]})]
        (is (= {1 "deadbeef"} keys))
        (is (= ["h"] hosts))
        (is (not (str/includes? (pr-str plan) "deadbeef"))
            "a depot key must never reach anything that gets printed or stored")))))

;; ---- execution --------------------------------------------------------------

(def ^:private plaintext
  "Three 10-byte chunks whose assembled content is known, so the only thing the
   test trusts is the bytes on disk."
  {"c0" (.getBytes "AAAAAAAAAA") "c1" (.getBytes "BBBBBBBBBB") "c2" (.getBytes "CCCCCCCCCC")})

(def ^:private all-chunks
  [{:index 0 :id "c0" :offset 0  :cb-original 10}
   {:index 1 :id "c1" :offset 10 :cb-original 10}
   {:index 2 :id "c2" :offset 20 :cb-original 10}])

(defn- plain-fetch
  "chunk/fetch-decoded minus the crypto: the real cdn rotation to the fixture."
  ^bytes [{:keys [hosts depot-id chunk]}]
  (cdn/fetch-with-rotation
   hosts
   (fn [host] (str "http://" host "/depot/" depot-id "/chunk/" (:id chunk)))
   "chunk"))

(defn- with-fixture-server
  "Serve `plaintext` at the chunk paths chunk/fetch-decoded requests, counting
   hits per chunk id. Port 0 so parallel test runs cannot collide."
  [f]
  (let [hits (atom {})
        srv  (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [id (last (str/split (.getPath (.getRequestURI ex)) #"/"))]
                          (swap! hits update id (fnil inc 0))
                          (if-let [^bytes b (plaintext id)]
                            (do (.sendResponseHeaders ex 200 (alength b))
                                (doto (.getResponseBody ex) (.write b) (.close)))
                            (.sendResponseHeaders ex 404 -1))))))
    (.start srv)
    (try (with-redefs [chunk/fetch-decoded plain-fetch]
           (f (str "127.0.0.1:" (.getPort (.getAddress srv))) hits))
         (finally (.stop srv 0)))))

(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-test" (make-array FileAttribute 0)))]
    (try (f d)
         (finally (run! #(io/delete-file % true) (reverse (file-seq d)))))))

(defn- ctx-for
  "A one-file plan (a.bin, 30 bytes) over `chunks`, pointed at the fixture."
  [host dest chunks]
  (download/make-ctx
   {:plan       {:download-bytes 30 :disk-bytes 30 :total-chunks (count chunks)
                 :dirs [] :copies [] :skipped 0
                 :files [{:path "a.bin" :size 30 :depot-id 1 :sha-content "sha-a"
                          :chunks (vec chunks)}]}
    :keys       {1 "deadbeef"}
    :hosts      [host]
    :dest       dest
    :appid      7
    :version-id "public"}))

(deftest chunks-land-at-their-declared-offsets
  (testing "byte-exactness is the only assertion that catches an off-by-one in
            the positional write -- a wrong offset still produces a full file"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            ;; chunks deliberately queued out of offset order
            (download/execute! (ctx-for host dest
                                        [{:index 2 :id "c2" :offset 20 :cb-original 10}
                                         {:index 0 :id "c0" :offset 0  :cb-original 10}
                                         {:index 1 :id "c1" :offset 10 :cb-original 10}])
                               {:workers 3})
            (is (= "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC"
                   (slurp (io/file dest "a.bin"))))))))))

(deftest a-killed-download-resumes-without-refetching
  (with-tmp
    (fn [dest]
      (with-fixture-server
        (fn [host hits]
          ;; first run: stop after one chunk
          (download/execute! (ctx-for host dest all-chunks) {:workers 1 :chunk-budget 1})
          (let [after-first @hits]
            (is (= 1 (reduce + 0 (vals after-first))))
            ;; second run resumes from the progress file
            (download/execute! (ctx-for host dest all-chunks) {:workers 3})
            (is (= "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC" (slurp (io/file dest "a.bin"))))
            (is (every? #(= 1 %) (vals @hits))
                "the interrupted screen promises nothing is re-fetched; prove it")))))))

(deftest cancel-lets-in-flight-chunks-finish
  (testing "a progress file that runs ahead of the disk silently skips chunks"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            (let [c (ctx-for host dest all-chunks)]
              (future (Thread/sleep 20) (download/cancel! c))
              (download/execute! c {:workers 1})
              (let [done (progress/load dest 7 "public")
                    ch   (java.io.RandomAccessFile. (io/file dest "a.bin") "r")]
                (doseq [i (get done "a.bin")]
                  (.seek ch (* 10 i))
                  (let [buf (byte-array 10)]
                    (.readFully ch buf)
                    (is (not= (seq (byte-array 10)) (seq buf))
                        (str "chunk " i " is recorded done but its region is empty"))))
                (.close ch)))))))))

(deftest a-chunk-whose-write-fails-is-never-recorded-done
  (testing "the ordering the cancel test above only samples: recorded implies
            written. A negative offset is the one write a test can make fail on
            demand -- in production it is a disk error or a kill between the two
            statements, and the consequence is the same either way: a resume
            that skips a chunk nothing ever wrote."
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            (let [ctx (ctx-for host dest [{:index 0 :id "c0" :offset 0 :cb-original 10}
                                          {:index 1 :id "c1" :offset -1 :cb-original 10}])]
              (is (thrown? Exception (download/execute! ctx {:workers 1})))
              (let [done (progress/load dest 7 "public")]
                (is (not (contains? (get done "a.bin") 1))
                    "a chunk recorded before its write is a chunk the resume will skip")))))))))

(deftest preallocation-fails-before-any-chunk-is-fetched
  (testing "a full disk must fail immediately, not at 94%"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host hits]
            ;; a path the OS will refuse: dest/a.bin exists as a DIRECTORY
            (.mkdirs (io/file dest "a.bin"))
            (is (thrown? Exception
                         (download/execute! (ctx-for host dest all-chunks) {:workers 3})))
            (is (empty? @hits)
                "not one chunk should be requested if the files cannot be created")))))))

(deftest a-worker-failure-raises-and-leaves-the-disk-alone
  (testing "a 404 chunk is a failed download, not a silently short file"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            (let [ctx (ctx-for host dest (conj (vec all-chunks)
                                               {:index 3 :id "missing" :offset 30
                                                :cb-original 10}))]
              (is (thrown? clojure.lang.ExceptionInfo (download/execute! ctx {:workers 1})))
              (is (= :failed (:stage (download/snapshot ctx))))
              (is (some? (:error (download/snapshot ctx))))
              (is (.isFile (io/file dest "a.bin"))
                  "whatever landed stays on disk for the resume"))))))))

(deftest a-finished-download-closes-its-progress-at-a-hundred-percent
  (with-tmp
    (fn [dest]
      (with-fixture-server
        (fn [host _hits]
          (let [snap (download/execute! (ctx-for host dest all-chunks) {:workers 3})]
            (is (= :done (:stage snap)))
            (is (= 30 (:bytes-done snap)))
            (is (= (:bytes-total snap) (:bytes-done snap)))
            (is (= 3 (:chunks-done snap) (:chunks-total snap)))
            (is (nil? (:error snap)))
            (is (not (str/includes? (pr-str snap) "deadbeef"))
                "no snapshot may carry a depot key")))))))

(deftest a-resumed-run-reports-the-whole-version-not-the-remainder
  (testing "a progress bar that restarts at zero on resume is a lie about the work"
    (with-tmp
      (fn [dest]
        (with-fixture-server
          (fn [host _hits]
            (download/execute! (ctx-for host dest all-chunks) {:workers 1 :chunk-budget 1})
            (let [ctx (ctx-for host dest all-chunks)]
              ;; before a single byte of the second run
              (is (= 30 (:bytes-total (download/snapshot ctx))))
              (let [snap (download/execute! ctx {:workers 3})]
                (is (= 30 (:bytes-done snap)))
                (is (= 3 (:chunks-done snap)))))))))))

(deftest duplicate-content-is-copied-after-its-source-lands
  (with-tmp
    (fn [dest]
      (with-fixture-server
        (fn [host hits]
          (let [ctx (-> (ctx-for host dest all-chunks)
                        (assoc-in [:plan :copies] [{:path "sub/b.bin" :source "a.bin" :size 30}])
                        (assoc-in [:plan :dirs] ["sub"]))]
            (download/execute! ctx {:workers 2})
            (is (= "AAAAAAAAAABBBBBBBBBBCCCCCCCCCC" (slurp (io/file dest "sub" "b.bin"))))
            (is (= 3 (reduce + 0 (vals @hits)))
                "a copy is free next to a download; it must not re-fetch")))))))
