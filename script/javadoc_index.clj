(ns javadoc-index
  "Index the JTS javadoc by [class-fqn member-name arity] so registry
   entries can carry the prose that documents them.

   Constants are stored as `{:method \"CAP_ROUND\" :params []}`, so fields
   land in the same key space as zero-arity methods. The registry could not
   tell them apart either.

   Doc values keep the original javadoc markup. Rendering is a per-emitter
   concern: the .d.ts wants markdown, a Java emitter would not."
  (:require [clojure.string :as str]
            [jts-sources :as src])
  (:import [com.github.javaparser.ast.body ConstructorDeclaration CallableDeclaration
            FieldDeclaration TypeDeclaration]
           [com.github.javaparser.javadoc Javadoc JavadocBlockTag]))

(defn- tag-content [^JavadocBlockTag t]
  (let [c (.toText (.getContent t))]
    (when (seq c) c)))

(defn- javadoc->map
  "Flatten a JavaParser Javadoc into an EDN-friendly map, or nil when the
   comment carries neither description nor tags.

   `:params` and `:throws` are vectors of pairs, not maps, because a map
   would not preserve the declaration order a @param list depends on."
  [^Javadoc jd]
  (when jd
    (let [desc  (.toText (.getDescription jd))
          tags  (vec (.getBlockTags jd))
          by    (fn [pred] (filter #(pred (.getTagName ^JavadocBlockTag %)) tags))
          pairs (fn [ts] (vec (keep (fn [^JavadocBlockTag t]
                                      (when-let [n (.orElse (.getName t) nil)]
                                        [n (or (tag-content t) "")]))
                                    ts)))
          params (pairs (by #{"param"}))
          ret    (some-> (first (by #{"return"})) tag-content)
          throws (pairs (by #{"throws" "exception"}))]
      (when (or (seq desc) (seq params) ret (seq throws))
        (cond-> {}
          (seq desc)   (assoc :desc desc)
          (seq params) (assoc :params params)
          ret          (assoc :returns ret)
          (seq throws) (assoc :throws throws))))))

(defn- own-member?
  "JavaParser's `findAll` descends into nested types. Keep only
   declarations whose immediate parent is the type we index, so a nested
   class's members are not attributed to its enclosing class.

   `identical?` and not `=`: JavaParser's `Node.equals` compares subtrees,
   so `=` would walk the full type for each member."
  [^TypeDeclaration td node]
  (identical? td (.orElse (.getParentNode node) nil)))

(defn- type-decls [cu]
  (for [^TypeDeclaration td (.findAll cu TypeDeclaration)
        :let [fqn (.orElse (.getFullyQualifiedName td) nil)]
        :when fqn]
    [fqn td]))

(defn- declared-types
  "Source-level parameter types, reduced to simple names. `List<Geometry>`
   becomes `List` and `Coordinate...` becomes `Coordinate[]`. Used only to
   tell overloads apart, never to generate anything."
  [^CallableDeclaration cd]
  (mapv (fn [p]
          (let [t (-> (.getType p) .asString
                      (str/replace #"<[^>]*>" "")
                      str/trim)
                t (if (.isVarArgs p) (str t "[]") t)]
            (str/replace t #"^.*\." "")))
        (.getParameters cd)))

(defn- callable-entries [cu]
  (for [[fqn ^TypeDeclaration td] (type-decls cu)
        ^CallableDeclaration cd (concat (.getMethods td)
                                        (.findAll td ConstructorDeclaration))
        :when (own-member? td cd)
        :let [doc (javadoc->map (.orElse (.getJavadoc cd) nil))]
        :when doc]
    [[fqn
      (if (instance? ConstructorDeclaration cd) "<init>" (.getNameAsString cd))
      (.size (.getParameters cd))]
     (assoc doc :types (declared-types cd))]))

(defn- field-entries [cu]
  ;; `int A = 1, B = 2;` is one FieldDeclaration sharing one doc comment,
  ;; but each variable gets its own registry entry, so each gets the doc.
  (for [[fqn ^TypeDeclaration td] (type-decls cu)
        ^FieldDeclaration fd (.findAll td FieldDeclaration)
        :when (own-member? td fd)
        :let [doc (javadoc->map (.orElse (.getJavadoc fd) nil))]
        :when doc
        v (.getVariables fd)]
    [[fqn (.getNameAsString v) 0] doc]))

(defn build-index
  "{[class-fqn member-name arity] [doc-map ...]} across the whole sources jar.

   The value is a vector because `[class name arity]` does not identify a
   method: JTS declares 220 same-name same-arity overload pairs. Collapsing
   them would attach one overload's prose to the other, which is worse than
   attaching none. `lookup` picks between them on parameter type."
  []
  (reduce (fn [acc [k doc]] (update acc k (fnil conj []) doc))
          {}
          (mapcat #(concat (callable-entries %) (field-entries %))
                  (src/compilation-units))))

(defn- ancestors-of
  "Superclasses and interfaces of `cls`, nearest first. Breadth-first so a
   direct interface wins over a grandparent class. JTS usually documents
   the interface and leaves the implementation bare."
  [^Class cls]
  (loop [frontier [cls], seen #{}, out []]
    (if (empty? frontier)
      out
      (let [next-gen (for [^Class c frontier
                           ^Class p (cons (.getSuperclass c) (.getInterfaces c))
                           :when (and p (not (seen p)))]
                       p)
            next-gen (vec (distinct next-gen))]
        (recur next-gen (into seen next-gen) (into out next-gen))))))

(defn- simple-type
  "Registry param type reduced to the form `declared-types` produces, for
   comparison against it: `org.locationtech.jts.geom.Coordinate[]` becomes
   `Coordinate[]`.

   Reflection writes a nested type as `PrecisionModel$Type` where the
   source writes `Type`, so the `$` part goes with the package part."
  [t]
  (-> t (str/replace #"^.*\." "") (str/replace #"^.*\$" "")))

(defn- pick-overload
  "Select one of the same-name same-arity declarations by parameter type.
   Returns nil unless exactly one candidate matches by simple name. No
   documentation is better than the documentation of another overload.

   One candidate alone is not sufficient. Only documented declarations
   reach the index, so the sole survivor of `foo(int)` and `foo(String)`
   can be the other overload.

   A field carries no `:types` and shares a key with a zero-arity method
   (`PrecisionModel.gridSize`), so a field matches only as a fallback."
  [candidates params]
  (let [want  (mapv simple-type params)
        typed (filter #(= want (:types %)) candidates)
        hits  (if (seq typed) typed (remove :types candidates))]
    (when (= 1 (count hits))
      (first hits))))

(defn lookup
  "Doc for a registry key, falling back to the nearest ancestor that
   documents the same name and arity.

   An inherited doc carries `:from` set to the declaring class, so the
   rendered `@see` link points at the class that documents the behavior.

   `params` tells same-arity overloads apart. An override has the signature
   it overrides, so inherited lookups compare against the same vector.
   Constructors are never inherited."
  [index class-fqn member-name params]
  (let [arity (count params)
        at    (fn [fqn] (some-> (get index [fqn member-name arity])
                                (pick-overload params)))]
    (or (at class-fqn)
        (when (not= "<init>" member-name)
          (some (fn [^Class a]
                  (when-let [d (at (.getName a))]
                    (assoc d :from (.getName a))))
                (try (ancestors-of (Class/forName class-fqn))
                     ;; A registry class that won't load is already reported
                     ;; by build-registry's own reflection step.
                     (catch Throwable _ nil)))))))

(defn undisambiguated
  "True if JTS documents this name and arity on the entry's class, but
   `pick-overload` cannot say which overload the entry is.

   A nil `lookup` means either no javadoc or javadoc this namespace
   discarded. Only the second is a codegen defect, so `build-registry`
   reports these."
  [index class-fqn member-name params]
  (boolean (and (seq (get index [class-fqn member-name (count params)]))
                (nil? (lookup index class-fqn member-name params)))))
