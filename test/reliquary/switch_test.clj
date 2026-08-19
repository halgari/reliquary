;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.switch-test
  "The orchestration only. Every step it calls -- indexing, planning, staging,
   applying -- is proven against live Steam in reliquary.steam.local-test; what
   is worth testing here is that they are called in the one order that is safe,
   with the right inputs, and that progress and cancellation reach the caller."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.download :as download]
            [reliquary.steam.chunk :as chunk]
            [reliquary.switch :as switch])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir ^File []
  (.toFile (Files/createTempDirectory "reliquary-switch" (make-array FileAttribute 0))))

(defn- rm-rf [^File d] (when (.exists d) (run! io/delete-file (reverse (file-seq d)))))

(def ^:private sha-abc "a9993e364706816aba3e25717850c26c9cd0d89d")
(def ^:private sha-new "66aabd91fc41cc4635aad94dc77d93a0e2eac67b")
(def ^:private sha-xyz "66b27417d37e024c46526c2f6d358a754fc552f3")

;; The fixture deliberately covers all three cases at once, because the phase
;; ordering is only observable when there is something to stage:
;;
;;   on disk        "abcxyz"   sha-abc@0, sha-xyz@3
;;   target                    sha-abc@0 (in place), sha-new@3 (fetch),
;;                             sha-xyz@6 (MOVES, so it must be staged first)
;;   result         "abcNEWxyz"

(def ^:private game {:appid 1 :title "G"})
(def ^:private from {:id "public" :label "Latest" :branch "public" :depots [{:depot-id 7}]})
(def ^:private to   {:id "old" :label "1.5.97" :branch "public" :depots [{:depot-id 7}]})

(defn- manifests-for
  "Stands in for download/version-manifests: `from` describes what is on disk,
   `to` what we want."
  [chunks]
  {:hosts ["cdn.example"]
   :manifests [{:depot-id 7 :key-hex "ab"
                :files [{:name "a.bin" :chunks chunks}]}]})

(defn- run-switch [root opts]
  (with-redefs [download/version-manifests
                (fn [_ _ v]
                  (if (= "public" (:id v))
                    (manifests-for [{:id sha-abc :offset "0" :cb-original 3}
                                    {:id sha-xyz :offset "3" :cb-original 3}])
                    (manifests-for [{:id sha-abc :offset "0" :cb-original 3}
                                    {:id sha-new :offset "3" :cb-original 3}
                                    {:id sha-xyz :offset "6" :cb-original 3}])))
                chunk/fetch-decoded (fn [{:keys [chunk]}]
                                      (when (= sha-new (:id chunk))
                                        (.getBytes "NEW" "UTF-8")))]
    (switch/run! (merge {:session :a-session :game game :install {:path (str root)}
                         :from from :to to}
                        opts))))

(deftest a-switch-writes-the-target-content
  (let [d (tmp-dir)]
    (try
      (spit (io/file d "a.bin") "abcxyz")
      (run-switch d {})
      (is (= "abcNEWxyz" (slurp (io/file d "a.bin")))
          "in place kept, moved chunk relocated from staging, new one fetched")
      (finally (rm-rf d)))))

(deftest the-phases-run-in-the-only-safe-order
  (testing "staging must finish before a single byte is written: a moving chunk's
            source is usually a region the writes are about to overwrite. The
            phase sequence is the guarantee, so it is asserted directly."
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (let [phases (atom [])]
          (run-switch d {:on-progress (fn [p] (swap! phases conj (:phase p)))})
          (is (= [:hashing :staging :applying] (distinct @phases))))
        (finally (rm-rf d))))))

(deftest progress-carries-bytes-for-every-phase
  (let [d (tmp-dir)]
    (try
      (spit (io/file d "a.bin") "abcxyz")
      (let [seen (atom [])]
        (run-switch d {:on-progress (fn [p] (swap! seen conj p))})
        (is (every? #(and (contains? % :done) (contains? % :total)) @seen)
            "every event must be renderable as a bar without further lookup"))
      (finally (rm-rf d)))))

(deftest a-switch-reports-what-it-will-move-before-it-moves-it
  (testing "the panel shows this before the transfer starts, and it is the number
            that justifies the whole feature -- 0.21 GB against 15"
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (let [plans (atom nil)]
          (run-switch d {:on-plan (fn [p] (reset! plans p))})
          (is (= 1 (count (:in-place @plans))) "the unchanged chunk")
          (is (= 1 (count (:copy @plans))) "the one that relocates")
          (is (= 1 (count (:fetch @plans))) "and the one that must be fetched")
          (is (= 3 (:fetch-bytes @plans)) "only the fetch is counted, not the move"))
        (finally (rm-rf d))))))

