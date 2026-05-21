(ns source-param-names
  "Parse interface method declarations from JTS source .java files
   to populate parameter names that aren't available in the compiled
   class file's LocalVariableTable.

   JTS interfaces compile without LVT (no method bodies to debug), so
   the registry would otherwise show `a1` / `a2` placeholders in the
   .d.ts for every method reflected against an interface. The sources
   jar (jts-core-X.Y.Z-sources.jar) carries the original parameter
   names; this module recovers them.

   Matching strategy: by [class-fqn method-name arity]. Most JTS
   interface methods have unique (name, arity) within a class, so
   exact type matching isn't needed. Ambiguous overloads (same name,
   same arity) are detected and skipped — the LVT fallback (a1/a2)
   stays for those rare cases."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.jar JarFile JarEntry]))

;; Source-jar lookup path:
;;   ~/.m2/repository/org/locationtech/jts/jts-core/<ver>/jts-core-<ver>-sources.jar
(defn- find-sources-jar
  "Locate the sources jar for JTS 1.20.0 under the local Maven cache.
   Returns nil if not present — callers should fall back gracefully."
  []
  (let [home (System/getProperty "user.home")
        path (str home "/.m2/repository/org/locationtech/jts/jts-core/1.20.0/jts-core-1.20.0-sources.jar")]
    (when (.exists (io/file path))
      path)))

(defn- read-jar-entry
  "Slurp the bytes of an entry within a jar as a UTF-8 string."
  [^JarFile jar entry-name]
  (with-open [is (.getInputStream jar (.getEntry jar entry-name))]
    (slurp is)))

(defn- entry->class-fqn
  "Path like `org/locationtech/jts/geom/prep/PreparedGeometry.java`
   becomes `org.locationtech.jts.geom.prep.PreparedGeometry`."
  [^String path]
  (-> path
      (str/replace #"\.java$" "")
      (str/replace #"/" ".")))

(defn- has-abstract-methods?
  "Heuristic for files worth parsing: `public interface` (every method
   is abstract) OR `abstract class` (some methods may be abstract).
   Concrete classes' method bodies open with `{`; the abstract-method
   regex won't match them, so scanning them is harmless but slow."
  [content]
  (boolean (re-find #"(?m)^(?:public\s+)?(?:interface\s+\w|abstract\s+class\s+\w)" content)))

(defn- strip-comments
  "Remove block /* ... */ and line // comments. Naive: assumes no
   `/*` inside string literals (interfaces don't have method bodies
   so string literals only appear in annotation values, vanishingly
   rare in JTS interfaces)."
  [content]
  (-> content
      (str/replace #"(?s)/\*.*?\*/" "")
      (str/replace #"//.*" "")))

(defn- join-multiline-signatures
  "Glue continuation lines: a `(` at end of line (possibly after
   whitespace) or a `,` at end of line means the method signature
   spans multiple lines. Joins them onto one line for easier regex
   match."
  [content]
  (-> content
      ;; `(` followed by newline+whitespace → `(`
      (str/replace #"\(\s*\n\s*" "(")
      ;; `,` followed by newline+whitespace → `, `
      (str/replace #",\s*\n\s*" ", ")
      ;; newline + whitespace + `)` → `)`
      (str/replace #"\n\s*\)" ")")))

(def ^:private method-decl-pattern
  ;; Matches abstract method declarations:
  ;;   [modifiers] [generic-spec] retType methodName(paramList) [throws ...];
  ;; Captures method-name (group 1) and param-list (group 2).
  ;; Limitation: returns containing generics with `,` (Map<K, V>) inside
  ;; aren't fully supported — return type regex stops at `(`. Param list
  ;; regex similarly assumes no `(` / `)` inside params (no method-ref
  ;; default values, which interfaces don't have anyway).
  #"(?m)^\s*(?:public\s+|abstract\s+|default\s+|static\s+)*(?:<[^>]+>\s+)?[\w\.<>,\[\]\s\?]+?\s+(\w+)\s*\(([^()]*)\)\s*(?:throws\s+[\w\.,\s]+)?\s*;")

(defn- parse-param
  "Parse a single parameter declaration `Type name` (with possible
   annotations, final modifier, varargs). Returns the name only;
   the type is irrelevant for our matching strategy."
  [raw]
  (let [;; Strip annotations like `@Nullable` (with possible parenthesized args)
        norm (-> raw str/trim
                 (str/replace #"@\w+(\s*\([^)]*\))?\s*" ""))
        ;; Strip leading `final ` modifier
        norm (str/replace norm #"^final\s+" "")
        ;; Strip trailing `...` (varargs)
        norm (str/replace norm #"\.\.\.\s*$" "")
        toks (str/split norm #"\s+")]
    (when (>= (count toks) 2)
      (last toks))))

(defn- parse-method
  "Match group: [method-name param-raw]. Returns [method-name arity param-names]."
  [[method-name param-raw]]
  (let [trimmed (str/trim (or param-raw ""))
        params (if (str/blank? trimmed)
                 []
                 (->> (str/split trimmed #",")
                      (mapv parse-param)))
        ;; If any param's name didn't parse cleanly, the whole match is suspect.
        all-named? (and (every? some? params)
                        (every? #(re-matches #"\w+" %) params))]
    (when all-named?
      [method-name (count params) (vec params)])))

(defn parse-interface-methods
  "Given the source content of a .java file, return
   {[method-name arity] [param-name ...]}.

   For overloaded methods that share both name and arity (e.g.
   Geometry's 4 apply(*Filter) variants) the index keeps the names
   when EVERY overload uses the same name at each position. Mixed
   names across overloads drop the entry — we can't pick a winner
   without type matching."
  [content]
  (let [content     (-> content strip-comments join-multiline-signatures)
        raw-methods (->> (re-seq method-decl-pattern content)
                         (map (fn [[_ name param-raw]]
                                (parse-method [name param-raw])))
                         (filter some?))
        by-key      (group-by (fn [[name arity _names]] [name arity]) raw-methods)]
    (into {}
          (keep (fn [[key matches]]
                  (cond
                    (= 1 (count matches))
                    (let [[_ _ names] (first matches)]
                      [key names])

                    ;; Multiple overloads share [name, arity]. Keep the
                    ;; names iff every overload agrees positionally.
                    (= 1 (count (distinct (map #(nth % 2) matches))))
                    (let [[_ _ names] (first matches)]
                      [key names]))))
          by-key)))

(defn build-source-name-index
  "Walk the sources jar, parse every interface file's method
   declarations, return {class-fqn {[method-name arity] [param-names]}}.
   Non-interface files are skipped."
  []
  (when-let [jar-path (find-sources-jar)]
    (with-open [jar (JarFile. jar-path)]
      (let [entries (->> (enumeration-seq (.entries jar))
                         (filter #(.endsWith (.getName ^JarEntry %) ".java"))
                         (map #(.getName ^JarEntry %)))]
        (into {}
              (keep (fn [entry-name]
                      (let [content (read-jar-entry jar entry-name)]
                        (when (has-abstract-methods? content)
                          (let [class-fqn (entry->class-fqn entry-name)
                                methods   (parse-interface-methods content)]
                            (when (seq methods)
                              [class-fqn methods]))))))
              entries)))))

(defn lookup-param-names
  "Returns [param-name ...] for [class-fqn method-name arity], or nil
   if no source-name entry. `index` is the result of
   `build-source-name-index`."
  [index class-fqn method-name arity]
  (get-in index [class-fqn [method-name arity]]))
