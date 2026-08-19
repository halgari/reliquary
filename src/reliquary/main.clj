;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main
  "The desktop entry point: opens a real window and drives it against real
   Steam. Everything below it -- the theme, the screenshot harness, the
   window frame, the four screens, the engine -- is already proven; this is
   the wiring that makes them a running app.

   ## The shape of the thing

   One atom, one `view`, and a handful of `!`-suffixed functions that move
   the atom. `view` dispatches on `:screen` (`:login`, `:library`,
   `:download`, `:done`) and hands the whole state map to a screen namespace
   that reads only the keys it documents. The screens are pure functions;
   every side effect in the app lives in this namespace.

   Every `:on-*` handler is built once, in `initial-state`, from the
   not-yet-populated state atom -- see that function's docstring for why
   that matters and which crash it prevents.

   ## Threads

   Three rules, all of them load-bearing:

   1. **Nothing that can block runs on the FX thread.** Opening a Steam
      session takes seconds and can fail; resolving a version is a dozen
      network round trips; `execute!` runs for half an hour. All of them
      run on threads created here, and the only thing they do to the state
      atom is `fx-run!` a `swap!` back onto the FX thread.
   2. **Every thread and executor created here is a daemon.** The JavaFX
      Application Thread is not, so the JVM only exits after `Platform/exit`
      -- and one non-daemon worker of ours would then keep the process alive
      with no window on screen and no way to reach it. `future` is
      deliberately not used anywhere in this namespace for exactly that
      reason: Clojure's agent pool threads are not daemons and hold the JVM
      open for up to a minute after the window closes.
   3. **A failure downstream degrades a screen, never blocks one.** A
      session that will not open leaves `:owned` nil, which `library/view`
      reads as \"treat everything as owned\" -- an unmarked library beats a
      library the user cannot reach.

   Deliberately does NOT require `reliquary.ui.shot`: that namespace sets
   `prism.lcdtext=false` at load time to make screenshots easier to review,
   and that property change would leak into the shipped app, degrading text
   rendering on a real monitor."
  (:gen-class)
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [reliquary.catalog :as catalog]
            [reliquary.config :as config]
            [reliquary.download :as download]
            [reliquary.session :as session]
            [reliquary.steam.auth :as auth]
            [reliquary.steam.installs :as installs]
            [reliquary.steam.local :as local]
            [reliquary.switch :as switch]
            [reliquary.ui.app :as app]
            [reliquary.ui.art :as art]
            [reliquary.ui.done :as done]
            [reliquary.ui.download :as download-screen]
            [reliquary.ui.library :as library]
            [reliquary.ui.login :as login]
            [reliquary.ui.switch :as switch-screen]
            [reliquary.ui.theme :as theme])
  (:import (java.io File)
           (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicLong)
           (javafx.application Platform)
           (javafx.stage DirectoryChooser)))

(defn fx-run!
  "Marshal onto the FX thread. A var so tests can replace it with identity."
  [f]
  (Platform/runLater f))

;; ---------------------------------------------------------------------------
;; threads -- every one of them a daemon, see the namespace docstring

(defn- daemon-factory ^ThreadFactory [prefix]
  (let [n (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. r (str prefix "-" (.incrementAndGet n)))
          (.setDaemon true))))))

(defn daemon!
  "Run `f` on a new daemon thread named `name`, and return the thread."
  ^Thread [^String name f]
  (doto (Thread. ^Runnable f name)
    (.setDaemon true)
    (.start)))

(defn- scheduler
  "A single-threaded, daemon `ScheduledExecutorService`."
  ^ScheduledExecutorService [prefix]
  (Executors/newSingleThreadScheduledExecutor (daemon-factory prefix)))

;; ---------------------------------------------------------------------------
;; the two live handles that are not state: a Steam session and a running
;; download's context. Neither belongs in the state atom -- one is an open
;; socket and the other carries an AtomicBoolean, and both would be printed
;; by any `pr-str` of the state a test or a log ever takes.

(def ^:private session* (atom nil))
(def ^:private ctx* (atom nil))
(def ^:private poller* (atom nil))

(defn open-session!
  "The open Steam session, opening one on first ask. BLOCKING -- seconds, and
   it can raise :unauthenticated -- so it is never called on the FX thread.

   One session is reused for the whole run: ownership, version resolution
   and every download go through the same logon, because logging on repeatedly
   is both slow and something Steam rate-limits."
  []
  (locking session*
    (or @session*
        (reset! session* (session/open!)))))

(defn close-session!
  "Drop the session, if any. Used on sign-out: the next open! must not reuse
   a logon that belongs to the account the user just signed out of."
  []
  (locking session*
    (when-let [s @session*]
      (try (session/close! s) (catch Exception _ nil))
      (reset! session* nil))
    nil))

;; ---------------------------------------------------------------------------
;; art -- `art/capsule` and `art/screenshot` read and decode a file per call,
;; and `view` calls them on the FX thread on EVERY render. The download
;; screen re-renders four times a second while a download runs, and decoding
;; a 1920x1080 JPEG at that rate on the FX thread is a stutter the user sees.
;; Non-nil results are memoized; nil is not, since nil only ever means "the
;; background fetch has not landed yet" and must stay retryable.

(def ^:private art-cache (atom {}))

(defn- cached-art [k f]
  (or (get @art-cache k)
      (when-let [image (f)]
        (swap! art-cache assoc k image)
        image)))

