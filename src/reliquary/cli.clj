;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.cli
  "A terminal face for the engine, so it can be driven before any UI exists.

   Built ahead of its plan task for one reason: logging in needs a human with a
   phone, and everything downstream needs the token that produces. Getting the
   token onto disk unblocks every other task."
  (:gen-class)
  (:require [clojure.string :as str]
            [reliquary.catalog :as catalog]
            [reliquary.config :as config]
            [reliquary.download :as download]
            [reliquary.error :as error]
            [reliquary.session :as session]
            [reliquary.steam.auth :as auth]
            [reliquary.steam.qr :as qr])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn exit-code-for
  "The CLI's exit-code contract, as promised in reliquary.error's docstring."
  [e]
  (case (:reliquary/error (ex-data e))
    :incorrect       1
    :unavailable     2
    :io              3
    :unauthenticated 4
    1))

(defn- gb
  "A size for humans. Community-sourced versions genuinely do not know their
   size, and 0 means unknown -- rendering that as '0.0 GB' would be a lie the
   user acts on."
  [bytes]
  (if (and bytes (pos? bytes))
    (format "%.1f GB" (/ (double bytes) (* 1024.0 1024 1024)))
    "size unknown"))

(defn- render-login-event
  "auth/login-qr! fires events; this is the only place that renders them.

   The refresh token never passes through here -- it is returned by the login
   call and goes straight to config/save-token!."
  [event]
  (when (= :qr (:type event))
    (println)
    (println (qr/terminal-string (:challenge-url event)))
    (println "  Scan with the Steam mobile app.")
    (println "  If the blocks will not scan, open this on your phone:")
    (println "   " (:challenge-url event))
    (println)
    (println "  Waiting for approval — this completes on its own.")
    (flush))
  nil)

(defn login
  "QR login, to a saved refresh token. Blocks until approved."
  [_]
  (let [{:keys [account steam-id] :as result} (auth/login-qr! render-login-event)]
    (config/save-token! result)
    ;; never print the token, not even truncated
    (println)
    (println "Signed in as" (or account steam-id))
    (println "Token saved to" (str (config/config-dir) "/config.edn") "(mode 0600)")
    0))

(defn logout [_]
  (config/forget-token!)
  (println "Signed out. The token is gone from the config file.")
  0)

(defn status
  "Prove the saved token actually logs on, rather than merely existing."
  [_]
  (let [{:keys [account]} (or (config/token)
                              (error/raise :unauthenticated
                                           "not signed in — run: reliquary login"))]
    (println "Stored account:" (or account "(unnamed)"))
    (let [s (session/open!)]
      (try
        (let [st (session/status s)]
          (println "Steam session:" (name (:status st))
                   "| steamid" (:steamid st)
                   "| licenses" (:licenses st)))
        (finally (session/close! s))))
    0))

(defn list-games
  "The catalog, as the app sees it."
  [_]
  (let [cat (catalog/load!)]
    (println "catalog" (:generated cat) "— schema" (:schema-version cat))
    (doseq [g (catalog/games cat)]
      (println)
      (println (format "%-8s %s" (str (:appid g)) (:title g)))
      (doseq [v (catalog/versions g)]
        ;; No build id. It is Valve's own ordinal for a build -- not the
        ;; version the user is choosing, and not what identifies the content.
        ;; The release date says more and every version has one.
        (println (format "   %-26s %-14s %s"
                         (:id v) (gb (:bytes v)) (or (:date v) "")))))
    0))

