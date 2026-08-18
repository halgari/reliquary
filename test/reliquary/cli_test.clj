(ns reliquary.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reliquary.cli :as cli]
            [reliquary.config :as config]
            [reliquary.steam.auth :as auth]))

(defn- with-hermetic-config
  "Run `f` with both of reliquary.config's dynamic directories pointed at a
   fresh temp dir, so a test that reaches reliquary.catalog/load! (which
   consults reliquary.config/data-dir for a cached catalog) never reads real
   machine state. catalog/load! is read-only, so the real token was never at
   risk -- this is purely about keeping the test hermetic."
  [f]
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "reliquary-cli-hermetic" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [config/*config-dir* d
                config/*data-dir*   d]
        (f))
      (finally (run! #(io/delete-file % true) (reverse (file-seq d)))))))

(deftest exit-codes-follow-the-error-contract
  (is (= 1 (cli/exit-code-for (ex-info "x" {:reliquary/error :incorrect}))))
  (is (= 2 (cli/exit-code-for (ex-info "x" {:reliquary/error :unavailable}))))
  (is (= 3 (cli/exit-code-for (ex-info "x" {:reliquary/error :io}))))
  (is (= 4 (cli/exit-code-for (ex-info "x" {:reliquary/error :unauthenticated}))))
  (is (= 1 (cli/exit-code-for (ex-info "x" {})))))

(deftest usage-is-shown-for-no-command-and-exits-zero
  (let [out (with-out-str (is (= 0 (cli/run []))))]
    (is (str/includes? out "usage: reliquary"))))

(deftest an-unknown-command-exits-nonzero
  (with-out-str (is (= 1 (cli/run ["nonsense"])))))

(deftest list-renders-the-real-bundled-catalog
  (let [out (with-out-str (is (= 0 (cli/run ["list"]))))]
    (is (str/includes? out "Cyberpunk 2077"))
    (is (str/includes? out "1_63_legacy_patch") "the historical branches must be offered")
    (is (str/includes? out "1_5_97") "Skyrim SE's community downgrade target")))

(deftest sizes-render-honestly
  (testing "no real size may round down to 0.0 GB and read as unknown"
    ;; substring matching is wrong here -- "110.0 GB" contains "0.0 GB".
    ;; Anchor on the whole size field instead.
    (let [out (with-out-str (cli/run ["list"]))]
      (is (not-any? #(re-find #"(?:^|\s)0\.0 GB" %) (str/split-lines out)))))

  (testing "an unknown size still says so -- the rule outlives the data"
    ;; Every catalog version now has a real size: the ones community downgrade
    ;; guides never published were resolved once against Steam's own manifests
    ;; by tool/catalog/resolve_sizes.clj. So this asserts the FORMATTER's rule
    ;; directly rather than hunting the catalog for a zero that no longer
    ;; exists -- a version added tomorrow without a size must still render
    ;; "size unknown" and never "0.0 GB".
    (is (= "size unknown" (#'cli/gb 0)))
    (is (= "size unknown" (#'cli/gb nil)))
    (is (= "12.7 GB" (#'cli/gb 13628807699)))))

(deftest download-requires-a-known-appid-and-version
  (with-hermetic-config
    (fn []
      (testing "a typo must not reach Steam"
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/parse ["download" "999999" "public" "/tmp/x"]))
            "an unknown appid")
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/parse ["download" "1091500" "nonsense-branch" "/tmp/x"]))
            "an unknown version-id for a real game")))))

(deftest download-parse-error-lists-the-valid-version-ids
  (with-hermetic-config
    (fn []
      (testing "the user just mistyped one -- show what would have worked"
        (try
          (cli/parse ["download" "1091500" "nonsense-branch" "/tmp/x"])
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :incorrect (:reliquary/error (ex-data e))))
            (is (str/includes? (ex-message e) "1_63_legacy_patch"))))))))

(deftest download-parse-accepts-a-real-appid-and-version
  (with-hermetic-config
    (fn []
      (let [{:keys [game version dest]} (cli/parse ["download" "1091500" "public" "/tmp/x"])]
        (is (= 1091500 (:appid game)))
        (is (= "public" (:id version)))
        (is (= "/tmp/x" dest))))))

(deftest download-requires-all-three-arguments
  (with-hermetic-config
    (fn []
      (is (thrown? clojure.lang.ExceptionInfo (cli/parse ["download" "1091500" "public"]))))))

(deftest login-saves-the-token-and-never-prints-it
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "reliquary-cli" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [config/*config-dir* d config/*data-dir* d]
        (with-redefs [auth/login-qr! (fn [on-event]
                                       (on-event {:type :qr :challenge-url "https://s.team/q/1/2"})
                                       {:refresh-token "SECRET-JWT-VALUE"
                                        :account "someone" :steam-id "765"})]
          (let [out (with-out-str (is (= 0 (cli/login []))))]
            (is (= {:refresh-token "SECRET-JWT-VALUE" :account "someone"} (config/token))
                "the token must reach disk")
            (is (not (str/includes? out "SECRET-JWT-VALUE"))
                "the token must never reach the terminal")
            (is (str/includes? out "Signed in as someone")))))
      (finally (run! clojure.java.io/delete-file (reverse (file-seq d)))))))

;; ---------------------------------------------------------------------------
;; credential login
;;
;; `reliquary login` scans a QR, which needs a phone. On a machine with no
;; phone to hand -- a server, or an account whose authenticator lives
;; elsewhere -- an account name and a password are the only way in.

(defn- with-tmp-config [f]
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "reliquary-cli-login" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (binding [config/*config-dir* d config/*data-dir* d] (f))
         (finally (run! #(io/delete-file % true) (reverse (file-seq d)))))))

(deftest an-account-name-selects-the-credential-flow
  (with-tmp-config
    (fn []
      (let [sent (atom nil)]
        (with-redefs [cli/read-secret (fn [_] "hunter2")
                      auth/login-credentials! (fn [u pw _]
                                                (reset! sent [u pw])
                                                {:refresh-token "SECRET-JWT" :account "someone"})]
          (let [out (with-out-str (is (= 0 (cli/login ["someone"]))))]
            (is (= ["someone" "hunter2"] @sent))
            (is (not (str/includes? out "hunter2"))
                "the password must never reach the terminal")
            (is (not (str/includes? out "SECRET-JWT")))
            (is (str/includes? out "Signed in as someone"))))))))

(deftest no-account-name-still-scans-a-qr-and-never-asks-for-a-password
  (with-tmp-config
    (fn []
      (let [asked? (atom false)]
        (with-redefs [cli/read-secret (fn [_] (reset! asked? true) "x")
                      auth/login-qr! (fn [_] {:refresh-token "T" :account "someone"})]
          (with-out-str (is (= 0 (cli/login []))))
          (is (not @asked?) "the QR flow needs no password"))))))

(deftest a-guard-code-is-read-from-the-terminal-and-returned-to-the-flow
  (testing "auth/login-credentials! takes the code as the RETURN value of its
            event callback -- printing a prompt and returning nil would raise
            'no steam guard code supplied' on a perfectly good login"
    (with-tmp-config
      (fn []
        (let [answered (atom nil)]
          (with-redefs [cli/read-secret (fn [_] "pw")
                        cli/read-visible (fn [_] "12345")
                        auth/login-credentials!
                        (fn [_ _ on-event]
                          (reset! answered (on-event {:type :guard-needed :code-type 3}))
                          {:refresh-token "T" :account "someone"})]
            (with-out-str (cli/login ["someone"]))
            (is (= "12345" @answered))))))))

(deftest a-refused-code-says-so-before-asking-again
  (with-tmp-config
    (fn []
      (with-redefs [cli/read-secret (fn [_] "pw")
                    cli/read-visible (fn [_] "12345")
                    auth/login-credentials!
                    (fn [_ _ on-event]
                      (on-event {:type :guard-needed :code-type 3 :retry? true})
                      {:refresh-token "T" :account "someone"})]
        (let [out (with-out-str (cli/login ["someone"]))]
          (is (str/includes? out "not accepted")))))))

(deftest a-pending-confirmation-tells-the-user-where-to-go
  (testing "types 4 and 5 are approved out of band; with nothing printed the
            terminal just sits there"
    (with-tmp-config
      (fn []
        (with-redefs [cli/read-secret (fn [_] "pw")
                      auth/login-credentials!
                      (fn [_ _ on-event]
                        (on-event {:type :confirmation-pending :confirmation-type 4})
                        {:refresh-token "T" :account "someone"})]
          (let [out (with-out-str (cli/login ["someone"]))]
            (is (str/includes? out "mobile app"))))))))

(deftest a-password-is-never-read-without-a-terminal
  (testing "no terminal means no way to turn echo off -- reading the password
            from a pipe would print it into whatever is logging the output"
    (with-redefs [cli/terminal (constantly nil)]
      (let [e (try (cli/read-secret "Password: ") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (str/includes? (ex-message e) "login"))))))

(deftest usage-mentions-the-credential-form
  (let [out (with-out-str (cli/run []))]
    (is (str/includes? out "login <account>"))))

(deftest an-abandoned-password-prompt-says-so-plainly
  (testing "Ctrl-D at the prompt makes Console.readPassword return nil, and that
            nil used to travel all the way into crypto/encrypt-password, NPE
            inside its try, and surface as \"steam returned an unusable RSA
            public key\" -- a message about Steam's key for something the user
            did at a prompt."
    (with-redefs [cli/terminal (constantly :a-terminal)
                  cli/read-password-chars (constantly nil)]
      (let [e (try (cli/read-secret "Password: ") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))
        (is (str/includes? (ex-message e) "password"))
        (is (not (str/includes? (ex-message e) "RSA")))))))

(deftest an-empty-password-is-refused-before-it-reaches-steam
  (testing "Steam rate-limits by account (eresult 84), so spending an attempt to
            be told a blank password is wrong costs the user one of the few tries
            they get -- and the answer is already known here"
    (with-redefs [cli/terminal (constantly :a-terminal)
                  cli/read-password-chars (constantly (char-array 0))]
      (let [e (try (cli/read-secret "Password: ") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :incorrect (:reliquary/error (ex-data e))))))))

(deftest a-real-password-comes-back-intact
  (with-redefs [cli/terminal (constantly :a-terminal)
                cli/read-password-chars (constantly (char-array [\h \i \5]))]
    (is (= "hi5" (cli/read-secret "Password: ")))))
