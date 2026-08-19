;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns reliquary.main-test
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config]
            [reliquary.download :as download]
            [reliquary.error :as error]
            [reliquary.main :as main]
            [reliquary.session :as session]
            [reliquary.steam.auth :as auth]
            [reliquary.catalog :as catalog]
            [reliquary.steam.installs :as installs]
            [reliquary.ui.art :as art]
            [reliquary.ui.done :as done])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.file Files)
           (java.util Base64)))

(defn- jwt
  "A minimal, unsigned-but-well-formed Steam-shaped JWT carrying only `exp`,
   built the same way steam.auth-test does -- enough for
   reliquary.session/expired? to read, nothing more."
  [exp]
  (let [enc #(.encodeToString (Base64/getUrlEncoder) (.getBytes ^String % "UTF-8"))]
    (str (enc "{}") "." (enc (str "{\"sub\":\"1\",\"exp\":" exp "}")) ".sig")))

;; The first test below does NOT stub config/save-token! -- start-login!
;; really does call it. Without this isolation it would write a literal
;; "SECRET" refresh token over the developer's actual
;; ~/.config/reliquary/config.edn, destroying a real signed-in session for a
;; test assertion. Every test in this namespace runs against a throwaway
;; directory for exactly that reason -- same pattern as config-test's
;; with-tmp.
(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-main-test"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (binding [config/*config-dir* d config/*data-dir* d] (f))
         (finally (run! io/delete-file (reverse (file-seq d)))))))

;; A catalog-shaped game with no art URLs at all: every test in this
;; namespace that reaches the library or the download screen renders through
;; `main/capsule-image` / `main/screenshot-image`, which call
;; `reliquary.ui.art` for real. No URL means no fetch -- a unit test must not
;; pull 600x900 JPEGs off Steam's CDN to prove a screen dispatched.
(def ^:private game
  {:appid 413150 :title "Stardew Valley" :studio "ConcernedApe"
   :art {:capsule nil :screenshots []}
   :quotes [{:text "Ah, the first day of spring." :attrib "Mayor Lewis"}]
   :versions [{:id "public" :label "Latest — public" :branch "public"
               :build "12345" :date "2026-01-01" :bytes 691846347}]})

(def ^:private version (first (:versions game)))

(def ^:private snapshot
  {:stage :downloading :bytes-done 100000000 :bytes-total 691846347
   :chunks-done 40 :chunks-total 300 :wire-bytes 90000000
   :bytes-per-sec 5.0E6 :wire-bytes-per-sec 4.5E6
   :samples [1.0E6 2.0E6 4.5E6] :error nil})

(defn- wired
  "A state atom whose handlers really are the ones `initial-state` wires --
   i.e. built against the very atom they mutate, exactly as `-main` does it."
  [overrides]
  (let [state (atom nil)]
    (reset! state (merge (main/initial-state state nil) overrides))
    state))

;; ---------------------------------------------------------------------------
;; login

(deftest a-qr-event-lands-in-state-on-the-fx-thread
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (fn [on-event & _]
                                        (on-event {:type :qr :challenge-url "https://s.team/q/9"})
                                        {:refresh-token "SECRET" :account "someone"})
                      main/fx-run! (fn [f] (f))   ; identity in tests
                      main/enter-library! (fn [_] nil)]
          @(main/start-login! state)
          (is (= "https://s.team/q/9" (:challenge-url @state))))))))

(deftest the-token-reaches-config-and-never-state
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (constantly {:refresh-token "SECRET" :account "someone"})
                      main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)
                      config/save-token! (fn [t] (is (= "SECRET" (:refresh-token t))) t)]
          @(main/start-login! state)
          (is (not (str/includes? (pr-str @state) "SECRET"))
              "a token in the state atom is a token one render away from the screen"))))))

(deftest a-successful-login-lands-on-the-library-not-a-waypoint-screen
  (testing "the placeholder ui/signed-in screen is gone: signing in must reach
            the real library, which is the whole point of Task E"
    (with-tmp
      (fn []
        (let [state   (atom {})
              landed? (atom false)]
          (with-redefs [auth/login-qr! (constantly {:refresh-token "SECRET" :account "someone"})
                        main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] (reset! landed? true))]
            @(main/start-login! state)
            (is (true? @landed?))
            (is (true? (:signed-in? @state)))))))))

(deftest signing-out-forgets-the-token-and-restarts-login
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token "OLD" :account "someone"})
      (let [state (atom {:signed-in? true :status-line "someone"})
            forgot? (atom false)]
        (with-redefs [auth/login-qr! (fn [on-event & _]
                                        (on-event {:type :qr :challenge-url "https://s.team/q/new"})
                                        {:refresh-token "NEW" :account "someone"})
                      main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)
                      config/forget-token! (fn [] (reset! forgot? true) nil)]
          @(main/sign-out! state)
          (is @forgot? "sign-out must actually forget the stored token")
          (is (= :login (:screen @state)) "sign-out must land back on the login screen")
          (is (= "https://s.team/q/new" (:challenge-url @state))
              "sign-out restarts the login flow rather than leaving a dead screen"))))))

(deftest a-failed-login-becomes-a-rendered-error-not-a-crash
  (with-tmp
    (fn []
      (let [state (atom {})]
        (with-redefs [auth/login-qr! (fn [_ & _] (error/raise :unavailable "steam is down"))
                      main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)]
          @(main/start-login! state)
          (is (str/includes? (str (:error @state)) "steam is down")))))))

(deftest the-initial-state-always-wires-a-sign-out-handler
  (testing "app/title-bar renders a Sign out button unconditionally whenever
            :signed-in? is true, wired to :on-action on-sign-out -- cljfx
            cannot coerce a nil handler, so a missing wiring here is not a
            style nit, it is the exact crash caught live while proving the
            QR flow against real Steam. This asserts the wiring directly,
            without mounting a real Stage, so removing it fails a plain unit
            test instead of only a manual run."
    (is (fn? (:on-sign-out (main/initial-state (atom {}) {:refresh-token "x" :account "a"})))
        "signed-in state must carry a real handler")
    (is (fn? (:on-sign-out (main/initial-state (atom {}) nil)))
        "wired unconditionally -- the atom's shape must not depend on this")))

