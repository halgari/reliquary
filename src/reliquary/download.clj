;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.download
  "The engine: a catalog version -> a work plan -> bytes on disk.

   Two halves. `resolve-version` talks to the CM and the CDN and produces the
   plan; `execute!` runs it. They are separate because only the first needs a
   Steam session, and only the second needs a disk.

   ## The context

   `resolve-version` returns {:plan … :keys {depot-id key-hex} :hosts [host …]}.
   The context `execute!` consumes is that map plus {:dest :appid :version-id},
   passed through `make-ctx`, which adds the two mutable handles:

       {:plan       <plan/build output>
        :keys       {depot-id key-hex}     ; NEVER serialized -- see below
        :hosts      [\"cdn.host\" …]
        :dest       <File or path string>  ; the install folder
        :appid      <long>
        :version-id <string>               ; the catalog version id
        :cancel     <AtomicBoolean>        ; cancel! sets it
        :state      <atom of the snapshot>}; snapshot reads it

   `make-ctx` is idempotent, and `execute!` applies it, so a caller may build
   the ctx either way -- but a caller that wants to `cancel!` a running
   download must hold the ctx `make-ctx` returned, since that is where the
   AtomicBoolean lives.

   `opts` is {:workers n :chunk-budget n}. :workers defaults to the config's
   :workers, then to 8. :chunk-budget stops the run after n chunks land; it
   exists so a test can simulate a kill without killing a JVM.

   ## Two rules that are not style

   A depot key is a secret (spec §9). It lives in :keys, is handed to
   `chunk/fetch-decoded`, and reaches nothing else -- not the plan, not the
   progress file, not a snapshot, not an ex-info's data map.

   A chunk is recorded in the progress file only AFTER its FileChannel write
   has returned. The interrupted screen promises the user that nothing needs
   re-fetching; a progress file that runs ahead of the disk turns that promise
   into a quietly corrupt install."
  (:require [clojure.java.io :as io]
            [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.plan :as plan]
            [reliquary.progress :as progress]
            [reliquary.steam.chunk :as chunk]
            [reliquary.steam.cm.content :as content]
            [reliquary.steam.manifest :as manifest])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.util.concurrent Callable Executors ExecutorService LinkedBlockingQueue
                                 ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)))

;; ---- resolution -------------------------------------------------------------

(defn resolve-version
  "One catalog `version` of `game` -> {:plan :keys :hosts}.

   Per depot: the decryption key, a manifest request code FOR THIS VERSION'S
   BRANCH, the manifest, and its parse. The branch is not decoration -- a
   historical version living on a named branch that asks for \"public\" gets
   either the wrong manifest or a denial.

   A denial at any step raises, and takes the whole version with it. Unlike a
   library sync, which is deliberately over-inclusive and skips what it cannot
   have, the catalog names exactly the depots this build needs: a partial
   install that runs is worse than an error that does not."
  [session game version]
  (let [c      (:conn session)
        appid  (:appid game)
        branch (:branch version)
        hosts  (content/cdn-servers c)
        manifests
        (mapv (fn [{:keys [depot-id manifest-gid]}]
                (let [key-hex (content/depot-key c appid depot-id)
                      code    (content/manifest-request-code c appid depot-id
                                                             manifest-gid branch)
                      blob    (manifest/fetch hosts depot-id manifest-gid code)]
                  ;; the requested depot id, not the manifest's: it is what the
                  ;; chunk URLs and the key lookup are keyed by.
                  (assoc (manifest/parse blob key-hex)
                         :depot-id depot-id
                         :key-hex  key-hex)))
              (:depots version))]
    {:plan  (plan/build manifests)
     :keys  (plan/keys-by-depot manifests)
     :hosts hosts}))

;; ---- context, snapshot, cancel ----------------------------------------------

(def ^:const default-workers 8)

(def ^:const sample-count
  "The sparkline's ring. 48 samples at 250ms is twelve seconds of history."
  48)

(defn- worker-count ^long []
  (let [n (:workers (config/read-config))]
    (if (and (integer? n) (pos? (long n))) (long n) default-workers)))

