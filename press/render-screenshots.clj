;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
;;
;; Renders the screenshots in press/screenshots/ at the app's real window size.
;;
;;   clojure -M:dev -i press/render-screenshots.clj
;;
;; The data is a mixture of the real catalog and a fixed fixture, so the images
;; are reproducible and show a populated library on any machine. Rendering at
;; 1100x720 is deliberate: shot/render! crops rather than shrinks, so anything
;; that does not fit here does not fit in the running app either.
(require '[clojure.java.io :as io]
         '[reliquary.catalog :as catalog]
         '[reliquary.main :as main]
         '[reliquary.ui.anim :as anim]
         '[reliquary.ui.app :as app]
         '[reliquary.ui.shot :as shot])

(def out "press/screenshots")
(.mkdirs (io/file out))

(def st* (atom nil))
(def signed (main/initial-state st* {:refresh-token "x" :account "halgari"}))
(def anon   (main/initial-state st* nil))
(def cat    (catalog/load!))
(def games  (vec (catalog/games cat)))
(def sky    (catalog/game cat 489830))
(def versions (catalog/versions sky))

;; A fixed install fixture rather than whatever this machine happens to have,
;; so the same images come out on any checkout.
(def install {:appid 489830
              :path "D:\\SteamLibrary\\steamapps\\common\\Skyrim Special Edition"
              :bytes 16095731388})
(def installs {489830 install 413150 {:appid 413150
                                      :path "D:\\SteamLibrary\\steamapps\\common\\Stardew Valley"
                                      :bytes 745000000}})
(def labels {489830 "Latest" 413150 "1.6.15"})

(defn shot! [n st]
  (binding [anim/*animate* false]
    (shot/render! (get-in (app/view (assoc st :content (main/screen st))) [:scene :root])
                  (str out "/" n ".png") {:width 1100 :height 720})))

(def lib (merge signed {:screen :library :games games :owned (set (map :appid games))
                        :installs installs :installed-labels labels
                        :capsule-fn main/capsule-image
                        :screenshot-fn main/screenshot-image}))

(shot! "01-library-installed"
       (merge lib {:tab :installed :selected-appid 489830
                   :selected-version-id "1_6_1130"
                   :install install :installed-version {:id "public" :label "Latest"}}))

(shot! "02-library-owned"
       (merge lib {:tab :owned :selected-appid 489830
                   :selected-version-id "1_6_640"
                   :folder "D:\\Games\\Skyrim 1.6.640"}))

(shot! "03-change-install"
       (merge signed {:screen :switch :game sky :install install
                      :installed-version {:id "public" :label "Latest"}
                      :target-version (second versions)}))

(shot! "04-change-install-progress"
       (merge signed {:screen :switch :game sky :install install
                      :installed-version {:id "public" :label "Latest"}
                      :target-version (second versions)
                      :snapshot {:stage :hashing :bytes-done 9200000000
                                 :bytes-total 15000000000
                                 :bytes-per-sec 1.02E9 :session-bytes-per-sec 9.8E8}}))

(shot! "05-downloading"
       (merge signed {:screen :download :game sky :version (second versions)
                      :shot-index 0 :quote-index 0
                      :screenshot-fn main/screenshot-image
                      :snapshot {:stage :downloading :bytes-done 7300000000
                                 :bytes-total 16095467175
                                 :chunks-done 41200 :chunks-total 90800
                                 :wire-bytes 7100000000
                                 :bytes-per-sec 6.4E7 :wire-bytes-per-sec 6.2E7
                                 :session-bytes-per-sec 5.9E7
                                 :samples [4.1E7 5.2E7 6.0E7 5.8E7 6.4E7 6.1E7 6.4E7]
                                 :error nil}}))

(shot! "06-done"
       (merge signed {:screen :done :game sky :version (second versions)
                      :path "D:\\Games\\Skyrim 1.6.1130"
                      :screenshot-fn main/screenshot-image
                      :snapshot {:stage :done :bytes-done 16095467175
                                 :bytes-total 16095467175
                                 :chunks-done 90800 :chunks-total 90800}}))

(shot! "07-sign-in"
       (merge anon {:screen :login :challenge-url "https://s.team/q/0/123456789"
                    :qr-state :waiting}))

(println "rendered" (count (.listFiles (io/file out))) "screenshots into" out)
(System/exit 0)