(defn capsule-image [game] (cached-art [:capsule (:appid game)] #(art/capsule game)))
(defn screenshot-image [game n] (cached-art [:shot (:appid game) n] #(art/screenshot game n)))

;; ---------------------------------------------------------------------------
;; login
;;
;; Two flows land here, and the login screen offers both at once: a QR poll
;; that starts the moment the screen appears, and a credential sign-in the
;; user starts by pressing a button. Neither knows about the other, so this
;; layer arbitrates between them.

(declare enter-library!)

(def ^:private login-epoch*
  "Which login attempt is the current one. Every start bumps it, and a login
   whose epoch is stale stops polling and drops whatever it was about to do.

   Without this the QR poll begun on entering the screen runs for the life of
   the process: the user signs in with a password, the library appears, and a
   thread behind it keeps asking Steam about a challenge nobody will ever scan,
   every few seconds, until the window closes. The stale thread can also still
   SUCCEED -- someone scans an old code -- and would otherwise save its token
   over the one the user actually chose."
  (atom 0))

(def ^:private guard-code*
  "The promise a blocked credential login is waiting on for a typed Steam Guard
   code, or nil.

   auth/login-credentials! wants the code as the synchronous RETURN value of
   its event callback, on a thread that must not be the FX thread. The UI has
   the opposite shape: it puts a field on screen and hears about the code
   whenever the user gets round to typing it. This promise is where the two
   shapes meet -- the login thread parks on it, and the Sign in button
   delivers to it."
  (atom nil))

(def ^:private login-finished
  "State keys that must not outlive the login thread they belong to.

   Every one of these describes a login IN PROGRESS: a code some thread is
   parked waiting for, a button that is inert because a submit is running, a
   password. When the thread stops -- succeeded, failed or abandoned -- they all
   have to go, and BOTH exit paths have to do it.

   Clearing only `:credential-state` on the error path left `:guard-type` set,
   which put a Guard-code field on screen with no login behind it. Pressing Sign
   in there delivered a code to a promise nobody held, set `:submitting`, and
   left the button disabled for the rest of the run -- a login screen whose only
   working control was Sign out."
  {:credential-state nil
   :confirmation-type nil
   :guard-type nil
   :guard-retry? nil
   :guard-code nil
   :password nil})

(defn- end-login!
  "Drop the Guard promise, waking anything parked on it. Paired with
   `begin-login-epoch!` around a login's lifetime."
  []
  (when-let [p @guard-code*] (deliver p nil))
  (reset! guard-code* nil))

(defn- begin-login-epoch!
  "Claim the newest login, returning its epoch.

   Delivering nil to any outstanding Guard promise is not a tidy-up: a login
   thread parked on a code the user has now abandoned would otherwise stay
   blocked on that promise forever. Waking it makes it raise, and the epoch
   check then discards the error rather than showing the user a complaint about
   a login they walked away from."
  []
  (end-login!)
  (swap! login-epoch* inc))

(defn- run-login!
  "Run a BLOCKING login on a daemon thread, marshalling its outcome into
   `state`. Returns a promise so callers (and tests) can await it.

   `login` is handed an opts map carrying :abort? and :current?, and returns the
   token map, or nil if it was abandoned. Both come from the same epoch check and
   both are load-bearing: :abort? stops the poll loop, and :current? gates the
   state writes the login makes WHILE running. Gating only the poll left an
   abandoned thread able to push a rotated challenge over the URL a newer login
   had already put on screen -- a QR belonging to a session nobody polls, which
   approves on the phone and never signs the user in. `approved` is merged into the state on success --
   the QR flow has a card to light up, the credential flow does not.

   A daemon thread and a promise rather than a `future`: Clojure's agent pool,
   which `future` uses, is not made of daemon threads and keeps the JVM alive
   for up to a minute after the window closes.

   The refresh token deliberately never enters `state` -- a token in the state
   atom is one careless render away from being on screen. It goes straight to
   config/save-token!."
  [state {:keys [thread approved on-failure]
          :or   {on-failure (fn [_] nil)}} login]
  (let [p (promise)
        epoch (begin-login-epoch!)
        current? #(= epoch @login-epoch*)]
    (daemon!
     thread
     (fn []
       (try
         ;; nil means abandoned: no token, no error, nothing to say
         (when-let [result (login {:abort? #(not (current?))
                                   :current? current?})]
           (when (current?)
             (config/save-token! result)
             (fx-run! #(swap! state merge login-finished approved
                              {:signed-in? true
                               :status-line (or (:account result) "signed in")}))
             (enter-library! state)))
         ;; Throwable, not ExceptionInfo. An IOException out of save-token!, an
         ;; NPE, a decode failure slipping past api/decode-response -- anything
         ;; that escaped left :credential-state :submitting with no :error, so
         ;; the button read "Signing in…" and stayed disabled for the rest of
         ;; the run with nothing on screen saying why. An uncategorized throw
         ;; has no message worth showing, so it gets one.
         (catch Throwable t
           (when (current?)
             (fx-run! #(swap! state merge login-finished
                              {:error (or (not-empty (ex-message t))
                                          (str "the sign-in failed unexpectedly ("
                                               (.getName (class t)) ")"))}))
             (on-failure state)))
         (finally
           (when (current?) (end-login!))
           (deliver p :done)))))
    p))

(defn start-login!
  "Run the BLOCKING QR login on a daemon thread. Returns a promise."
  [state]
  (run-login!
   state
   {:thread "reliquary-login" :approved {:qr-state :approved}}
   (fn [{:keys [current?] :as opts}]
     (auth/login-qr!
      (fn [event]
        (when (and (current?) (= :qr (:type event)))
          (fx-run! #(swap! state assoc
                           :challenge-url (:challenge-url event)
                           :qr-state :waiting)))
        nil)
      opts))))

(defn start-credential-login!
  "Run the BLOCKING credential login on a daemon thread. Returns a promise.

   `password` is a parameter rather than something read out of `state` for the
   same reason the token never goes in: the caller clears it from the atom as
   it hands it over, and from here on the only copy is this thread's local."
  [state username password]
  (run-login!
   state
   {:thread "reliquary-credential-login"
    ;; Starting this login aborted the QR poll, and nothing else ever restarts
    ;; it -- `sign-out!` is the only other caller and its button does not render
    ;; on the login screen. The card stayed on screen animating under "Waiting
    ;; for approval on your device" with no thread behind it, so a user who
    ;; mistyped a password and then reached for their phone could scan a
    ;; challenge nobody was polling and wait forever. A failed credential
    ;; attempt leaves the user on this screen, so the other half of it has to
    ;; work.
    :on-failure start-login!}
   (fn [{:keys [current?] :as opts}]
     (auth/login-credentials!
      username password
      (fn [event]
        (when (current?)
          (case (:type event)
            ;; MUST return the code: this blocks the login thread until the
            ;; button delivers one. `guard-code*` is installed before the prompt
            ;; reaches the screen, so there is no window in which the user can
            ;; submit a code with nothing waiting for it.
            :guard-needed
            (let [p (promise)]
              (reset! guard-code* p)
              (fx-run! #(swap! state assoc
                               :guard-type (:code-type event)
                               :guard-retry? (boolean (:retry? event))
                               :guard-code ""
                               ;; back to pressable: the code is the user's move
                               :credential-state nil))
              @p)

            ;; types 4 and 5: nothing to type, but saying nothing leaves the
            ;; user watching a screen that looks exactly as it did before they
            ;; pressed
            ;; the TYPE rides along: 4 is the mobile app and 5 is a link Steam
            ;; emails, and the panel needs to name the right one
            :confirmation-pending
            (do (fx-run! #(swap! state assoc
                                 :credential-state :confirmation-pending
                                 :confirmation-type (:confirmation-type event)))
                nil)

            nil)))
      opts))))