(defn- initial-snapshot [plan]
  {:stage         :idle
   :bytes-done    0
   :bytes-total   (or (:download-bytes plan) 0)
   :chunks-done   0
   :chunks-total  (or (:total-chunks plan) 0)
   :wire-bytes    0
   :bytes-per-sec 0.0
   :samples       []
   :error         nil})

(defn make-ctx
  "Add the mutable handles -- :cancel and :state -- to a resolved context.

   Idempotent: a ctx that already carries them is returned untouched, so
   `execute!` can apply it to whatever it was given without stealing the
   caller's cancel flag."
  [ctx]
  (if (and (:cancel ctx) (:state ctx))
    ctx
    (assoc ctx
           :cancel (AtomicBoolean. false)
           :state  (atom (initial-snapshot (:plan ctx))))))

(defn snapshot
  "The engine's whole public surface for a UI: a plain map, never internals.

   {:stage :bytes-done :bytes-total :chunks-done :chunks-total :wire-bytes
    :bytes-per-sec :samples :error}

   :bytes-done counts DECOMPRESSED bytes, so it closes exactly against the
   plan's :download-bytes; :wire-bytes counts compressed bytes off the wire,
   which is what a throughput number should be computed from. Reporting one
   number for both makes a progress bar that cannot reach 100%.

   Stages: :idle :preparing :downloading :copying :done :cancelled :failed."
  [ctx]
  (some-> (:state ctx) deref))

(defn cancel!
  "Ask the run to stop. In-flight chunks finish rather than being torn out --
   a half-written chunk that got recorded would make the progress file lie."
  [ctx]
  (when-let [^AtomicBoolean b (:cancel ctx)] (.set b true))
  nil)

;; ---- disk -------------------------------------------------------------------

(def ^:const max-open-channels
  "How many FileChannels to hold open across the run.

   One per file is the intent -- a positional write needs no lock, so there is
   nothing to serialize on -- but a real depot has thousands of files and the
   usual soft fd limit is 1024. Past this cap a write opens its own channel for
   the duration of the write instead; correctness is identical either way,
   since a positional write is a pwrite whichever channel it goes through."
  256)

(defn- ensure-dir! [^File d]
  (when-not (or (.isDirectory d) (.mkdirs d) (.isDirectory d))
    (error/raise :io (str "cannot create folder " (.getPath d)) {:path (.getPath d)})))

(defn- close-all! [channels]
  (doseq [^FileChannel ch (vals channels)]
    (try (.close ch) (catch Exception _ nil))))

(defn- open-sized!
  "Create `f` if absent and set it to exactly `size`, returning the open
   RandomAccessFile. Any failure is :io and names the path, never the cause's
   full text."
  ^RandomAccessFile [^File f size path]
  (ensure-dir! (.getParentFile f))
  (let [^RandomAccessFile raf
        (try (RandomAccessFile. f "rw")
             (catch Exception e
               (error/raise :io (str "cannot create " path " ("
                                     (.getSimpleName (class e)) ")")
                            {:path path})))]
    (try (.setLength raf (long size))
         raf
         (catch Exception e
           (try (.close raf) (catch Exception _ nil))
           (error/raise :io (str "cannot reserve " size " bytes for " path " ("
                                 (.getSimpleName (class e)) ")")
                        {:path path})))))