(deftest every-screen-handler-is-wired-not-defaulted-to-a-no-op
  (testing "library/view, download/view and done/view all default a missing
            :on-* to a no-op, so an unwired handler renders perfectly and
            does nothing at all -- a screen that looks right and cannot be
            used. Only initial-state can catch that, and only by asserting
            every key it must carry."
    (let [s (main/initial-state (atom {}) nil)]
      (doseq [k [:on-close :on-sign-out :on-query-change :on-select-game
                 :on-select-version :on-change-folder :on-download :on-cancel
                 :on-retry :on-back :on-open]]
        (is (fn? (get s k)) (str k " must be a real function")))
      (is (fn? (:capsule-fn s)))
      (is (fn? (:screenshot-fn s))))))

(deftest an-expired-token-is-not-usable
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token (jwt 1) :account "someone"})
      (is (nil? (main/usable-token (quot (System/currentTimeMillis) 1000)))
          "presence on disk is not enough -- an expired token must not produce a signed-in state")
      (is (false? (:signed-in? (main/initial-state (atom {}) (main/usable-token
                                                               (quot (System/currentTimeMillis) 1000)))))
          "an expired token must land on the login screen, not a broken signed-in one"))))

(deftest a-valid-unexpired-token-is-usable
  (with-tmp
    (fn []
      (config/save-token! {:refresh-token (jwt 4102444800) :account "someone"}) ; year 2100
      (is (some? (main/usable-token (quot (System/currentTimeMillis) 1000)))
          "a token that has not yet expired is exactly what \"usable\" means")
      (is (= :library (:screen (main/initial-state (atom {}) (main/usable-token
                                                               (quot (System/currentTimeMillis) 1000)))))
          "a usable token starts on the library, not on a QR code nobody needs to scan"))))

(deftest every-state-mutation-during-start-login-goes-through-fx-run
  (testing "the four other tests above all replace fx-run! with identity, so
            none of them prove marshalling actually happens -- a start-login!
            that mutated `state` directly, bypassing fx-run!, would still
            pass every one of them. This watches the atom itself and records
            whether a change ever landed outside fx-run!'s dynamic extent."
    (with-tmp
      (fn []
        (let [state (atom {})
              in-fx-run? (atom false)
              violations (atom [])]
          (add-watch state ::probe
                     (fn [_ _ _ _]
                       (when-not @in-fx-run?
                         (swap! violations conj @state))))
          (with-redefs [auth/login-qr! (fn [on-event & _]
                                          (on-event {:type :qr :challenge-url "https://s.team/q/9"})
                                          {:refresh-token "SECRET" :account "someone"})
                        main/enter-library! (fn [_] nil)
                        main/fx-run! (fn [f]
                                       (reset! in-fx-run? true)
                                       (try (f) (finally (reset! in-fx-run? false))))]
            @(main/start-login! state))
          (remove-watch state ::probe)
          (is (empty? @violations)
              "every mutation start-login! made to state must have happened inside fx-run!"))))))

;; ---------------------------------------------------------------------------
;; :screen dispatch
;;
;; Reported live before this dispatch existed: "I logged in via the QR code
;; but the app doesn't move on." `view` rendered login/view unconditionally,
;; so a signed-in start showed a BLANK QR card reading "Waiting for approval
;; on your device" -- asking the user to scan a challenge that was never
;; fetched. That branch is now one of four, and all four are pinned here.

(defn- screen-state [screen extra]
  (merge (main/initial-state (atom {}) nil) {:screen screen} extra))

(deftest the-screen-key-selects-the-screen
  (testing ":login"
    (let [s (pr-str (main/view (screen-state :login {:challenge-url "https://s.team/q/1/2"
                                                     :qr-state :waiting})))]
      (is (str/includes? s "Scan to sign in"))))

  (testing ":library"
    (let [s (pr-str (main/view (screen-state :library {:games [game]})))]
      (is (str/includes? s "1 of 1 titles"))
      (is (str/includes? s "Stardew Valley"))
      (is (not (str/includes? s "Scan to sign in"))
          "a signed-in user must never be told to scan a code")))

  (testing ":download"
    (let [s (pr-str (main/view (screen-state :download {:game game :version version
                                                        :snapshot snapshot})))]
      (is (str/includes? s "Time remaining"))
      (is (str/includes? s "Stardew Valley"))
      (is (not (str/includes? s "1 of 1 titles")))))

  (testing ":done"
    (let [s (pr-str (main/view (screen-state :done {:game game :version version
                                                    :path "/tmp/stardew"})))]
      (is (str/includes? s "Stardew Valley is ready"))
      (is (str/includes? s "/tmp/stardew"))))

  (testing "an unknown or absent :screen falls back to the one screen that is
            safe without a session, a catalog or a selection"
    (is (str/includes? (pr-str (main/view (screen-state nil {}))) "Scan to sign in"))
    (is (str/includes? (pr-str (main/view (screen-state :nonsense {}))) "Scan to sign in"))))

(deftest every-screen-instantiates-real-javafx-nodes
  (testing "pr-str only checks description SHAPE and never builds a Node,
            which is exactly what missed a nil handler / bad prop before --
            most recently a VBox layout that silently truncated a title.
            This builds each screen through the same lifecycle the renderer
            uses, with the REAL handlers initial-state wires."
    (doseq [[label state] [[:login    (screen-state :login {:challenge-url "https://s.team/q/1/2"
                                                            :qr-state :waiting})]
                           [:library  (screen-state :library {:games [game]})]
                           [:library-selected (screen-state :library {:games [game]
                                                                      :selected-appid 413150
                                                                      :selected-version-id "public"
                                                                      :folder "/tmp/games"})]
                           [:download (screen-state :download {:game game :version version
                                                               :snapshot snapshot})]
                           [:interrupted (screen-state :download
                                                       {:game game :version version
                                                        :snapshot (assoc snapshot :stage :failed
                                                                         :error {:category :io
                                                                                 :message "disk full"})})]
                           [:done     (screen-state :done {:game game :version version
                                                           :path "/tmp/stardew"})]]]
      (let [component @(fx/on-fx-thread (fx/create-component (main/screen state)))]
        (is (some? (fx/instance component)) (str "failed to instantiate " label))))))

