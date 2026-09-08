(ns jts-sources
  "Locate and parse the JTS sources jar. `javadoc-index` reads the doc
   comments and `source-param-names` reads interface parameter names, both
   through here so there is one location mechanism and one parse.

   The jar reaches the classpath from the `jts-core$sources` coordinate in
   deps.edn. A missing jar throws, because an undocumented .d.ts should
   fail the build rather than ship."
  (:require [clojure.string :as str])
  (:import [java.util.jar JarFile JarEntry]
           [com.github.javaparser JavaParser ParserConfiguration
            ParserConfiguration$LanguageLevel]))

(defn jar-path
  "Path to jts-core-<version>-sources.jar, taken from the classpath."
  []
  (or (->> (str/split (System/getProperty "java.class.path")
                      (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
           (filter #(re-find #"jts-core-[^/\\]*-sources\.jar$" %))
           first)
      (throw (ex-info (str "jts-core sources jar not on the classpath. "
                           "Check the org.locationtech.jts/jts-core$sources "
                           "coordinate in deps.edn.")
                      {:classpath (System/getProperty "java.class.path")}))))

(defn- parser
  "JavaParser configured to retain comments. `setAttributeComments` is what
   makes `.getJavadoc` return anything; without it the doc comments are
   parsed as orphaned and never attached to their declaration."
  []
  (JavaParser. (doto (ParserConfiguration.)
                 (.setLanguageLevel ParserConfiguration$LanguageLevel/JAVA_17)
                 (.setAttributeComments true))))

(defn- parse-jar
  "Parse every .java entry in the jar. Returns a seq of CompilationUnit.
   Entries that fail to parse are reported on stderr and skipped so one
   bad file can't take down the whole registry build."
  []
  (let [jp (parser)]
    (with-open [jar (JarFile. ^String (jar-path))]
      (->> (enumeration-seq (.entries jar))
           (filter #(str/ends-with? (.getName ^JarEntry %) ".java"))
           (keep (fn [^JarEntry e]
                   (let [content (with-open [is (.getInputStream jar e)] (slurp is))
                         result  (.parse jp content)]
                     (if (.isSuccessful result)
                       (.orElse (.getResult result) nil)
                       (binding [*out* *err*]
                         (println "  warn: javadoc parse failed (skipped):" (.getName e))
                         nil)))))
           ;; Force inside with-open — the CompilationUnits are detached
           ;; from the stream, but a lazy seq would outlive the JarFile.
           doall))))

(def compilation-units
  "Parsed CompilationUnits for the whole sources jar. Parsing 600+ files
   costs a few seconds, and both consumers plus repeated emit runs in one
   process would otherwise pay it each time."
  (memoize parse-jar))