(defn- preallocate!
  "Create every directory, then create and size every file and every copy
   destination, BEFORE a single chunk is requested.

   A download that discovers a full disk at 94% has spent the user's bandwidth
   to produce nothing. Returns {path -> FileChannel} for the files it kept
   open; anything opened is closed again if a later file fails."
  [^File dest {:keys [dirs files copies]}]
  (run! #(ensure-dir! (io/file dest %)) dirs)
  (let [opened (atom {})]
    (try
      (doseq [{:keys [path size]} files]
        (let [^RandomAccessFile raf (open-sized! (io/file dest path) (long size) path)]
          (if (< (count @opened) max-open-channels)
            (swap! opened assoc path (.getChannel raf))
            (.close raf))))
      ;; copy destinations are real bytes on the same disk, so they are
      ;; reserved here too rather than discovered missing at the end.
      (doseq [{:keys [path size]} copies]
        (.close ^RandomAccessFile (open-sized! (io/file dest path) (long size) path)))
      @opened
      (catch Throwable t
        (close-all! @opened)
        (throw t)))))

(defn- write-at!
  "Positional write of the whole buffer at `offset`. Positional writes do not
   touch the channel's file position, so parallel workers need no lock."
  [^FileChannel ch ^bytes b ^long offset]
  (let [buf (ByteBuffer/wrap b)]
    (loop [pos offset]
      (when (.hasRemaining buf)
        (recur (+ pos (long (.write ch buf pos))))))))

(defn- write-chunk!
  [channels ^File dest ^String path ^bytes b offset]
  (let [offset (long offset)]
    (if-let [^FileChannel ch (get channels path)]
      (write-at! ch b offset)
      (with-open [raf (RandomAccessFile. (io/file dest path) "rw")]
        (write-at! (.getChannel raf) b offset)))))

;; ---- execution --------------------------------------------------------------

(defn- daemon-factory ^ThreadFactory [prefix]
  (let [n (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. r (str prefix "-" (.incrementAndGet n)))
          (.setDaemon true))))))

