;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.connection
  "The CM WebSocket connection: job/response correlation and EMsg dispatch.

  This is the one module deliberately NOT transliterated from the CLJS
  original. Node was single-threaded (atoms needed no locking, async via
  promises on the event loop); on the JVM the WebSocket delivers frames on an
  executor thread while callers await responses from other threads. So each
  pending job is a CompletableFuture completed by the receive path and joined
  by the caller, the job/session tables are atoms, and per-job timeouts run on
  a scheduler (re-armed on each partial of a multi-part response, matching the
  original's PICS handling).

  The transport is injected as :send-fn / :close-fn so tests drive the receive
  path (deliver-frame!) directly against a capturing fake, with no socket."
  (:require [reliquary.steam.proto :as proto]
            [reliquary.steam.cm.envelope :as env]
            [reliquary.steam.cm.multi :as multi])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.io ByteArrayOutputStream)
           (java.util.concurrent CompletableFuture ExecutionException Executors
                                 ScheduledExecutorService ScheduledFuture TimeUnit)))

(def ^:dynamic *job-timeout-ms*
  "How long a pending job waits for its response before its future is rejected
  with :reliquary/error :unavailable. Re-armed on each partial of a multi-part
  response. A var so tests can shorten it."
  60000)

(defn- new-conn [send-fn close-fn scheduler]
  {:send-fn send-fn
   :close-fn close-fn
   :scheduler scheduler
   :next-job (atom 0)
   :jobs (atom {})        ; jobid-str -> {:resp-type :cf :collect :done? :timeout}
   :emsg (atom {})        ; emsg-kw -> {:type resp-type :handler fn}
   :session (atom {:steamid "0" :session-id 0})
   :licenses (atom [])})

(defn set-session! [conn s] (swap! (:session conn) merge s))
(defn on-emsg! [conn kw type handler]
  (swap! (:emsg conn) assoc kw {:type type :handler handler}))

(defn- header-base [conn extra]
  (let [{:keys [steamid session-id]} @(:session conn)]
    (merge {:steamid steamid :client-sessionid session-id} extra)))

(defn- send-bytes! [conn ^bytes b] ((:send-fn conn) b))

;; ---- job timeouts -----------------------------------------------------------

(defn- cancel-timeout! [job]
  (when-let [^ScheduledFuture t @(:timeout job)]
    (.cancel t false)))

(defn- arm-timeout! [conn jid job]
  (let [^ScheduledExecutorService sched (:scheduler conn)
        t (.schedule sched
                     ^Runnable (fn []
                                 (when (get @(:jobs conn) jid)
                                   (swap! (:jobs conn) dissoc jid)
                                   (.completeExceptionally ^CompletableFuture (:cf job)
                                                           (ex-info "steam job timeout"
                                                                    {:reliquary/error :unavailable :jobid jid}))))
                     (long *job-timeout-ms*) TimeUnit/MILLISECONDS)]
    (reset! (:timeout job) t)))

;; ---- receive path -----------------------------------------------------------

(defn- handle-job-response [conn jt job body]
  (let [resp (proto/decode (:resp-type job) body)]
    (cancel-timeout! job)
    (if-let [done? (:done? job)]
      (do (swap! (:collect job) conj resp)
          (if (done? resp)
            (do (swap! (:jobs conn) dissoc jt)
                (.complete ^CompletableFuture (:cf job) @(:collect job)))
            (arm-timeout! conn jt job)))       ; more parts coming — re-arm
      (do (swap! (:jobs conn) dissoc jt)
          (.complete ^CompletableFuture (:cf job) resp)))))

(defn deliver-frame!
  "Feed one decoded WebSocket binary frame (a byte[]) into the connection. The
  real transport calls this from the socket thread; tests call it directly."
  [conn ^bytes packet]
  (let [{:keys [emsg proto? header body]} (env/decode-message packet)]
    (when proto?
      (when-let [sid (:client-sessionid header)]
        (when (pos? (long sid)) (swap! (:session conn) assoc :session-id sid)))
      (if (= emsg (:multi env/e))
        (doseq [p (multi/expand (proto/decode "CMsgMulti" body))]
          (deliver-frame! conn p))
        (let [jt (:jobid-target header)
              job (get @(:jobs conn) jt)]
          (if job
            (handle-job-response conn jt job body)
            ;; unsolicited (e.g. logon response, license list) — route by EMsg
            (when-let [h (get @(:emsg conn) (get env/by-int emsg))]
              ((:handler h) (proto/decode (:type h) body)))))))))

;; ---- send -------------------------------------------------------------------