(defn parse
  "Validate CLI arguments before anything talks to Steam.

   `args` is the full argument vector including the command word, e.g.
   [\"download\" \"1091500\" \"public\" \"/games/cp2077\"]. For `download`, this
   resolves the appid and version-id against the catalog and raises :incorrect
   if either is unknown -- naming what was not found and, for a bad
   version-id, listing the game's valid ids, since the likely cause is a typo
   the user just made. A session is never opened to discover this.

   Returns {:game :version :dest} for `download`."
  [args]
  (let [[cmd & more] args]
    (case cmd
      "download"
      (let [[appid-str version-id dest] more]
        (when (or (str/blank? appid-str) (str/blank? version-id) (str/blank? dest))
          (error/raise :incorrect
                       "usage: reliquary download <appid> <version-id> <dest>"))
        (let [appid (try (Long/parseLong appid-str)
                         (catch Exception _
                           (error/raise :incorrect (str "not a valid appid: " appid-str))))
              cat   (catalog/load!)
              game  (catalog/game cat appid)]
          (when-not game
            (error/raise :incorrect (str "unknown appid: " appid-str)))
          (let [version (catalog/version game version-id)]
            (when-not version
              (error/raise :incorrect
                           (str "unknown version \"" version-id "\" for " (:title game)
                                " -- valid ids: "
                                (str/join ", " (map :id (:versions game))))))
            {:game game :version version :dest dest})))
      {:command cmd :args (vec more)})))

(defn- fmt-mb [bytes]
  (format "%.0f MB" (/ (double (or bytes 0)) 1048576.0)))

(defn- print-progress!
  "One rewriting terminal line -- a long download must not scroll the
   terminal with thousands of lines. Never touches depot keys or the token;
   the snapshot carries neither.

   The speed is :wire-bytes-per-sec, the COMPRESSED bytes coming off the
   wire -- the number a user can compare against their connection.
   :bytes-per-sec measures decompressed bytes, which is the right input for
   the percentage above but overstates the network by the compression ratio.
   Both arrive in B/s; the engine does not pre-scale, so the division to
   MB/s happens here, at the point of display."
  [{:keys [stage bytes-done bytes-total wire-bytes-per-sec]}]
  (let [pct (if (and bytes-total (pos? bytes-total))
              (* 100.0 (/ (double bytes-done) (double bytes-total)))
              0.0)]
    (print (format "\r%-11s %5.1f%%  %s / %s  %6.1f MB/s   "
                    (name (or stage :idle)) pct
                    (fmt-mb bytes-done) (fmt-mb bytes-total)
                    (/ (double (or wire-bytes-per-sec 0.0)) 1048576.0)))
    (flush)))

(defn- run-download!
  "Poll `ctx`'s snapshot onto one rewriting line while `execute!` runs on this
   thread, whatever it returns or throws. Always leaves a final progress line
   and a trailing newline, so a failure's stack trace (printed by -main)
   starts on its own line.

   `done-latch` counts down in the `finally`, AFTER `execute!` has returned or
   thrown -- which is after its own `finally` has flushed the progress file.
   That ordering, not a thread join, is what a Ctrl-C shutdown hook waits on:
   see `download` below for why joining the main thread deadlocks the JVM."
  [ctx ^CountDownLatch done-latch]
  (let [stop?   (atom false)
        printer (doto (Thread. (fn []
                                  (while (not @stop?)
                                    (print-progress! (download/snapshot ctx))
                                    (Thread/sleep 200)))
                                "reliquary-progress")
                  (.setDaemon true)
                  (.start))]
    (try
      (download/execute! ctx)
      (finally
        (reset! stop? true)
        (print-progress! (download/snapshot ctx))
        (println)
        (.countDown done-latch)))))

(defn download
  "Resolve `appid`/`version-id` against the catalog, open a Steam session,
   and run the download. The appid/version-id pair is checked BEFORE the
   session opens, so a typo costs nothing but a catalog lookup.

   Ctrl-C sets the engine's cancel flag via a shutdown hook, so an
   interrupted run flushes its progress file and resumes on the next
   invocation instead of losing work.

   The hook does NOT join the main thread -- that deadlocks the JVM. Per
   Runtime#exit's documented contract, once shutdown hooks are running, any
   OTHER thread's call to System/exit blocks indefinitely. -main always ends
   with System/exit on the main thread, so a hook that joins the main thread
   forms a cycle: the hook waits for main to finish, main finishes and calls
   System/exit, that call blocks because shutdown is already under way, so
   main never returns from it, so the join never returns, so the hook never
   completes, so the JVM never halts. The process hangs until SIGKILL.

   The fix is a CountDownLatch instead of a join: `run-download!`'s `finally`
   counts it down once the progress flush is done, and the hook awaits that
   latch (bounded, so a wedged download thread cannot hang Ctrl-C forever)
   instead of the thread itself. Once the hook returns, the JVM halts
   regardless of what the main thread is doing -- so main blocking forever in
   System/exit becomes harmless instead of fatal."
  [args]
  (let [{:keys [game version dest]} (parse (into ["download"] args))
        session (session/open!)]
    (try
      (println "Resolving" (:title game) (:label version)
               (str "(" (gb (:bytes version)) ")") "to" (str dest) "...")
      (let [rv         (download/resolve-version session game version)
            ctx        (download/make-ctx
                        (assoc rv :dest dest :appid (:appid game) :version-id (:id version)))
            done-latch (CountDownLatch. 1)
            hook       (Thread. (fn []
                                   (download/cancel! ctx)
                                   (.await done-latch 10 TimeUnit/SECONDS))
                                 "reliquary-cancel")]
        (.addShutdownHook (Runtime/getRuntime) hook)
        (try
          (let [snap (run-download! ctx done-latch)]
            (case (:stage snap)
              :done      (do (println "Download complete:" (str dest)) 0)
              :cancelled (do (println "Cancelled -- rerun the same command to resume.") 130)
              (do (println "Download did not complete (stage" (name (:stage snap)) ")") 1)))
          (finally
            (try (.removeShutdownHook (Runtime/getRuntime) hook)
                 (catch IllegalStateException _ nil)))))
      (finally (session/close! session)))))

(def ^:private commands
  {"login"    #'login
   "logout"   #'logout
   "status"   #'status
   "list"     #'list-games
   "download" #'download})

(defn run [args]
  (if-let [f (commands (first args))]
    (f (rest args))
    (do (println "usage: reliquary <login|logout|status|list|download>")
        (println)
        (println "  login    scan a QR with the Steam mobile app; saves a refresh token")
        (println "  status   confirm the saved token still logs on to Steam")
        (println "  list     show the bundled catalog's games and versions")
        (println "  download <appid> <version-id> <dest>   fetch a version to disk")
        (println "  logout   forget the saved token")
        (if (first args) 1 0))))

(defn -main [& args]
  (System/exit
   (try (run args)
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println "error:" (ex-message e)))
          (exit-code-for e)))))
