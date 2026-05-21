(ns emit-dts
  "Emit types/wasmts.d.ts from registry.edn.

   Two surfaces derived from the same entries:

     - Functional: `declare global { const wasmts: { geom: {...} } }` —
       one function per `wasmts.geom.<method>` path.
     - OO: `interface Geometry { ... }` — same entries with the first
       argument (the receiver) curried away.

   js/build.mjs is expected to copy types/wasmts.d.ts to dist/ alongside
   wasmts.js so npm consumers pick up the types. (Not wired yet; the
   .d.ts is a generated source artifact under version control until the
   build pipeline links it.)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [codegen-common :refer [geometry-subtypes js-path in-scope? dedup-by-path]]))

(defn- ts-type
  "Map a normalized Java type string to a TypeScript type."
  [t]
  (cond
    (#{"boolean"} t)                            "boolean"
    (#{"int" "long" "short" "byte"
       "double" "float"
       "java.lang.Integer" "java.lang.Long" "java.lang.Short" "java.lang.Byte"
       "java.lang.Double" "java.lang.Float"} t) "number"
    (#{"java.lang.String"} t)                   "string"
    (geometry-subtypes t)                       "Geometry"
    (= t "org.locationtech.jts.geom.Envelope")       "Envelope"
    (= t "org.locationtech.jts.geom.Coordinate")     "Coordinate"
    (= t "org.locationtech.jts.geom.PrecisionModel") "PrecisionModel"
    (= t "org.locationtech.jts.geom.Triangle")       "Triangle"
    (= t "org.locationtech.jts.geom.LineSegment")    "LineSegment"
    (= t "org.locationtech.jts.algorithm.MinimumBoundingCircle") "MinimumBoundingCircle"
    (= t "org.locationtech.jts.algorithm.MinimumDiameter")       "MinimumDiameter"
    (= t "org.locationtech.jts.algorithm.Centroid")              "Centroid"
    (= t "org.locationtech.jts.algorithm.InteriorPointArea")     "InteriorPointArea"
    (= t "org.locationtech.jts.algorithm.InteriorPointLine")     "InteriorPointLine"
    (= t "org.locationtech.jts.algorithm.InteriorPointPoint")    "InteriorPointPoint"
    (= t "org.locationtech.jts.geom.prep.PreparedGeometryFactory") "PreparedGeometryFactory"
    (= t "org.locationtech.jts.geom.prep.PreparedGeometry")      "PreparedGeometry"
    (= t "org.locationtech.jts.operation.buffer.OffsetCurve")    "OffsetCurve"
    (= t "org.locationtech.jts.operation.buffer.BufferOp")       "BufferOp"
    (= t "org.locationtech.jts.operation.distance.DistanceOp")   "DistanceOp"
    (= t "org.locationtech.jts.geom.IntersectionMatrix")         "IntersectionMatrix"
    (= t "org.locationtech.jts.densify.Densifier")               "Densifier"
    (= t "org.locationtech.jts.geom.util.GeometryFixer")         "GeometryFixer"
    (= t "org.locationtech.jts.precision.GeometryPrecisionReducer") "GeometryPrecisionReducer"
    (= t "org.locationtech.jts.geom.CoordinateSequence")         "CoordinateSequence"
    (str/ends-with? t "[]")                     (str (ts-type (subs t 0 (- (count t) 2))) "[]")
    :else                                       (str "any /* " t " */")))

(defn- method-name [{:keys [method]}]
  ;; Ctors are factories on the class, not instance behavior, so `<init>`
  ;; should never reach this OO-surface helper.
  method)

(def ^:private ts-id-pattern #"^[a-zA-Z_$][a-zA-Z0-9_$]*$")

(defn- ts-ident
  "JTS source param names usually pass through as valid TS identifiers, but
   a malformed name (or nil from a partial LVT) falls back to `aN`."
  [name idx]
  (if (and name (re-matches ts-id-pattern name))
    name
    (format "a%d" (inc idx))))

(defn- param-name-at
  "Pick the identifier for arg `idx`. `names` is the `:param-names` vector
   (may be nil for partial coverage); `offset` shifts into it when the
   receiver was prepended at idx 0 for the functional surface."
  [names idx offset]
  (ts-ident (when names (get names (- idx offset))) idx))

(def ^:private field-shapes
  "Shape keywords that install a plain primitive constant on the JS
   namespace via emit_api's `installConstant<X>`. The d.ts emit for
   these is a property, not a function — `INTERSECTION: number;` not
   `INTERSECTION(): number;`."
  #{:static-int-field :static-char-field :static-double-field})

(defn- field-sig
  "Property signature for a static field entry: `<name>: <ts-type>;`.
   char fields render as `string` (the JS install coerces via
   String.fromCharCode); int/double render as `number`."
  [name {:keys [returns]}]
  (let [t (:type returns)]
    (format "%s: %s;"
            name
            (case t
              "char" "string"
              "number"))))

(defn- receiver-ident
  "Name the implicit receiver in the functional form. Uses the simple
   class name lowercased so `Triangle.area(t)` becomes `area(triangle)`
   and `LineSegment.project(ls, ...)` becomes `project(lineSegment, ...)`.
   Falls back to `a1` if the simple name doesn't yield a valid identifier."
  [class-fqn]
  (let [simple (last (str/split class-fqn #"\."))
        ident  (when (seq simple)
                 (str (str/lower-case (subs simple 0 1))
                      (subs simple 1)))]
    (if (and ident (re-matches ts-id-pattern ident))
      ident
      "a1")))

(defn- functional-sig
  "Functional form: `methodName(a1: TypeA, ...): Return;`. The name is
   taken from the JS-path's last segment so ctors render as `createN`
   and `:js-path`-overridden entries pick up their bespoke names.
   Static entries (including ctors) take no implicit receiver; instance
   entries cons their receiver as the first arg — named by the
   receiver class's simple name (lowercased) instead of the `a1`
   fallback."
  [name {:keys [class method params]} {:keys [returns static? param-names]}]
  (let [ret  (ts-type (:type returns))
        ctor? (= method "<init>")
        receiver? (not (or static? ctor?))
        full-params (if receiver? (cons class params) params)
        offset (if receiver? 1 0)
        recv-name (when receiver? (receiver-ident class))
        param-strs (->> full-params
                        (map-indexed
                         (fn [i t]
                           (format "%s: %s"
                                   (if (and receiver? (zero? i))
                                     recv-name
                                     (param-name-at param-names i offset))
                                   (ts-type t)))))]
    (format "%s(%s): %s;"
            name
            (str/join ", " param-strs)
            ret)))

(defn- oo-sig
  "OO form: `methodName(a1: TypeA, ...): Return;`. Receiver curried —
   so numbering restarts at a1 for the first non-receiver argument."
  [{:keys [params] :as k} {:keys [returns param-names]}]
  (let [ret (ts-type (:type returns))
        param-strs (->> params
                        (map-indexed (fn [i t]
                                       (format "%s: %s"
                                               (param-name-at param-names i 0)
                                               (ts-type t)))))]
    (format "%s(%s): %s;"
            (method-name k)
            (str/join ", " param-strs)
            ret)))

(def ^:private file-banner
  "// AUTO-GENERATED by script/emit_dts.clj from registry.edn.
// Do not edit by hand. Run `bb gen:dts` to regenerate.

")

(def ^:private oo-interfaces
  "Each entry maps a JTS class to the TS interface name and brand
   string used for the OO surface. Methods whose receiver matches the
   class (or, for Geometry, any subclass in geometry-subtypes) get
   listed on that interface. Add entries here as new receiver shapes
   land.

   Coordinate is included as a brand-only stub for now; its receiver
   shapes haven't landed but emit_api may reference it as a return
   type already."
  [{:class "org.locationtech.jts.geom.Geometry"  :tsname "Geometry"
    :brand "Geometry"
    :match (fn [c] (geometry-subtypes c))}
   {:class "org.locationtech.jts.geom.Envelope" :tsname "Envelope"
    :brand "Envelope"
    :match (fn [c] (= c "org.locationtech.jts.geom.Envelope"))}
   {:class "org.locationtech.jts.geom.Coordinate" :tsname "Coordinate"
    :brand "Coordinate"
    :match (fn [c] (= c "org.locationtech.jts.geom.Coordinate"))}
   {:class "org.locationtech.jts.geom.PrecisionModel" :tsname "PrecisionModel"
    :brand "PrecisionModel"
    :match (fn [c] (= c "org.locationtech.jts.geom.PrecisionModel"))}
   {:class "org.locationtech.jts.geom.Triangle" :tsname "Triangle"
    :brand "Triangle"
    :match (fn [c] (= c "org.locationtech.jts.geom.Triangle"))}
   {:class "org.locationtech.jts.geom.LineSegment" :tsname "LineSegment"
    :brand "LineSegment"
    :match (fn [c] (= c "org.locationtech.jts.geom.LineSegment"))}
   {:class "org.locationtech.jts.algorithm.MinimumBoundingCircle" :tsname "MinimumBoundingCircle"
    :brand "MinimumBoundingCircle"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.MinimumBoundingCircle"))}
   {:class "org.locationtech.jts.algorithm.MinimumDiameter" :tsname "MinimumDiameter"
    :brand "MinimumDiameter"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.MinimumDiameter"))}
   {:class "org.locationtech.jts.algorithm.Centroid" :tsname "Centroid"
    :brand "Centroid"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.Centroid"))}
   {:class "org.locationtech.jts.algorithm.InteriorPointArea" :tsname "InteriorPointArea"
    :brand "InteriorPointArea"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.InteriorPointArea"))}
   {:class "org.locationtech.jts.algorithm.InteriorPointLine" :tsname "InteriorPointLine"
    :brand "InteriorPointLine"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.InteriorPointLine"))}
   {:class "org.locationtech.jts.algorithm.InteriorPointPoint" :tsname "InteriorPointPoint"
    :brand "InteriorPointPoint"
    :match (fn [c] (= c "org.locationtech.jts.algorithm.InteriorPointPoint"))}
   {:class "org.locationtech.jts.geom.prep.PreparedGeometryFactory" :tsname "PreparedGeometryFactory"
    :brand "PreparedGeometryFactory"
    :match (fn [c] (= c "org.locationtech.jts.geom.prep.PreparedGeometryFactory"))}
   {:class "org.locationtech.jts.geom.prep.PreparedGeometry" :tsname "PreparedGeometry"
    :brand "PreparedGeometry"
    :match (fn [c] (= c "org.locationtech.jts.geom.prep.PreparedGeometry"))}
   {:class "org.locationtech.jts.operation.buffer.OffsetCurve" :tsname "OffsetCurve"
    :brand "OffsetCurve"
    :match (fn [c] (= c "org.locationtech.jts.operation.buffer.OffsetCurve"))}
   {:class "org.locationtech.jts.operation.buffer.BufferOp" :tsname "BufferOp"
    :brand "BufferOp"
    :match (fn [c] (= c "org.locationtech.jts.operation.buffer.BufferOp"))}
   {:class "org.locationtech.jts.operation.distance.DistanceOp" :tsname "DistanceOp"
    :brand "DistanceOp"
    :match (fn [c] (= c "org.locationtech.jts.operation.distance.DistanceOp"))}
   {:class "org.locationtech.jts.geom.IntersectionMatrix" :tsname "IntersectionMatrix"
    :brand "IntersectionMatrix"
    :match (fn [c] (= c "org.locationtech.jts.geom.IntersectionMatrix"))}])

(defn- entries-for-interface [{:keys [match]} entries]
  ;; Ctors are factories on the class, not instance behavior — exclude them
  ;; from the OO receiver interface.
  (filter (fn [[k _]] (and (not= "<init>" (:method k))
                           (match (:class k))))
          entries))

(defn- read-extra-dts []
  ;; manual.edn :extra-dts is a map JS-path -> raw TS signature. Used for
  ;; bespoke @JS exports in API.java not in the registry and for entries
  ;; whose runtime contract differs from what auto-gen would produce.
  ;; Injected as synthetic leaves in the functional path tree only — they
  ;; do NOT contribute to the OO interface surface.
  (let [f (io/file "manual.edn")]
    (if (.exists f)
      (-> f slurp edn/read-string :extra-dts (or {}))
      {})))

(defn- ->path-tree
  "Build a nested map keyed by JS-path segment from resolved entries
   plus the manual.edn :extra-dts supplement. Registry-derived leaves
   are `[k v]` pairs; supplement leaves are `[:raw sig]` so the tree
   walker can render them as-is. Internal nodes are sorted-maps.
   The `wasmts` root segment is dropped — the caller renders that prefix."
  [entries extra-dts]
  (let [base (reduce
               (fn [tree [k v]]
                 (let [path (js-path k (:js-path v))
                       segs (rest (str/split path #"\."))]
                   (assoc-in tree segs [k v])))
               (sorted-map)
               entries)]
    (reduce
      (fn [tree [path sig]]
        (let [segs (rest (str/split path #"\."))]
          (assoc-in tree segs [:raw sig])))
      base
      extra-dts)))

(defn- leaf? [node]
  ;; Two leaf shapes: registry-derived [k v] (both maps) or supplement-derived
  ;; [:raw sig] (keyword + string). Internal nodes are sorted-maps.
  (and (vector? node) (= 2 (count node))
       (or (= :raw (first node))
           (and (map? (first node)) (map? (second node))))))

(defn- emit-tree [tree indent]
  (let [pad (apply str (repeat indent \space))
        sb  (StringBuilder.)]
    ;; assoc-in produces nested hash-maps regardless of the root being a
    ;; sorted-map, so re-sort at every recursion.
    (doseq [[seg node] (sort-by key tree)]
      (if (leaf? node)
        (let [[a b] node]
          (cond
            (= :raw a)
            (.append sb (format "%s%s\n" pad b))

            (field-shapes (:shape b))
            (.append sb (format "%s%s\n" pad (field-sig seg b)))

            :else
            (.append sb (format "%s%s\n" pad (functional-sig seg a b)))))
        (do
          (.append sb (format "%s%s: {\n" pad seg))
          (.append sb (emit-tree node (+ indent 2)))
          (.append sb (format "%s};\n" pad)))))
    (.toString sb)))

(defn- emit-oo-interface [{:keys [tsname brand]} entries]
  (let [sb (StringBuilder.)]
    (.append sb (format "export interface %s {\n" tsname))
    (.append sb "  /** Brand: prevents structural assignment from plain objects. */\n")
    (.append sb (format "  readonly __jtsBrand?: '%s';\n\n" brand))
    (doseq [[k v] (sort-by (fn [[k _]] (method-name k)) entries)]
      (.append sb (format "  %s\n"
                          (if (field-shapes (:shape v))
                            (field-sig (method-name k) v)
                            (oo-sig k v)))))
    (.append sb "}\n\n")
    (.toString sb)))

(defn- emit-file [registry]
  (let [resolved (dedup-by-path (filter in-scope? registry))
        extra    (read-extra-dts)
        tree     (->path-tree resolved extra)
        sb (StringBuilder.)]
    (.append sb file-banner)
    (doseq [iface oo-interfaces]
      (.append sb (emit-oo-interface iface (entries-for-interface iface resolved))))
    (.append sb "declare global {\n")
    (.append sb "  const wasmts: {\n")
    (.append sb (emit-tree tree 4))
    (.append sb "  };\n}\n")
    ;; `export {}` makes the file a module so `declare global` is honored.
    (.append sb "\nexport {};\n")
    (.toString sb)))

(def ^:private output-path "types/wasmts.d.ts")

(defn -main [& _]
  (let [registry (edn/read-string (slurp "registry.edn"))
        file     (emit-file registry)
        in-scope (filter in-scope? registry)]
    (io/make-parents output-path)
    (spit output-path file)
    (println "Wrote" output-path)
    (println "  in-scope shapes:    " (count in-scope) "of" (count registry))))
