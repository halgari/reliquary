;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Copied from the mauvi mod manager, whose Steam client this is. Copied
;; deliberately rather than depended on: Reliquary shares no runtime, no store
;; and no build with mauvi, and this code is Reliquary's to change.
(ns reliquary.steam.descriptor-set-test
  "The committed descriptor set is a build artifact -- these tests are what
   catch a stale or truncated steam.desc after a .proto edit."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io])
  (:import (com.google.protobuf DescriptorProtos$FileDescriptorSet)))

(defn- file-set []
  (with-open [in (io/input-stream (io/resource "steam/steam.desc"))]
    (DescriptorProtos$FileDescriptorSet/parseFrom in)))

(deftest descriptor-set-is-on-the-classpath
  (is (some? (io/resource "steam/steam.desc"))
      "resources/steam/steam.desc missing -- run bin/gen-protos.sh"))

(deftest descriptor-set-covers-both-proto-files
  (is (= #{"steam_auth.proto" "steam_cm.proto"}
         (into #{} (map #(.getName %)) (.getFileList (file-set))))))

(deftest descriptor-set-carries-the-messages-the-session-needs
  (let [names (into #{}
                    (mapcat (fn [f] (map #(.getName %) (.getMessageTypeList f))))
                    (.getFileList (file-set)))]
    (doseq [n ["CMsgProtoBufHeader" "CMsgMulti" "CMsgClientLogon"
               "CMsgClientLogonResponse" "CMsgClientLicenseList"
               "CAuthentication_BeginAuthSessionViaQR_Request"
               "CAuthentication_BeginAuthSessionViaQR_Response"
               "CAuthentication_PollAuthSessionStatus_Request"
               "CAuthentication_PollAuthSessionStatus_Response"]]
      (is (contains? names n) (str "descriptor set is missing " n)))))