(defn send!
  "Fire-and-forget: no response is awaited."
  [conn kw type body-map]
  (send-bytes! conn (env/encode-message (get env/e kw) (header-base conn {})
                                        (proto/encode type body-map))))

(defn- send-with-job* [conn emsg-kw header-extra type body-map resp-type done?]
  (let [jid (str (swap! (:next-job conn) inc))
        cf (CompletableFuture.)
        job {:resp-type resp-type :cf cf :collect (atom []) :done? done? :timeout (atom nil)}]
    (swap! (:jobs conn) assoc jid job)
    (arm-timeout! conn jid job)
    (send-bytes! conn (env/encode-message (get env/e emsg-kw)
                                          (header-base conn (merge header-extra {:jobid-source jid}))
                                          (proto/encode type body-map)))
    cf))

(defn join
  "Block on a job CompletableFuture, unwrapping the ExecutionException so the
  original ex-info surfaces to the caller."
  [^CompletableFuture cf]
  (try
    (.get cf)
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn send-job!
  "Send a request and return a CompletableFuture of its single decoded response."
  [conn kw req-type req-map resp-type]
  (send-with-job* conn kw {} req-type req-map resp-type nil))

(defn send-job-collect!
  "Like send-job! but accumulates multi-part responses until (done? part) is
  true; the future resolves to the vector of parts."
  [conn kw req-type req-map resp-type done?]
  (send-with-job* conn kw {} req-type req-map resp-type done?))

(defn send-service!
  "A service-method call (EMsg ServiceMethodCallFromClient) with the method
  name in target_job_name."
  [conn method-name req-type req-map resp-type]
  (send-with-job* conn :service-call {:target-job-name method-name} req-type req-map resp-type nil))

(defn close! [conn]
  (doseq [[_ job] @(:jobs conn)]
    (cancel-timeout! job)
    (.completeExceptionally ^CompletableFuture (:cf job)
                            (ex-info "connection closed" {:reliquary/error :unavailable :kind :connection-closed})))
  (reset! (:jobs conn) {})
  (.shutdownNow ^ScheduledExecutorService (:scheduler conn))
  ((:close-fn conn)))

;; ---- test seam --------------------------------------------------------------

(defn conn-with-send-fn
  "A connection whose outgoing frames go to send-fn (a fn of byte[]) and whose
  close calls close-fn. Feed inbound frames via deliver-frame!. Used by tests."
  ([send-fn] (conn-with-send-fn send-fn (fn [])))
  ([send-fn close-fn]
   (new-conn send-fn close-fn
             (Executors/newSingleThreadScheduledExecutor
              (reify java.util.concurrent.ThreadFactory
                (newThread [_ r]
                  (doto (Thread. ^Runnable r "steam-cm-timeout") (.setDaemon true))))))))

;; ---- real transport ---------------------------------------------------------

(defn connect!
  "Open a real CM WebSocket connection to url (blocking until open). Frames are
  reassembled from fragments and dispatched through deliver-frame!."
  [url]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor
                   (reify java.util.concurrent.ThreadFactory
                     (newThread [_ r]
                       (doto (Thread. ^Runnable r "steam-cm-timeout") (.setDaemon true)))))
        conn-atom (atom nil)
        acc (ByteArrayOutputStream.)
        send-lock (Object.)
        ws-atom (atom nil)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1))
                   (onBinary [_ ws data last]
                     (let [n (.remaining data)
                           b (byte-array n)]
                       (.get data b)
                       (.write acc b 0 n))
                     (when last
                       (let [frame (.toByteArray acc)]
                         (.reset acc)
                         (try (deliver-frame! @conn-atom frame)
                              (catch Throwable t
                                (binding [*out* *err*]
                                  (println "steam cm: frame error:" (ex-message t)))))))
                     (.request ws 1)
                     nil)
                   (onClose [_ _ws _code _reason] nil)
                   (onError [_ _ws err]
                     (binding [*out* *err*]
                       (println "steam cm: socket error:" (ex-message err)))))
        ws ^WebSocket (-> (HttpClient/newHttpClient)
                          (.newWebSocketBuilder)
                          (.buildAsync (URI/create url) listener)
                          (.get 20 TimeUnit/SECONDS))
        _ (reset! ws-atom ws)
        send-fn (fn [^bytes b]
                  (locking send-lock
                    (let [^CompletableFuture cf (.sendBinary ^WebSocket ws (java.nio.ByteBuffer/wrap b) true)]
                      (.get cf 20 TimeUnit/SECONDS))))
        close-fn (fn [] (.sendClose ^WebSocket ws WebSocket/NORMAL_CLOSURE "bye"))
        conn (new-conn send-fn close-fn scheduler)]
    (reset! conn-atom conn)
    conn))
