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
            [reliquary.ui.art :as art]
            [reliquary.ui.done :as done])
  (:import (java.nio.file Files)
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
        (with-redefs [auth/login-qr! (fn [on-event]
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
        (with-redefs [auth/login-qr! (fn [on-event]
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
        (with-redefs [auth/login-qr! (fn [_] (error/raise :unavailable "steam is down"))
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
      (doseq [k [:on-sign-out :on-query-change :on-select-game :on-select-version
                 :on-change-folder :on-download :on-cancel :on-retry :on-back :on-open]]
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
          (with-redefs [auth/login-qr! (fn [on-event]
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
