(ns source-param-names
  "Recover the parameter names that a compiled class file does not carry,
   by reading the declarations out of the JTS sources jar.

   A method with no body compiles without a LocalVariableTable — there is
   nothing to debug — so every method reflected against a JTS interface or
   an abstract declaration would surface as `a1` / `a2` placeholders in the
   .d.ts. The sources jar carries the original names; this module recovers
   them. Methods that do have a body already carry an LVT (JTS is compiled
   with `-g`), so they are not indexed.

   Matching is by [class-fqn method-name arity]. Most JTS abstract methods
   have a unique (name, arity) within their class, so exact type matching
   is not needed. Overloads sharing both name and arity keep their names
   only when every overload spells them the same; otherwise the entry drops
   and the `a1` / `a2` fallback stays.

   The parse comes from `jts-sources/compilation-units`, the same pass
   `javadoc-index` reads, so the jar is opened and parsed once per run."
  (:require [jts-sources :as src])
  (:import [com.github.javaparser.ast CompilationUnit PackageDeclaration]
           [com.github.javaparser.ast.body MethodDeclaration Parameter
            TypeDeclaration]))

(defn- type-declarations
  "Every type declared in `cu`, paired with its fully-qualified name in
   reflection spelling. Nested types join with `$`, the way
   `Class/getName` writes them, so an index key can be compared against a
   reflected class name directly."
  [^CompilationUnit cu]
  (let [pkg    (some-> ^PackageDeclaration (.orElse (.getPackageDeclaration cu) nil)
                       .getNameAsString)
        prefix (if pkg (str pkg ".") "")
        walk   (fn walk [prefix ^TypeDeclaration td]
                 (let [fqn (str prefix (.getNameAsString td))]
                   (cons [fqn td]
                         (->> (.getMembers td)
                              (filter #(instance? TypeDeclaration %))
                              (mapcat #(walk (str fqn "$") %))))))]
    (mapcat #(walk prefix %) (.getTypes cu))))

(defn- bodyless-methods
  "The methods `td` declares with no body: interface methods, and the
   abstract methods of an abstract class. `default` and `static` interface
   methods have a body and are excluded, as are the type's nested types'
   methods — those belong to the nested type's own index entry."
  [^TypeDeclaration td]
  (->> (.getMembers td)
       (filter #(instance? MethodDeclaration %))
       (remove #(.isPresent (.getBody ^MethodDeclaration %)))))

(defn- method-entry
  "[method-name arity [param-name ...]] for one declaration."
  [^MethodDeclaration m]
  (let [names (mapv #(.getNameAsString ^Parameter %) (.getParameters m))]
    [(.getNameAsString m) (count names) names]))

(defn class-index
  "{[method-name arity] [param-name ...]} for one type declaration, or nil
   when it declares no bodyless method."
  [^TypeDeclaration td]
  (let [by-key (group-by (fn [[nm arity _]] [nm arity])
                         (map method-entry (bodyless-methods td)))
        idx    (into {}
                     (keep (fn [[k entries]]
                             ;; One declaration, or several that agree
                             ;; positionally. Disagreeing overloads have no
                             ;; winner without type matching, so drop them.
                             (when (= 1 (count (distinct (map #(nth % 2) entries))))
                               [k (nth (first entries) 2)])))
                     by-key)]
    (when (seq idx) idx)))

(defn build-source-name-index
  "{class-fqn {[method-name arity] [param-name ...]}} over the whole
   sources jar."
  []
  (into {}
        (keep (fn [[fqn td]]
                (when-let [idx (class-index td)]
                  [fqn idx])))
        (mapcat type-declarations (src/compilation-units))))

(defn lookup-param-names
  "Returns [param-name ...] for [class-fqn method-name arity], or nil
   if no source-name entry. `index` is the result of
   `build-source-name-index`."
  [index class-fqn method-name arity]
  (get-in index [class-fqn [method-name arity]]))
