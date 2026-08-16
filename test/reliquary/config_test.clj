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

(deftest config-dir-is-owner-only
  (with-tmp (fn [d]
              (config/write-config! {:a 1})
              (let [p     (.toPath d)
                    perms (PosixFilePermissions/toString (Files/getPosixFilePermissions p (make-array java.nio.file.LinkOption 0)))]
                (is (= "rwx------" perms)
                    "the refresh token lives in here; the directory must not be world/group traversable")))))

(deftest non-map-config-reads-as-empty-and-save-token-still-works
  (with-tmp (fn [d]
              (spit (io/file d "config.edn") "[1 2 3]")
              (is (= {} (config/read-config))
                  "read-config promises {} for a corrupt config -- a vector must not slip through")
              (config/save-token! {:refresh-token "jwt.abc.def" :account "someone"})
              (is (= {:refresh-token "jwt.abc.def" :account "someone"} (config/token))))))

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

;; ---- the reliquary.test marker: an absent path property must throw, not
;; ---- silently fall through to the real XDG path ----

(defn- with-sysprops
  "Runs `f` with each `name` in `prop-map` set to its value (or cleared, if
   the value is nil), restoring every property's ORIGINAL value afterward --
   these are real, global JVM properties, not thread-local state, so a test
   that mutates them must always put them back."
  [prop-map f]
  (let [orig (into {} (map (fn [[k _]] [k (System/getProperty k)])) prop-map)]
    (try
      (doseq [[k v] prop-map]
        (if v (System/setProperty k v) (System/clearProperty k)))
      (f)
      (finally
        (doseq [[k v] orig]
          (if v (System/setProperty k v) (System/clearProperty k)))))))

(deftest clearing-config-dir-under-the-test-marker-throws
  (testing "reliquary.test is already set for this whole suite (the :test
            alias sets it) -- clearing JUST reliquary.config-dir here is
            exactly what a runtime (System/clearProperty
            \"reliquary.config-dir\") would produce. Nothing calls that
            today, but the property being merely absent must never be
            silently safe: it must throw, not fall through to the real
            ~/.config/reliquary."
    (with-sysprops {"reliquary.config-dir" nil}
      (fn []
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"reliquary\.test is set but reliquary\.config-dir is absent"
                               (config/config-dir)))))))

(deftest clearing-data-dir-under-the-test-marker-throws
  (with-sysprops {"reliquary.data-dir" nil}
    (fn []
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"reliquary\.test is set but reliquary\.data-dir is absent"
                             (config/data-dir))))))

(deftest production-with-no-marker-and-no-properties-is-unchanged
  (testing "outside a test context -- no reliquary.test marker at all, which
            is the case for production, :cli, :app, and the packaged binary,
            none of which ever set it -- an absent path property must NOT
            throw. It must resolve to the ordinary XDG path exactly as it
            did before this whole wave of fixes."
    (with-sysprops {"reliquary.test" nil "reliquary.config-dir" nil "reliquary.data-dir" nil}
      (fn []
        (let [expected-config (io/file (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                                           (str (System/getProperty "user.home") "/.config"))
                                       "reliquary")
              expected-data   (io/file (or (not-empty (System/getenv "XDG_DATA_HOME"))
                                          (str (System/getProperty "user.home") "/.local/share"))
                                       "reliquary")]
          (is (= expected-config (config/config-dir))
              "no marker, no property: normal XDG resolution, no throw")
          (is (= expected-data (config/data-dir))
              "no marker, no property: normal XDG resolution, no throw"))))))