(deftest aborting-stops-before-writing-anything
  (testing "a user who closes the panel mid-hash must not have their install
            rewritten a moment later"
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (run-switch d {:abort? (constantly true)})
        (is (= "abcxyz" (slurp (io/file d "a.bin")))
            "the install is exactly as it was")
        (finally (rm-rf d))))))

(deftest staging-is-cleared-when-the-switch-finishes
  (let [d (tmp-dir)]
    (try
      (spit (io/file d "a.bin") "abcxyz")
      (run-switch d {})
      (is (not (.exists (io/file d ".reliquary-staging")))
          "derived data, and a gigabyte of it on a real switch")
      (finally (rm-rf d)))))

(deftest a-fetch-failure-leaves-a-half-switched-install-and-says-so
  (testing "not transactional, by design: the install is simply another state to
            hash and diff from, and re-running converges. What must NOT happen is
            a silent success."
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (is (thrown? clojure.lang.ExceptionInfo
                     (with-redefs [download/version-manifests
                                   (fn [_ _ v]
                                     (if (= "public" (:id v))
                                       (manifests-for [{:id sha-abc :offset "0" :cb-original 3}])
                                       (manifests-for [{:id sha-new :offset "0" :cb-original 3}])))
                                   chunk/fetch-decoded (fn [_] nil)]
                       (switch/run! {:session :s :game game :install {:path (str d)}
                                     :from from :to to}))))
        (finally (rm-rf d))))))

;; ---------------------------------------------------------------------------
;; forcing a switch onto an install we cannot name
;;
;; A build the catalog does not carry is the normal state of a hand-downgraded
;; game, and it is exactly the install most likely to want changing. Refusing it
;; left the one user who needs this feature with a dead button.
;;
;; The boundary map is the TARGET's manifest in that case. It is safe for the
;; same reason the ordinary path is: chunk-index hashes every chunk and records
;; only those whose bytes match the id they claim, so a wrongly-guessed boundary
;; produces no index entry rather than a corrupt one. It simply reuses less --
;; a file unchanged between builds is byte-identical and matches at the target's
;; own boundaries, and a file that did change was going to be fetched anyway.

(defn- run-forced [root opts]
  (with-redefs [download/version-manifests
                (fn [_ _ v]
                  (when-not v
                    (throw (AssertionError. "must not fetch manifests for an unknown source")))
                  (manifests-for [{:id sha-abc :offset "0" :cb-original 3}
                                  {:id sha-new :offset "3" :cb-original 3}]))
                chunk/fetch-decoded (fn [{:keys [chunk]}]
                                      (when (= sha-new (:id chunk))
                                        (.getBytes "NEW" "UTF-8")))]
    (switch/run! (merge {:session :a-session :game game :install {:path (str root)}
                         :from nil :to to}
                        opts))))

(deftest a-forced-switch-converges-without-knowing-what-was-there
  (let [d (tmp-dir)]
    (try
      ;; "abc" is already the target's first chunk; "xyz" is not its second
      (spit (io/file d "a.bin") "abcxyz")
      (run-forced d {})
      (is (= "abcNEW" (slurp (io/file d "a.bin")))
          "what already matched was kept, what did not was fetched")
      (finally (rm-rf d)))))

(deftest a-forced-switch-still-reuses-what-is-already-correct
  (testing "this is the whole reason not to just re-download: a hand-downgraded
            15 GB install is mostly already the bytes the target wants"
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (let [plan (atom nil)]
          (run-forced d {:on-plan #(reset! plan %)})
          (is (= 1 (count (:in-place @plan))) "the chunk that was already right")
          (is (= 1 (count (:fetch @plan))) "and only the one that was not"))
        (finally (rm-rf d))))))

(deftest a-forced-switch-does-not-ask-steam-about-a-version-it-has-no-name-for
  (testing "there is no `from` to fetch manifests for -- asking would raise on a
            nil version id long before anything useful happened"
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (is (some? (run-forced d {})) "it ran at all")
        (finally (rm-rf d))))))

(defn- spit! 
  "spit, but making the directories first -- spit does not, and a test that
   silently failed to create its own fixture would prove nothing."
  [f content]
  (io/make-parents f)
  (spit f content))

;; ---------------------------------------------------------------------------
;; a modded install, through the whole orchestration
;;
;; The engine-level guarantees are in reliquary.steam.local-test. This is the
;; same question asked of `run!`, because that is what the app actually calls
;; and because a switch is the one operation that rewrites a folder the user has
;; spent months arranging.

(deftest a-switch-leaves-the-users-own-files-alone
  (testing "a real Skyrim folder holds SKSE, plugins, ini edits and whatever a
            mod manager left behind, none of it in any Steam manifest"
    (let [d (tmp-dir)]
      (try
        (spit (io/file d "a.bin") "abcxyz")
        (spit! (io/file d "skse64_loader.exe") "loader")
        (spit! (io/file d "Data" "MyMod.esp") "plugin bytes")
        (spit! (io/file d "SkyrimCustom.ini") "[General]")
        (run-switch d {})
        (is (= "abcNEWxyz" (slurp (io/file d "a.bin"))) "the switch happened")
        (is (= "loader" (slurp (io/file d "skse64_loader.exe"))))
        (is (= "plugin bytes" (slurp (io/file d "Data" "MyMod.esp"))))
        (is (= "[General]" (slurp (io/file d "SkyrimCustom.ini"))))
        (finally (rm-rf d))))))

