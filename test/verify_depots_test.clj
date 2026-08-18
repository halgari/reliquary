;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns verify-depots-test
  "The tool writes license-denied-depots.json, and both fetch_versions.py and
   assemble.py read it to decide what ships. A depot wrongly recorded there is a
   catalog version quietly missing base game content, with nothing failing until
   a user downloads it -- so what counts as a denial is worth pinning."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.error :as error]
            [reliquary.steam.cm.content :as content]
            [verify-depots :as vd]))

(defn- probe [f]
  (with-redefs [content/depot-key (fn [_ _ _] (f))]
    (#'vd/probe :conn 22380 22493)))

(defn- denial [eresult]
  ;; the exact shape content/depot-key raises: it carries :eresult for EVERY
  ;; non-1 result, not only for a refusal
  (error/raise :incorrect "steam denied depot 22493"
               {:eresult eresult :depot-id 22493}))

(deftest access-denied-is-recorded-as-a-license-denial
  (testing "EResult 15 AccessDenied is the whole point of the sweep: the depot is
            listed in the game's table and the game's licence does not cover it"
    (is (= 15 (probe #(denial 15))))))

(deftest a-granted-key-records-nothing
  (is (nil? (probe (constantly "deadbeef")))))

(deftest a-transient-failure-is-rethrown-not-recorded
  (testing "content/depot-key raises with :eresult for ANY non-1 result, so a
            dropped session or a rate limit looked exactly like a refusal and was
            written into license-denied-depots.json as `not part of the game`.
            That file is read forever after: the catalog then ships a version
            missing base game content, and nothing fails until a download. The
            tool's own docstring already promised this -- `anything that is not a
            categorized refusal is rethrown` -- while the code recorded them all.

            20 ServiceUnavailable, 21 NotLoggedOn, 16 Timeout, 84 RateLimit."
    (doseq [er [20 21 16 84]]
      (is (thrown? clojure.lang.ExceptionInfo (probe #(denial er)))
          (str "eresult " er " is not a licence answer and must not be recorded")))))

(deftest an-error-carrying-no-eresult-is-still-rethrown
  (is (thrown? clojure.lang.ExceptionInfo
               (probe #(error/raise :unavailable "connection closed")))))
