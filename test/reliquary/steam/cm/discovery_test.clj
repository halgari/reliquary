;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.cm.discovery-test
  "The GET is covered by the live gate. The parse is where the bugs live."
  (:require [clojure.test :refer [deftest is testing]]
            [reliquary.steam.cm.discovery :as disc]))

(def ^:private sample
  (str "{\"response\":{\"serverlist\":["
       "{\"endpoint\":\"ext1-ord1.steamserver.net:27019\",\"type\":\"netfilter\"},"
       "{\"endpoint\":\"ext1-ord1.steamserver.net:443\",\"type\":\"websockets\"},"
       "{\"endpoint\":\"ext2-ord1.steamserver.net:443\",\"type\":\"websockets\"}"
       "]}}"))

(deftest keeps-only-websocket-endpoints
  (testing "netfilter and TCP entries are in the same list and are unusable here"
    (is (= ["wss://ext1-ord1.steamserver.net:443/cmsocket/"
            "wss://ext2-ord1.steamserver.net:443/cmsocket/"]
           (disc/parse-server-list sample)))))

(deftest an-empty-server-list-yields-an-empty-vector
  (is (= [] (disc/parse-server-list "{\"response\":{\"serverlist\":[]}}"))))

(deftest a-missing-serverlist-key-yields-an-empty-vector
  (testing "must not NPE -- Steam has returned bodies without the key"
    (is (= [] (disc/parse-server-list "{\"response\":{}}")))
    (is (= [] (disc/parse-server-list "{}")))))

(deftest malformed-json-is-categorized
  (let [e (try (disc/parse-server-list "not json at all") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :unavailable (:reliquary/error (ex-data e))))))
