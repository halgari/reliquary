(ns reliquary.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reliquary.config :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)))

(defn- with-tmp [f]
  (let [d (.toFile (Files/createTempDirectory "reliquary-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (binding [config/*config-dir* d config/*data-dir* d] (f d))
         (finally (run! io/delete-file (reverse (file-seq d)))))))

(deftest missing-config-reads-as-empty
  (with-tmp (fn [_] (is (= {} (config/read-config))))))

(deftest config-round-trips
  (with-tmp (fn [_]
              (config/write-config! {:folder "/games" :workers 8})
              (is (= {:folder "/games" :workers 8} (config/read-config))))))

(deftest config-file-is-owner-only
  (with-tmp (fn [d]
              (config/write-config! {:a 1})
              (let [p (.toPath (io/file d "config.edn"))
                    perms (PosixFilePermissions/toString (Files/getPosixFilePermissions p (make-array java.nio.file.LinkOption 0)))]
                (is (= "rw-------" perms)
                    "the refresh token lives here; group and other must not read it")))))

(deftest corrupt-config-reads-as-empty-rather-than-throwing
  (with-tmp (fn [d]
              (spit (io/file d "config.edn") "{:unbalanced ")
              (is (= {} (config/read-config))
                  "a corrupt config must not brick startup"))))

(deftest token-round-trips-and-forgets
  (with-tmp (fn [_]
              (is (nil? (config/token)))
              (config/save-token! {:refresh-token "jwt.abc.def" :account "someone"})
              (is (= {:refresh-token "jwt.abc.def" :account "someone"} (config/token)))
              (config/write-config! (assoc (config/read-config) :folder "/games"))
              (config/forget-token!)
              (is (nil? (config/token)))
              (is (= "/games" (:folder (config/read-config)))
                  "forgetting the token must not discard unrelated settings"))))

(deftest write-is-atomic
  (with-tmp (fn [d]
              (config/write-config! {:a 1})
              (config/write-config! {:a 2})
              (is (= 1 (count (filter #(.isFile %) (.listFiles d))))
                  "no temp file left behind"))))
