;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cdn-test
  "Host rotation and retry, extracted from manifest so the chunk path can share
   one policy instead of growing a second, subtly different one."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cdn :as cdn]))

(defn- url-for [host] (str "https://" host "/depot/1/chunk/abc"))

(deftest rotates-to-the-next-host-after-exhausting-attempts
  (let [calls (atom [])]
    (with-redefs [cdn/get-once (fn [uri]
                                 (swap! calls conj (str uri))
                                 (if (re-find #"host-b" (str uri))
                                   (byte-array [1 2 3])
                                   (throw (ex-info "down" {:reliquary/error :unavailable}))))]
      (binding [cdn/*fetch-attempts* 2]
        (let [got (cdn/fetch-with-rotation ["host-a" "host-b"] url-for "chunk")]
          (is (= [1 2 3] (vec got)) "the second host's body is returned")
          (is (= 3 (count @calls)) "two attempts on host-a, then one on host-b")
          (is (every? #(re-find #"host-a" %) (take 2 @calls))))))))

(deftest a-4xx-aborts-immediately-without-trying-another-host
  (let [calls (atom [])]
    (with-redefs [cdn/get-once (fn [uri]
                                 (swap! calls conj (str uri))
                                 (throw (ex-info "rejected" {:reliquary/error :incorrect})))]
      (binding [cdn/*fetch-attempts* 3]
        (let [e (try (cdn/fetch-with-rotation ["host-a" "host-b"] url-for "chunk") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :incorrect (:reliquary/error (ex-data e))))
          (is (= 1 (count @calls))
              "a rejected request is not a transport problem; no host answers differently"))))))

(deftest exhausting-every-host-is-unavailable
  (with-redefs [cdn/get-once (fn [_] (throw (ex-info "down" {:reliquary/error :unavailable})))]
    (binding [cdn/*fetch-attempts* 1]
      (let [e (try (cdn/fetch-with-rotation ["host-a" "host-b"] url-for "chunk") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :unavailable (:reliquary/error (ex-data e))))))))

(deftest an-empty-host-list-is-unavailable
  (let [e (try (cdn/fetch-with-rotation [] url-for "chunk") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :unavailable (:reliquary/error (ex-data e))))))

(deftest a-malformed-url-never-quotes-the-url
  (testing "the URL can carry a manifest request code, which is a capability"
    (let [e (try (cdn/fetch-with-rotation ["bad host with spaces"]
                                           (fn [h] (str "https://" h "/depot/1/manifest/2/5/SUPERSECRETCODE"))
                                           "manifest")
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :incorrect (:reliquary/error (ex-data e))))
      (is (not (re-find #"SUPERSECRETCODE" (ex-message e)))
          "java.net's own message quotes the whole URL; ours must not")
      (is (not (re-find #"SUPERSECRETCODE" (pr-str (ex-data e))))))))