(deftest a-switch-creates-a-file-the-target-version-adds
  (testing "an upgrade that introduces content the install has never had, in a
            directory that does not exist yet"
    (let [d (tmp-dir)]
      (try
        (spit! (io/file d "a.bin") "abcxyz")
        (spit! (io/file d "Data" "MyMod.esp") "plugin bytes")
        (with-redefs [download/version-manifests
                      (fn [_ _ v]
                        (if (= "public" (:id v))
                          (manifests-for [{:id sha-abc :offset "0" :cb-original 3}
                                          {:id sha-xyz :offset "3" :cb-original 3}])
                          ;; the target keeps a.bin as it was and adds a file
                          {:hosts ["cdn.example"]
                           :manifests [{:depot-id 7 :key-hex "ab"
                                        :files [{:name "a.bin"
                                                 :chunks [{:id sha-abc :offset "0" :cb-original 3}
                                                          {:id sha-xyz :offset "3" :cb-original 3}]}
                                                {:name "Data/NewDLC/thing.bsa"
                                                 :chunks [{:id sha-new :offset "0" :cb-original 3}]}]}]}))
                      chunk/fetch-decoded (fn [{:keys [chunk]}]
                                            (when (= sha-new (:id chunk))
                                              (.getBytes "NEW" "UTF-8")))]
          (switch/run! {:session :s :game game :install {:path (str d)}
                        :from from :to to}))
        (is (= "NEW" (slurp (io/file d "Data" "NewDLC" "thing.bsa")))
            "the new file, and the folder it needed, were created")
        (is (= "abcxyz" (slurp (io/file d "a.bin"))) "the file that did not change was left alone")
        (is (= "plugin bytes" (slurp (io/file d "Data" "MyMod.esp")))
            "and the user's own file in the same tree survived")
        (finally (rm-rf d))))))

(deftest the-staging-area-does-not-take-user-files-with-it
  (testing "staging lives INSIDE the install, so clearing it runs a delete
            inside a folder full of things that are not ours"
    (let [d (tmp-dir)]
      (try
        (spit! (io/file d "a.bin") "abcxyz")
        (spit! (io/file d "Data" "MyMod.esp") "plugin bytes")
        (run-switch d {})
        (is (not (.exists (io/file d ".reliquary-staging"))) "staging is gone")
        (is (= "plugin bytes" (slurp (io/file d "Data" "MyMod.esp"))) "and nothing else is")
        (finally (rm-rf d))))))

(deftest a-file-only-the-newer-build-has-survives-a-downgrade
  (testing "The flip side of never deleting, recorded deliberately rather than
            discovered later.

            Steam's own downgrade would remove a file the older build does not
            have. This does not: nothing enumerates the local tree, which is
            exactly what keeps a user's mods safe, and the same rule keeps a
            leftover from the newer build. For Bethesda titles that is usually
            harmless -- an unreferenced archive is not loaded -- but it is a real
            difference from a clean install and belongs in a test rather than in
            somebody's bug report."
    (let [d (tmp-dir)]
      (try
        (spit! (io/file d "a.bin") "abcxyz")
        ;; content the newer build shipped and the older one never had
        (spit! (io/file d "Data" "NewBuildOnly.bsa") "only in the new build")
        (with-redefs [download/version-manifests
                      (fn [_ _ v]
                        (if (= "public" (:id v))
                          ;; what is on disk now: the newer build, which names it
                          {:hosts ["cdn.example"]
                           :manifests [{:depot-id 7 :key-hex "ab"
                                        :files [{:name "a.bin"
                                                 :chunks [{:id sha-abc :offset "0" :cb-original 3}
                                                          {:id sha-xyz :offset "3" :cb-original 3}]}
                                                {:name "Data/NewBuildOnly.bsa"
                                                 :chunks []}]}]}
                          ;; the older build: no such file
                          (manifests-for [{:id sha-abc :offset "0" :cb-original 3}
                                          {:id sha-xyz :offset "3" :cb-original 3}])))
                      chunk/fetch-decoded (fn [_] nil)]
          (switch/run! {:session :s :game game :install {:path (str d)}
                        :from from :to to}))
        (is (.isFile (io/file d "Data" "NewBuildOnly.bsa"))
            "left in place: the switch removes nothing at all")
        (is (= "only in the new build" (slurp (io/file d "Data" "NewBuildOnly.bsa"))))
        (finally (rm-rf d))))))
