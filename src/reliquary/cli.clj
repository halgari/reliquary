;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.cli
  "A terminal face for the engine, so it can be driven before any UI exists.

   Built ahead of its plan task for one reason: logging in needs a human with a
   phone, and everything downstream needs the token that produces. Getting the
   token onto disk unblocks every other task."
  (:gen-class)
  (:require [reliquary.catalog :as catalog]
            [reliquary.config :as config]
            [reliquary.error :as error]
            [reliquary.session :as session]
            [reliquary.steam.auth :as auth]
            [reliquary.steam.qr :as qr]))

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
      (doseq [v (:versions g)]
        (println (format "   %-26s %-14s %s"
                         (:id v) (gb (:bytes v))
                         (if (seq (:build v)) (str "build " (:build v)) "build unknown")))))
    0))

(def ^:private commands
  {"login"  #'login
   "logout" #'logout
   "status" #'status
   "list"   #'list-games})

(defn run [args]
  (if-let [f (commands (first args))]
    (f (rest args))
    (do (println "usage: reliquary <login|logout|status|list>")
        (println)
        (println "  login    scan a QR with the Steam mobile app; saves a refresh token")
        (println "  status   confirm the saved token still logs on to Steam")
        (println "  list     show the bundled catalog's games and versions")
        (println "  logout   forget the saved token")
        (if (first args) 1 0))))

(defn -main [& args]
  (System/exit
   (try (run args)
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println "error:" (ex-message e)))
          (exit-code-for e)))))
