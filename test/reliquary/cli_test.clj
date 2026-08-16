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
