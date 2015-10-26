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
                 ['junit/junit "3.8.1" :scope "test"]]}
   :pod {:dir  #{"pod/src/"}
         :deps [['boot/base                               version :scope "provided"]
                ['org.clojure/clojure                     "1.6.0" :scope "provided"]
                ['org.tcrawley/dynapath                   "0.2.3" :scope "compile"]
                ['org.projectodd.shimdandy/shimdandy-impl "1.2.0" :scope "compile"]]}
   :core {:dir  #{"core/src/"}
          :deps [['org.clojure/clojure "1.6.0" :scope "provided"]
                 ['boot/base           version :scope "provided"]
                 ['boot/pod            version :scope "compile"]]}
   :aether {:dir  #{"aether/src/"}
            :deps [['org.clojure/clojure      "1.6.0" :scope "compile"]
                   ['boot/pod                 version :scope "compile"]
                   ['com.cemerick/pomegranate "0.3.0" :scope "compile"]]}
   :worker {:dir  #{"boot/worker/src/" "boot/worker/third_party/barbarywatchservice/src"}
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

(task-options! pom pom-base)

(defmacro task-when
  [pred & body]
  `(if-not ~pred identity (do ~@body)))

(defmacro with-env
  [env & body]
  (let [orig (into {} (map #(vector % (gensym)) (keys env)))]
    `(let [~@(mapcat (fn [[k v]] [v `(boot.core/get-env ~k)]) orig)]
       ~@(for [[k v] env] `(boot.core/set-env! ~k ~v))
       (boot.util/with-let [ret# (do ~@body)]
         ~@(for [[k v] orig] `(boot.core/set-env! ~k ~v))))))

(deftask print-paths []
  (with-pre-wrap fs
    (doseq [f (ls fs)]
      (println (:path f)))
    (println "--------------------------------")
    fs))

(defn same-val [m]
  (-> (fn [nm [k v]]
        (let [in-other? (-> (dissoc m k) vals set)]
          (if (in-other? v)
            (assoc nm k v)
            nm)))
      (reduce nil m)))

(defn dir-scoper
  "Returns a variadic function for one or two filesets."
  [dirs]
  (fn scope-unscope
    ([fileset]
     (let [pttrns      (re-pattern (string/join "|" (map #(str "^" %) dirs)))
           path->new   (fn [path] (string/replace path pttrns ""))
           mapping     (into {} (for [f    (ls fileset)
                                      :let [old (:path f)
                                            new (path->new (:path f))]]
                                  [old (if-not (= new old) new)]))
           {:keys [rm* mv*]} (group-by #(if (nil? (val %)) :rm* :mv*) mapping)
           in-scope    (by-path (map first mv*) (ls fileset))
           out-scope   (by-path (map first rm*) (ls fileset))
           only-in     (rm fileset out-scope)]
       (when-let [conflicts (same-val (into {} mv*))]
         (util/warn "Scoping conflict, files with same relative path exist\n%s"
                    (with-out-str (clojure.pprint/pprint conflicts))))
       (reduce #(mv %1 (first %2) (second %2)) only-in mv*)))
    ([original scoped]
     (reduce (fn [fs f]
               (let [exists?  (->> fs :tree keys set)
                     new-locs (map #(str % (:path f)) dirs)
                     matches  (->> new-locs (map exists?) (remove nil?))]
                 (let [new-loc (first matches)]
                   (if (exists? new-loc)
                     (do (util/dbug "Moving %s to %s\n" (:path f) new-loc)
                         (assoc-in fs [:tree new-loc] (assoc f :path new-loc)))
                     (do (util/dbug "Moving %s to %s\n" (:path f) (:path f))
                         (assoc-in fs [:tree (:path f)] f))))))
             original
             (ls scoped)))))

(deftask with-dir
  ;; TODO generalize to take transformation functions before/after
  [d dir          DIR  #{str}  "directory in fileset to use as root - must end with /"
   t task         TASK  code "task to run with scoped fileset as input"
   m transform-fn TRANS code "function to merge resulting fileset with prev"]
  (let [scope   #((dir-scoper dir) %)
        unscope #((dir-scoper dir) %1 %2)
        prev    (atom {})]
    (fn [next-handler]
      (fn [original-fs]
        (let [scoped (scope original-fs)
              diff   (fileset-diff @prev scoped)]
          (reset! prev scoped)
          (if (seq (ls diff))
            ((task
              (fn [scoped-fs]
                (next-handler (commit! (unscope original-fs scoped-fs)))))
             (commit! scoped))
            (next-handler original-fs)))))))

(set-env! :resource-paths #{"boot"}
          :source-paths #{"boot"})

(deftask pod
  [r launch-repl bool "repl"]
  (set-env! :dependencies (-> settings :pod :deps))
  (with-dir
    :dir (-> settings :pod :dir)
    :task (if launch-repl
            (repl)
            (comp (pom :project     'boot/pod
                       :version     version
                       :description "Boot pod module–this is included with all pods.")
                  (aot :all true)
                  (jar :file "boot-pod.jar")))))

(deftask worker []
  (with-env {:source-paths (-> settings :worker :dir)
             :resource-paths (-> settings :worker :dir)
             :dependencies (-> settings :worker :deps)}
    (comp (pom :project      'boot/worker
               :version      version
               :description  "Boot worker module–this is the worker pod for built-in tasks.")
          (javac)
          (aot :all true)
          (jar :file "boot-worker.jar"))))

(deftask aether
  [u uberjar bool "build uberjar?"]
  ;; cd bookkt/aether && lein install && lein uberjar && mkdir -p ../base/src/main/resources
  ;;    && cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber)
  (set-env! :dependencies (-> settings :aether :deps))
  (with-dir
    :dir (-> settings :aether :dir)
    :task (comp (pom :project     'boot/aether
                     :version     version
                     :description "Boot aether module–performs maven dependency resolution.")
                (aot :all true)
                (task-when uberjar (uber))
                (jar :file (if uberjar
                             "boot-aether-uber.jar"
                             "boot-aether.jar")))))

(deftask core []
  ;; :jar-exclusions [#"^clojure/core/"]
  (set-env! :dependencies (-> settings :core :deps))
  (with-dir
    :dir (-> settings :core :dir)
    :task (comp (pom :project      'boot/core
                     :version      version
                     :description  "Core boot module–boot scripts run in this pod.")
                (aot :namespace #{'boot.cli 'boot.core 'boot.git 'boot.main 'boot.repl
                                  'boot.task.built-in 'boot.task-helpers 'boot.tmregistry})
                (jar :file "boot-core.jar"))))

(deftask base
  [u uberjar bool "build uberjar?"]
  (with-env {:source-paths   (-> settings :base :dir)
             :resource-paths #{"boot/base/resources"}
             :dependencies   (-> settings :base :deps)}
    (comp
     (task-when uberjar (aether :uberjar true))
     (task-when uberjar (sift :add-resource #{"boot-aether-uber.jar"}))
     (with-pre-wrap fs
       (let [t (tmp-dir!)
             f (io/file t "boot/base/version.properties")]
         (io/make-parents f)
         (spit f (str "version=" version))
         (-> fs (add-resource t) commit!)))
     (pom :project     'boot/base
          :version     version
          :description "Boot Java application loader and class.")
     (javac)
     (task-when uberjar (uber))
     (jar :file (if uberjar
                  "boot-base-with-dependencies.jar"
                  "boot-base.jar")))))

(deftask build-lib []
  (comp (pod)
        (aether)
        (worker)
        (core)))

(deftask build-bin []
  )

;; Placeholder module, not yet sure what's going on
#_(deftask boot []
  (set-env! :source-paths #{} :dependencies [])
  (task-options! pom {:project      'boot
                      :version      version
                      :description  "Placeholder to synchronize other boot module versions."})
  identity)
