;; Reliquary — Copyright (C) 2026 Timothy Baldridge
;; Licensed under the GNU General Public License v3 or later. See LICENSE.
(ns build
  "Uberjar builds. jpackage consumes the jar this produces."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/lib/reliquary.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

;; b/uber matches exclusions with re-matches, so each must match a whole path.
;;
;; org.tukaani.xz ships a module-info under META-INF/versions/9. It is inert
;; on the classpath, but jdeps reads it as the *whole uberjar's* module
;; descriptor and then reports the jar needs nothing beyond java.base --
;; which would silently produce a jlink runtime missing every module the app
;; actually uses. b/uber's own exclusion list only covers a root-level
;; module-info.class, so it misses this one.
(def ^:private exclusions [".*/module-info\\.class"])

(defn- drop-javafx
  "Remove every org.openjfx artifact from the basis's libs.

   A jlink runtime supplies JavaFX as real modules, which take precedence
   over classpath copies of the same packages anyway -- so in a packaged
   build those copies are dead weight. cljfx also drags in javafx-web and
   javafx-media transitively; it loads their lifecycles lazily and Reliquary
   uses neither, but libjfxwebkit.so alone is ~40 MB of the uberjar."
  [basis]
  (update basis :libs #(into {} (remove (fn [[lib _]] (= "org.openjfx" (namespace lib)))) %)))

(defn clean [_] (b/delete {:path "target"}))

(defn uber
  "Build target/lib/reliquary.jar with `main` as its Main-Class.

   :omit-javafx true drops the bundled JavaFX jars. Use it only for a jar
   destined for bin/package.sh, whose jlink runtime carries JavaFX as
   modules; the resulting jar will not run under a plain `java -jar`."
  [{:keys [main omit-javafx] :or {main 'reliquary.main}}]
  (b/delete {:path class-dir})
  (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
  (b/compile-clj {:basis      @basis
                  :src-dirs   ["src"]
                  :ns-compile [main]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (cond-> @basis omit-javafx drop-javafx)
           :main      main
           :exclude   exclusions}))