(defn submit-credentials!
  "The Sign in button. One control, three outcomes.

   The branch is on the PROMISE, not on `:guard-type`. A parked promise is what
   actually means \"a login thread is waiting for a code\"; `:guard-type` only
   means \"a code field is on screen\", and keying on it let the two disagree --
   a delivery to nothing, `:submitting` left set, and a dead button with no
   login behind it. On the promise, that disagreement cannot be represented: if
   nothing is waiting, there is no code to submit, and the honest move is to
   drop the stale prompt and let the user start over.

   `:credential-state` is set BEFORE the promise is delivered, not after. The
   delivery unblocks a thread that can finish and clear the state key
   immediately, and a swap! landing after that would re-disable the button on a
   screen that is already done with it."
  [state]
  (let [{:keys [account password guard-code guard-type]} @state
        waiting (when guard-type @guard-code*)]
    (cond
      waiting
      (do (swap! state assoc :guard-code "" :guard-retry? false
                 :credential-state :submitting)
          (deliver waiting guard-code)
          nil)

      ;; a code field with nothing behind it: the login it belonged to is gone
      guard-type
      (do (swap! state merge login-finished
                 {:error "That sign-in attempt has lapsed. Please sign in again."})
          nil)

      :else
      (do (swap! state assoc :password nil :error nil :guard-retry? false
                 :credential-state :submitting)
          (start-credential-login! state account password)))))

;; ---------------------------------------------------------------------------
;; the library screen

