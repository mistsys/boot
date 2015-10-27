(comment "
Install Steps
1. base-jar
   cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml && mvn -q install
2. all jars
   cd boot/pod && lein install
   cd boot/worker && lein install
   cd boot/core && lein install
   cd boot/boot && lein install
   cd boot/aether && lein install && lein uberjar && mkdir -p ../base/src/main/resources
      && cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber)
   cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies
3. bootbin
4. bootexe")

(import [java.util Properties])
(require '[clojure.java.io :as io]
         '[clojure.string :as string]
         '[boot.util :as util]
         '[boot.pod :as pod])
(def propsfile "version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(def pom-base {:version version
               :url     "http://github.com/boot-clj/boot"
               :license {"EPL" "http://www.eclipse.org/legal/epl-v10.html"}
               :scm     {:url "https://github.com/boot-clj/boot.git"}})
(task-options! pom pom-base)

(def settings
  {:base {:dir {:src #{"boot/base/src/"}
                :rsc #{"boot/base/resources/"}}
          :deps [['org.projectodd.shimdandy/shimdandy-api "1.2.0"]
                 ['junit/junit "3.8.1" :scope "test"]]}
   :loader {:dir {:src #{"boot/loader/src/"}
                  :rsc #{"boot/loader/resources/"}}}
   :pod {:dir  {:src #{"boot/pod/src/"}}
         :deps [['boot/base                               version :scope "provided"]
                ['org.clojure/clojure                     "1.6.0" :scope "provided"]
                ['org.tcrawley/dynapath                   "0.2.3" :scope "compile"]
                ['org.projectodd.shimdandy/shimdandy-impl "1.2.0" :scope "compile"]]}
   :core {:dir  #{"boot/core/src/"}
          :deps [['org.clojure/clojure "1.6.0" :scope "provided"]
                 ['boot/base           version :scope "provided"]
                 ['boot/pod            version :scope "compile"]]}
   :aether {:dir  {:src #{"boot/aether/src/"}}
            :deps [['org.clojure/clojure      "1.6.0" :scope "compile"]
                   ['boot/pod                 version :scope "compile"]
                   ['com.cemerick/pomegranate "0.3.0" :scope "compile"]]}
   :worker {:dir  {:src #{"boot/worker/src/" "boot/worker/third_party/barbarywatchservice/src/"}}
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
                   ['org.clojure/tools.namespace "0.2.11"]]}})

(defn set-env-for!
  ([subproject]
   (set-env-for! subproject false))
  ([subproject uber?]
   (set-env! :dependencies   (or (-> settings subproject :deps) [])
             :source-paths   (or (-> settings subproject :dir :src) #{})
             :resource-paths (or (-> settings subproject :dir :rsc) #{})
             :target-path (str "target/" (name subproject) (if uber? "-uber")))))

(defn jarname*
  ([lib]
   (jarname* lib false))
  ([lib uber?]
   (str "boot-" (name lib) "-" version (when uber? "-uber") ".jar")))

(deftask print-paths []
  (with-pre-wrap fs
    (println "Paths =====================================")
    (doseq [f (take 100 (sort (-> fs :tree keys)))]
      (println f))
    fs))

(defmacro task-when
  [pred & body]
  `(if-not ~pred identity (do ~@body)))

(deftask pod
  [r launch-repl bool "repl"]
  (set-env-for! :pod)
  (if launch-repl
    (repl)
    (comp (pom :project     'boot/pod
               :description "Boot pod module–this is included with all pods.")
          (aot :all true)
          (jar :file (jarname* :pod)))))

(deftask worker []
  (set-env-for! :worker)
  (comp (pom :project      'boot/worker
             :description  "Boot worker module–this is the worker pod for built-in tasks.")
        (javac)
        (aot :all true)
        (jar :file (jarname* :worker))))

(deftask aether
  [u uberjar bool "build uberjar?"]
  (set-env-for! :aether uberjar)
  (comp (pom :project     'boot/aether
             :description "Boot aether module–performs maven dependency resolution.")
        (aot :all true)
        (task-when uberjar (uber))
        (jar :file (jarname* :aether uberjar))))

(deftask core []
  ;; :jar-exclusions [#"^clojure/core/"]
  (set-env-for! :core)
  (comp (pom :project      'boot/core
             :description  "Core boot module–boot scripts run in this pod.")
        (aot :namespace #{'boot.cli 'boot.core 'boot.git 'boot.main 'boot.repl
                          'boot.task.built-in 'boot.task-helpers 'boot.tmregistry})
        (jar :file (jarname* :core))))

(deftask loader []
  (set-env-for! :loader)
  (comp (pom :project     'boot/loader
             :description "Boot loader class.")
        (javac)
        (jar :file (jarname* :loader))))

(deftask base
  [u uberjar bool "build uberjar?"]
  (set-env-for! :base uberjar)
  (comp (with-pre-wrap fs
          (let [t (tmp-dir!)
                f (io/file t "boot/base/version.properties")]
            (io/make-parents f)
            (spit f (str "version=" version))
            (-> fs (add-resource t) commit!)))
        (pom :project     'boot/base
             :description "Boot Java application loader and class.")
        (javac)
        (print-paths)
        ;; (with-pre-wrap fs
        ;;   (let [tgt (tmp-dir!)
        ;;         f (get-in fs [:tree "boot-aether-2.4.2-SNAPSHOT-uber.jar"])]
        ;;     (pod/unpack-jar (tmp-file f) tgt
        ;;                     :mergers pod/standard-jar-mergers
        ;;                     :include #{}
        ;;                     :exclude pod/standard-jar-exclusions)
        ;;     (-> fs (add-resource tgt :mergers pod/standard-jar-mergers) commit!)))
        ;; (print-paths)
        (task-when uberjar (uber))
        (print-paths)
        (jar :file (jarname* :base uberjar))))

;; (deftask build-lib []
;;   (comp (base) (pod) (aether) (worker) (core)))

(deftask build-bin []
  (comp (loader)
        (with-pre-wrap fs
          (let [tmp  (tmp-dir!)
                head (tmp-file (get-in fs [:tree "head.sh"]))
                ujar (tmp-file (get-in fs [:tree (jarname* :loader)]))
                bin  (io/file tmp "boot.sh")]
            (spit (io/file tmp "boot.sh")
                  (str (slurp head) (slurp ujar)))
            (-> fs (add-resource tmp) commit!)))))

(deftask transaction-jar []
  (comp (pom :project      'boot/boot
             :description  "Placeholder to synchronize other boot module versions.")
        (jar)))

(defn runboot
  [& boot-args]
  (future
    (boot.App/runBoot
      (boot.App/newCore)
      (future @pod/worker-pod)
      (into-array String boot-args))))

(deftask pick
  [f files PATH #{str} "The files to pick."
   d dir PATH     str  "The directory to put the files."]
  (with-pre-wrap [fs]
    (with-let [fs fs]
      (let [files (->> (output-files fs)
                       (map (juxt tmp-path tmp-file))
                       (filter #((or files #{}) (first %))))]
        (doseq [[p f] files]
          (when (.exists f)
            (io/copy f (io/file dir p))))))))

(deftask build
  []
  ;; (info "Building base...\n")
  ;; (runboot "watch" "base")
  ;; (info "Building pod...\n")
  ;; (runboot "watch" "pod")
  ;; (info "Building core...\n")
  ;; (runboot "watch" "core")
  ;; (info "Building worker...\n")
  ;; (runboot "watch" "worker")
  ;; (info "Building aether...\n")
  ;; (runboot "watch" "aether")
  ;; (info "Building loader...\n")
  ;; (runboot "watch" "loader")
  (info "Building aether uberjar...\n")
  (runboot "watch" "aether" "--uberjar" "pick" "-d" "boot/base/resources" "-f" (jarname* :aether true))
  (info "Building base uberjar...\n")
  (runboot "watch" "base" "--uberjar")
  (wait))