;; ---------------------------------------------------------------------------
;; library wiring

(deftest a-session-failure-still-yields-a-usable-library
  (testing "ownership marking is a courtesy; the authority is Steam's answer
            to the depot-key request during a download. A library greyed out
            end to end because a socket did not open is a worse app than a
            library that simply does not mark anything."
    (with-tmp
      (fn []
        (let [state (wired {})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        art/prefetch! (fn [_] nil)
                        art/capsule (fn [_] nil)
                        session/open! (fn [] (error/raise :unauthenticated "not signed in to steam"))]
            (main/enter-library! state)
            (.join (main/load-ownership! state) 10000)
            (is (= :library (:screen @state)) "a dead session must not keep the user off the library")
            (is (seq (:games @state)) "the catalog is local and does not need a session")
            (is (nil? (:owned @state))
                "nil :owned is library/view's 'treat everything as owned' -- not an empty set")
            (let [s (pr-str (main/view @state))]
              (is (not (str/includes? s "Not owned"))
                  "nothing may be marked unowned when nobody could answer the question"))))))))

(deftest a-live-session-marks-ownership
  (with-tmp
    (fn []
      (let [state (wired {})]
        (with-redefs [main/fx-run! (fn [f] (f))
                      art/prefetch! (fn [_] nil)
                      art/capsule (fn [_] nil)
                      main/open-session! (fn [] {:conn :fake})
                      session/owned-appids (fn [_] #{413150})]
          (main/enter-library! state)
          (.join (main/load-ownership! state) 10000)
          (is (= #{413150} (:owned @state)))
          (is (str/includes? (pr-str (main/view @state)) "Not owned")
              "with a real answer, the games the account does not own are marked"))))))

(deftest selecting-a-different-game-clears-the-version-selection
  (testing "version ids are only unique within a game -- `public` exists for
            all of them -- so carrying one across would arm the download
            button for a version of a game the user is no longer looking at"
    (let [state (wired {:games [game] :selected-appid 1 :selected-version-id "public"})]
      (main/select-game! state 413150)
      (is (nil? (:selected-version-id @state)))
      (main/select-version! state "public")
      (main/select-game! state 413150)
      (is (= "public" (:selected-version-id @state))
          "re-selecting the SAME game must not clear the version under the user"))))

(deftest the-chosen-folder-round-trips-through-config
  (with-tmp
    (fn []
      (is (nil? (config/folder)) "no invented default -- 'No folder selected' is the honest state")
      (config/save-folder! "/tmp/reliquary-games")
      (is (= "/tmp/reliquary-games" (config/folder)))
      (is (= "/tmp/reliquary-games" (:folder (main/initial-state (atom {}) nil)))
          "the next launch starts where the user left off")
      (testing "and it does not disturb the credential living in the same file"
        (config/save-token! {:refresh-token "SECRET" :account "someone"})
        (config/save-folder! "/tmp/elsewhere")
        (is (= "SECRET" (:refresh-token (config/token))))
        (is (= "/tmp/elsewhere" (config/folder)))))))

;; ---------------------------------------------------------------------------
;; download wiring

(deftest pressing-download-runs-the-engine-and-lands-on-done
  (with-tmp
    (fn []
      (let [state    (wired {:games [game] :selected-appid 413150
                             :selected-version-id "public" :folder "/tmp/reliquary-dest"})
            executed (atom nil)]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/open-session! (fn [] {:conn :fake})
                      download/resolve-version (fn [_ g v]
                                                 (is (= 413150 (:appid g)))
                                                 (is (= "public" (:id v)))
                                                 {:plan {} :keys {} :hosts [] :manifests {}})
                      download/execute! (fn [ctx]
                                          (reset! executed ctx)
                                          {:stage :done :bytes-done 691846347
                                           :bytes-total 691846347 :samples []})]
          ;; the REAL button handler, not a hand-rolled call
          (.join ^Thread ((:on-download @state) nil) 10000)
          (is (some? @executed) "the engine actually ran")
          (is (= "/tmp/reliquary-dest" (str (:dest @executed))))
          (is (= 413150 (:appid @executed)))
          (is (= "public" (:version-id @executed)))
          (is (= :done (:screen @state)))
          (is (= "/tmp/reliquary-dest" (:path @state)))
          (is (str/includes? (pr-str (main/view @state)) "Stardew Valley is ready")))))))

(deftest a-download-error-lands-on-the-interrupted-state-with-a-category
  (with-tmp
    (fn []
      (let [state (wired {:game game :version version :folder "/tmp/reliquary-dest"})]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/open-session! (fn [] {:conn :fake})
                      download/resolve-version (fn [_ _ _] {:plan {} :keys {} :hosts [] :manifests {}})
                      download/execute! (fn [_]
                                          (throw (ex-info "connection reset by peer"
                                                          {:reliquary/error :unavailable})))]
          (.join (main/run-download! state) 10000)
          (is (= :download (:screen @state)) "the interrupted state lives on the download screen")
          (is (= :failed (get-in @state [:snapshot :stage])))
          (is (= :unavailable (get-in @state [:snapshot :error :category]))
              "the category comes from the ExceptionInfo's :reliquary/error")
          (is (= "connection reset by peer" (get-in @state [:snapshot :error :message])))
          (let [s (pr-str (main/view @state))]
            (is (str/includes? s "DOWNLOAD INTERRUPTED"))
            (is (str/includes? s "UNAVAILABLE"))
            (is (str/includes? s "connection reset by peer"))
            (is (str/includes? s "Resume download"))
            (is (not (str/includes? s "clojure.lang"))
                "ex-message and a category, never a stack trace")))))))

