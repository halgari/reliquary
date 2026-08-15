(ns reliquary.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config]
            [reliquary.session :as session]
            [reliquary.steam.cm.client :as client]
            [reliquary.steam.crypto :as crypto]))

(deftest expired-reads-the-jwt-expiry
  (with-redefs [crypto/jwt-claims (fn [_] {:sub "76561198000000000" :exp 1000})]
    (is (session/expired? "jwt" 1001))
    (is (not (session/expired? "jwt" 999)))
    (is (session/expired? "jwt" 1000)
        "exp == now is deliberately treated as expired, not as the last valid second")))

(deftest expired-with-no-exp-claim-is-expired
  (with-redefs [crypto/jwt-claims (fn [_] {:sub "76561198000000000"})]
    (is (session/expired? "jwt" 0)
        "a claims map with no :exp is unusable and must count as expired")))

(deftest an-unreadable-token-counts-as-expired
  (with-redefs [crypto/jwt-claims (fn [_] (throw (ex-info "bad jwt" {})))]
    (is (session/expired? "garbage" 0)
        "a token we cannot read is a token we cannot use")))

(deftest open-without-a-token-is-unauthenticated
  (with-redefs [config/token (constantly nil)]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (session/open!)))]
      (is (= :unauthenticated (:reliquary/error (ex-data e)))))))

(deftest open-with-an-expired-token-is-unauthenticated
  (with-redefs [config/token  (constantly {:refresh-token "jwt" :account "a"})
                crypto/jwt-claims (fn [_] {:exp 0})]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (session/open!)))]
      (is (= :unauthenticated (:reliquary/error (ex-data e)))))))

(deftest open-logs-on-with-the-stored-token
  (let [seen (atom nil)]
    (with-redefs [config/token      (constantly {:refresh-token "jwt" :account "someone"})
                  crypto/jwt-claims (fn [_] {:exp 99999999999})
                  client/logon!     (fn [t a] (reset! seen [t a])
                                      {:conn :a-conn :steamid "765" :heartbeat nil})]
      (let [s (session/open!)]
        (is (= ["jwt" "someone"] @seen))
        (is (= "someone" (:account s)))
        (is (= :a-conn (:conn s)))))))

(deftest an-error-never-quotes-the-token
  (with-redefs [config/token      (constantly {:refresh-token "SECRET-JWT" :account "a"})
                crypto/jwt-claims (fn [_] {:exp 0})]
    (try (session/open!)
         (catch clojure.lang.ExceptionInfo e
           (is (not (re-find #"SECRET-JWT" (str (ex-message e) (pr-str (ex-data e))))))))))
