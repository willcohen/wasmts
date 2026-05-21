(ns build-registry
  "Reflect the configured JTS classes, classify each member by shape,
   validate the result against a Malli meta-schema, and write registry.edn.

   One process, no intermediate disk artifact. The reflection step
   produces a value that the classifier consumes directly.

   Public entry points:

     (-main)            ; default: jts-classes.edn + manual.edn -> registry.edn
     (dump-reflection)  ; pretty-print reflection output to stdout (debug)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [param-names :as pn]
   [source-param-names :as spn])
  (:import
   [java.lang.reflect Constructor Field Method Modifier]))

(declare concrete-class? no-checked-throws? apply-elem-type-hint)

;; Type normalization

(defn- normalize-type
  "Render a java.lang.Class to source form.

   - Primitives stay primitive (\"double\" not \"java.lang.Double\").
   - Arrays use source form (\"double[]\", \"org.locationtech.jts.geom.Coordinate[]\").
   - Classes use the dotted fully-qualified name."
  [^Class cls]
  (cond
    (.isPrimitive cls) (.getName cls)
    (.isArray cls)     (str (normalize-type (.getComponentType cls)) "[]")
    :else              (.getName cls)))

;; Reflection

;; capture generic types for option E (java.util.List /
;; Collection element-type-aware codegen). java.lang.reflect.Type is the
;; sealed interface returned by getGenericReturnType / getGenericParameter
;; Types. We render it to a string form parallel to `normalize-type`:
;;   - Class       → same as normalize-type (erased)
;;   - Parameterized "java.util.List" with arg "org.locationtech.jts.geom.Geometry"
;;                 → "java.util.List<org.locationtech.jts.geom.Geometry>"
;;   - Other (TypeVariable, WildcardType, GenericArrayType) → erased fallback
;; Stored on the VALUE side only (`:generic-params`, `:returns
;; :generic-type`). Registry keys keep erased `:params` so existing
;; manual.edn :skip entries and JS-path lookups stay stable.
(defn- normalize-generic-type
  [^java.lang.reflect.Type t]
  (cond
    (instance? Class t)
    (normalize-type t)

    (instance? java.lang.reflect.ParameterizedType t)
    (let [raw  (normalize-type (.getRawType ^java.lang.reflect.ParameterizedType t))
          args (.getActualTypeArguments ^java.lang.reflect.ParameterizedType t)]
      (str raw "<"
           (str/join "," (map normalize-generic-type args))
           ">"))

    :else
    ;; TypeVariable / WildcardType / GenericArrayType — these always
    ;; appear alongside an erased Class<?> we could fall back to via
    ;; getRawType, but the call site has the erased Type[] from
    ;; getParameterTypes already and can stitch the result. Returning a
    ;; "?" sentinel here lets the consumer detect "no generic info
    ;; usable" and drop back to the erased form.
    "?"))

(defn- generic-params-vec
  "Return a vector of generic-type strings (parallel to :params) for a
   Method or Constructor. When the generic-info equals the erased form
   for every position, returns nil so callers can skip the field."
  [generic-types erased-types]
  (let [pairs (map vector generic-types erased-types)
        gens  (mapv (fn [[g e]] (or (when (not= g "?") g)
                                     (normalize-type e)))
                    pairs)]
    (when (some (fn [[g e]] (and g e (not= g (normalize-type e)))) pairs)
      gens)))

(defn- method-key [^Method m]
  {:class  (.getName (.getDeclaringClass m))
   :method (.getName m)
   :params (mapv normalize-type (.getParameterTypes m))})

(defn- method-value [^Method m param-names]
  (let [erased-params  (.getParameterTypes m)
        generic-params (mapv normalize-generic-type (.getGenericParameterTypes m))
        gp-vec         (generic-params-vec generic-params erased-params)
        erased-ret     (.getReturnType m)
        generic-ret    (normalize-generic-type (.getGenericReturnType m))
        generic-ret    (when (and (not= generic-ret "?")
                                  (not= generic-ret (normalize-type erased-ret)))
                         generic-ret)]
    (cond-> {:static?  (Modifier/isStatic (.getModifiers m))
             :returns  (cond-> {:type (normalize-type erased-ret)}
                         generic-ret (assoc :generic-type generic-ret))
             :throws   (mapv normalize-type (.getExceptionTypes m))
             :varargs? (.isVarArgs m)}
      gp-vec      (assoc :generic-params gp-vec)
      param-names (assoc :param-names param-names))))

(defn- constructor-key [^Constructor c]
  {:class  (.getName (.getDeclaringClass c))
   :method "<init>"
   :params (mapv normalize-type (.getParameterTypes c))})

(defn- constructor-value [^Constructor c param-names]
  (let [erased-params  (.getParameterTypes c)
        generic-params (mapv normalize-generic-type (.getGenericParameterTypes c))
        gp-vec         (generic-params-vec generic-params erased-params)]
    (cond-> {:static?  true
             :returns  {:type (.getName (.getDeclaringClass c))}
             :throws   (mapv normalize-type (.getExceptionTypes c))
             :varargs? (.isVarArgs c)}
      gp-vec      (assoc :generic-params gp-vec)
      param-names (assoc :param-names param-names))))

;; Public static final fields participate in the registry as zero-arg key
;; entries with the field's name in the :method slot. The
;; :static-int-field / :static-char-field / :static-double-field shapes
;; install them as plain constants on the target JS namespace
;; (Dimension.P, OverlayNG.INTERSECTION, Memory.KB, etc.); the field's
;; value lives in the entry under :value so the emitter renders it
;; without re-reflecting.
;;
;; Filter to public-static-final primitive fields whose name is
;; UPPER_SNAKE_CASE (Java convention for user-visible constants). Single
;; uppercase letters (Dimension.P, Coordinate.X) qualify too. Lowercase
;; or mixed-case fields are JTS-internal storage that shouldn't surface
;; in the JS namespace.
(def ^:private const-supported-types
  #{"int" "char" "double"})

(defn- constant-field?
  [^Field f]
  (let [m (.getModifiers f)]
    (and (Modifier/isPublic m)
         (Modifier/isStatic m)
         (Modifier/isFinal m)
         (.isPrimitive (.getType f))
         (const-supported-types (normalize-type (.getType f)))
         (re-matches #"[A-Z][A-Z0-9_]*" (.getName f)))))

(defn- field-key [^Field f]
  {:class  (.getName (.getDeclaringClass f))
   :method (.getName f)
   :params []})

(defn- field-value [^Field f]
  (let [t (.getType f)
        type-name (normalize-type t)
        v (try (.get f nil) (catch Throwable _ nil))]
    (cond-> {:static?  true
             :returns  {:type type-name}
             :throws   []
             :varargs? false
             :field?   true}
      ;; Store the runtime value. char arrives as a Character; coerce
      ;; to int so the EDN stays representation-stable. double stays
      ;; double; int stays int.
      (and v (= "int" type-name))    (assoc :value (int v))
      (and v (= "char" type-name))   (assoc :value (int (char v)))
      (and v (= "double" type-name)) (assoc :value (double v)))))

(defn- public? [^java.lang.reflect.Member m]
  (Modifier/isPublic (.getModifiers m)))

(defn- real-method?
  "Drop JVM-synthesised bridge methods. Covariant-return overrides like
   `Polygon.reverse() : Polygon` come back from `getDeclaredMethods` paired
   with a synthetic bridge `Polygon.reverse() : Geometry`; both share the
   same (class, method, params) key but differ in return type, and which
   one wins map dedup depends on JVM iteration order — non-deterministic
   across runs."
  [^Method m]
  (not (.isBridge m)))

(defn- reflect-one
  "Reflect a single class. Returns a map of registry entries keyed by
   [class+method+params] for its declared public methods and constructors.
   Inherited methods are not included — subclasses get their own entries
   only if they declare new methods.

   Param names come from the class file's `LocalVariableTable` (JTS is
   compiled with `-g`). Interfaces have no LVT (no method bodies), so
   their methods would surface as `a1` / `a2` placeholders downstream;
   `source-idx` (built from the JTS sources jar) fills that gap. The
   source-side fallback matches by `[method-name arity]` within the
   declaring class — sufficient for unique signatures, no-op for
   ambiguous overloads."
  [^Class cls source-idx]
  (let [class-fqn     (.getName cls)
        names-by-desc (pn/param-names-for class-fqn)
        from-source   (fn [^String method-name n-params]
                        (spn/lookup-param-names source-idx class-fqn method-name n-params))
        method-names  (fn [^Method m]
                        (let [lvt (get names-by-desc [(.getName m)
                                                      (pn/method-descriptor m)])]
                          (cond
                            (pn/fully-named? lvt) lvt
                            :else (from-source (.getName m) (.getParameterCount m)))))
        ctor-names    (fn [^Constructor c]
                        (let [lvt (get names-by-desc ["<init>" (pn/ctor-descriptor c)])]
                          (cond
                            (pn/fully-named? lvt) lvt
                            :else (from-source "<init>" (.getParameterCount c)))))
        methods       (->> (.getDeclaredMethods cls)
                           (filter public?)
                           (filter real-method?)
                           (map (juxt method-key #(method-value % (method-names %)))))
        ctors         (->> (.getDeclaredConstructors cls)
                           (filter public?)
                           (map (juxt constructor-key #(constructor-value % (ctor-names %)))))
        ;; Surface every UPPER_SNAKE_CASE public-static-final primitive
        ;; field as a registry entry. `constant-field?` enforces both
        ;; the modifier set and the name convention so internal storage
        ;; fields (`isCCW`, `epsilon`) don't slip into the JS namespace.
        fields        (->> (.getDeclaredFields cls)
                           (filter constant-field?)
                           (map (juxt field-key field-value)))]
    (into {} (concat methods ctors fields))))

(defn reflect-classes
  "Reflect every class in the supplied seq. Returns one merged map of
   registry entries. Classes that fail to load (deleted, renamed, or
   misspelled in jts-classes.edn) are warned about on stderr and
   skipped — the pipeline shouldn't die because a class name is
   stale.

   Builds the JTS sources-jar interface-name index once and threads it
   through `reflect-one` so the source-fallback for LVT-less interface
   methods is computed exactly once per run.

   Pure other than the stderr logging."
  [class-names]
  (let [source-idx (spn/build-source-name-index)]
    (transduce (map (fn [n]
                      (try
                        (reflect-one (Class/forName n) source-idx)
                        (catch ClassNotFoundException _
                          (binding [*out* *err*]
                            (println "  warn: class not found (skipped):" n))
                          {}))))
               merge
               {}
               class-names)))

;; Shape classification

(defn- jts-geometry?
  "Heuristic: does this type denote some org.locationtech.jts.geom.* class
   that represents a Geometry? Used to lump Point/Polygon/LineString/etc.
   under the generic :geom->bool/:geom->geom shape categories."
  [t]
  (or (= t "org.locationtech.jts.geom.Geometry")
      (#{"org.locationtech.jts.geom.Point"
         "org.locationtech.jts.geom.LineString"
         "org.locationtech.jts.geom.LinearRing"
         "org.locationtech.jts.geom.Polygon"
         "org.locationtech.jts.geom.MultiPoint"
         "org.locationtech.jts.geom.MultiLineString"
         "org.locationtech.jts.geom.MultiPolygon"
         "org.locationtech.jts.geom.GeometryCollection"}
       t)))

(def ^:private envelope-class        "org.locationtech.jts.geom.Envelope")
(def ^:private precision-model-class "org.locationtech.jts.geom.PrecisionModel")
(def ^:private coordinate-class      "org.locationtech.jts.geom.Coordinate")
(def ^:private lineseg-class         "org.locationtech.jts.geom.LineSegment")
(def ^:private geom-factory-class    "org.locationtech.jts.geom.GeometryFactory")
(def ^:private point-class           "org.locationtech.jts.geom.Point")
(def ^:private linestring-class      "org.locationtech.jts.geom.LineString")
(def ^:private js-wrappers-built-from-geometry
  "Subset of js-wrapper-classes whose only constructor takes a single
   Geometry and whose only instance method is a no-arg getter returning
   Coordinate (Centroid + the three InteriorPoint variants). Generalised
   via :ctor-wrapped-from-geom + :wrapped->coord shapes — dispatch derives
   extract<SimpleName> at emit time from the class. Adding a class here
   turns its `<init>(Geometry)` into :ctor-wrapped-from-geom and its
   no-arg Coord-returning instance method into :wrapped->coord; the class
   also needs a js-wrapper-classes entry in emit_api.clj so the
   createJS<X> + extract<X> helpers emit."
  #{"org.locationtech.jts.algorithm.Centroid"
    "org.locationtech.jts.algorithm.InteriorPointArea"
    "org.locationtech.jts.algorithm.InteriorPointLine"
    "org.locationtech.jts.algorithm.InteriorPointPoint"})

(def ^:private auto-ctor-classes
  "Wrapped classes whose ctors match the generic :ctor-wrapped template
   (createJS<Helper>(new <FQN>(coerce-args))). Dispatch derives helper via
   js-helper-for at emit time (handles MBC / MDiam shorthand exceptions).

   Excluded — kept on per-class :ctor-* shapes:
     - Envelope / PrecisionModel / IntersectionMatrix (attach-override
       wrappers; their createJS<X> takes extra params or rebuilds setters
       mid-construction; the shape-specific ctor branches stay).
     - Coordinate (JS-literal, kept on :ctor-coord for the
       arity-specific JS-literal contract).
     - GeometryFactory (extractGeometryFactory not in
       emit_api/js-wrapper-classes today; kept on :ctor-gf)."
  #{"org.locationtech.jts.algorithm.Centroid"
    "org.locationtech.jts.algorithm.ConvexHull"
    "org.locationtech.jts.algorithm.HCoordinate"
    "org.locationtech.jts.algorithm.InteriorPointArea"
    "org.locationtech.jts.algorithm.InteriorPointLine"
    "org.locationtech.jts.algorithm.InteriorPointPoint"
    "org.locationtech.jts.algorithm.LineIntersector"
    "org.locationtech.jts.algorithm.MinimumBoundingCircle"
    "org.locationtech.jts.algorithm.MinimumDiameter"
    "org.locationtech.jts.algorithm.PointLocator"
    "org.locationtech.jts.algorithm.RayCrossingCounter"
    "org.locationtech.jts.algorithm.RectangleLineIntersector"
    "org.locationtech.jts.algorithm.RobustLineIntersector"
    "org.locationtech.jts.algorithm.construct.LargestEmptyCircle"
    "org.locationtech.jts.algorithm.construct.MaximumInscribedCircle"
    "org.locationtech.jts.algorithm.distance.DiscreteFrechetDistance"
    "org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance"
    "org.locationtech.jts.algorithm.distance.PointPairDistance"
    "org.locationtech.jts.algorithm.hull.ConcaveHull"
    "org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons"
    "org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator"
    "org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator"
    "org.locationtech.jts.algorithm.match.AreaSimilarityMeasure"
    "org.locationtech.jts.algorithm.match.FrechetSimilarityMeasure"
    "org.locationtech.jts.algorithm.match.HausdorffSimilarityMeasure"
    "org.locationtech.jts.awt.GeometryCollectionShape"
    "org.locationtech.jts.awt.IdentityPointTransformation"
    "org.locationtech.jts.awt.PolygonShape"
    "org.locationtech.jts.awt.ShapeWriter"
    "org.locationtech.jts.coverage.CoverageGapFinder"
    "org.locationtech.jts.coverage.CoveragePolygonValidator"
    "org.locationtech.jts.coverage.CoverageSimplifier"
    "org.locationtech.jts.coverage.CoverageValidator"
    "org.locationtech.jts.densify.Densifier"
    "org.locationtech.jts.dissolve.LineDissolver"
    "org.locationtech.jts.edgegraph.EdgeGraph"
    "org.locationtech.jts.edgegraph.EdgeGraphBuilder"
    "org.locationtech.jts.edgegraph.HalfEdge"
    "org.locationtech.jts.edgegraph.MarkHalfEdge"
    "org.locationtech.jts.geom.CoordinateList"
    "org.locationtech.jts.geom.CoordinateSequenceComparator"
    "org.locationtech.jts.geom.GeometryCollectionIterator"
    "org.locationtech.jts.geom.LineSegment"
    "org.locationtech.jts.geom.OctagonalEnvelope"
    "org.locationtech.jts.geom.TopologyException"
    "org.locationtech.jts.geom.Triangle"
    "org.locationtech.jts.geom.impl.CoordinateArraySequence"
    "org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory"
    "org.locationtech.jts.geom.prep.PreparedGeometryFactory"
    "org.locationtech.jts.geom.util.AffineTransformation"
    "org.locationtech.jts.geom.util.AffineTransformationBuilder"
    "org.locationtech.jts.geom.util.GeometryCombiner"
    "org.locationtech.jts.geom.util.GeometryEditor"
    "org.locationtech.jts.geom.util.GeometryFixer"
    "org.locationtech.jts.geom.util.GeometryTransformer"
    "org.locationtech.jts.geom.util.ShortCircuitedGeometryVisitor"
    "org.locationtech.jts.geom.util.SineStarFactory"
    "org.locationtech.jts.index.ArrayListVisitor"
    "org.locationtech.jts.index.VertexSequencePackedRtree"
    "org.locationtech.jts.index.bintree.Bintree"
    "org.locationtech.jts.index.chain.MonotoneChainOverlapAction"
    "org.locationtech.jts.index.chain.MonotoneChainSelectAction"
    "org.locationtech.jts.index.hprtree.HPRtree"
    "org.locationtech.jts.index.hprtree.HilbertEncoder"
    "org.locationtech.jts.index.intervalrtree.IntervalRTreeNode"
    "org.locationtech.jts.index.intervalrtree.SortedPackedIntervalRTree"
    "org.locationtech.jts.index.kdtree.KdTree"
    "org.locationtech.jts.index.quadtree.DoubleBits"
    "org.locationtech.jts.index.quadtree.Key"
    "org.locationtech.jts.index.quadtree.Node"
    "org.locationtech.jts.index.quadtree.Quadtree"
    "org.locationtech.jts.index.strtree.AbstractNode"
    "org.locationtech.jts.index.strtree.AbstractSTRtree"
    "org.locationtech.jts.index.strtree.BoundablePairDistanceComparator"
    "org.locationtech.jts.index.strtree.GeometryItemDistance"
    "org.locationtech.jts.index.strtree.SIRtree"
    "org.locationtech.jts.index.strtree.STRtree"
    "org.locationtech.jts.index.sweepline.SweepLineIndex"
    "org.locationtech.jts.index.sweepline.SweepLineInterval"
    "org.locationtech.jts.io.ByteArrayInStream"
    "org.locationtech.jts.io.ByteOrderDataInStream"
    "org.locationtech.jts.io.OrdinateFormat"
    "org.locationtech.jts.io.WKBReader"
    "org.locationtech.jts.io.WKBWriter"
    "org.locationtech.jts.io.WKTReader"
    "org.locationtech.jts.io.WKTWriter"
    "org.locationtech.jts.io.geojson.GeoJsonReader"
    "org.locationtech.jts.io.geojson.GeoJsonWriter"
    "org.locationtech.jts.io.gml2.GMLWriter"
    "org.locationtech.jts.io.kml.KMLReader"
    "org.locationtech.jts.io.kml.KMLWriter"
    "org.locationtech.jts.io.twkb.TWKBReader"
    "org.locationtech.jts.io.twkb.TWKBWriter"
    "org.locationtech.jts.linearref.LengthIndexedLine"
    "org.locationtech.jts.linearref.LengthLocationMap"
    "org.locationtech.jts.linearref.LinearIterator"
    "org.locationtech.jts.linearref.LinearLocation"
    "org.locationtech.jts.linearref.LocationIndexedLine"
    "org.locationtech.jts.math.DD"
    "org.locationtech.jts.math.Plane3D"
    "org.locationtech.jts.math.Vector2D"
    "org.locationtech.jts.math.Vector3D"
    "org.locationtech.jts.operation.BoundaryOp"
    "org.locationtech.jts.operation.IsSimpleOp"
    "org.locationtech.jts.operation.buffer.BufferCurveSetBuilder"
    "org.locationtech.jts.operation.buffer.BufferInputLineSimplifier"
    "org.locationtech.jts.operation.buffer.BufferOp"
    "org.locationtech.jts.operation.buffer.BufferParameters"
    "org.locationtech.jts.operation.buffer.OffsetCurve"
    "org.locationtech.jts.operation.buffer.OffsetCurveBuilder"
    "org.locationtech.jts.operation.buffer.validate.BufferCurveMaximumDistanceFinder"
    "org.locationtech.jts.operation.buffer.validate.BufferDistanceValidator"
    "org.locationtech.jts.operation.buffer.validate.BufferResultValidator"
    "org.locationtech.jts.operation.distance.DistanceOp"
    "org.locationtech.jts.operation.distance.FacetSequence"
    "org.locationtech.jts.operation.distance.GeometryLocation"
    "org.locationtech.jts.operation.distance.IndexedFacetDistance"
    "org.locationtech.jts.operation.distance3d.Distance3DOp"
    "org.locationtech.jts.operation.linemerge.LineMerger"
    "org.locationtech.jts.operation.linemerge.LineSequencer"
    "org.locationtech.jts.operation.overlay.OverlayNodeFactory"
    "org.locationtech.jts.operation.overlay.OverlayOp"
    "org.locationtech.jts.operation.overlay.snap.GeometrySnapper"
    "org.locationtech.jts.operation.overlay.snap.LineStringSnapper"
    "org.locationtech.jts.operation.overlay.snap.SnapIfNeededOverlayOp"
    "org.locationtech.jts.operation.overlay.snap.SnapOverlayOp"
    "org.locationtech.jts.operation.overlay.validate.FuzzyPointLocator"
    "org.locationtech.jts.operation.overlay.validate.OffsetPointGenerator"
    "org.locationtech.jts.operation.overlay.validate.OverlayResultValidator"
    "org.locationtech.jts.operation.overlayng.FastOverlayFilter"
    "org.locationtech.jts.operation.overlayng.LineLimiter"
    "org.locationtech.jts.operation.overlayng.OverlayNG"
    "org.locationtech.jts.operation.overlayng.RingClipper"
    "org.locationtech.jts.operation.polygonize.Polygonizer"
    "org.locationtech.jts.operation.predicate.RectangleContains"
    "org.locationtech.jts.operation.predicate.RectangleIntersects"
    "org.locationtech.jts.operation.relate.EdgeEndBuilder"
    "org.locationtech.jts.operation.relate.RelateNodeGraph"
    "org.locationtech.jts.operation.relate.RelateOp"
    "org.locationtech.jts.operation.relateng.RelateNG"
    "org.locationtech.jts.operation.union.OverlapUnion"
    "org.locationtech.jts.operation.union.UnaryUnionOp"
    "org.locationtech.jts.operation.union.UnionInteracting"
    "org.locationtech.jts.operation.valid.IsValidOp"
    "org.locationtech.jts.operation.valid.RepeatedPointTester"
    "org.locationtech.jts.operation.valid.TopologyValidationError"
    "org.locationtech.jts.precision.CommonBits"
    "org.locationtech.jts.precision.CommonBitsOp"
    "org.locationtech.jts.precision.CommonBitsRemover"
    "org.locationtech.jts.precision.CoordinatePrecisionReducerFilter"
    "org.locationtech.jts.precision.GeometryPrecisionReducer"
    "org.locationtech.jts.precision.MinimumClearance"
    "org.locationtech.jts.precision.PrecisionReducerCoordinateOperation"
    "org.locationtech.jts.precision.SimpleGeometryPrecisionReducer"
    "org.locationtech.jts.precision.SimpleMinimumClearance"
    "org.locationtech.jts.shape.fractal.KochSnowflakeBuilder"
    "org.locationtech.jts.shape.fractal.SierpinskiCarpetBuilder"
    "org.locationtech.jts.shape.random.RandomPointsBuilder"
    "org.locationtech.jts.shape.random.RandomPointsInGridBuilder"
    "org.locationtech.jts.simplify.LinkedLine"
    "org.locationtech.jts.simplify.PolygonHullSimplifier"
    "org.locationtech.jts.simplify.TopologyPreservingSimplifier"
    "org.locationtech.jts.simplify.VWSimplifier"
    "org.locationtech.jts.triangulate.ConformingDelaunayTriangulationBuilder"
    "org.locationtech.jts.triangulate.ConstraintEnforcementException"
    "org.locationtech.jts.triangulate.ConstraintVertex"
    "org.locationtech.jts.triangulate.DelaunayTriangulationBuilder"
    "org.locationtech.jts.triangulate.MidpointSplitPointFinder"
    "org.locationtech.jts.triangulate.NonEncroachingSplitPointFinder"
    "org.locationtech.jts.triangulate.Segment"
    "org.locationtech.jts.triangulate.SplitSegment"
    "org.locationtech.jts.triangulate.VertexTaggedGeometryDataMapper"
    "org.locationtech.jts.triangulate.VoronoiDiagramBuilder"
    "org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator"
    "org.locationtech.jts.triangulate.polygon.PolygonHoleJoiner"
    "org.locationtech.jts.triangulate.polygon.PolygonTriangulator"
    "org.locationtech.jts.triangulate.quadedge.EdgeConnectedTriangleTraversal"
    "org.locationtech.jts.triangulate.quadedge.LocateFailureException"
    "org.locationtech.jts.triangulate.quadedge.QuadEdge"
    "org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision"
    "org.locationtech.jts.triangulate.quadedge.Vertex"
    "org.locationtech.jts.triangulate.tri.Tri"
    "org.locationtech.jts.util.CoordinateArrayFilter"
    "org.locationtech.jts.util.CoordinateCountFilter"
    "org.locationtech.jts.util.GeometricShapeFactory"
    "org.locationtech.jts.util.IntArrayList"
    "org.locationtech.jts.util.ObjectCounter"
    "org.locationtech.jts.util.PriorityQueue"
    "org.locationtech.jts.util.Stopwatch"
    "org.locationtech.jts.util.UniqueCoordinateArrayFilter"})


;; Template-engine classifier sets
;; Three sets gate the generic :receiver-call / :static-call / :ctor
;; classifier rules hoisted to the TOP of classify-shape's cond. For a
;; class in one of these sets, the hoisted rule fires first for any
;; method whose params and return are coercible; the per-shape rules
;; below the hoisted block are reached only by methods on classes NOT
;; in any templated-* set, or by methods that fall through the
;; coercibility guards or manual-classification-overrides exemptions.

(def ^:private instance-dispatch-classes
  "Classes whose instance methods route through the generic
   :receiver-call dispatch template. js-helper-for resolves the receiver's
   extract<Helper>; strip-api-prefix-from-helpers strips the API. prefix for
   classes in js-wrapper-helper-names.

   STRtree / LineMerger are NOT in this set because their only in-scope
   methods have non-coercible param or return types (Object, List) that
   the generic template can't express; they stay on per-shape rules.

   Per-method bespoke semantics on a class IN this set (callback
   wrapping, polymorphic dispatch, OrNull wrapping, parse-exception
   translation) are exempted via manual-classification-overrides + a
   per-shape rule lower in the cond."
  #{"org.locationtech.jts.algorithm.Centroid"
    "org.locationtech.jts.algorithm.ConvexHull"
    "org.locationtech.jts.algorithm.HCoordinate"
    "org.locationtech.jts.algorithm.InteriorPointArea"
    "org.locationtech.jts.algorithm.InteriorPointLine"
    "org.locationtech.jts.algorithm.InteriorPointPoint"
    "org.locationtech.jts.algorithm.LineIntersector"
    "org.locationtech.jts.algorithm.MinimumBoundingCircle"
    "org.locationtech.jts.algorithm.MinimumDiameter"
    "org.locationtech.jts.algorithm.PointLocator"
    "org.locationtech.jts.algorithm.RayCrossingCounter"
    "org.locationtech.jts.algorithm.RectangleLineIntersector"
    "org.locationtech.jts.algorithm.RobustLineIntersector"
    "org.locationtech.jts.algorithm.construct.LargestEmptyCircle"
    "org.locationtech.jts.algorithm.construct.MaximumInscribedCircle"
    "org.locationtech.jts.algorithm.distance.DiscreteFrechetDistance"
    "org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance"
    "org.locationtech.jts.algorithm.distance.PointPairDistance"
    "org.locationtech.jts.algorithm.hull.ConcaveHull"
    "org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons"
    "org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator"
    "org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator"
    "org.locationtech.jts.algorithm.match.AreaSimilarityMeasure"
    "org.locationtech.jts.algorithm.match.FrechetSimilarityMeasure"
    "org.locationtech.jts.algorithm.match.HausdorffSimilarityMeasure"
    "org.locationtech.jts.awt.GeometryCollectionShape"
    "org.locationtech.jts.awt.IdentityPointTransformation"
    "org.locationtech.jts.awt.PolygonShape"
    "org.locationtech.jts.awt.ShapeWriter"
    "org.locationtech.jts.coverage.CoverageGapFinder"
    "org.locationtech.jts.coverage.CoveragePolygonValidator"
    "org.locationtech.jts.coverage.CoverageSimplifier"
    "org.locationtech.jts.coverage.CoverageValidator"
    "org.locationtech.jts.densify.Densifier"
    "org.locationtech.jts.dissolve.LineDissolver"
    "org.locationtech.jts.edgegraph.EdgeGraph"
    "org.locationtech.jts.edgegraph.EdgeGraphBuilder"
    "org.locationtech.jts.edgegraph.HalfEdge"
    "org.locationtech.jts.edgegraph.MarkHalfEdge"
    "org.locationtech.jts.geom.Coordinate"
    "org.locationtech.jts.geom.CoordinateList"
    "org.locationtech.jts.geom.CoordinateSequence"
    "org.locationtech.jts.geom.CoordinateSequenceComparator"
    "org.locationtech.jts.geom.Envelope"
    "org.locationtech.jts.geom.Geometry"
    "org.locationtech.jts.geom.GeometryCollectionIterator"
    "org.locationtech.jts.geom.GeometryFactory"
    "org.locationtech.jts.geom.IntersectionMatrix"
    "org.locationtech.jts.geom.LineSegment"
    "org.locationtech.jts.geom.LineString"
    "org.locationtech.jts.geom.OctagonalEnvelope"
    "org.locationtech.jts.geom.Point"
    "org.locationtech.jts.geom.Polygon"
    "org.locationtech.jts.geom.PrecisionModel"
    "org.locationtech.jts.geom.TopologyException"
    "org.locationtech.jts.geom.Triangle"
    "org.locationtech.jts.geom.impl.CoordinateArraySequence"
    "org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory"
    "org.locationtech.jts.geom.prep.PreparedGeometry"
    "org.locationtech.jts.geom.prep.PreparedGeometryFactory"
    "org.locationtech.jts.geom.util.AffineTransformation"
    "org.locationtech.jts.geom.util.AffineTransformationBuilder"
    "org.locationtech.jts.geom.util.GeometryCombiner"
    "org.locationtech.jts.geom.util.GeometryEditor"
    "org.locationtech.jts.geom.util.GeometryFixer"
    "org.locationtech.jts.geom.util.GeometryTransformer"
    "org.locationtech.jts.geom.util.ShortCircuitedGeometryVisitor"
    "org.locationtech.jts.geom.util.SineStarFactory"
    "org.locationtech.jts.index.ArrayListVisitor"
    "org.locationtech.jts.index.VertexSequencePackedRtree"
    "org.locationtech.jts.index.bintree.Bintree"
    "org.locationtech.jts.index.chain.MonotoneChainOverlapAction"
    "org.locationtech.jts.index.chain.MonotoneChainSelectAction"
    "org.locationtech.jts.index.hprtree.HPRtree"
    "org.locationtech.jts.index.hprtree.HilbertEncoder"
    "org.locationtech.jts.index.intervalrtree.IntervalRTreeNode"
    "org.locationtech.jts.index.intervalrtree.SortedPackedIntervalRTree"
    "org.locationtech.jts.index.kdtree.KdTree"
    "org.locationtech.jts.index.quadtree.DoubleBits"
    "org.locationtech.jts.index.quadtree.Key"
    "org.locationtech.jts.index.quadtree.Node"
    "org.locationtech.jts.index.quadtree.Quadtree"
    "org.locationtech.jts.index.strtree.AbstractNode"
    "org.locationtech.jts.index.strtree.AbstractSTRtree"
    "org.locationtech.jts.index.strtree.BoundablePairDistanceComparator"
    "org.locationtech.jts.index.strtree.GeometryItemDistance"
    "org.locationtech.jts.index.strtree.SIRtree"
    "org.locationtech.jts.index.sweepline.SweepLineIndex"
    "org.locationtech.jts.index.sweepline.SweepLineInterval"
    "org.locationtech.jts.io.ByteArrayInStream"
    "org.locationtech.jts.io.ByteOrderDataInStream"
    "org.locationtech.jts.io.OrdinateFormat"
    "org.locationtech.jts.io.WKBReader"
    "org.locationtech.jts.io.WKBWriter"
    "org.locationtech.jts.io.WKTReader"
    "org.locationtech.jts.io.WKTWriter"
    "org.locationtech.jts.io.geojson.GeoJsonReader"
    "org.locationtech.jts.io.geojson.GeoJsonWriter"
    "org.locationtech.jts.io.gml2.GMLWriter"
    "org.locationtech.jts.io.kml.KMLReader"
    "org.locationtech.jts.io.kml.KMLWriter"
    "org.locationtech.jts.io.twkb.TWKBReader"
    "org.locationtech.jts.io.twkb.TWKBWriter"
    "org.locationtech.jts.linearref.LengthIndexedLine"
    "org.locationtech.jts.linearref.LengthLocationMap"
    "org.locationtech.jts.linearref.LinearIterator"
    "org.locationtech.jts.linearref.LinearLocation"
    "org.locationtech.jts.linearref.LocationIndexedLine"
    "org.locationtech.jts.math.DD"
    "org.locationtech.jts.math.Plane3D"
    "org.locationtech.jts.math.Vector2D"
    "org.locationtech.jts.math.Vector3D"
    "org.locationtech.jts.operation.BoundaryOp"
    "org.locationtech.jts.operation.IsSimpleOp"
    "org.locationtech.jts.operation.buffer.BufferCurveSetBuilder"
    "org.locationtech.jts.operation.buffer.BufferInputLineSimplifier"
    "org.locationtech.jts.operation.buffer.BufferOp"
    "org.locationtech.jts.operation.buffer.BufferParameters"
    "org.locationtech.jts.operation.buffer.OffsetCurve"
    "org.locationtech.jts.operation.buffer.OffsetCurveBuilder"
    "org.locationtech.jts.operation.buffer.validate.BufferCurveMaximumDistanceFinder"
    "org.locationtech.jts.operation.buffer.validate.BufferDistanceValidator"
    "org.locationtech.jts.operation.buffer.validate.BufferResultValidator"
    "org.locationtech.jts.operation.distance.DistanceOp"
    "org.locationtech.jts.operation.distance.FacetSequence"
    "org.locationtech.jts.operation.distance.GeometryLocation"
    "org.locationtech.jts.operation.distance.IndexedFacetDistance"
    "org.locationtech.jts.operation.distance3d.Distance3DOp"
    "org.locationtech.jts.operation.linemerge.LineSequencer"
    "org.locationtech.jts.operation.overlay.OverlayNodeFactory"
    "org.locationtech.jts.operation.overlay.OverlayOp"
    "org.locationtech.jts.operation.overlay.snap.GeometrySnapper"
    "org.locationtech.jts.operation.overlay.snap.LineStringSnapper"
    "org.locationtech.jts.operation.overlay.snap.SnapIfNeededOverlayOp"
    "org.locationtech.jts.operation.overlay.snap.SnapOverlayOp"
    "org.locationtech.jts.operation.overlay.validate.FuzzyPointLocator"
    "org.locationtech.jts.operation.overlay.validate.OffsetPointGenerator"
    "org.locationtech.jts.operation.overlay.validate.OverlayResultValidator"
    "org.locationtech.jts.operation.overlayng.FastOverlayFilter"
    "org.locationtech.jts.operation.overlayng.LineLimiter"
    "org.locationtech.jts.operation.overlayng.OverlayNG"
    "org.locationtech.jts.operation.overlayng.RingClipper"
    "org.locationtech.jts.operation.polygonize.Polygonizer"
    "org.locationtech.jts.operation.predicate.RectangleContains"
    "org.locationtech.jts.operation.predicate.RectangleIntersects"
    "org.locationtech.jts.operation.relate.EdgeEndBuilder"
    "org.locationtech.jts.operation.relate.RelateNodeGraph"
    "org.locationtech.jts.operation.relate.RelateOp"
    "org.locationtech.jts.operation.relateng.RelateNG"
    "org.locationtech.jts.operation.union.OverlapUnion"
    "org.locationtech.jts.operation.union.UnaryUnionOp"
    "org.locationtech.jts.operation.union.UnionInteracting"
    "org.locationtech.jts.operation.valid.IsValidOp"
    "org.locationtech.jts.operation.valid.RepeatedPointTester"
    "org.locationtech.jts.operation.valid.TopologyValidationError"
    "org.locationtech.jts.precision.CommonBits"
    "org.locationtech.jts.precision.CommonBitsOp"
    "org.locationtech.jts.precision.CommonBitsRemover"
    "org.locationtech.jts.precision.CoordinatePrecisionReducerFilter"
    "org.locationtech.jts.precision.GeometryPrecisionReducer"
    "org.locationtech.jts.precision.MinimumClearance"
    "org.locationtech.jts.precision.PrecisionReducerCoordinateOperation"
    "org.locationtech.jts.precision.SimpleGeometryPrecisionReducer"
    "org.locationtech.jts.precision.SimpleMinimumClearance"
    "org.locationtech.jts.shape.fractal.KochSnowflakeBuilder"
    "org.locationtech.jts.shape.fractal.SierpinskiCarpetBuilder"
    "org.locationtech.jts.shape.random.RandomPointsBuilder"
    "org.locationtech.jts.shape.random.RandomPointsInGridBuilder"
    "org.locationtech.jts.simplify.LinkedLine"
    "org.locationtech.jts.simplify.PolygonHullSimplifier"
    "org.locationtech.jts.simplify.TopologyPreservingSimplifier"
    "org.locationtech.jts.simplify.VWSimplifier"
    "org.locationtech.jts.triangulate.ConformingDelaunayTriangulationBuilder"
    "org.locationtech.jts.triangulate.ConstraintEnforcementException"
    "org.locationtech.jts.triangulate.ConstraintVertex"
    "org.locationtech.jts.triangulate.DelaunayTriangulationBuilder"
    "org.locationtech.jts.triangulate.MidpointSplitPointFinder"
    "org.locationtech.jts.triangulate.NonEncroachingSplitPointFinder"
    "org.locationtech.jts.triangulate.Segment"
    "org.locationtech.jts.triangulate.SplitSegment"
    "org.locationtech.jts.triangulate.VertexTaggedGeometryDataMapper"
    "org.locationtech.jts.triangulate.VoronoiDiagramBuilder"
    "org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator"
    "org.locationtech.jts.triangulate.polygon.PolygonHoleJoiner"
    "org.locationtech.jts.triangulate.polygon.PolygonTriangulator"
    "org.locationtech.jts.triangulate.quadedge.EdgeConnectedTriangleTraversal"
    "org.locationtech.jts.triangulate.quadedge.LocateFailureException"
    "org.locationtech.jts.triangulate.quadedge.QuadEdge"
    "org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision"
    "org.locationtech.jts.triangulate.quadedge.Vertex"
    "org.locationtech.jts.triangulate.tri.Tri"
    "org.locationtech.jts.util.CoordinateArrayFilter"
    "org.locationtech.jts.util.CoordinateCountFilter"
    "org.locationtech.jts.util.GeometricShapeFactory"
    "org.locationtech.jts.util.IntArrayList"
    "org.locationtech.jts.util.ObjectCounter"
    "org.locationtech.jts.util.PriorityQueue"
    "org.locationtech.jts.util.Stopwatch"
    "org.locationtech.jts.util.UniqueCoordinateArrayFilter"})

(def ^:private static-dispatch-classes
  "Classes whose static methods route through the generic :static-call
   dispatch template. :static-int-field / :static-char-field entries
   are auto-excluded by the hoisted rule's `field?` guard;
   :static-collection->geom is auto-excluded because its
   Collection<Geometry> param isn't coercible."
  #{"org.locationtech.jts.algorithm.Angle"
    "org.locationtech.jts.algorithm.Area"
    "org.locationtech.jts.algorithm.CGAlgorithms"
    "org.locationtech.jts.algorithm.CGAlgorithms3D"
    "org.locationtech.jts.algorithm.CGAlgorithmsDD"
    "org.locationtech.jts.algorithm.Centroid"
    "org.locationtech.jts.algorithm.Distance"
    "org.locationtech.jts.algorithm.InteriorPoint"
    "org.locationtech.jts.algorithm.InteriorPointArea"
    "org.locationtech.jts.algorithm.InteriorPointLine"
    "org.locationtech.jts.algorithm.InteriorPointPoint"
    "org.locationtech.jts.algorithm.Intersection"
    "org.locationtech.jts.algorithm.Length"
    "org.locationtech.jts.algorithm.LineIntersector"
    "org.locationtech.jts.algorithm.MinimumAreaRectangle"
    "org.locationtech.jts.algorithm.MinimumBoundingCircle"
    "org.locationtech.jts.algorithm.MinimumDiameter"
    "org.locationtech.jts.algorithm.Orientation"
    "org.locationtech.jts.algorithm.PointLocation"
    "org.locationtech.jts.algorithm.PolygonNodeTopology"
    "org.locationtech.jts.algorithm.RayCrossingCounter"
    "org.locationtech.jts.algorithm.Rectangle"
    "org.locationtech.jts.algorithm.RobustDeterminant"
    "org.locationtech.jts.algorithm.construct.LargestEmptyCircle"
    "org.locationtech.jts.algorithm.construct.MaximumInscribedCircle"
    "org.locationtech.jts.algorithm.distance.DiscreteFrechetDistance"
    "org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance"
    "org.locationtech.jts.algorithm.distance.DistanceToPoint"
    "org.locationtech.jts.algorithm.hull.ConcaveHull"
    "org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons"
    "org.locationtech.jts.algorithm.hull.HullTriangulation"
    "org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator"
    "org.locationtech.jts.algorithm.match.HausdorffSimilarityMeasure"
    "org.locationtech.jts.algorithm.match.SimilarityMeasureCombiner"
    "org.locationtech.jts.awt.FontGlyphReader"
    "org.locationtech.jts.coverage.CoverageGapFinder"
    "org.locationtech.jts.coverage.CoveragePolygonValidator"
    "org.locationtech.jts.coverage.CoverageUnion"
    "org.locationtech.jts.coverage.CoverageValidator"
    "org.locationtech.jts.densify.Densifier"
    "org.locationtech.jts.dissolve.LineDissolver"
    "org.locationtech.jts.edgegraph.EdgeGraph"
    "org.locationtech.jts.edgegraph.HalfEdge"
    "org.locationtech.jts.edgegraph.MarkHalfEdge"
    "org.locationtech.jts.geom.Coordinate"
    "org.locationtech.jts.geom.CoordinateArrays"
    "org.locationtech.jts.geom.CoordinateSequences"
    "org.locationtech.jts.geom.Coordinates"
    "org.locationtech.jts.geom.Dimension"
    "org.locationtech.jts.geom.Envelope"
    "org.locationtech.jts.geom.IntersectionMatrix"
    "org.locationtech.jts.geom.LineSegment"
    "org.locationtech.jts.geom.Location"
    "org.locationtech.jts.geom.OctagonalEnvelope"
    "org.locationtech.jts.geom.Position"
    "org.locationtech.jts.geom.PrecisionModel"
    "org.locationtech.jts.geom.Quadrant"
    "org.locationtech.jts.geom.Triangle"
    "org.locationtech.jts.geom.prep.PreparedGeometryFactory"
    "org.locationtech.jts.geom.util.AffineTransformation"
    "org.locationtech.jts.geom.util.AffineTransformationFactory"
    "org.locationtech.jts.geom.util.GeometryCombiner"
    "org.locationtech.jts.geom.util.GeometryFixer"
    "org.locationtech.jts.geom.util.GeometryMapper"
    "org.locationtech.jts.geom.util.LineStringExtracter"
    "org.locationtech.jts.geom.util.LinearComponentExtracter"
    "org.locationtech.jts.geom.util.PolygonalExtracter"
    "org.locationtech.jts.geom.util.SineStarFactory"
    "org.locationtech.jts.index.chain.MonotoneChainBuilder"
    "org.locationtech.jts.index.quadtree.DoubleBits"
    "org.locationtech.jts.index.quadtree.IntervalSize"
    "org.locationtech.jts.index.quadtree.Key"
    "org.locationtech.jts.index.quadtree.Node"
    "org.locationtech.jts.index.quadtree.NodeBase"
    "org.locationtech.jts.index.quadtree.Quadtree"
    "org.locationtech.jts.index.strtree.EnvelopeDistance"
    "org.locationtech.jts.io.ByteOrderValues"
    "org.locationtech.jts.io.Ordinate"
    "org.locationtech.jts.io.OrdinateFormat"
    "org.locationtech.jts.io.geojson.OrientationTransformer"
    "org.locationtech.jts.io.twkb.Varint"
    "org.locationtech.jts.linearref.LengthLocationMap"
    "org.locationtech.jts.linearref.LinearLocation"
    "org.locationtech.jts.math.DD"
    "org.locationtech.jts.math.MathUtil"
    "org.locationtech.jts.math.Matrix"
    "org.locationtech.jts.math.Vector2D"
    "org.locationtech.jts.math.Vector3D"
    "org.locationtech.jts.noding.Octant"
    "org.locationtech.jts.noding.SegmentPointComparator"
    "org.locationtech.jts.noding.SegmentStringUtil"
    "org.locationtech.jts.operation.BoundaryOp"
    "org.locationtech.jts.operation.buffer.BufferInputLineSimplifier"
    "org.locationtech.jts.operation.buffer.BufferOp"
    "org.locationtech.jts.operation.buffer.BufferParameters"
    "org.locationtech.jts.operation.buffer.OffsetCurve"
    "org.locationtech.jts.operation.buffer.VariableBuffer"
    "org.locationtech.jts.operation.buffer.validate.BufferResultValidator"
    "org.locationtech.jts.operation.buffer.validate.DistanceToPointFinder"
    "org.locationtech.jts.operation.distance.DistanceOp"
    "org.locationtech.jts.operation.distance.FacetSequenceTreeBuilder"
    "org.locationtech.jts.operation.distance.IndexedFacetDistance"
    "org.locationtech.jts.operation.distance3d.AxisPlaneCoordinateSequence"
    "org.locationtech.jts.operation.distance3d.Distance3DOp"
    "org.locationtech.jts.operation.linemerge.LineSequencer"
    "org.locationtech.jts.operation.overlay.OverlayOp"
    "org.locationtech.jts.operation.overlay.snap.GeometrySnapper"
    "org.locationtech.jts.operation.overlay.snap.SnapIfNeededOverlayOp"
    "org.locationtech.jts.operation.overlay.snap.SnapOverlayOp"
    "org.locationtech.jts.operation.overlay.validate.OverlayResultValidator"
    "org.locationtech.jts.operation.overlayng.CoverageUnion"
    "org.locationtech.jts.operation.overlayng.EdgeMerger"
    "org.locationtech.jts.operation.overlayng.OverlayNG"
    "org.locationtech.jts.operation.overlayng.OverlayNGRobust"
    "org.locationtech.jts.operation.overlayng.OverlayUtil"
    "org.locationtech.jts.operation.overlayng.PrecisionReducer"
    "org.locationtech.jts.operation.overlayng.PrecisionUtil"
    "org.locationtech.jts.operation.overlayng.UnaryUnionNG"
    "org.locationtech.jts.operation.predicate.RectangleContains"
    "org.locationtech.jts.operation.predicate.RectangleIntersects"
    "org.locationtech.jts.operation.relate.RelateOp"
    "org.locationtech.jts.operation.relateng.DimensionLocation"
    "org.locationtech.jts.operation.relateng.PolygonNodeConverter"
    "org.locationtech.jts.operation.relateng.RelateNG"
    "org.locationtech.jts.operation.relateng.RelatePredicate"
    "org.locationtech.jts.operation.relateng.TopologyPredicateTracer"
    "org.locationtech.jts.operation.union.CascadedPolygonUnion"
    "org.locationtech.jts.operation.union.UnaryUnionOp"
    "org.locationtech.jts.operation.union.UnionInteracting"
    "org.locationtech.jts.operation.valid.IsSimpleOp"
    "org.locationtech.jts.operation.valid.IsValidOp"
    "org.locationtech.jts.precision.EnhancedPrecisionOp"
    "org.locationtech.jts.precision.GeometryPrecisionReducer"
    "org.locationtech.jts.precision.MinimumClearance"
    "org.locationtech.jts.precision.PointwisePrecisionReducerTransformer"
    "org.locationtech.jts.precision.PrecisionReducerTransformer"
    "org.locationtech.jts.precision.SimpleGeometryPrecisionReducer"
    "org.locationtech.jts.precision.SimpleMinimumClearance"
    "org.locationtech.jts.shape.CubicBezierCurve"
    "org.locationtech.jts.shape.fractal.HilbertCode"
    "org.locationtech.jts.shape.fractal.KochSnowflakeBuilder"
    "org.locationtech.jts.shape.fractal.MortonCode"
    "org.locationtech.jts.shape.fractal.SierpinskiCarpetBuilder"
    "org.locationtech.jts.simplify.DouglasPeuckerSimplifier"
    "org.locationtech.jts.simplify.PolygonHullSimplifier"
    "org.locationtech.jts.simplify.TopologyPreservingSimplifier"
    "org.locationtech.jts.simplify.VWSimplifier"
    "org.locationtech.jts.triangulate.DelaunayTriangulationBuilder"
    "org.locationtech.jts.triangulate.NonEncroachingSplitPointFinder"
    "org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator"
    "org.locationtech.jts.triangulate.polygon.PolygonHoleJoiner"
    "org.locationtech.jts.triangulate.polygon.PolygonTriangulator"
    "org.locationtech.jts.triangulate.polygon.TriDelaunayImprover"
    "org.locationtech.jts.triangulate.quadedge.QuadEdge"
    "org.locationtech.jts.triangulate.quadedge.QuadEdgeTriangle"
    "org.locationtech.jts.triangulate.quadedge.QuadEdgeUtil"
    "org.locationtech.jts.triangulate.quadedge.TrianglePredicate"
    "org.locationtech.jts.triangulate.quadedge.Vertex"
    "org.locationtech.jts.triangulate.tri.Tri"
    "org.locationtech.jts.triangulate.tri.TriangulationBuilder"
    "org.locationtech.jts.util.Assert"
    "org.locationtech.jts.util.CollectionUtil"
    "org.locationtech.jts.util.Debug"
    "org.locationtech.jts.util.Memory"
    "org.locationtech.jts.util.NumberUtil"
    "org.locationtech.jts.util.Stopwatch"
    "org.locationtech.jts.util.StringUtil"
    "org.locationtech.jts.util.TestBuilderProxy"
    "org.locationtech.jts.util.UniqueCoordinateArrayFilter"})

(def ^:private ctor-dispatch-classes
  "Classes whose <init> entries route through the generic :ctor template.
   Spans auto-ctor-classes plus js-wrappers-built-from-geometry plus
   the attach-override wrappers (Envelope, PrecisionModel, Coordinate,
   GeometryFactory, IntersectionMatrix) whose ctors emit
   `createJS<helper>(new <FQN>(<coerce-args>))` via the generic
   template."
  (-> auto-ctor-classes
      (into js-wrappers-built-from-geometry)
      (into ["org.locationtech.jts.geom.Coordinate"
              "org.locationtech.jts.geom.Envelope"
              "org.locationtech.jts.geom.GeometryFactory"
              "org.locationtech.jts.geom.IntersectionMatrix"
              "org.locationtech.jts.geom.PrecisionModel"])))

(def ^:private manual-classification-overrides
  "(class, method, params) tuples that route through bespoke per-shape
   classify-shape rules instead of the generic template rules at the
   top of the cond. Used when the receiver class IS in
   instance-dispatch-classes but a SPECIFIC method on it has bespoke
   semantics the generic templates can't express.

   - LineString.getCoordinateSequence / Point.getCoordinateSequence
     share `wasmts.geom.getCoordinateSequence` JS path (geometry-subtypes
     collapse). Both entries classify as :geometry-get-coordinate-sequence which
     emits a polymorphic instanceof Point / LineString dispatch in a
     single lambda body. Routing either through the generic
     :receiver-call template would emit a per-receiver lambda, breaking
     the polymorphic contract (the dedup-by-path drops one entry, leaving
     only one receiver's dispatch installed).

   - Reader.read(String|byte[]) overloads on the five reader classes
     declare `throws ParseException`. The Fn2 functional interface
     doesn't declare throws, so the dispatch body must catch the
     ParseException inside the lambda and rethrow as RuntimeException.
     The hoisted generic rules skip these tuples (their no-checked-throws?
     gate rejects them); the reader-read classify rule below fires instead
     and produces {:kind :receiver-call :throws-translate \"ParseException\"}
     so the receiver-call template emits the try/catch block lambda.

   - Geometry.getCoordinate() returns null on empty geometries; the
     per-shape :geometry-get-coordinate-or-null defmethod wraps with createJSCoordinateOrNull
     to pass null through to JS. The generic :receiver-call template
     uses createJSCoordinate (non-null) and would crash.

   - LineSegment.intersection(LineSegment) / lineIntersection(LineSegment)
     return null when segments don't intersect / lines are parallel.
     LineSegment.project(LineSegment) returns null when there is no
     projection overlap. The per-shape :lineseg*lineseg->{coord,lineseg}
     defmethods wrap with createJSCoordinateOrNull /
     createJSLineSegmentOrNull. The sibling project(Coordinate)
     overload always returns a point and stays on :receiver-call."
  #{{:class "org.locationtech.jts.geom.LineString" :method "getCoordinateSequence" :params []}
    {:class "org.locationtech.jts.geom.Point"      :method "getCoordinateSequence" :params []}
    {:class "org.locationtech.jts.io.WKTReader"             :method "read" :params ["java.lang.String"]}
    {:class "org.locationtech.jts.io.WKBReader"             :method "read" :params ["byte[]"]}
    {:class "org.locationtech.jts.io.geojson.GeoJsonReader" :method "read" :params ["java.lang.String"]}
    {:class "org.locationtech.jts.io.kml.KMLReader"         :method "read" :params ["java.lang.String"]}
    {:class "org.locationtech.jts.io.twkb.TWKBReader"       :method "read" :params ["byte[]"]}
    {:class "org.locationtech.jts.geom.Geometry"            :method "getCoordinate"     :params []}
    {:class "org.locationtech.jts.geom.LineSegment"         :method "intersection"      :params ["org.locationtech.jts.geom.LineSegment"]}
    {:class "org.locationtech.jts.geom.LineSegment"         :method "lineIntersection"  :params ["org.locationtech.jts.geom.LineSegment"]}
    {:class "org.locationtech.jts.geom.LineSegment"         :method "project"           :params ["org.locationtech.jts.geom.LineSegment"]}})

(def ^:private cs-class              "org.locationtech.jts.geom.CoordinateSequence")
(def ^:private cs-filter-class       "org.locationtech.jts.geom.CoordinateSequenceFilter")
(def ^:private coord-filter-class    "org.locationtech.jts.geom.CoordinateFilter")
(def ^:private gc-filter-class       "org.locationtech.jts.geom.GeometryComponentFilter")
(def ^:private geom-filter-class     "org.locationtech.jts.geom.GeometryFilter")
(def ^:private strtree-class         "org.locationtech.jts.index.strtree.STRtree")
(def ^:private linemerger-class      "org.locationtech.jts.operation.linemerge.LineMerger")
(def ^:private parse-exception-class "org.locationtech.jts.io.ParseException")
(def ^:private string-class          "java.lang.String")
(def ^:private byte-array-class      "byte[]")

(def ^:private static-utility-classes
  "Whitelist of JTS static-utility classes the codegen exposes today.
   Restricted to classes designed by JTS as static-only (Angle, Distance,
   Centroid, ...). Value-type classes with parallel static/instance APIs
   (Triangle, LineSegment) do NOT belong here — their statics collide
   with their instances at the JS path, and dedup-by-path silently drops
   one. For per-method opt-in on those classes (Triangle.angleBisector,
   etc.), use manual.edn :static-only-methods — entries that collide on
   the JS path with their instance counterpart still need a :hints
   :js-path entry to coexist."
  #{"org.locationtech.jts.algorithm.Angle"
    "org.locationtech.jts.algorithm.Distance"
    "org.locationtech.jts.algorithm.Centroid"
    "org.locationtech.jts.algorithm.Length"
    "org.locationtech.jts.algorithm.Orientation"
    "org.locationtech.jts.algorithm.Area"
    "org.locationtech.jts.algorithm.CGAlgorithms3D"
    "org.locationtech.jts.algorithm.InteriorPoint"
    "org.locationtech.jts.algorithm.InteriorPointArea"
    "org.locationtech.jts.algorithm.InteriorPointLine"
    "org.locationtech.jts.algorithm.InteriorPointPoint"
    "org.locationtech.jts.algorithm.PointLocation"
    "org.locationtech.jts.geom.CoordinateArrays"
    "org.locationtech.jts.geom.Dimension"
    "org.locationtech.jts.io.geojson.OrientationTransformer"
    "org.locationtech.jts.math.MathUtil"
    "org.locationtech.jts.operation.buffer.BufferOp"
    "org.locationtech.jts.operation.buffer.OffsetCurve"
    "org.locationtech.jts.operation.distance.DistanceOp"
    "org.locationtech.jts.triangulate.quadedge.TrianglePredicate"
    ;; 55 pure-static-utility classes (also in
    ;; static-dispatch-classes). Adding here gates the per-shape
    ;; classify rules' static-allowed? branch in case any classify-
    ;; shape per-shape rule is still alive for that signature.
    "org.locationtech.jts.algorithm.CGAlgorithms"
    "org.locationtech.jts.algorithm.CGAlgorithmsDD"
    "org.locationtech.jts.algorithm.Intersection"
    "org.locationtech.jts.algorithm.PolygonNodeTopology"
    "org.locationtech.jts.algorithm.Rectangle"
    "org.locationtech.jts.algorithm.RobustDeterminant"
    "org.locationtech.jts.algorithm.distance.DistanceToPoint"
    "org.locationtech.jts.algorithm.hull.HullTriangulation"
    "org.locationtech.jts.algorithm.match.SimilarityMeasureCombiner"
    "org.locationtech.jts.awt.FontGlyphReader"
    "org.locationtech.jts.geom.CoordinateSequences"
    "org.locationtech.jts.geom.Coordinates"
    "org.locationtech.jts.geom.Location"
    "org.locationtech.jts.geom.Position"
    "org.locationtech.jts.geom.Quadrant"
    "org.locationtech.jts.geom.util.AffineTransformationFactory"
    "org.locationtech.jts.geom.util.GeometryMapper"
    "org.locationtech.jts.geom.util.PolygonalExtracter"
    "org.locationtech.jts.index.chain.MonotoneChainBuilder"
    "org.locationtech.jts.index.quadtree.IntervalSize"
    "org.locationtech.jts.index.strtree.EnvelopeDistance"
    "org.locationtech.jts.io.ByteOrderValues"
    "org.locationtech.jts.io.Ordinate"
    "org.locationtech.jts.io.twkb.Varint"
    "org.locationtech.jts.math.Matrix"
    "org.locationtech.jts.noding.Octant"
    "org.locationtech.jts.noding.SegmentPointComparator"
    "org.locationtech.jts.noding.SegmentStringUtil"
    "org.locationtech.jts.operation.buffer.validate.DistanceToPointFinder"
    "org.locationtech.jts.operation.distance.FacetSequenceTreeBuilder"
    "org.locationtech.jts.operation.overlayng.CoverageUnion"
    "org.locationtech.jts.operation.overlayng.EdgeMerger"
    "org.locationtech.jts.operation.overlayng.OverlayNGRobust"
    "org.locationtech.jts.operation.overlayng.OverlayUtil"
    "org.locationtech.jts.operation.overlayng.PrecisionReducer"
    "org.locationtech.jts.operation.overlayng.PrecisionUtil"
    "org.locationtech.jts.operation.overlayng.UnaryUnionNG"
    "org.locationtech.jts.operation.relateng.DimensionLocation"
    "org.locationtech.jts.operation.relateng.PolygonNodeConverter"
    "org.locationtech.jts.operation.relateng.RelatePredicate"
    "org.locationtech.jts.operation.relateng.TopologyPredicateTracer"
    "org.locationtech.jts.precision.EnhancedPrecisionOp"
    "org.locationtech.jts.precision.PointwisePrecisionReducerTransformer"
    "org.locationtech.jts.precision.PrecisionReducerTransformer"
    "org.locationtech.jts.shape.fractal.HilbertCode"
    "org.locationtech.jts.shape.fractal.MortonCode"
    "org.locationtech.jts.triangulate.polygon.TriDelaunayImprover"
    "org.locationtech.jts.triangulate.quadedge.QuadEdgeUtil"
    "org.locationtech.jts.triangulate.tri.TriangulationBuilder"
    "org.locationtech.jts.util.Assert"
    "org.locationtech.jts.util.CollectionUtil"
    "org.locationtech.jts.util.Memory"
    "org.locationtech.jts.util.NumberUtil"
    "org.locationtech.jts.util.StringUtil"
    "org.locationtech.jts.util.TestBuilderProxy"})

;; coerce-arg-expr in emit_api.clj handles this set of param
;; types. Mirror the set here so the generic classifier rules only fire
;; on entries the dispatch templates can actually emit.
(def ^:private param-types-with-extractors
  "Param types for which coerce-arg-expr in emit_api.clj knows how to
   produce a Java extractor call. The explicit base set covers primitives,
   primitive arrays, Strings, and the hand-extracted JTS types; auto-ctor-classes
   is unioned in because coerce-arg-expr wildcards on js-helper-by-class —
   any class in js-wrapper-classes is automatically a coercible param.
   Forward-stable: adding a class to auto-ctor-classes makes it usable as
   a method-param type anywhere in the registry without further wiring."
  (into #{"boolean"
          "byte[]"
          "char"
          "double"
          "double[]"
          "int"
          "int[]"
          "java.lang.String"
          "java.util.Collection<org.locationtech.jts.geom.Coordinate>"
          "java.util.Collection<org.locationtech.jts.geom.Geometry>"
          "java.util.Collection<org.locationtech.jts.geom.LineString>"
          "java.util.List<org.locationtech.jts.geom.Coordinate>"
          "java.util.List<org.locationtech.jts.geom.Geometry>"
          "java.util.List<org.locationtech.jts.geom.LineString>"
          "long"
          "org.locationtech.jts.geom.Coordinate"
          "org.locationtech.jts.geom.CoordinateSequence"
          "org.locationtech.jts.geom.Coordinate[]"
          "org.locationtech.jts.geom.Envelope"
          "org.locationtech.jts.geom.Geometry"
          "org.locationtech.jts.geom.GeometryCollection"
          "org.locationtech.jts.geom.GeometryFactory"
          "org.locationtech.jts.geom.Geometry[]"
          "org.locationtech.jts.geom.IntersectionMatrix"
          "org.locationtech.jts.geom.LineSegment"
          "org.locationtech.jts.geom.LineString"
          "org.locationtech.jts.geom.LineString[]"
          "org.locationtech.jts.geom.LinearRing"
          "org.locationtech.jts.geom.LinearRing[]"
          "org.locationtech.jts.geom.MultiLineString"
          "org.locationtech.jts.geom.MultiPoint"
          "org.locationtech.jts.geom.MultiPolygon"
          "org.locationtech.jts.geom.Point"
          "org.locationtech.jts.geom.Point[]"
          "org.locationtech.jts.geom.Polygon"
          "org.locationtech.jts.geom.Polygon[]"
          "org.locationtech.jts.geom.PrecisionModel"
          "org.locationtech.jts.geom.PrecisionModel$Type"
          "org.locationtech.jts.geom.Triangle"
          "org.locationtech.jts.math.Vector3D"
          "org.locationtech.jts.operation.buffer.BufferParameters"}
        auto-ctor-classes))

(def ^:private return-types-with-wrappers
  "Return types for which return-type-wrappers in emit_api.clj directly
   provides a wrap template (JSBoolean.of, JSNumber.of, etc.). Any other
   type is rendered via the js-helper-for fallback (createJS<Helper>).
   The classifier accepts a return as coercible if it's a primitive /
   value-type listed here, a geometry subtype, or a class in the
   js-wrapper-classes universe."
  #{"boolean"
    "byte[]"
    "char"
    "double"
    "double[]"
    "int"
    "int[]"
    "java.lang.String"
    "java.lang.String[]"
    "java.util.Collection<org.locationtech.jts.geom.Coordinate>"
    "java.util.Collection<org.locationtech.jts.geom.Geometry>"
    "java.util.Collection<org.locationtech.jts.geom.LineString>"
    "java.util.Collection<org.locationtech.jts.triangulate.quadedge.QuadEdge>"
    "java.util.Collection<org.locationtech.jts.triangulate.quadedge.Vertex>"
    "java.util.List<java.lang.Object>"
    "java.util.List<org.locationtech.jts.geom.Coordinate>"
    "java.util.List<org.locationtech.jts.geom.Coordinate[]>"
    "java.util.List<org.locationtech.jts.geom.Geometry>"
    "java.util.List<org.locationtech.jts.geom.LineString>"
    "java.util.List<org.locationtech.jts.triangulate.quadedge.QuadEdge>"
    "java.util.List<org.locationtech.jts.triangulate.quadedge.QuadEdge[]>"
    "java.util.List<org.locationtech.jts.triangulate.quadedge.Vertex>"
    "java.util.List<org.locationtech.jts.triangulate.quadedge.Vertex[]>"
    "long"
    "org.locationtech.jts.geom.Coordinate"
    "org.locationtech.jts.geom.CoordinateSequence"
    "org.locationtech.jts.geom.Coordinate[]"
    "org.locationtech.jts.geom.Envelope"
    "org.locationtech.jts.geom.GeometryFactory"
    "org.locationtech.jts.geom.Geometry[]"
    "org.locationtech.jts.geom.IntersectionMatrix"
    "org.locationtech.jts.geom.LineSegment"
    "org.locationtech.jts.geom.PrecisionModel"
    "org.locationtech.jts.geom.Triangle"
    "org.locationtech.jts.geom.prep.PreparedGeometry"
    "org.locationtech.jts.linearref.LinearLocation[]"
    "org.locationtech.jts.math.Vector3D"
    "org.locationtech.jts.operation.distance.GeometryLocation[]"
    "void"})

(defn- coercible-return? [t]
  (or (contains? return-types-with-wrappers t)
      (jts-geometry? t)
      (contains? auto-ctor-classes t)))

(defn- coercible-param? [t]
  (contains? param-types-with-extractors t))

(defn classify-shape
  "Pure: given a registry key + value, return a shape keyword.

   Shapes are receiver-typed. The receiver is the class declaring the
   method (`:class` field); for instance methods that's the JS-side
   first argument. The current categories cover Geometry receiver and
   Envelope receiver; more receiver classes land as their shapes get
   exercised.

   Drops Geometry-subclass overrides (Point/Polygon/MultiPoint etc.
   that override Geometry.x) — JS dispatch is polymorphic on the JTS
   object, so only the Geometry base entry needs to install.

   `params` lives in the key (part of the entry's identity), `returns`
   lives in the value.

   `static-only-methods` is an optional per-class opt-in for static
   methods on value-type classes (Triangle, LineSegment) that aren't
   on the static-utility-classes whitelist. Same key shape as :skip:
   class symbol -> #{method-name} | #{[method-name params-vec]}. A
   classified entry still has to dodge a JS-path collision with any
   instance variant via a :hints :js-path entry."
  [{:keys [class method params] :as k} {:keys [returns static? throws field? generic-params param-override]} static-only-methods]
  ;; when :generic-params is present (auto-captured or merged
  ;; from a manual :elem-type hint), use it as the "effective" param list.
  ;; Same for :returns :generic-type. Erased forms remain available
  ;; through :params / :returns :type for the few rules that need them.
  ;; :param-override (per-position type map from manual.edn :hints) is
  ;; applied last so a method whose Java declaration is Object-typed but
  ;; whose JS contract carries a coercible type passes the coercibility
  ;; gate and routes through {:kind :receiver-call}.
  (let [base-params      (or generic-params params)
        effective-params (cond->> base-params
                           param-override
                           (map-indexed (fn [i p] (get param-override i p)))
                           param-override
                           vec)
        effective-ret    (or (:generic-type returns) (:type returns))
        r                    effective-ret
        geom-receiver?       (= class "org.locationtech.jts.geom.Geometry")
        point-receiver?      (= class point-class)
        linestring-receiver? (= class linestring-class)
        pm-receiver?         (= class precision-model-class)
        lineseg-receiver?    (= class lineseg-class)
        strtree-receiver?    (= class strtree-class)
        linemerger-receiver? (= class linemerger-class)
        throws-parse?        (contains? (set throws) parse-exception-class)
        coord?               (= r coordinate-class)
        ctor?                (= method "<init>")
        static-method?       (and static? (not ctor?))
        static-allowed?      (let [entries (get static-only-methods (symbol class))]
                               (or (static-utility-classes class)
                                   (boolean (and entries
                                                 (or (entries method)
                                                     (entries [method params]))))))]
    (cond
      ;; Generic template-engine classifier rules, hoisted so they fire first
      ;; for classes in `*-dispatch-classes`. Guards:
      ;;   - `field?` entries fall through to per-shape :static-*-field rules
      ;;     (installConstantInt path, not dispatch-body);
      ;;   - `manual-classification-overrides` exempts specific tuples (e.g.
      ;;     LineString/Point.getCoordinateSequence → :geometry-get-coordinate-sequence polymorphic).
      ;; All three use the same coercible-param? gate, mirroring
      ;; coerce-arg-expr in emit_api.clj. Ctor returns the class itself, so
      ;; no explicit coercible-return? check is needed for the ctor rule.
      (and (not field?)
           ctor?
           (contains? ctor-dispatch-classes class)
           (concrete-class? class)
           (no-checked-throws? throws)
           (every? coercible-param? effective-params))
      {:kind :ctor}

      (and (not field?)
           static-method?
           (contains? static-dispatch-classes class)
           (not (contains? manual-classification-overrides
                           (select-keys k [:class :method :params])))
           (no-checked-throws? throws)
           (every? coercible-param? effective-params)
           (coercible-return? r))
      {:kind :static-call}

      (and (not field?)
           (not ctor?) (not static-method?)
           (contains? instance-dispatch-classes class)
           (not (contains? manual-classification-overrides
                           (select-keys k [:class :method :params])))
           (no-checked-throws? throws)
           (every? coercible-param? effective-params)
           (coercible-return? r))
      {:kind :receiver-call}

      ;; Coordinate[] / inner-class param ctors stay uncategorized until
      ;; their extractor helpers land.
      ctor? :uncategorized

      ;; Geometry receiver: the bulk of :geom->X / :geom*X shapes go
      ;; through the hoisted {:kind :receiver-call}; the bespoke shapes
      ;; below stay because each is either coercibility-skipped (Object
      ;; params, *Filter params, GeometryFactory return) or exempted via
      ;; manual-classification-overrides (getCoordinate).
      ;;
      ;; equals(Object) / compareTo(Object) route through the hoisted
      ;; {:kind :receiver-call} via a :param-override hint in manual.edn
      ;; that narrows the Object position to Geometry; no per-shape rule
      ;; needed here.

      ;; Geometry.getUserData() / setUserData(Object) — raw Object
      ;; passthrough. Object return / Object param are not coercible so
      ;; the hoisted rule skips; these rules route through
      ;; {:kind :receiver-call} directly. The "java.lang.Object"
      ;; return-type-wrappers entry is identity; coerce-arg-expr emits
      ;; `(Object) aN` for the setter param.
      (and geom-receiver? (= r "java.lang.Object") (empty? params))
      {:kind :receiver-call}

      (and geom-receiver? (= r "void")
           (= 1 (count params)) (= "java.lang.Object" (first params)))
      {:kind :receiver-call}

      ;; Geometry.getCoordinate() returns null on empty geometries.
      ;; Route through {:kind :receiver-call} with :return-null-safe?
      ;; true so the receiver-call template post-processes the wrap
      ;; from createJSCoordinate to createJSCoordinateOrNull. The
      ;; (Geometry, getCoordinate, []) tuple stays in
      ;; manual-classification-overrides so the hoisted generic rule
      ;; skips it (otherwise it would produce the non-OrNull form).
      (and geom-receiver? coord? (empty? params))
      {:kind :receiver-call :return-null-safe? true}

      ;; Geometry.getFactory() returns GeometryFactory, which is not
      ;; in auto-ctor-classes (extractGeometryFactory lives
      ;; in API.java, not API_Generated). coercible-return? is false
      ;; so the hoisted rule skips. The per-shape defmethod uses
      ;; createJSGeometryFactoryFromInstance (vs the generic
      ;; createJSGeometryFactory ctor wrap).
      (and geom-receiver? (= r geom-factory-class)
           (empty? params))
      :geometry-get-factory

      ;; Geometry.apply(*Filter) JS-callback marshalling. The 4 *Filter
      ;; param types are not coercible so the hoisted rule skips. The
      ;; dispatch body wraps the callback in API.JSCallback<X>Filter and
      ;; returns the modified copy as a JS geometry, preserving the
      ;; existing wasmts.geom.apply* contract.
      (and geom-receiver? (= r "void")
           (= 1 (count params))
           (#{cs-filter-class coord-filter-class gc-filter-class geom-filter-class}
            (first params)))
      :geometry-apply-filter

      ;; Subtype receivers (Point/LineString/Polygon): methods land at
      ;; wasmts.geom.<method> via the geometry-subtypes path collapse; the
      ;; dispatch body uses extractPoint / extractLineString / extractPolygon
      ;; (instanceof-check after extractGeometry, throwing on type mismatch).

      ;; :pm->type-friendly: PrecisionModel.getType() returns the nested
      ;; PrecisionModel$Type enum, mapped via API.precisionModelTypeFriendlyName.
      (and pm-receiver?
           (= r "org.locationtech.jts.geom.PrecisionModel$Type")
           (empty? params))
      :pm->type-friendly


      ;; Generalised across any class with a read(String|byte[]) method
      ;; that throws ParseException and returns Geometry. Routes through
      ;; {:kind :receiver-call} with a :throws-translate flag; the
      ;; receiver-call template binds the call to a local, wraps it, and
      ;; emits the catch that rethrows ParseException as RuntimeException
      ;; (Fn2 doesn't declare throws). The 5 reader.read tuples stay in
      ;; manual-classification-overrides so the hoisted generic rule skips
      ;; them and this rule fires to attach the flag (the generic rule's
      ;; no-checked-throws? gate would reject them anyway). Matches
      ;; GeoJsonReader / WKTReader / WKBReader / KMLReader / TWKBReader;
      ;; future readers get covered once their extract helper exists.
      ;; Reader overloads on java.io / InStream stream types stay
      ;; :skip-listed in manual.edn.
      (and (jts-geometry? r)
           (#{[string-class] [byte-array-class]} params)
           throws-parse?)
      {:kind :receiver-call :throws-translate "ParseException"}


      ;; LineSegment is in instance-dispatch-classes; instance rules
      ;; flow through :receiver-call. The three OrNull-wrapping cases
      ;; (intersection / lineIntersection return null for parallel /
      ;; non-intersecting; project returns null for off-segment) stay
      ;; in manual-classification-overrides so the hoisted generic
      ;; rule skips them; this per-shape rule produces
      ;; {:kind :receiver-call :return-null-safe? true} so the
      ;; receiver-call template applies the OrNull wrap.
      (and lineseg-receiver?
           (or coord? (= r lineseg-class))
           (= 1 (count params)) (= lineseg-class (first params)))
      {:kind :receiver-call :return-null-safe? true}

      ;; STRtree item arg: Object passthrough — the coerce-arg-expr fallback
      ;; emits `(java.lang.Object) aN` (no-op cast). Pre-checked here so the
      ;; param doesn't slip into other shapes. Params count excludes the
      ;; receiver, so insert/remove have 2 declared params.
      (and strtree-receiver? (= r "void")
           (= params [envelope-class "java.lang.Object"]))
      :strtree-insert

      (and strtree-receiver? (= r "boolean")
           (= params [envelope-class "java.lang.Object"]))
      :strtree-remove

      (and strtree-receiver? (= r "java.util.List")
           (= 1 (count params)) (= envelope-class (first params)))
      :strtree-query

      (and strtree-receiver? (= r "int") (empty? params))
      :strtree-size

      (and linemerger-receiver? (= r "void")
           (= 1 (count params)) (jts-geometry? (first params)))
      :linemerger-add

      (and linemerger-receiver? (= r "java.util.Collection") (empty? params))
      :linemerger-get-merged-line-strings

      ;; getCoordinateSequence() — declared on Point and on LineString
      ;; (inherited by LinearRing). Both routes collapse to the
      ;; wasmts.geom.getCoordinateSequence JS path under the geometry-subtypes
      ;; class collapse; the :geometry-get-coordinate-sequence dispatch body does instanceof
      ;; polymorphism so a single installed handler serves either receiver.
      (and (or point-receiver? linestring-receiver?)
           (= r cs-class) (empty? params))
      :geometry-get-coordinate-sequence

      ;; Three bespoke static rules stay below — the generic :static-call
      ;; rule can't model them:
      ;;   - :static-collection->geom: Collection<Geometry> param needs the
      ;;     Arrays.asList(extractGeometryArray(...)) adapter; Collection isn't
      ;;     in param-types-with-extractors, so the generic rejects it.
      ;;   - :static-int-field / :static-char-field: Dimension constants are
      ;;     static FIELDS, not methods; the `field?` guard on the generic
      ;;     rule lets them fall through here.
      (and static-method? static-allowed? (jts-geometry? r)
           (= params ["java.util.Collection"]))
      :static-collection->geom

      (and field? (= r "int"))
      :static-int-field

      (and field? (= r "char"))
      :static-char-field

      (and field? (= r "double"))
      :static-double-field


      :else :uncategorized)))

;; Classification (merge reflection + manual overrides + shape)

(defn- skip?
  "Is this entry covered by a hand-written wrapper in API.java, or
   does it fall under a wholesale package skip?

   Three key shapes in `skip-map`:

   - Class-keyed (`<class-symbol>` -> `:all | #{method | [method params]} | :keep`).
     Matches a specific class, optionally narrowed to method names or
     overloads. Same key shape as :static-only-methods. `:keep` is a
     sentinel that means \"never skip via this class\" — it exempts the
     class from any package-prefix skip that would otherwise catch it.

   - Package-keyed (`<\"package.prefix\">` -> `:all`). Matches any class
     whose FQN starts with the prefix followed by `.`. Used to skip
     entire internal packages (DCEL, noding pipeline, planar graphs)
     in one block rather than enumerating every class. Class-keyed
     entries take precedence — checked first — so a `:keep` carve-out
     on a specific class overrides the package-level :all (for the
     handful of classes in an otherwise-internal package whose methods
     classify into a real shape and should stay on the JS surface)."
  [{:keys [class method params]} skip-map]
  (let [class-entry (get skip-map (symbol class))]
    (if (some? class-entry)
      ;; A class-keyed entry takes full precedence over any
      ;; package-prefix entry that would also match. Otherwise a
      ;; set-keyed entry meant to narrow skipping to a few methods
      ;; would silently widen to skip everything else via the
      ;; package wildcard.
      (cond
        (= class-entry :keep) false
        (= class-entry :all)  true
        (set? class-entry)    (boolean
                                (or (contains? class-entry method)
                                    (contains? class-entry [method params])))
        :else false)
      ;; No class-keyed entry: fall back to package-prefix wildcard match.
      (boolean
        (some (fn [[k v]]
                (and (string? k)
                     (= v :all)
                     (str/starts-with? class (str k "."))))
              skip-map)))))

(defn- merge-hint [entry hint]
  ;; Hints are shallow-merged over the reflected entry. Callers supply
  ;; only the keys they want to override.
  (merge entry hint))

(defn- utility-class-names
  "Set of class names whose non-ctor methods are 100% static. JTS hides
   the ctor on these (private/default) so the no-arg <init> that
   reflection still emits is synthetic noise — users only ever call
   the static methods. Used by classify to auto-skip the synthetic
   ctor without forcing a per-class entry in manual.edn :skip."
  [raw]
  (let [by-class (group-by (fn [[k _]] (:class k)) raw)]
    (into #{}
          (for [[cls entries] by-class
                :let [non-ctor (filter (fn [[k _]] (not= "<init>" (:method k))) entries)]
                :when (and (seq non-ctor)
                           (every? (fn [[_ v]] (:static? v)) non-ctor))]
            cls))))

(defn- utility-class-synthetic-ctor?
  "True for the no-arg <init> on a class whose other methods are 100%
   static. The :tags entry it produces (#{:bespoke
   :utility-class-synthetic-ctor}) is the discoverable marker — grep
   registry.edn for it to audit the bucket."
  [{:keys [method params class]} utility-class-set]
  (and (= method "<init>")
       (empty? params)
       (contains? utility-class-set class)))

(defn- object-override?
  "True for the four canonical java.lang.Object methods that virtually
   every Java class overrides: equals(Object), hashCode(), toString(),
   compareTo(Object). The :tags entry produced (#{:bespoke
   :object-override}) is the discoverable marker."
  [{:keys [method params]}]
  (or (and (= method "equals")    (= params ["java.lang.Object"]))
      (and (= method "hashCode")  (empty? params))
      (and (= method "toString")  (empty? params))
      (and (= method "compareTo") (= params ["java.lang.Object"]))))

(def ^:private object-override-keep
  "Object-override entries that should classify normally instead of
   auto-skipping. Four known cases today:

   - Geometry.equals(Object) / compareTo(Object) route through
     {:kind :receiver-call} (the hoisted instance-dispatch rule);
     a :param-override hint in manual.edn narrows the Object position
     to Geometry so the coercibility gate accepts the entry and the
     receiver-call template emits extractGeometry(a2) instead of
     (Object) a2. Polymorphic equality / ordering across the Geometry
     hierarchy works because Java accepts a Geometry where an Object
     is wanted.

   - IntersectionMatrix.toString() returns the 9-char DE-9IM matrix
     pattern ('212101212'-style), not a debug string — it's the
     canonical text form of a relate result and is exercised by the
     npm test suite. Classifies via :im->string.

   - PrecisionModel.toString() returns a deterministic descriptor
     ('Floating' / 'Floating-Single' / 'Fixed(<scale>)') that's
     useful for cross-runtime equality. Pre-VV manual.edn left this
     out of the skip set (unlike PM.hashCode, which is meaningless
     across the JS boundary) so the differential test suite carried
     a passing :pm->string defspec; keep that surface.

   Without this whitelist, receiver-specific shapes like :pm->int /
   :pm->string / :geom->int / :geom->string would be too greedy
   (PrecisionModel.hashCode / Geometry.toString, etc.), so the
   auto-skip runs BEFORE classify-shape and exempts only this set."
  #{{:class "org.locationtech.jts.geom.Geometry"
     :method "equals"
     :params ["java.lang.Object"]}
    {:class "org.locationtech.jts.geom.Geometry"
     :method "compareTo"
     :params ["java.lang.Object"]}
    {:class "org.locationtech.jts.geom.IntersectionMatrix"
     :method "toString"
     :params []}
    {:class "org.locationtech.jts.geom.PrecisionModel"
     :method "toString"
     :params []}})

(defn- geometry-base-method-keys
  "Set of [method params] pairs for every entry whose :class is
   org.locationtech.jts.geom.Geometry. Used by subclass-override? to
   detect subclass overrides whose dispatch can ride the Geometry-base
   entry via Java polymorphism."
  [raw]
  (into #{}
        (keep (fn [[{:keys [class method params]} _]]
                (when (= class "org.locationtech.jts.geom.Geometry")
                  [method params])))
        raw))

(defn- subclass-override?
  "True for a Geometry-subclass entry (Point / Polygon / MultiPoint /
   GeometryCollection / MultiPolygon / etc.) whose {method, params}
   pair also exists on the Geometry base. JS dispatch routes through
   Java polymorphism to the Geometry-base entry (the {:kind
   :receiver-call} rule classifies it; dedup-by-path prefers it on a
   path collision), so the subclass entry is redundant noise. The
   :tags entry produced (#{:bespoke :subclass-override}) is the
   discoverable marker — grep registry.edn for it to audit."
  [{:keys [class method params]} geom-method-keys]
  (and (not= class "org.locationtech.jts.geom.Geometry")
       (jts-geometry? class)
       (contains? geom-method-keys [method params])))

(defn classify
  "Pure: take a raw reflection map and a manual-overrides map; return
   a registry with :shape, :tags, and any :generic-hint applied. The
   :shape value is either a keyword (per-shape dispatch via legacy
   defmethod) or a structured map `{:kind :ctor/:static-call/
   :receiver-call}` from the hoisted template-engine classifier rules."
  [raw {:keys [skip hints static-only-methods elem-type] :as _overrides}]
  (let [util-classes     (utility-class-names raw)
        geom-method-keys (geometry-base-method-keys raw)
        elem-lookup  (fn [k]
                       (when-let [cls-map (get elem-type (symbol (:class k)))]
                         (or (get cls-map [(:method k) (:params k)])
                             (get cls-map [(:method k) (vec (:params k))]))))]
    (reduce-kv
      (fn [acc k v]
        (let [hint      (get hints k)
              hinted    (cond-> v hint (merge-hint hint))
              elem-hint (elem-lookup k)
              hinted    (cond-> hinted
                          elem-hint (#(apply-elem-type-hint k % elem-hint)))
              tagged  (cond
                        (skip? k skip)
                        (assoc hinted :shape :bespoke :tags #{:bespoke})

                        ;; auto-skip canonical Object
                        ;; overrides. Runs before classify-shape (so
                        ;; receiver-specific shapes like :pm->int /
                        ;; :geom->string don't greedily grab
                        ;; PrecisionModel.hashCode / Geometry.toString).
                        ;; A small whitelist exempts the deliberately-
                        ;; classified Geometry equals(Object) /
                        ;; compareTo(Object).
                        (and (object-override? k)
                             (not (contains? object-override-keep
                                             (select-keys k [:class :method :params]))))
                        (assoc hinted :shape :bespoke
                               :tags #{:bespoke :object-override})

                        (utility-class-synthetic-ctor? k util-classes)
                        (assoc hinted :shape :bespoke
                               :tags #{:bespoke :utility-class-synthetic-ctor})

                        ;; Geometry-subclass methods whose {method,
                        ;; params} pair already exists on the Geometry
                        ;; base. JS dispatch rides the Geometry-base
                        ;; entry via Java polymorphism, so the subclass
                        ;; entry is redundant.
                        (subclass-override? k geom-method-keys)
                        (assoc hinted :shape :bespoke
                               :tags #{:bespoke :subclass-override})

                        :else
                        (assoc hinted
                               :shape (classify-shape k hinted static-only-methods)
                               :tags  #{}))]
          (assoc acc k tagged)))
      {}
      raw)))

;; Validation (Malli is optional; do a structural check by hand for now)

(def ^:private valid-entry-keys
  #{:static? :returns :throws :varargs? :shape :tags :js-path :gen-overrides :gen-tuple
    ;; public static final field entries carry :field? true
    ;; and (for int / char fields) :value with the reflected constant.
    :field? :value
    ;; parallel to :params with generic types
    ;; preserved (e.g. "java.util.List<org.locationtech.jts.geom.Geometry>").
    ;; Present only when the erased form would lose element-type info;
    ;; consumers fall back to :params when absent.
    :generic-params
    ;; parallel to :params with the JTS source-level parameter names from
    ;; the class file's LocalVariableTable. Present only when LVT supplied
    ;; a non-nil name for every position; consumers fall back to aN when
    ;; absent.
    :param-names
    ;; per-position type override applied to params before classify-shape's
    ;; coercibility check and before the receiver-call template's
    ;; coerce-args-from. Map of 0-indexed position -> override type FQN.
    ;; Lets a method whose Java declaration carries an Object param (or
    ;; otherwise non-coercible type) classify and emit as if the JS
    ;; contract narrowed it to a coercible type. Geometry.equals(Object) /
    ;; compareTo(Object) use this to force extractGeometry(aN) over the
    ;; default (Object) aN cast.
    :param-override
    ;; per-method differential-compare strategy override (emit_tests). One
    ;; of :same-shape / :length / :area. Replaces the default return-type
    ;; compare for construction methods whose port output is geometrically
    ;; correct but not vertex-for-vertex identical to the JVM oracle (a
    ;; near-duplicate vertex, or a tie-broken non-canonical representative
    ;; on a symmetric input). See manual.edn :hints.
    :compare})

(defn validate
  "Throw on structurally broken registry entries. Returns the registry
   unchanged on success. Skips the Malli dep until the pipeline is
   producing real output worth meta-schema-validating."
  [registry]
  (doseq [[k v] registry]
    (when-not (and (map? k) (every? k [:class :method :params]))
      (throw (ex-info "Bad registry key shape" {:key k})))
    (when-not (every? valid-entry-keys (keys v))
      (throw (ex-info "Unknown keys in registry value"
                      {:key k :unknown (remove valid-entry-keys (keys v))})))
    (when-let [pn (:param-names v)]
      (when (not= (count pn) (count (:params k)))
        (throw (ex-info ":param-names length mismatches :params"
                        {:key k :params (:params k) :param-names pn})))))
  registry)

;; I/O

(defn- public-class?
  "Class/forName + Modifier check. Package-private top-level classes
   live in JTS (DimensionLocation, OverlayUtil, etc.); reflecting them
   succeeds but downstream callers from outside the package hit
   IllegalAccessError. Drop them at discovery time."
  [fqn]
  (try
    (java.lang.reflect.Modifier/isPublic
      (.getModifiers (Class/forName fqn)))
    (catch Throwable _ false)))

(defn- concrete-class?
  "Reject abstract classes and interfaces. The auto-emitted :ctor
   template would call `new Foo(...)`, which fails to compile for
   anything not concretely instantiable. JTS exposes abstract bases
   like AbstractSTRtree / AbstractNode / LineIntersector and several
   *Visitor / *Filter abstract bases."
  [fqn]
  (try
    (let [m (.getModifiers (Class/forName fqn))]
      (not (or (java.lang.reflect.Modifier/isAbstract m)
               (java.lang.reflect.Modifier/isInterface m))))
    (catch Throwable _ false)))

(defn- checked-exception?
  "An exception is `checked` if it extends Throwable but not
   RuntimeException or Error. Methods that throw any checked exception
   can't ride the FnN functional interface (Fn1/Fn2/... don't declare
   throws), so the lambda body would fail to compile. Use this to
   filter out throws-bearing methods from the generic dispatch rules."
  [fqn]
  (try
    (let [c (Class/forName fqn)]
      (and (.isAssignableFrom Throwable c)
           (not (.isAssignableFrom RuntimeException c))
           (not (.isAssignableFrom Error c))))
    (catch Throwable _ false)))

(defn- no-checked-throws?
  "True when the method's throws list contains no checked exceptions.
   Reader.read methods (which throw ParseException) are exempted via
   manual-classification-overrides so this guard doesn't gate them."
  [throws]
  (not-any? checked-exception? throws))

(defn discover-classes
  "Walk every JTS jar on the classpath and harvest every public,
   top-level class under org/locationtech/jts/**. Inner classes
   ($-named), package-info, module-info, and package-private
   top-level classes are filtered out.

   Returns a sorted set of fully-qualified class names."
  []
  (let [jar-paths (-> (System/getProperty "java.class.path")
                      (str/split (re-pattern java.io.File/pathSeparator)))
        jts-jars  (filter #(re-find #"jts.*\.jar$" %) jar-paths)
        accum     (volatile! (sorted-set))]
    (doseq [jar-path jts-jars]
      (with-open [jf (java.util.jar.JarFile. (java.io.File. jar-path))]
        (doseq [entry (enumeration-seq (.entries jf))
                :let  [n (.getName entry)]
                :when (and (str/starts-with? n "org/locationtech/jts/")
                           (str/ends-with? n ".class")
                           (not (str/includes? n "$"))
                           (not (str/includes? n "package-info"))
                           (not (str/includes? n "module-info")))]
          (let [fqn (-> n
                        (str/replace "/" ".")
                        (str/replace #"\.class$" ""))]
            (when (public-class? fqn)
              (vswap! accum conj fqn))))))
    @accum))

(defn read-class-list
  "Resolve the JTS class list. Priority:
     1. resources/jts-classes.edn — explicit curated list (escape hatch).
     2. Auto-discover from JTS jars on the classpath (default).

   The curated list is kept around for the case where you want to
   target a subset, but the default is comprehensive coverage."
  []
  (if-let [resource (io/resource "jts-classes.edn")]
    (do
      (binding [*out* *err*]
        (println "  using curated jts-classes.edn (" (count (edn/read-string (slurp resource))) "classes)"))
      (edn/read-string (slurp resource)))
    (let [discovered (discover-classes)]
      (binding [*out* *err*]
        (println "  auto-discovered" (count discovered) "JTS classes from classpath jars"))
      discovered)))

(defn read-overrides []
  (let [f (io/file "manual.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {:skip {} :hints {} :static-only-methods {} :elem-type {}})))

(defn- apply-elem-type-hint
  "merge a manual `:elem-type` hint into an entry's
   `:generic-params` and `:returns :generic-type`. Hint shape:

       {:params  {<index> \"<elem-fqn>\"}
        :returns \"<elem-fqn>\"}

   For each position in `:params` map, if the erased param at that
   index is `java.util.List` / `Collection` / `Set` / `Iterator`,
   replaces the corresponding `:generic-params` entry with
   `<erased><<elem-fqn>>`. For `:returns`, same shape for the
   single return value.

   No-op when the erased type isn't a raw container — protects
   against accidentally annotating a non-generic param."
  [{:keys [params]} v {hint-params :params hint-returns :returns}]
  (let [container? #{"java.util.List" "java.util.Collection"
                     "java.util.Set" "java.util.Iterator"
                     "java.util.ArrayList"}
        gp-now      (or (:generic-params v) params)
        gp-merged   (reduce-kv
                      (fn [acc idx elem]
                        (let [erased (nth params idx nil)]
                          (if (container? erased)
                            (assoc acc idx (str erased "<" elem ">"))
                            acc)))
                      (vec gp-now)
                      hint-params)
        gp-changed? (not= gp-merged (vec gp-now))
        ret-erased  (get-in v [:returns :type])
        ret-merged  (when (and hint-returns (container? ret-erased))
                      (str ret-erased "<" hint-returns ">"))]
    (cond-> v
      gp-changed? (assoc :generic-params gp-merged)
      ret-merged  (assoc-in [:returns :generic-type] ret-merged))))

(defn- canonicalise
  "Produce a sort-stable form so registry.edn diffs cleanly across runs."
  [registry]
  (into (sorted-map-by
         (fn [a b]
           (compare [(:class a) (:method a) (:params a)]
                    [(:class b) (:method b) (:params b)])))
        registry))

(defn write-registry [registry path]
  (binding [*print-namespace-maps* false]
    (spit path (with-out-str (pprint/pprint registry)))))

;; Entry points

(defn build []
  (-> (read-class-list)
      reflect-classes
      (classify (read-overrides))
      validate
      canonicalise))

(defn- shape-label
  "Stable text label for a shape. structured map shapes use
   the `:kind` keyword's name; legacy keyword shapes use their own
   name. Used by `summary` for grouping/sorting and by registry.edn
   pretty-printing where a map's identity as a key isn't sortable."
  [s]
  (cond
    (keyword? s) (name s)
    (map? s)     (name (:kind s))
    :else        (str s)))

(defn- summary [registry]
  (let [shape-counts (->> registry vals (map :shape) (map shape-label) frequencies (into (sorted-map)))]
    (str "  total entries:      " (count registry) "\n"
         (str/join "\n" (for [[s c] shape-counts]
                          (format "  %-22s %4d" s c))))))

(defn -main [& _]
  (let [registry (build)]
    (write-registry registry "registry.edn")
    (println "Wrote registry.edn")
    (println (summary registry))
    (let [unc (filter (fn [[_ v]] (= :uncategorized (:shape v))) registry)]
      (when (seq unc)
        (println "\nUncategorized (add a shape branch or a manual.edn skip):")
        (doseq [[k _] (take 20 unc)]
          (println " " (pr-str k)))
        (when (> (count unc) 20)
          (println "  ... and" (- (count unc) 20) "more"))))))

(defn dump-reflection
  "Pretty-print the raw reflection map to stdout. Useful for one-off
   inspection without writing an on-disk artifact."
  [& _]
  (binding [*print-namespace-maps* false]
    (pprint/pprint (-> (read-class-list) reflect-classes))))