(deftest an-uncategorized-failure-still-produces-a-category
  (testing "execute! guarantees a :reliquary/error, but resolve-version runs
            first and a bug anywhere in this chain must still reach the user
            as a screen rather than a dead window"
    (with-tmp
      (fn []
        (let [state (wired {:game game :version version :folder "/tmp/reliquary-dest"})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/open-session! (fn [] (throw (RuntimeException. "socket closed")))]
            (.join (main/run-download! state) 10000)
            (is (= :io (get-in @state [:snapshot :error :category])))
            (is (= "socket closed" (get-in @state [:snapshot :error :message])))))))))

(deftest cancel-reaches-the-engine-and-returns-to-the-library
  (with-tmp
    (fn []
      (let [state     (wired {:game game :version version :folder "/tmp/reliquary-dest"})
            cancelled (atom nil)]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/open-session! (fn [] {:conn :fake})
                      download/resolve-version (fn [_ _ _] {:plan {} :keys {} :hosts [] :manifests {}})
                      download/cancel! (fn [ctx] (reset! cancelled ctx) nil)
                      download/execute! (fn [_]
                                          ;; the user presses Cancel mid-run
                                          ((:on-cancel @state) nil)
                                          {:stage :cancelled :bytes-done 5 :bytes-total 10 :samples []})]
          (.join (main/run-download! state) 10000)
          (is (some? @cancelled) "the Cancel button must reach download/cancel! with the LIVE ctx")
          (is (= :library (:screen @state))
              "a cancel is not a failure: back to the library, with what it fetched still on disk"))))))

(deftest back-to-library-clears-the-finished-download
  (let [state (wired {:screen :done :snapshot snapshot :opened? true :error "boom"})]
    (main/back-to-library! state)
    (is (= :library (:screen @state)))
    (is (nil? (:snapshot @state)))
    (is (false? (:opened? @state)))
    (is (nil? (:error @state)))))

(deftest opening-the-install-folder-reports-a-failure-as-a-gold-line-not-a-crash
  (let [state (wired {:screen :done :game game :version version :path "/tmp/nope"})]
    (with-redefs [main/fx-run! (fn [f] (f))]
      (testing "success"
        (with-redefs [done/desktop-open! (constantly true)]
          (.join (main/open-install-folder! state) 10000)
          (is (true? (:opened? @state)))
          (is (nil? (:error @state)))
          (is (str/includes? (pr-str (main/view @state)) "Opened in your file browser."))))
      (testing "failure -- neither java.awt.Desktop nor xdg-open available"
        (with-redefs [done/desktop-open! (constantly false)
                      done/xdg-open! (constantly false)]
          (.join (main/open-install-folder! state) 10000)
          (is (false? (:opened? @state)))
          (is (str/includes? (:error @state) "/tmp/nope"))
          (is (str/includes? (pr-str (main/view @state)) "no file browser is available")))))))

;; ---------------------------------------------------------------------------
;; credential login
;;
;; login/credential-panel has rendered an account field, a password field, a
;; Guard-code field and a Sign in button since the screen was built, and
;; auth/login-credentials! has been implemented and unit-tested for just as
;; long. Nothing connected them: initial-state supplied no :on-account,
;; :on-password, :on-guard or :on-submit, and the panel defaults a missing
;; handler to (fn [_]). Every control on that half of the screen rendered
;; perfectly and did nothing at all.

(defn- await-state
  "Block until `pred` holds of the state, or give up after three seconds. The
   login runs on its own thread, so the Guard prompt reaches the atom
   asynchronously -- a test that reads it straight after the button press is
   racing it."
  [state pred]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (cond (pred @state) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

(deftest the-credential-handlers-are-wired-not-defaulted-to-a-no-op
  (let [s (main/initial-state (atom {}) nil)]
    (doseq [k [:on-account :on-password :on-guard :on-submit]]
      (is (fn? (get s k)) (str k " must be a real function")))))

(deftest the-typed-fields-land-in-state
  (let [state (wired {})]
    ((:on-account @state) "someone")
    ((:on-password @state) "hunter2")
    ((:on-guard @state) "12345")
    (is (= "someone" (:account @state)))
    (is (= "hunter2" (:password @state)))
    (is (= "12345" (:guard-code @state)))))

(deftest pressing-sign-in-hands-the-typed-credentials-to-steam
  (with-tmp
    (fn []
      (let [state (wired {:account "someone" :password "hunter2"})
            sent (atom nil)]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)
                      auth/login-credentials! (fn [u pw _ & _]
                                                (reset! sent [u pw])
                                                {:refresh-token "SECRET" :account "someone"})]
          @((:on-submit @state) nil)
          (is (= ["someone" "hunter2"] @sent)))))))

(deftest the-password-leaves-state-as-soon-as-the-login-thread-has-it
  (testing "the same rule the refresh token follows: a secret in the state atom
            is one careless render away from being on screen, and every test
            and log that pr-strs the state prints it"
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "hunter2"})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  {:refresh-token "SECRET" :account "someone"})]
            @((:on-submit @state) nil)
            (is (not (seq (:password @state)))
                "the password must be gone from the atom, not merely unrendered")
            (is (not (str/includes? (pr-str @state) "hunter2")))))))))