(defn load-ownership!
  "Populate `:owned` from the Steam session, on a daemon thread.

   A failure -- no session, an expired token, Steam unreachable -- leaves
   `:owned` nil, and `library/view` reads nil as \"treat every game as
   owned\". That is the whole point: ownership marking is a courtesy (the
   authority is Steam's answer to the depot-key request during a download),
   and a library greyed out end to end because a socket did not open is a
   worse app than a library that simply does not mark anything."
  [state]
  (daemon!
   "reliquary-ownership"
   (fn []
     (let [owned (try (session/owned-appids (open-session!))
                      (catch Throwable _ nil))]
       (fx-run! #(swap! state assoc :owned owned))))))

(defn- nudge-art!
  "Re-render periodically for a short while after landing on the library.

   Capsule art is fetched in the background and lands on disk seconds after
   the grid first renders, but a cljfx renderer only re-renders when the
   state map CHANGES -- so without this the grid would keep showing hatch
   placeholders until the user happened to type in the filter box. Bumping a
   counter is the smallest honest way to say \"the world may look different
   now\". Twenty seconds is well past the point every capsule has landed;
   the scheduler is a daemon and shuts itself down when the count runs out."
  [state]
  (let [sched (scheduler "reliquary-art-tick")
        left  (AtomicLong. 20)]
    (.scheduleAtFixedRate
     sched
     (fn []
       (try
         (if (pos? (.decrementAndGet left))
           (fx-run! #(swap! state update :art-tick (fnil inc 0)))
           (.shutdown sched))
         (catch Throwable _ nil)))
     1 1 TimeUnit/SECONDS)
    sched))

(defn enter-library!
  "Land on the library screen: the catalog now, ownership and art when they
   arrive. Safe to call from any thread -- every state change goes through
   `fx-run!`."
  [state]
  (let [games (catalog/games (catalog/load!))]
    (fx-run! #(swap! state assoc
                     :screen :library
                     :games games
                     :snapshot nil
                     :error nil))
    (daemon! "reliquary-art-prefetch" (fn [] (run! art/prefetch! games)))
    (load-ownership! state)
    (nudge-art! state)
    games))

(defn refresh-catalog!
  "Ask the repo for a newer catalog and, if one arrives, put it on screen.

   `catalog/refresh!` does the work and returns immediately: it fetches on its
   own daemon thread, caps the response, parses it, keeps it only if it is newer
   than what we already have, caches it to disk for next launch, and swallows
   every failure. So the only thing left to decide here is what to do with a
   catalog that IS newer.

   Silent on failure, by design and by request. There is no `:error` set and no
   status line changed: a catalog that could not be fetched leaves the app
   exactly as it was, running on the bundled copy or the last good cache. An app
   that cannot show its library because GitHub was slow is a worse app.

   Returns whatever `catalog/refresh!` returns, so a caller can await the thread;
   `-main` does not.

   Art matters here and is easy to miss: `enter-library!` prefetches art for the
   catalog it read at the time, so a game that only arrives in this fresher
   document has nothing on disk and would render the hatch placeholder for the
   rest of the session."
  [state]
  (catalog/refresh!
   catalog/catalog-url
   (fn [fresh]
     (let [games (catalog/games fresh)]
       ;; on-done runs on refresh!'s daemon thread, so this is exactly the case
       ;; the namespace docstring's rule 1 exists for
       (fx-run! #(swap! state assoc :games games))
       (daemon! "reliquary-catalog-art" (fn [] (run! art/prefetch! games)))))))

(defn selected-game
  "The game `:selected-appid` names, or nil."
  [{:keys [games selected-appid]}]
  (when selected-appid
    (first (filter #(= selected-appid (:appid %)) games))))

(defn selected-version
  "The version `:selected-version-id` names within the selected game, or nil."
  [state]
  (catalog/version (selected-game state) (:selected-version-id state)))

(def ^:private rate-interval-ms
  "How often progress reaches the screen -- a switch's, and identification's.
   The engine samples at 250 ms and the sparkline is a ring of those, so both
   match it."
  250)

(defn advance-rate
  "Fold one progress reading into a rate tracker.

   Returns the tracker, carrying `:emit?` when a screen update is due. Public
   because it is the one piece of this worth testing directly.

   Throttled, and NOT for tidiness: `chunk-index` reports progress per chunk --
   about 16,000 of them in the 16 seconds a 15 GB install takes -- and
   marshalling every one onto the FX thread would flood it with work nobody can
   see. One reading every 250 ms is what the screen can actually draw.

   Used by the switch screen and by the library panel's identification pass,
   which is why it lives above both.

   Two rates, because they answer different questions. `:bytes-per-sec` is the
   live rate over the last interval, which is what the speedometer and the
   sparkline want. `:session-bytes-per-sec` is the average across the whole run,
   which is what the ETA wants: `download/eta-seconds` takes it deliberately,
   because dividing the remaining bytes by an instantaneous rate gives a clock
   that swings between four minutes and forty twice a second.

   A reading that goes BACKWARDS is treated as a restart rather than a negative
   rate. Each phase begins again at zero -- hashing ends at 15 GB and applying
   starts at 0 -- and a negative rate renders as a backwards sparkline and a
   nonsense clock."
  [{:keys [t0 base last-emit last-done samples] :as st} done now]
  (let [done (long (or done 0))]
    (cond
      ;; first reading, or a phase that restarted below where we were
      (or (nil? t0) (< done (long (or last-done 0))))
      {:t0 now :base done :last-emit now :last-done done
       :samples [] :bytes-per-sec 0.0 :session-bytes-per-sec 0.0 :emit? true}

      (< (- now (long last-emit)) rate-interval-ms)
      (assoc st :emit? false)

      :else
      (let [dt   (/ (- now (long last-emit)) 1000.0)
            bps  (if (pos? dt) (/ (- done (long last-done)) dt) 0.0)
            sdt  (/ (- now (long t0)) 1000.0)
            sbps (if (pos? sdt) (/ (- done (long base)) sdt) 0.0)]
        (assoc st
               :last-emit now
               :last-done done
               :samples (vec (take-last download/sample-count (conj (or samples []) bps)))
               :bytes-per-sec (max 0.0 (double bps))
               :session-bytes-per-sec (max 0.0 (double sbps))
               :emit? true)))))

(def hashing-visible-after-ms
  "How long identification must have been running before its bar is shown.

   Measured on a real Skyrim SE install, identification reads two executables --
   46 MB -- in about 22 ms warm. Emitting the first reading the moment it arrives
   put a progress box on screen for a single frame on every click in the library,
   which reads as a glitch rather than as progress. Below this the panel simply
   goes from empty to answered, as it did before it could report at all.

   It is not always fast: the same pass on a cold spinning disk, or on a game
   whose executables are several hundred megabytes, is seconds rather than
   milliseconds, and that is the case the bar exists for.

   Public so a test can move it rather than sleep."
  200)

(defn- identify-installed
  "Which version this install IS, decided by hashing its executable.

   NOT from Steam's appmanifest. That is bookkeeping: it goes stale the moment
   files are swapped by hand, it says nothing after a half-applied switch, and it
   is exactly what this app makes untrue. The bytes are the authority.

   Free, and offline. The catalog carries each version's executable hashes -- a
   manifest is immutable, so they are resolved once by the catalog tool and never
   fetched again -- so this reads two files (about 40 ms on a real Skyrim
   install) and looks them up. No session, no manifest, no network.

   Falls back to the appmanifest's manifest ids when the catalog has no
   executable hashes for this game, which is true of every catalog generated
   before the field existed, including the one currently shipped. The hash wins
   wherever the catalog can answer."
  [game install opts]
  (let [candidates (local/catalog-candidates (catalog/versions game))]
    (if (seq candidates)
      (let [paths  (distinct (mapcat #(local/exe-paths (:files %)) candidates))
            hashes (local/hash-paths (:path install) paths opts)]
        (:version (local/identify hashes candidates)))
      ;; nothing was read, so nothing to report -- the fallback is one small
      ;; file of Steam's bookkeeping, not a pass over the install
      (installs/installed-version game install))))

(defn detect-install!
  "Look for an existing Steam install of `appid` and, if it is still the selected
   game when the answer arrives, put it on the panel. Returns a promise.

   Off the calling thread, and not as a precaution: `installs/find-install` walks
   every Steam library and reads every appmanifest in each, and `select-game!` is
   a click handler. On a machine with several libraries on a spinning disk that
   is long enough to drop a frame.

   The selection is re-checked after the lookup. Clicking Skyrim and then Stardew
   before the first answer returns would otherwise paint Skyrim's install path
   underneath Stardew's version list -- someone else's folder with a Switch
   button under it, which is the one thing a destructive action must never
   show."
  [state appid]
  (let [p (promise)]
    (daemon!
     "reliquary-install-detect"
     (fn []
       (try
         (let [install (installs/find-install appid)
               game    (first (filter #(= appid (:appid %)) (:games @state)))
               ;; the selection moving on is the abort signal. select-game! has
               ;; already cleared the panel and started another detect by then,
               ;; and a user clicking down a library must not leave one thread
               ;; per game reading executables off a spinning disk.
               stale?  (fn [] (not= appid (:selected-appid @state)))
               t0      (System/currentTimeMillis)
               rate*   (atom {})
               version (when (and install game)
                         (identify-installed
                          game install
                          {:abort? stale?
                           :on-progress
                           (fn [{:keys [done total]}]
                             ;; throttled and rate-tracked exactly like the
                             ;; switch screen's, so the box gets the rate and
                             ;; the clock it now renders
                             (let [now (System/currentTimeMillis)
                                   r   (swap! rate* advance-rate done now)]
                               (when (and (:emit? r)
                                          (>= (- now t0) hashing-visible-after-ms))
                                 (fx-run!
                                  #(swap! state
                                          (fn [st]
                                            (if (= appid (:selected-appid st))
                                              (assoc st :hashing
                                                     {:done done :total total
                                                      :bytes-per-sec (:bytes-per-sec r)
                                                      :session-bytes-per-sec
                                                      (:session-bytes-per-sec r)})
                                              st)))))))}))]
           (fx-run! #(swap! state (fn [s]
                                    (if (= appid (:selected-appid s))
                                      (assoc s :install install :installed-version version)
                                      s)))))
         ;; a Steam directory we cannot read is not an error the user needs: the
         ;; panel simply stays in its ordinary download shape
         (catch Throwable _ nil)
         (finally
           ;; ALWAYS, including the throw above: a bar left under an answered
           ;; panel reads as still working. Guarded on the selection so a slow
           ;; thread finishing cannot wipe the bar of the game now on screen.
           (fx-run! #(swap! state (fn [s]
                                    (if (= appid (:selected-appid s))
                                      (assoc s :hashing nil)
                                      s))))
           (deliver p :done)))))
    p))

(def ^:private switch-cancelled*
  "Set by the Cancel button while a switch runs.

   A download is cancelled through its own context; a switch has none, so before
   this the Cancel button rendered on every switch stage and did nothing at all
   -- inert on the one operation that most needs stopping, because it rewrites a
   game in place and reads fifteen gigabytes to decide how."
  (atom false))

(defn- switch-snapshot
  "A `download/snapshot`-shaped map for a switch phase, so the download screen
   renders a switch without knowing one from a download.

   The phases map onto stages that screen already understands: hashing and
   staging are preparation, applying is the transfer. `:switching` rather than
   `:downloading` because the user is changing a game they already have, and
   \"Downloading\" over a 0.21 GB transfer into an existing folder reads as a
   fresh fifteen gigabyte install."
  [phase {:keys [done total]} plan rate]
  {;; marks this as a switch for the screen's copy: without it the panel calls a
   ;; failed switch an interrupted DOWNLOAD and offers to resume one, and the
   ;; header advertises the finished install size next to a transfer a fraction
   ;; of it. Both were visible only by rendering the real window.
   :switch?     true
   :stage       (case phase :hashing :hashing :staging :staging :switching)
   :bytes-done  (or done 0)
   :bytes-total (or total 0)
   :chunks-done 0
   :chunks-total (count (:fetch plan))
   :wire-bytes  0
   ;; the live rate drives the speedometer and the sparkline; the session
   ;; average drives the clock. See `advance-rate` for why they are not the same
   ;; number.
   :bytes-per-sec (:bytes-per-sec rate 0.0)
   :wire-bytes-per-sec (:bytes-per-sec rate 0.0)
   :session-bytes-per-sec (:session-bytes-per-sec rate 0.0)
   :samples     (:samples rate [])
   :error       nil})

(defn open-switch-screen!
  "The library panel's Switch button: show the switch screen, ready.

   Nothing runs yet. The screen names the install, the version on it and the
   version it would become, and offers the action -- which is the least a screen
   can do before rewriting a game in place."
  [state]
  (let [{:keys [games selected-appid selected-version-id]} @state
        game (first (filter #(= selected-appid (:appid %)) games))]
    (swap! state assoc
           :screen :switch
           :game game
           :target-version (first (filter #(= selected-version-id (:id %))
                                          (catalog/versions game)))
           :snapshot nil)))

(defn switch-install!
  "Change the selected install to the selected version. Returns a promise.

   Refuses without an identified installed version, and that refusal is the
   design rather than caution: the chunk index is built with the INSTALLED
   version's manifest as its boundary map, because Steam's chunk boundaries come
   from a manifest and cannot be recomputed from local bytes. With no known
   version there are no boundaries, and guessing one would index garbage and
   then write it.

   Everything runs on a daemon thread; the screen moves to the transfer view and
   is driven by `switch-snapshot`. A failure lands in the snapshot's `:error`
   rather than being swallowed -- this rewrites a game in place, and a failure
   that left the screen looking finished would be the worst outcome available."
  [state]
  (let [p (promise)
        {:keys [install installed-version games selected-appid selected-version-id]} @state
        game    (first (filter #(= selected-appid (:appid %)) games))
        version (or (:target-version @state)
                    (first (filter #(= selected-version-id (:id %)) (catalog/versions game))))]
    (cond
      (not (and install game version))
      (do (swap! state assoc :error "Nothing to switch: pick an installed game and a version.")
          (deliver p :done))

      (nil? installed-version)
      (do (swap! state assoc
                 :error (str "Reliquary cannot tell which version is installed, so it "
                             "cannot work out what to change. Reinstall from Steam, or "
                             "download this version to a new folder instead."))
          (deliver p :done))

      :else
      (daemon!
       "reliquary-switch"
       (fn []
         (try
           ;; the screen moves FIRST, before anything that can fail. Opening the
           ;; session raises when the token has expired, and a failure raised
           ;; before the move would land in a snapshot on a screen the user is
           ;; not looking at -- they would press Switch and watch nothing happen.
           (let [_ (fx-run! #(swap! state assoc
                                    :screen :switch
                                    :game game :version version
                                    :target-version version
                                    :snapshot (switch-snapshot :hashing {} nil nil)))
                 _ (reset! switch-cancelled* false)
                 session (open-session!)
                 plan*   (atom nil)
                 rate*   (atom {})
                 phase*  (atom nil)]
             (switch/run!
              {:session session :game game :install install
               :from installed-version :to version
               :abort?  (fn [] @switch-cancelled*)
               :on-plan (fn [pl] (reset! plan* pl))
               :on-progress
               (fn [{:keys [phase done] :as ev}]
                 ;; each phase measures its own bytes and restarts at zero, so a
                 ;; phase change resets the tracker rather than reporting a
                 ;; 15 GB drop as a rate
                 (when (not= phase @phase*) (reset! phase* phase) (reset! rate* {}))
                 (let [r (swap! rate* advance-rate done (System/currentTimeMillis))]
                   (when (:emit? r)
                     (fx-run! #(swap! state assoc
                                      :snapshot (switch-snapshot phase ev @plan* r))))))})
             (if @switch-cancelled*
               ;; a cancelled run returns having done part of the work; landing
               ;; on "is ready" would tell the user a switch they stopped had
               ;; finished. The install is simply in another state to hash from.
               (fx-run! #(swap! state assoc :screen :library :snapshot nil))
               (fx-run! #(swap! state assoc
                              :screen :switch
                              ;; those bytes ARE the target now. Left at the old
                              ;; version, the library goes on offering a switch
                              ;; to what is already on disk, and the NEXT switch
                              ;; would build its chunk index from the wrong
                              ;; manifest -- the boundary map has to match what
                              ;; is actually there.
                              :installed-version version
                              ;; ui/done reads :path, and open-install-folder!
                              ;; opens it. Without this the done screen after a
                              ;; switch showed an empty box and the button did
                              ;; nothing -- the install is Steam's folder, not a
                              ;; :folder the user picked here.
                              :path (:path install)
                              :snapshot (assoc (switch-snapshot :applying {} @plan* nil)
                                               :stage :done)))))
           (catch Throwable t
             ;; the same {:category :message} shape `interrupt!` uses, because
             ;; ui/download's interrupted panel destructures it. A bare string
             ;; rendered as "UNKNOWN" with no message -- the failure was
             ;; reported and unreadable, which is barely better than silence.
             (fx-run! #(swap! state assoc
                              :snapshot (assoc (or (:snapshot @state) {})
                                               :switch? true
                                               :stage :failed
                                               :error {:category (or (:reliquary/error (ex-data t)) :io)
                                                       :message (or (not-empty (ex-message t))
                                                                    "the switch failed unexpectedly")}))))
           (finally (deliver p :done))))))
    p))

(defn select-game!
  "Selecting a DIFFERENT game clears the version selection: version ids are
   only unique within a game (`public` exists for all of them), so carrying
   one across would silently arm the download button for a version of a game
   the user is no longer looking at."
  [state appid]
  (swap! state (fn [s]
                 (cond-> (assoc s :selected-appid appid)
                   (not= appid (:selected-appid s)) (assoc :selected-version-id nil))))
  ;; and clear the PREVIOUS game's install immediately rather than waiting for
  ;; the lookup: leaving it up means the panel briefly shows another game's
  ;; folder under this game's versions
  (swap! state assoc :install nil :installed-version nil :hashing nil)
  (detect-install! state appid))

(defn select-version! [state version-id]
  (swap! state assoc :selected-version-id version-id))

(defn choose-folder!
  "The `Change…` button: a real `DirectoryChooser`. Runs on the FX thread
   (it is a window) and persists the choice, so the next launch starts where
   the user left off. Returns the chosen path, or nil if they cancelled."
  [state]
  (let [chooser (DirectoryChooser.)
        current (some-> ^String (:folder @state) io/file)]
    (.setTitle chooser "Choose the install folder")
    (when (and current (.isDirectory ^File current))
      (.setInitialDirectory chooser current))
    (when-let [dir (.showDialog chooser nil)]
      (let [path (.getAbsolutePath ^File dir)]
        (config/save-folder! path)
        (swap! state assoc :folder path)
        path))))

;; ---------------------------------------------------------------------------
;; the download screen

(defn- start-snapshot
  "What the download screen shows between the button press and the engine's
   first real snapshot -- which is several seconds away, since resolving a
   version is a dozen network round trips. `:bytes-total` comes from the
   catalog so the size on screen does not jump once the plan lands."
  [version]
  {:stage :preparing :bytes-done 0 :bytes-total (or (:bytes version) 0)
   :chunks-done 0 :chunks-total 0 :wire-bytes 0
   :bytes-per-sec 0.0 :wire-bytes-per-sec 0.0 :samples [] :error nil})

(defn stop-polling! []
  (when-let [^ScheduledExecutorService s @poller*]
    (.shutdownNow s)
    (reset! poller* nil))
  nil)

(defn start-polling!
  "Push `download/snapshot` into `state` every 250ms, from a DAEMON
   scheduler, via `fx-run!`. 250ms is the engine's own sampling interval, so
   this reads each sample once rather than showing the same numbers twice.

   Every 32nd tick -- eight seconds -- also advances the stage panel's
   screenshot and quote, which is what makes that panel rotate."
  [state ctx]
  (stop-polling!)
  (let [sched (scheduler "reliquary-ui-poll")
        ticks (AtomicLong. 0)]
    (.scheduleAtFixedRate
     sched
     (fn []
       (try
         (let [n    (.incrementAndGet ticks)
               snap (download/snapshot ctx)
               turn (zero? (mod n 32))]
           (fx-run! #(swap! state (fn [s]
                                    (cond-> (assoc s :snapshot snap)
                                      turn (update :shot-index (fnil inc 0))
                                      turn (update :quote-index (fnil inc 0)))))))
         (catch Throwable _ nil)))
     250 250 TimeUnit/MILLISECONDS)
    (reset! poller* sched)
    sched))

(defn interrupt!
  "Put `state` into the download screen's interrupted state for `t`.

   `execute!` guarantees an ExceptionInfo carrying `:reliquary/error`, and
   `resolve-version` raises through the same helper, so the category is
   normally right there in the ex-data; `:io` is the backstop for a
   Throwable that somehow arrives without one. The user sees `ex-message`
   and the category, never a stack trace -- and the last snapshot is kept so
   the panel can still say how much is already on disk."
  [state t]
  (let [snapshot (or (some-> @ctx* download/snapshot) (:snapshot @state) {})
        category (or (:reliquary/error (ex-data t)) :io)]
    (fx-run! #(swap! state assoc
                     :screen :download
                     :snapshot (assoc snapshot
                                      :stage :failed
                                      :error {:category category
                                              :message (or (ex-message t) (str t))})))))

(defn run-download!
  "Resolve `:game`/`:version` and run the engine into `:folder`, on a daemon
   thread, with a poller feeding the screen.

   This is also `Resume download`: a resume is just running the same
   download again -- the engine's progress file is what makes that pick up
   where it stopped rather than starting over, and it is bound to the
   manifests of the build being fetched, so a resume against a moved
   `public` is rejected rather than silently mixed."
  [state]
  (let [{:keys [game version folder]} @state
        dest (io/file folder)]
    ;; a stale ctx from an earlier run must not be what the interrupted
    ;; state reports "kept on disk" from if THIS run fails before it has one
    (reset! ctx* nil)
    (fx-run! #(swap! state assoc
                     :screen :download
                     :path (.getPath dest)
                     :opened? false
                     :error nil
                     :snapshot (start-snapshot version)))
    (daemon!
     "reliquary-download"
     (fn []
       (try
         (let [session (open-session!)
               ctx     (download/make-ctx
                        (assoc (download/resolve-version session game version)
                               :dest dest
                               :appid (:appid game)
                               :version-id (:id version)))]
           (reset! ctx* ctx)
           (start-polling! state ctx)
           (let [snap (download/execute! ctx)]
             (stop-polling!)
             ;; a cancel is not a failure: it goes back to the library with
             ;; everything it fetched still on disk, ready to resume.
             (fx-run! #(swap! state assoc
                              :snapshot snap
                              :screen (if (= :cancelled (:stage snap)) :library :done)))))
         (catch Throwable t
           (stop-polling!)
           (interrupt! state t)))))))

(defn download-pressed!
  "The library's primary button. A folder is required before anything can be
   written, so an unset one opens the chooser rather than failing later with
   a path the user never picked."
  [state]
  (let [s       @state
        game    (selected-game s)
        version (selected-version s)]
    (when (and game version)
      (when-let [folder (or (:folder s) (choose-folder! state))]
        (swap! state assoc :game game :version version :folder folder)
        (run-download! state)))))

(defn cancel-transfer!
  "The Cancel button, for either kind of transfer.

   The screen is shared, so this has to be too. A download is cancelled through
   its context: in-flight chunks finish rather than being torn out, so the screen
   keeps updating for a moment after this returns, and `execute!` then hands back
   a `:cancelled` snapshot. A switch has no context and is stopped by a flag its
   `:abort?` predicate reads, which every phase checks."
  [_state]
  (reset! switch-cancelled* true)
  (when-let [ctx @ctx*] (download/cancel! ctx))
  nil)

(defn back-to-library!
  "`Back to library`, from the done screen and from the interrupted state."
  [state]
  (swap! state assoc :screen :library :snapshot nil :opened? false :error nil))

(defn open-install-folder!
  "The done screen's `Open folder`. `done/open-folder!` shells out to
   `xdg-open` when java.awt.Desktop cannot do it, and waiting on a
   subprocess is not something the FX thread may do -- so this runs on a
   daemon thread and reports back through `fx-run!`. A failure is a gold
   error line, never a crash."
  [state]
  (let [path (:path @state)]
    (daemon! "reliquary-open-folder"
             (fn []
               (let [err (done/open-folder! path)]
                 (fx-run! #(swap! state assoc :opened? (nil? err) :error err)))))))

;; ---------------------------------------------------------------------------
;; view

(defn screen
  "Dispatch on `:screen` -- `:login`, `:library`, `:download`, `:done` -- to
   that screen's own root description, unframed.

   Anything else, including a state with no `:screen` at all, renders the
   login screen: the one screen that is safe to show without a session,
   without a catalog and without a selection.

   Each screen namespace is a pure function that reads only the keys it
   documents, so the whole state map is handed to it as-is rather than being
   picked apart here -- which keeps this dispatch honest and the screens
   independently testable. `:capsule-fn` and `:screenshot-fn` ride in the
   same map (wired in `initial-state`), so no screen ever has to know how
   art gets fetched.

   Separate from `view` so a test can instantiate a real screen through
   `fx/create-component` without instantiating -- and therefore SHOWING -- a
   `:stage`."
  [state]
  (case (:screen state)
    :library  (library/view state)
    :switch   (switch-screen/view state)
    :download (download-screen/view state)
    :done     (done/view state)
    (login/view state)))

(defn view
  "The whole window: the frame from `ui/app`, wrapped around `screen`."
  [state]
  (app/view (assoc state :content (screen state))))

(defn sign-out!
  "The title bar's Sign out button, wired here rather than left nil: a
   signed-in state without this handler crashes the renderer the moment
   `:signed-in?` is true, because cljfx cannot coerce a nil :on-action.
   Forgets the stored token, drops the Steam session (the next logon must
   not reuse the one belonging to the account just signed out of), and drops
   back to a fresh QR login -- and clears the credential fields with it."
  [state]
  (config/forget-token!)
  (close-session!)
  (swap! state assoc
         :screen :login
         :signed-in? false :status-line "not signed in" :error nil
         :challenge-url nil :qr-state :waiting
         ;; the credential half of the screen too: the next user of this
         ;; machine must not find the last one's account name in the field,
         ;; and a password left in the atom outlives its session
         :account nil :password nil :guard-code nil :guard-type nil
         :guard-retry? nil :credential-state nil :confirmation-type nil
         ;; the detected install too: the next user of this machine must not
         ;; find the last one's Steam path on screen
         :install nil :installed-version nil :hashing nil
         :games [] :owned nil :selected-appid nil :selected-version-id nil
         :snapshot nil)
  (start-login! state))

(defn exit-app!
  "Shut the app down. A var so a test can prove the wiring without killing the
   JVM the test is running in.

   `Platform/exit` rather than anything more elaborate: with `setImplicitExit`
   true, this is exactly what closing the OS window used to do, and every thread
   this namespace starts is a daemon precisely so that one call is enough. A
   download in flight dies with them, which is the same outcome the native close
   button always had."
  []
  (Platform/exit))

(defn close-window!
  "The title bar's close button. The window is undecorated now, so this is the
   only way out of the app -- app/view defaults a missing handler to a no-op,
   which would render a close button that looks right and traps the user."
  [_]
  (exit-app!))

(defn usable-token
  "config/token's value, but only when it is not expired as of `now-secs` --
   presence on disk is not the same thing as usability. An expired token used
   to be enough to land -main on a signed-in screen whose QR panel never
   fetches anything and whose only working control is Sign out: a broken
   screen dressed up as a good one. session/expired? is pure and offline (it
   only reads the JWT's own exp claim), so this costs nothing to check before
   ever mounting a window."
  [now-secs]
  (when-let [t (config/token)]
    (when-not (session/expired? (:refresh-token t) now-secs)
      t)))

(defn initial-state
  "The renderer's starting state map, built before `fx/mount-renderer` ever
   runs. Every `:on-*` handler is populated here -- not by a later `swap!` --
   for a concrete reason: app/title-bar renders a Sign out button
   unconditionally whenever `:signed-in?` is true, wired to `:on-action
   on-sign-out`, and cljfx cannot coerce a nil handler. A first render with
   `:signed-in? true` and no `:on-sign-out` crashes the renderer -- this is
   not hypothetical, it is the exact bug main_test.clj's
   `the-initial-state-always-wires-a-sign-out-handler` guards, caught live
   while proving the QR flow against real Steam. Building the whole map in
   one function, called with the not-yet-populated `state` atom already in
   hand, is what makes that wiring a thing a plain unit test can assert on
   without mounting a real Stage. The library's five handlers, the download
   screen's three and the done screen's two are here for the same reason:
   `library/view` defaults a missing handler to a no-op, which renders fine
   and does nothing at all -- a screen that looks right and cannot be used.

   A signed-in start lands on `:library` directly; `-main` then fills
   `:games` and `:owned` in. `:folder` is whatever the user chose last time,
   read back from config."
  [state signed-in]
  {:screen (if signed-in :library :login)
   :status-line (if signed-in (:account signed-in) "not signed in")
   :signed-in? (boolean signed-in)
   :games []
   :owned nil
   :query ""
   :folder (config/folder)
   :capsule-fn capsule-image
   :screenshot-fn screenshot-image
   :on-close          (fn [_]  (close-window! nil))
   :on-sign-out       (fn [_]  (sign-out! state))
   :on-account        (fn [v]  (swap! state assoc :account v))
   :on-password       (fn [v]  (swap! state assoc :password v))
   :on-guard          (fn [v]  (swap! state assoc :guard-code v))
   :on-submit         (fn [_]  (submit-credentials! state))
   :on-query-change   (fn [q]  (swap! state assoc :query q))
   :on-select-game    (fn [id] (select-game! state id))
   :on-select-version (fn [id] (select-version! state id))
   :on-change-folder  (fn [_]  (choose-folder! state))
   :on-download       (fn [_]  (download-pressed! state))
   ;; the Switch button. Hashing and the switch itself land next; wiring the
   ;; handler now keeps library/view from silently defaulting it to a no-op,
   ;; which renders a working-looking button that does nothing.
   ;; opens the screen; it does not start the run. This rewrites a game in
   ;; place, so the screen states what it will do and asks.
   :on-analyze        (fn [_]  (open-switch-screen! state))
   :on-switch         (fn [_]  (switch-install! state))
   :on-cancel         (fn [_]  (cancel-transfer! state))
   ;; the button says "Resume switch" after a switch, and it must mean it:
   ;; run-download! resolves the version and downloads the WHOLE game into
   ;; :folder, so retrying a failed switch that way fetched fifteen gigabytes
   ;; into a folder the user never chose
   :on-retry          (fn [_]  (if (:switch? (:snapshot @state))
                                 (switch-install! state)
                                 (run-download! state)))
   :on-back           (fn [_]  (back-to-library! state))
   :on-open           (fn [_]  (open-install-folder! state))})

(defn -main
  "Open the window and hand back the state atom.

   The return value is the app's whole live surface: `clojure -M:app`
   discards it, but a REPL -- or the headless driver that proved this
   wiring against real Steam -- needs a handle on the running app to watch
   it move and to fire its buttons. Returning it costs nothing and is the
   difference between an app that can be driven and one that can only be
   watched."
  [& _]
  (theme/load-fonts!)
  ;; cljfx sets implicit exit to false when it starts the toolkit, which is
  ;; right for a screenshot harness and wrong for an app: without this, the
  ;; JavaFX Application Thread (non-daemon) survives the window closing and
  ;; the process never exits. Every thread this namespace starts is a daemon
  ;; precisely so that this one call is enough to make the app quit.
  (Platform/setImplicitExit true)
  (let [signed-in (usable-token (quot (System/currentTimeMillis) 1000))
        state (atom nil)]
    (reset! state (initial-state state signed-in))
    (let [renderer (fx/create-renderer :middleware (fx/wrap-map-desc #'view))]
      (fx/mount-renderer state renderer)
      (if signed-in
        (enter-library! state)
        (start-login! state))
      ;; After the screens are up, never before: this is best-effort and must
      ;; never be something the window waits on. enter-library! has already read
      ;; the bundled catalog and any cached one, so a refresh that lands late
      ;; simply replaces what is on screen, and one that never lands changes
      ;; nothing at all.
      (refresh-catalog! state)
      state)))
