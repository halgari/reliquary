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

(defn terminal
  "The controlling terminal, or nil when there is not one.

   `System/console` is not that question. Since JDK 22 it returns a Console
   even when stdin is a pipe or a file, so a non-nil answer says nothing about
   whether echo can be turned off; `.isTerminal` is the part that matters."
  ^java.io.Console []
  (when-let [c (System/console)]
    (when (.isTerminal c) c)))

(defn read-password-chars
  "The raw `Console.readPassword` call, as its own var so a test can stand in for
   it without a tty. Returns a char[], or nil at end of input."
  ^chars [c ^String prompt]
  (.readPassword ^java.io.Console c "%s" (into-array Object [prompt])))

(defn read-secret
  "Read a password from the terminal WITHOUT echoing it.

   Three refusals, all of them replacing a worse outcome:

   - No terminal: there is no way to suppress the echo, and a password read off
     a pipe is a password printed into whatever captures that output.
   - End of input (Ctrl-D): `readPassword` returns nil, and that nil used to
     travel into `crypto/encrypt-password`, NPE inside its catch-all, and
     surface as \"steam returned an unusable RSA public key\" -- a message about
     Steam's key for something that happened at a prompt.
   - An empty password: Steam rate-limits per account (eresult 84), so spending
     one of the few attempts the user gets to be told a blank password is wrong
     is worse than saying so here, where the answer is already known."
  ^String [^String prompt]
  (let [c (or (terminal)
              (error/raise :incorrect
                           (str "no terminal here to read a password without echoing it -- "
                                "run `reliquary login` with no account name and scan the QR")))
        chars (read-password-chars c prompt)
        pw (when chars (String. ^chars chars))]
    (when-not (seq pw)
      (error/raise :incorrect "no password entered"))
    pw))

(defn read-visible
  "Read an echoed line from the terminal. A Steam Guard code is not worth
   hiding from the person typing it, and hiding a five-character code makes it
   much easier to mistype -- which costs a whole login attempt."
  ^String [^String prompt]
  (if-let [c (terminal)]
    (.readLine c "%s" (into-array Object [prompt]))
    (do (print prompt) (flush) (read-line))))

(defn- handle-login-event
  "Both login flows fire events; this is the only place that renders them --
   and, for :guard-needed, the only place that ANSWERS one. That code has to be
   the return value: printing a prompt and returning nil raises \"no steam
   guard code supplied\" on an otherwise perfectly good login.

   The refresh token never passes through here -- it is returned by the login
   call and goes straight to config/save-token!."
  [event]
  (case (:type event)
    :qr
    (do (println)
        (println (qr/terminal-string (:challenge-url event)))
        (println "  Scan with the Steam mobile app.")
        (println "  If the blocks will not scan, open this on your phone:")
        (println "   " (:challenge-url event))
        (println)
        (println "  Waiting for approval — this completes on its own.")
        (flush)
        nil)

    :guard-needed
    (do (when (:retry? event)
          (println "  That code was not accepted. Check it and try again."))
        (read-visible (case (:code-type event)
                        2 "  Steam Guard code (emailed to you): "
                        3 "  Steam Guard code (authenticator app): "
                        "  Steam Guard code: ")))

    :confirmation-pending
    (do (println)
        (println (if (= 5 (:confirmation-type event))
                   "  Approve the sign-in using the link Steam just emailed you."
                   "  Approve the sign-in request in your Steam mobile app."))
        (println "  This completes on its own — there is nothing to type.")
        (flush)
        nil)

    nil))

(defn login
  "Sign in, to a saved refresh token. Blocks until the login is approved.

   With no account name this is the QR flow, which needs a phone. With one it
   is account name and password, for a machine that has no phone to hand or an
   authenticator that lives somewhere else."
  [[account-name]]
  (let [{:keys [account steam-id] :as result}
        (if (seq account-name)
          (auth/login-credentials! account-name
                                   (read-secret (str "Password for " account-name ": "))
                                   handle-login-event)
          (auth/login-qr! handle-login-event))]
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
        (println "  login <account>   sign in with a password instead of a phone")
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