(deftest a-credential-login-saves-its-token-and-lands-on-the-library
  (testing "save-token! is stubbed rather than left to write for real: the login
            runs on a raw Thread, and a `binding` of config/*config-dir* does
            not cross one -- see reliquary.config/prop-dir-or-die"
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "pw"})
              saved (atom nil)
              landed? (atom false)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] (reset! landed? true))
                        config/save-token! (fn [t] (reset! saved t) t)
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  {:refresh-token "SECRET" :account "someone"})]
            @((:on-submit @state) nil)
            (is (= "SECRET" (:refresh-token @saved)) "the token must reach config")
            (is (not (str/includes? (pr-str @state) "SECRET"))
                "the token must never enter the state atom")
            (is (true? (:signed-in? @state)))
            (is @landed?)))))))

(deftest a-typed-guard-code-reaches-the-blocked-login-thread
  (testing "auth/login-credentials! wants the code as a synchronous RETURN from
            its event callback, on a thread that is not the FX thread; the UI
            collects it from a field whenever the user gets round to it. The
            handoff is what makes the two shapes meet."
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "pw"})
              got (promise)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-credentials!
                        (fn [_ _ on-event & _]
                          (deliver got (on-event {:type :guard-needed :code-type 3}))
                          {:refresh-token "SECRET" :account "someone"})]
            (let [p ((:on-submit @state) nil)]
              (is (await-state state #(= 3 (:guard-type %)))
                  "the prompt must reach the screen, or there is no field to type into")
              (is (not (:password @state)) "the password is spent by now")
              ((:on-guard @state) "12345")
              ((:on-submit @state) nil)
              (is (= "12345" (deref got 3000 :timed-out)))
              @p)))))))

(deftest a-refused-guard-code-is-shown-as-refused
  (with-tmp
    (fn []
      (let [state (wired {:account "someone" :password "pw"})]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)
                      auth/login-credentials!
                      (fn [_ _ on-event & _]
                        (on-event {:type :guard-needed :code-type 3})
                        (on-event {:type :guard-needed :code-type 3 :retry? true})
                        {:refresh-token "SECRET" :account "someone"})]
          (let [p ((:on-submit @state) nil)]
            (is (await-state state #(= 3 (:guard-type %))))
            ((:on-guard @state) "WRONG")
            ((:on-submit @state) nil)
            (is (await-state state :guard-retry?)
                "a refusal must be visible; the field otherwise just clears")
            (is (not (seq (:guard-code @state)))
                "the refused code must not sit in the field pretending to be new")
            ((:on-guard @state) "RIGHT")
            ((:on-submit @state) nil)
            @p))))))

(deftest a-pending-device-confirmation-becomes-a-rendered-state
  (with-tmp
    (fn []
      (let [state (wired {:account "someone" :password "pw"})
            release (promise)]
        (with-redefs [main/fx-run! (fn [f] (f))
                      main/enter-library! (fn [_] nil)
                      config/save-token! identity
                      auth/login-credentials!
                      (fn [_ _ on-event & _]
                        (on-event {:type :confirmation-pending :confirmation-type 4})
                        ;; stand where the real poll loop stands: waiting on a
                        ;; human with a phone. Success clears this state, and
                        ;; should -- so asserting after the login returns would
                        ;; prove nothing.
                        (deref release 3000 nil)
                        {:refresh-token "SECRET" :account "someone"})]
          (let [p ((:on-submit @state) nil)]
            (is (await-state state #(= :confirmation-pending (:credential-state %)))
                "type 4 needs a phone, and the screen has to say so")
            (deliver release true)
            @p))))))

(deftest a-failed-credential-login-leaves-the-button-pressable-again
  (testing "an error that left :credential-state :submitting would disable the
            Sign in button for good -- a dead screen whose only working control
            is Sign out"
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "pw"})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  (error/raise :incorrect "bad password"))]
            @((:on-submit @state) nil)
            (is (str/includes? (str (:error @state)) "bad password"))
            (is (nil? (:credential-state @state)))))))))

(deftest starting-a-credential-login-abandons-the-qr-poll
  (testing "the QR poll otherwise runs for the life of the process, hitting
            Steam's auth API every few seconds behind a library screen"
    (with-tmp
      (fn []
        (let [state (wired {})
              abort-fn (promise)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-qr! (fn [_ opts]
                                         (deliver abort-fn (:abort? opts))
                                         ;; the real poll loop, minus the network
                                         (loop []
                                           (if ((:abort? opts))
                                             nil
                                             (do (Thread/sleep 5) (recur)))))
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  {:refresh-token "SECRET" :account "someone"})]
            (let [qr (main/start-login! state)
                  abort? (deref abort-fn 3000 nil)]
              (is (fn? abort?) "the QR login must be handed a way to be abandoned")
              (is (not (abort?)) "nothing supersedes it while it is the only login")
              (swap! state assoc :account "someone" :password "pw")
              @((:on-submit @state) nil)
              (is (abort?) "the credential login supersedes the QR poll")
              (is (= :done (deref qr 3000 :timed-out))
                  "and the QR thread actually finishes rather than looping on"))))))))

(deftest a-superseded-login-cannot-sign-the-user-in
  (testing "a QR approval that lands after a credential login already succeeded
            must not save its token over the one the user actually asked for.
            The abort check happens before each poll, so this window is small --
            and it is exactly one poll wide, which is not the same as closed."
    (with-tmp
      (fn []
        (let [state (wired {:account "chosen" :password "pw"})
              credential-done (promise)
              saved (atom [])]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-qr! (fn [_ _]
                                         ;; approved, but only after the
                                         ;; credential login has already landed
                                         (deref credential-done 3000 nil)
                                         {:refresh-token "STALE" :account "stale"})
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  {:refresh-token "CHOSEN" :account "chosen"})
                        config/save-token! (fn [t] (swap! saved conj (:account t)) t)]
            (let [qr (main/start-login! state)]
              @((:on-submit @state) nil)
              (deliver credential-done true)
              (is (= :done (deref qr 3000 :timed-out)))
              (is (= ["chosen"] @saved)
                  "the stale QR token must never be saved at all")
              (is (= "chosen" (:status-line @state))))))))))

(deftest signing-out-clears-the-credential-fields
  (testing "the next user of this machine must not find the last one's account
            name sitting in the field, and a password left in the atom outlives
            the session it belonged to"
    (with-tmp
      (fn []
        (let [state (wired {:signed-in? true
                            :account "someone" :password "hunter2"
                            :guard-code "12345" :guard-type 3 :guard-retry? true
                            :credential-state :submitting})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        config/forget-token! (fn [] nil)
                        auth/login-qr! (fn [_ & _] nil)]
            @(main/sign-out! state)
            (doseq [k [:account :password :guard-code :guard-type :guard-retry?
                       :credential-state]]
              (is (not (get @state k)) (str k " must not survive a sign-out")))))))))

(deftest a-failure-after-a-guard-prompt-does-not-dead-end-the-screen
  (testing "walk the whole sequence: prompt, typed code, then a refusal that
            retyping cannot fix (eresult 84 is a rate limit). The error path
            cleared :credential-state but left :guard-type set, so the screen
            still showed a Guard field with no login thread behind it -- and
            pressing Sign in there delivered a code to a dead promise, set
            :submitting, and disabled the button for the rest of the run. A
            login screen whose only working control is Sign out."
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "pw"})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        config/save-token! identity
                        auth/login-credentials!
                        (fn [_ _ on-event & _]
                          (on-event {:type :guard-needed :code-type 3})
                          (error/raise :incorrect "too many attempts"))]
            (let [p ((:on-submit @state) nil)]
              (is (await-state state #(= 3 (:guard-type %))))
              ((:on-guard @state) "12345")
              ((:on-submit @state) nil)
              @p
              (is (str/includes? (str (:error @state)) "too many attempts"))
              (is (nil? (:guard-type @state))
                  "no login is waiting for a code, so no code field may be shown")
              (is (not= :submitting (:credential-state @state))
                  "the Sign in button must be pressable again")
              (is (not (seq (:guard-code @state)))
                  "and no stale code may sit in a field that is no longer shown")
              ;; the promise the dead login was parked on must be released too,
              ;; or the next login's `begin-login-epoch!` is what finally frees
              ;; that thread -- one leaked thread per failed login until then
              (is (nil? @@#'main/guard-code*)))))))))

(deftest pressing-sign-in-with-no-login-waiting-for-a-code-is-recoverable
  (testing "the guard branch used to key on :guard-type -- a state key -- while
            the promise is what actually decides whether a thread is waiting for
            a code. If the two ever disagree, `when-let` delivered to nothing,
            :submitting stayed set, and the button was dead with no login behind
            it. Keying on the promise makes that disagreement unrepresentable;
            this pins the recovery so a future edit cannot reintroduce a silent
            dead end."
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :guard-type 3 :guard-code "12345"})]
          (with-redefs [main/fx-run! (fn [f] (f))]
            ;; no login thread, so nothing is parked on a promise
            (is (nil? @@#'main/guard-code*))
            ((:on-submit @state) nil)
            (is (not= :submitting (:credential-state @state))
                "a submit with nothing to submit to must not disable the button")
            (is (nil? (:guard-type @state))
                "and must not keep showing a code field nothing is waiting for")
            (is (seq (:error @state))
                "the user needs telling why the code went nowhere")))))))

(deftest a-superseded-login-cannot-overwrite-the-live-challenge-url
  (testing "the epoch gated POLLING and the two state writes at the END of a
            login, but not the events fired mid-flight. So an abandoned QR
            thread sitting in a poll could still push a rotated challenge into
            state after a newer login had already put its own there -- leaving
            the screen showing a QR belonging to a session nobody polls. Scanning
            it approves on the phone and the app never moves: exactly the failure
            `the-screen-key-selects-the-screen` was written for."
    (with-tmp
      (fn []
        (let [state (wired {})
              calls (atom 0)
              ;; `started` is what makes this deterministic. Each login runs on
              ;; its own daemon thread, so `(swap! calls inc)` happens THERE --
              ;; whichever thread the scheduler runs first claims number 1. This
              ;; test failed on CI and passed locally for exactly that reason:
              ;; the second login won the swap, so the CURRENT login was the one
              ;; that fired STALE-URL and the assertion caught the test's race
              ;; rather than the behaviour. Waiting for the first thread to be
              ;; inside the stub before spawning the second removes the
              ;; ambiguity entirely -- only one thread exists at that point.
              started (promise)
              release-stale (promise)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-qr!
                        (fn [on-event _]
                          (if (= 1 (swap! calls inc))
                            ;; the soon-to-be-superseded login: parked in a poll
                            (do (deliver started true)
                                (deref release-stale 5000 nil)
                                (on-event {:type :qr :challenge-url "STALE-URL"})
                                nil)
                            (do (on-event {:type :qr :challenge-url "FRESH-URL"})
                                nil)))]
            (let [stale (main/start-login! state)]
              (is (true? (deref started 5000 nil))
                  "the first login must reach the stub before a second is started")
              (let [fresh (main/start-login! state)]
                @fresh
                (is (= "FRESH-URL" (:challenge-url @state)))
                (deliver release-stale true)
                @stale
                (is (= "FRESH-URL" (:challenge-url @state))
                    "the abandoned login must not repaint the QR the user is looking at")))))))))

(deftest a-failed-credential-login-leaves-a-scannable-qr-not-a-dead-one
  (testing "starting a credential login aborts the QR poll for good, and nothing
            restarts it -- sign-out's button is the only caller and it does not
            render on the login screen. But :challenge-url and :qr-state stayed
            in state, so the card kept animating under 'Waiting for approval on
            your device'. Mistype a password, decide to use the phone after all,
            scan: nothing happens, ever, because no thread is polling that
            challenge."
    (with-tmp
      (fn []
        (let [state (wired {})
              qr-starts (atom 0)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        ;; the URL comes from this call's OWN counter value,
                        ;; not from re-reading the atom: two live logins would
                        ;; otherwise be able to report the same URL
                        auth/login-qr! (fn [on-event _]
                                         (let [n (swap! qr-starts inc)]
                                           (on-event {:type :qr :challenge-url (str "URL-" n)})
                                           nil))
                        auth/login-credentials! (fn [_ _ _ & _]
                                                  (error/raise :incorrect "bad password"))]
            @(main/start-login! state)
            (is (= "URL-1" (:challenge-url @state)))
            (swap! state assoc :account "someone" :password "wrong")
            @((:on-submit @state) nil)
            (is (str/includes? (str (:error @state)) "bad password"))
            ;; `on-failure` restarts the QR login, which spawns ANOTHER daemon
            ;; thread -- so awaiting the credential login's promise says nothing
            ;; about whether that thread has reached the stub yet. Reading the
            ;; atom straight afterwards is a race, and CI lost it where this
            ;; machine won it. Wait for the observable result instead.
            (is (await-state state #(= "URL-2" (:challenge-url %)))
                "the QR half must be polling again, or the card on screen is a lie")
            (is (= 2 @qr-starts)
                "and exactly one restart, not a storm of them")))))))

(deftest a-login-thread-cannot-die-silently-and-strand-the-button
  (testing "run-login! caught only ExceptionInfo. Anything else on that thread --
            an IOException out of save-token!, an NPE, a decode failure slipping
            past decode-response -- escaped, leaving :credential-state
            :submitting with no :error. The button then read 'Signing in…' and
            was disabled for the rest of the run, with nothing on screen saying
            why: the exact dead-screen shape `login-finished` exists to prevent."
    (with-tmp
      (fn []
        (let [state (wired {:account "someone" :password "pw"})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        auth/login-qr! (fn [_ _] nil)
                        auth/login-credentials!
                        (fn [_ _ _ & _] (throw (java.io.IOException. "disk went away")))]
            @((:on-submit @state) nil)
            (is (not= :submitting (:credential-state @state))
                "the button must not be stranded by a non-ExceptionInfo throw")
            (is (seq (:error @state)) "and the screen must say something")))))))

(deftest the-initial-state-wires-the-window-close-handler
  (testing "the window is undecorated now, so app/title-bar's close button is the
            ONLY way to shut the app. app/view defaults a missing :on-close to a
            no-op, which renders a button that looks fine and traps the user."
    (is (fn? (:on-close (main/initial-state (atom {}) nil))))
    (is (fn? (:on-close (main/initial-state (atom {}) {:refresh-token "x" :account "a"}))))))

(deftest closing-the-window-shuts-the-app-down
  (testing "and does it through main/exit-app!, so the test does not have to
            actually kill the JVM to prove the wiring"
    (let [exited? (atom false)]
      (with-redefs [main/exit-app! (fn [] (reset! exited? true))]
        ((:on-close (main/initial-state (atom {}) nil)) nil))
      (is @exited?))))

;; ---------------------------------------------------------------------------
;; catalog refresh
;;
;; catalog/refresh! was written, tested and never called: the app only ever read
;; the bundled copy and whatever a previous run had cached. Now that the repo is
;; public, the raw endpoint on main is the distribution channel, so startup asks
;; it for a newer catalog.

(def ^:private newer-catalog
  {:schema-version 1
   :generated "2099-01-01T00:00:00Z"
   :games [(assoc game :appid 999 :title "A Game That Only Exists Upstream")]})

(deftest a-refresh-fetches-the-repos-catalog-url
  (let [asked (atom nil)]
    (with-redefs [catalog/refresh! (fn [url _] (reset! asked url) nil)]
      (main/refresh-catalog! (atom {})))
    (is (= catalog/catalog-url @asked)
        "it must ask the repo, not some other URL assembled locally")))

(deftest a-newer-catalog-replaces-the-games-on-screen
  (testing "the point of the exercise: a catalog pushed to the repo takes effect
            without the user reinstalling anything"
    (let [state (atom {:screen :library :games [game]})]
      (with-redefs [main/fx-run! (fn [f] (f))
                    art/prefetch! (fn [_] nil)
                    ;; stand in for the network: hand on-done a fresher catalog
                    catalog/refresh! (fn [_ on-done] (on-done newer-catalog) nil)]
        (main/refresh-catalog! state))
      (is (= ["A Game That Only Exists Upstream"] (mapv :title (:games @state)))
          "the fresh catalog's games must replace what was listed"))))

(deftest a-refreshed-catalog-lands-on-the-fx-thread
  (testing "on-done runs on catalog/refresh!'s own daemon thread, so touching
            the state atom from there without marshalling is the one rule this
            namespace's docstring says must never be broken"
    (let [state (atom {:games [game]})
          in-fx? (atom false)
          violations (atom 0)]
      (add-watch state ::probe (fn [_ _ _ _] (when-not @in-fx? (swap! violations inc))))
      (with-redefs [art/prefetch! (fn [_] nil)
                    catalog/refresh! (fn [_ on-done] (on-done newer-catalog) nil)
                    main/fx-run! (fn [f] (reset! in-fx? true)
                                   (try (f) (finally (reset! in-fx? false))))]
        (main/refresh-catalog! state))
      (remove-watch state ::probe)
      (is (zero? @violations)))))

(deftest a-failed-refresh-changes-nothing-and-says-nothing
  (testing "silent by request, and catalog/refresh! already swallows every
            failure -- so on-done simply never fires and the screen keeps
            whatever it had. No :error, no empty grid."
    (let [state (atom {:screen :library :games [game] :error nil})]
      (with-redefs [main/fx-run! (fn [f] (f))
                    catalog/refresh! (fn [_ _] nil)]   ; the fetch failed
        (main/refresh-catalog! state))
      (is (= [game] (:games @state)) "the games on screen must be untouched")
      (is (nil? (:error @state)) "and a failed refresh is not an error the user sees"))))

(deftest a-refresh-prefetches-art-for-games-it-has-just-learned-about
  (testing "enter-library! prefetches art for the catalog it read at the time.
            A game that arrives later has none on disk, so without this it
            renders the hatch placeholder for the rest of the session."
    ;; The wait sits INSIDE with-redefs on purpose. The prefetch runs on its own
    ;; daemon thread, and with-redefs restores the real var when its body exits
    ;; -- so asserting outside raced the thread and called the REAL art/prefetch!,
    ;; which is both a failed test and a namespace reaching for the network.
    (let [prefetched (promise)]
      (with-redefs [main/fx-run! (fn [f] (f))
                    art/prefetch! (fn [g] (deliver prefetched (:appid g)))
                    catalog/refresh! (fn [_ on-done] (on-done newer-catalog) nil)]
        (main/refresh-catalog! (atom {:games [game]}))
        (is (= 999 (deref prefetched 3000 :timed-out)))))))

(deftest a-newer-catalog-on-the-wire-reaches-the-library
  (testing "The whole chain with nothing stubbed but the URL: a real HTTP server,
            the real catalog/refresh! (its own daemon thread, size cap, parser and
            freshness guard), and the real main/refresh-catalog! on-done. The
            tests above each stub one seam; this is the one that would notice if
            the seams did not meet.

            The URL has to be stubbable for this to be possible at all -- see
            catalog/catalog-url's docstring on why it is not ^:const.

            refresh! writes its cache through config/data-dir, which reads a JVM
            property rather than any binding, so the write lands in the shared
            target/test-state/data and is cleaned up here -- a future-dated
            catalog left there shadows the bundled one for every later test."
    (let [body   (-> (slurp (io/resource "catalog.edn"))
                     (.replace "2026-08-17T18:37:21Z" "2099-12-31T00:00:00Z")
                     (.replace "Stardew Valley" "Stardew Valley (FROM THE WIRE)"))
          bytes  (.getBytes ^String body "UTF-8")
          server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                   (.createContext "/catalog.edn"
                     (reify HttpHandler
                       (handle [_ ex]
                         (.sendResponseHeaders ^HttpExchange ex 200 (alength bytes))
                         (with-open [os (.getResponseBody ^HttpExchange ex)]
                           (.write os bytes)))))
                   (.start))
          url    (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/catalog.edn")
          cache  (io/file (config/data-dir) "catalog.edn")
          state  (atom {:screen :library :games []})
          landed (promise)]
      (io/delete-file cache true)
      (try
        (with-redefs [catalog/catalog-url url
                      art/prefetch! (fn [_] nil)
                      main/fx-run! (fn [f] (f))]
          (add-watch state ::probe
                     (fn [_ _ _ new-state]
                       (when (seq (:games new-state)) (deliver landed true))))
          (main/refresh-catalog! state)
          (is (true? (deref landed 8000 nil)) "the fetched catalog must reach state")
          (is (some #{"Stardew Valley (FROM THE WIRE)"} (map :title (:games @state)))
              "and it must be the document the server served, not the bundled one"))
        (finally
          (remove-watch state ::probe)
          (.stop server 0)
          (io/delete-file cache true))))))

;; ---------------------------------------------------------------------------
;; detecting an install when a game is selected
;;
;; The switch-mode panel is inert without this: it renders whatever :install and
;; :installed-version say, and nothing was setting them.

(def ^:private an-install
  {:appid 413150 :name "Stardew Valley" :build "16826371" :bytes 100
   :path "/steam/common/Stardew Valley"
   :manifests {1 "aaaa"}})

(deftest selecting-a-game-looks-for-an-existing-install
  (with-tmp
    (fn []
      (let [state (wired {:games [game]})
            asked (atom nil)]
        (with-redefs [main/fx-run! (fn [f] (f))
                      installs/find-install (fn [appid] (reset! asked appid) an-install)
                      installs/installed-version (fn [_ _] nil)]
          ;; select-game! returns detect-install!'s promise: the lookup is on a
          ;; daemon thread, so reading state without awaiting it races the thread
          @(main/select-game! state 413150)
          (is (= 413150 @asked) "it must look for THIS game")
          (is (= an-install (:install @state))))))))

(deftest a-game-with-no-install-clears-the-switch-state
  (testing "selecting an uninstalled game after an installed one must not leave
            the previous game's path on screen -- that is someone else's folder
            with a Switch button under it"
    (with-tmp
      (fn []
        (let [state (wired {:games [game] :install an-install
                            :installed-version {:id "public"}})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        installs/find-install (constantly nil)]
            (main/select-game! state 413150)
            (is (nil? (:install @state)))
            (is (nil? (:installed-version @state)))))))))

(deftest the-installed-version-is-resolved-from-the-catalog
  (with-tmp
    (fn []
      (let [state (wired {:games [game]})]
        (with-redefs [main/fx-run! (fn [f] (f))
                      installs/find-install (constantly an-install)
                      installs/installed-version (fn [g i]
                                                   (is (= 413150 (:appid g)))
                                                   (is (= an-install i))
                                                   {:id "public" :label "Latest"})]
          @(main/select-game! state 413150)
          (is (= "Latest" (:label (:installed-version @state)))))))))

(deftest install-detection-never-blocks-the-fx-thread
  (testing "find-install walks every Steam library and stats every manifest. On a
            spinning disk with several libraries that is not instant, and
            select-game! is a click handler."
    (with-tmp
      (fn []
        (let [state (wired {:games [game]})
              on-fx (atom nil)]
          (with-redefs [main/fx-run! (fn [f] (f))
                        installs/find-install (fn [_]
                                                (reset! on-fx (Thread/currentThread))
                                                an-install)
                        installs/installed-version (constantly nil)]
            @(main/detect-install! state 413150)
            (is (not= (Thread/currentThread) @on-fx)
                "the lookup must happen off the calling thread")))))))

(deftest a-stale-detection-does-not-overwrite-a-newer-selection
  (testing "click Skyrim, then click Stardew before the first lookup returns --
            the slow answer must not paint Skyrim's path under Stardew's versions"
    (with-tmp
      (fn []
        (let [state (wired {:games [game] :selected-appid 999})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        installs/find-install (constantly an-install)
                        installs/installed-version (constantly nil)]
            ;; the detection is for 413150 while the user is now on 999
            @(main/detect-install! state 413150)
            (is (nil? (:install @state))
                "an answer about a game that is no longer selected is dropped")))))))

(deftest the-analyze-handler-is-wired-like-every-other
  (testing "library/view defaults a missing :on-analyze to a no-op, so an unwired
            Switch button renders perfectly and does nothing -- the same trap
            every other handler in initial-state exists to avoid"
    (is (fn? (:on-analyze (main/initial-state (atom {}) nil))))))

(deftest signing-out-clears-the-detected-install
  (testing "the next user of this machine must not find the last one's Steam path
            on screen"
    (with-tmp
      (fn []
        (let [state (wired {:signed-in? true :install an-install
                            :installed-version {:id "public"}
                            :hashing {:done 1 :total 2}})]
          (with-redefs [main/fx-run! (fn [f] (f))
                        main/enter-library! (fn [_] nil)
                        config/forget-token! (fn [] nil)
                        auth/login-qr! (fn [_ & _] nil)]
            @(main/sign-out! state)
            (doseq [k [:install :installed-version :hashing]]
              (is (nil? (get @state k)) (str k " must not survive a sign-out")))))))))
