(comment "
Install Steps
1. base-jar
   cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml && mvn -q install
2. all jars
   cd boot/pod && lein install
   cd boot/aether && lein install && lein uberjar && mkdir -p ../base/src/main/resources
      && cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber)
   cd boot/worker && lein install
   cd boot/core && lein install
   cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies
   cd boot/boot && lein install
3. bootbin
4. bootexe")

(import [java.util Properties])
(require '[clojure.java.io :as io]
         '[clojure.string :as string]
         '[boot.util :as util])
(def propsfile "version.properties")
(def version "2.3.1-SNAPSHOT" #_(-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(def pom-base {:url     "http://github.com/boot-clj/boot"
               :license {"EPL" "http://www.eclipse.org/legal/epl-v10.html"}
               :scm     {:url "https://github.com/boot-clj/boot.git"}})

(def settings
  {:base {:dir #{"boot/base/src"}
          :deps [['org.projectodd.shimdandy/shimdandy-api "1.2.0"]
                 ['junit/junit "3.8.1" :scope "test"]]
          :pom (merge pom-base
                      {:project     'boot/base
                       :version     version
                       :description "Boot Java application loader and class."})}
   :pod {:dir  #{"boot/pod/src"}
         :deps [['boot/base                               version :scope "provided"]
                ['org.clojure/clojure                     "1.6.0" :scope "provided"]
                ['org.tcrawley/dynapath                   "0.2.3" :scope "compile"]
                ['org.projectodd.shimdandy/shimdandy-impl "1.2.0" :scope "compile"]]
         :pom (merge pom-base
                     {:project     'boot/pod
                      :version     version
                      :description "Boot pod module–this is included with all pods."})}
   :core {:dir  #{"boot/core/src"}
          :deps [['org.clojure/clojure "1.6.0" :scope "provided"]
                 ['boot/base           version :scope "provided"]
                 ['boot/pod            version :scope "compile"]]
          :pom (merge pom-base
                      {:project      'boot/core
                       :version      version
                       :description  "Core boot module–boot scripts run in this pod."})}
   :aether {:dir  #{"boot/aether/src"}
            :deps [['org.clojure/clojure      "1.6.0" :scope "compile"]
                   ['boot/pod                 version :scope "compile"]
                   ['com.cemerick/pomegranate "0.3.0" :scope "compile"]]
            :pom (merge pom-base
                        {:project     'boot/aether
                         :version     version
                         :description "Boot aether module–performs maven dependency resolution."})}
   :worker {:dir  #{"boot/worker/src"}
            :deps [['org.clojure/clojure         "1.6.0" :scope "provided"]
                   ['boot/base                   version :scope "provided"]
                   ['boot/aether                 version]
                   ;; see https://github.com/boot-clj/boot/issues/82
                  ['net.cgrand/parsley          "0.9.3" :exclusions ['org.clojure/clojure]]
                   ['reply                       "0.3.5"]
                   ['cheshire                    "5.3.1"]
                   ['clj-jgit                    "0.8.0"]
                   ['clj-yaml                    "0.4.0"]
                   ['javazoom/jlayer             "1.0.1"]
                   ['mvxcvi/clj-pgp              "0.5.4"]
                   ['net.java.dev.jna/jna        "4.1.0"]
                   ['alandipert/desiderata       "1.0.2"]
                   ['org.clojure/data.xml        "0.0.8"]
                   ['org.clojure/data.zip        "0.1.1"]
                   ['org.clojure/tools.namespace "0.2.11"]]
            :pom (merge pom-base
                        {:project      'boot/worker
                         :version      version
                         :description  "Boot worker module–this is the worker pod for built-in tasks."})}})

(deftask base-jar []
  (set-env! :source-paths   (-> settings :base :dir)
            :resource-paths #{"boot/base/resources"}
            :dependencies   (-> settings :base :deps))
  (task-options! pom (-> settings :base :pom))
  (comp
   (with-pre-wrap fs
     (let [t (tmp-dir!)
           f (io/file t "boot/base/version.properties")]
       (io/make-parents f)
       (spit f (str "version=" version))
       (-> fs (add-resource t) commit!)))
   (pom) (javac) (jar :file "boot-base.jar")))


(deftask build-jar []
  (comp (pom) (aot) (jar) #_(install)))

(deftask uber-jar
  [_ prefix PREFIX str "Prefix to prepend to jar name"]
  (comp (pom) (aot) (uber) (jar :file (str prefix "-uber.jar"))))

(deftask pod []
  (set-env! :source-paths   (-> settings :pod :dir)
            :resource-paths (-> settings :pod :dir)
            :dependencies   (-> settings :pod :deps))
  (task-options!
   jar {:file "boot-pod.jar"}
   aot {:all true}
   pom (-> settings :pod :pom))
  identity)

(deftask aether []
  ;; cd bookkt/aether && lein install && lein uberjar && mkdir -p ../base/src/main/resources
  ;;    && cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber)
  (set-env! :source-paths   (-> settings :aether :dir)
            :resource-paths (-> settings :aether :dir)
            :dependencies   (-> settings :aether :deps))
  (task-options!
   jar {:file "boot-aether.jar"}
   aot {:all  true}
   pom (-> settings :aether :pom))
  identity)

(deftask worker []
  (set-env! :source-paths   (-> settings :worker :dir)
            :resource-paths (-> settings :worker :dir)
            :dependencies   (-> settings :worker :deps))
  (task-options!
   jar {:file        "boot-worker.jar"}
   pom (-> settings :worker :pom))
  identity)

(deftask core []
  ;; :aot          [#"^(?!boot\.repl-server).*$"]
  ;; :jar-exclusions [#"^clojure/core/"]
  (set-env! :source-paths   (-> settings :core :dir)
            :resource-paths (-> settings :core :dir)
            :dependencies   (-> settings :core :deps))
  (task-options!
   jar {:file        "boot-core.jar"}
   aot {:namespace    #{"boot.repl-server"}}
   pom (-> settings :core :pom))
  identity)

;; Placeholder module, not yet sure what's going on
#_(deftask boot []
  (set-env! :source-paths #{} :dependencies [])
  (task-options! pom {:project      'boot
                      :version      version
                      :description  "Placeholder to synchronize other boot module versions."})
  identity)