(defn- complete-path?
  "Is every chunk this file's plan declares now recorded done?"
  [done {:keys [path chunks]}]
  (let [idxs (get done path #{})]
    (every? #(contains? idxs (:index %)) chunks)))

(defn- apply-copies!
  "Files sharing a content SHA-1 are fetched once; the rest are copied, but
   only once their source is actually complete. Copying a half-written source
   would produce a file that no chunk hash will ever catch."
  [^File dest plan done]
  (let [complete (into #{} (comp (filter #(complete-path? done %)) (map :path))
                       (:files plan))]
    (doseq [{:keys [path source]} (:copies plan)
            :when (contains? complete source)]
      (let [src (io/file dest source)
            dst (io/file dest path)]
        (ensure-dir! (.getParentFile dst))
        (try
          (Files/copy (.toPath src) (.toPath dst)
                      ^"[Ljava.nio.file.CopyOption;"
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (catch Exception e
            (error/raise :io (str "cannot copy " source " to " path " ("
                                  (.getSimpleName (class e)) ")")
                         {:path path})))))))

(defn execute!
  "Run the plan in `ctx` to completion, and return the final snapshot.

   Order, and none of it is arbitrary:

     1. Load the progress file and drop the chunks it records.
     2. Create the directories, then create and size EVERY file. A disk that
        cannot hold this version fails here, before any bandwidth is spent.
     3. Queue the remaining chunks; a fixed pool of workers drains the queue.
        Each worker checks the cancel flag, fetches, writes at the chunk's
        offset, and only THEN records the chunk as done.
     4. Apply :copies whose sources completed.

   Progress is flushed every three seconds and once more on the way out --
   always from the same atom the workers only ever add to after a write
   returned.

   A worker exception sets :error, drains the queue so nothing further is
   fetched, leaves every byte already written on disk for the resume, and is
   re-thrown from here still carrying its :reliquary/error category. A cancel
   is not an error: it returns a snapshot at stage :cancelled."
  ([ctx] (execute! ctx {}))
  ([ctx opts]
  (let [{:keys [plan hosts appid version-id state] :as ctx} (make-ctx ctx)
        ^AtomicBoolean cancel (:cancel ctx)
        depot-keys  (:keys ctx)
        ^File dest  (io/file (:dest ctx))
        workers     (max 1 (long (or (:workers opts) (worker-count))))
        budget      (:chunk-budget opts)
        done0       (progress/load dest appid version-id)
        done0       (into {} (map (fn [[k v]] [k (set v)])) done0)
        todo        (progress/remaining plan done0)
        done        (atom done0)
        bytes-done  (AtomicLong. (progress/done-bytes plan done0))
        wire-bytes  (AtomicLong. 0)
        chunks-done (AtomicLong. (- (long (or (:total-chunks plan) 0))
                                    (long (or (:total-chunks todo) 0))))
        landed      (AtomicLong. 0)
        failure     (atom nil)
        stage!      (fn [s] (swap! state assoc :stage s))
        refresh!    (fn [] (swap! state assoc
                                  :bytes-done (.get bytes-done)
                                  :bytes-total (or (:download-bytes plan) 0)
                                  :chunks-done (.get chunks-done)
                                  :chunks-total (or (:total-chunks plan) 0)
                                  :wire-bytes (.get wire-bytes)))]
    (try
      (swap! state assoc :stage :preparing :error nil)
      (refresh!)
      (let [channels (preallocate! dest todo)
            q        (LinkedBlockingQueue.)
            ^ScheduledExecutorService sched
            (Executors/newScheduledThreadPool 1 (daemon-factory "reliquary-sampler"))
            ^ExecutorService pool
            (Executors/newFixedThreadPool workers (daemon-factory "reliquary-worker"))]
        (doseq [{:keys [path depot-id chunks]} (:files todo)
                c chunks]
          (.add q {:path path :depot-id depot-id :chunk c}))
        (try
          (stage! :downloading)
          (let [prev (AtomicLong. (.get bytes-done))]
            (.scheduleAtFixedRate
             sched
             (fn []
               (let [now  (.get bytes-done)
                     rate (/ (double (- now (.get prev))) 0.25)]
                 (.set prev now)
                 (swap! state (fn [s]
                                (-> s
                                    (assoc :bytes-done now
                                           :chunks-done (.get chunks-done)
                                           :wire-bytes (.get wire-bytes)
                                           :bytes-per-sec rate)
                                    (update :samples
                                            (fn [xs]
                                              (let [xs (conj (or xs []) (/ rate 1048576.0))]
                                                (vec (take-last sample-count xs))))))))))
             250 250 TimeUnit/MILLISECONDS))
          (.scheduleAtFixedRate
           sched
           (fn [] (try (progress/save! dest appid version-id @done)
                       (catch Exception _ nil)))
           3 3 TimeUnit/SECONDS)
          (let [task (fn []
                       (loop []
                         (when-not (.get cancel)
                           (when-let [{:keys [path depot-id chunk]} (.poll q)]
                             (try
                               (let [b (chunk/fetch-decoded
                                        {:hosts    hosts
                                         :depot-id depot-id
                                         :key-hex  (get depot-keys depot-id)
                                         :chunk    chunk})]
                                 (write-chunk! channels dest path b (long (:offset chunk)))
                                 ;; ONLY NOW is the chunk done: the write returned.
                                 (swap! done update path (fnil conj #{}) (:index chunk))
                                 (.addAndGet bytes-done (long (:cb-original chunk)))
                                 (.addAndGet wire-bytes (long (or (:cb-compressed chunk) 0)))
                                 (.incrementAndGet chunks-done)
                                 (when (and budget (>= (.incrementAndGet landed) (long budget)))
                                   (.set cancel true)))
                               (catch Throwable t
                                 (compare-and-set! failure nil t)
                                 (.set cancel true)
                                 (.clear q)))
                             (recur)))))
                futures (mapv (fn [_] (.submit pool ^Callable task)) (range workers))]
            (doseq [f futures] (deref f)))
          (when-not @failure
            (stage! :copying)
            (apply-copies! dest plan @done))
          (finally
            (.shutdownNow sched)
            (.shutdownNow pool)
            (close-all! channels)
            ;; the last word on what is on disk, written after every worker
            ;; stopped touching it.
            (try (progress/save! dest appid version-id @done)
                 (catch Exception e
                   (compare-and-set! failure nil e))))))
      (when-let [t @failure] (throw t))
      (refresh!)
      (stage! (if (.get cancel) :cancelled :done))
      @state
      (catch Throwable t
        (swap! state assoc
               :stage :failed
               :error {:category (:reliquary/error (ex-data t))
                       :message  (ex-message t)})
        (throw t))))))
