(ns build
  (:refer-clojure :exclude [test])
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'com.shipclojure/re-frame-query)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" lib version))

(defn jar
  "Build the JAR."
  [opts]
  (b/delete {:path "target"})
  (println "\nCopying source...")
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (println "\nWriting pom.xml...")
  ;; Use the hand-maintained root pom.xml as source — no auto-generated deps
  (b/copy-file {:src "pom.xml"
                :target (format "%s/META-INF/maven/%s/%s/pom.xml"
                                class-dir (namespace lib) (name lib))})
  (spit (format "%s/META-INF/maven/%s/%s/pom.properties"
                class-dir (namespace lib) (name lib))
        (format "version=%s\ngroupId=%s\nartifactId=%s\n"
                version (namespace lib) (name lib)))
  (println "\nBuilding JAR...")
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println (str "\nBuild Done ✅ " jar-file))
  opts)

(defn install
  "Install the JAR locally."
  [opts]
  (jar opts)
  (b/install {:basis (b/create-basis {})
              :lib lib :version version
              :jar-file jar-file
              :class-dir class-dir})
  opts)

(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (jar opts)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (format "%s/META-INF/maven/%s/%s/pom.xml"
                                class-dir (namespace lib) (name lib))})
  opts)
