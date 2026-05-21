(ns emit-tests
  "Emit test/clj/wasmts/differential/generated.clj from registry.edn.

   For every in-scope entry, emit a clojure.test.check defspec that
   compares JTS-on-JVM against wasmts-via-RPC. Generators are shape-
   specific (binary predicates take two WKTs, unary ops take one, etc.).

   Each defspec runs 100 cases by default. The generated file is
   expected to be loaded under `clojure -M:test`."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [codegen-common :refer [js-path in-scope? dedup-by-path simple-name geometry-subtypes]]))


(defn- spec-name
  "Stable test-name symbol keyed on class+method+arity+params. Class
   disambiguation — Geometry and Envelope both have `contains`,
   `intersects`, `disjoint`. Param-type tags disambiguate same-name
   same-arity overloads: LineSegment.distance(Coordinate) vs
   LineSegment.distance(LineSegment) both resolve to arity 1 and would
   collide on the Var name without the type suffix."
  [{:keys [class method params]}]
  (let [arity (count params)
        cls   (-> class (str/split #"\.") last)
        tag   (fn [t]
                (if (#{"double" "int" "long" "short" "byte" "float" "boolean" "char"} t)
                  t
                  (-> t (str/split #"\.") last
                      (str/replace "[]" "Array"))))
        sig   (when (seq params) (str/join "-" (map tag params)))]
    (symbol (str cls "-" method "-" arity (when sig (str "-" sig)) "-prop"))))

(defn- canonical-type
  "Normalise any geometry subtype (Point / LineString / Polygon /
   LinearRing / MultiPoint / MultiLineString / MultiPolygon /
   GeometryCollection) to the base Geometry type. classify-shape's
   per-shape rules used `jts-geometry?` (a predicate) for return /
   param matching, so a single shape like `:gf*coord->geom` covered
   methods declaring any Geometry subtype. Mirror that here so one
   spec-form defmethod per logical shape suffices."
  [t]
  (if (geometry-subtypes t) "org.locationtech.jts.geom.Geometry" t))

(defn- spec-form-key
  "Derive a shape-vocabulary-independent dispatch
   key from the entry. Instance methods key on
   `[:instance receiver-simple return params]`; static methods on
   `[:static return params]`; ctors on `[:ctor receiver-simple params]`.
   Geometry subtypes canonicalise to Geometry (return + params) so a
   single defmethod covers the polymorphic family.

   This decouples spec-form from the legacy `:shape` keyword so the
   per-shape classify-shape rules can retire (the template engine's
   structured-shape map shapes work the same)."
  [k v]
  (let [{:keys [class method params]} k
        {:keys [static? returns]}     v
        ret    (canonical-type (:type returns))
        params (mapv canonical-type params)]
    (cond
      (= method "<init>") [:ctor (simple-name class) params]
      static?             [:static ret params]
      :else               [:instance (simple-name class) ret params])))

(defmulti spec-form
  "Emit the `(defspec ...)` form (as a Clojure value, not a string) for
   one in-scope registry entry. Dispatch derives a synthetic key
   from the entry via `spec-form-key`; defmethods are keyed on that
   vector tuple, NOT on the legacy `:shape` keyword."
  spec-form-key)

(declare generic-spec)

;; Fallback for any entry no hand-written tuple matches: the generic
;; template engine (below) builds a defspec for {:kind ...}-shaped entries
;; whose receiver + every param has a generator/shipper and whose return
;; has a comparator, mirroring emit_api's 3 generic dispatch templates.
;; Returns nil otherwise, so the existing hand tuples stay authoritative
;; and coverage only grows.
(defmethod spec-form :default [k v] (generic-spec k v))

(defn- binary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt-a ~'gen-wkt
                       ~'wkt-b ~'gen-wkt]
         (let [~'a   (~'rpc/read-jts ~'wkt-a)
               ~'b   (~'rpc/read-jts ~'wkt-b)
               ~'ha  (~'rpc/jts->wasmts ~'a)
               ~'hb  (~'rpc/jts->wasmts ~'b)
               ~'jts-result    (. ~'a ~(symbol method) ~'b)
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'ha ~'hb))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'ha)
               (~'rpc/release! ~'hb))))))))

(defn- binary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt-a ~'gen-wkt
                       ~'wkt-b ~'gen-wkt]
         (let [~'a   (~'rpc/read-jts ~'wkt-a)
               ~'b   (~'rpc/read-jts ~'wkt-b)
               ~'ha  (~'rpc/jts->wasmts ~'a)
               ~'hb  (~'rpc/jts->wasmts ~'b)
               ~'jts-result    (double (. ~'a ~(symbol method) ~'b))
               ~'wasmts-result (double (~'rpc/call! ~path ~'ha ~'hb))]
           (try
             (< (~'Math/abs (- ~'jts-result ~'wasmts-result)) 1e-9)
             (finally
               (~'rpc/release! ~'ha)
               (~'rpc/release! ~'hb))))))))

(defn- binary-geom-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'wkt-a ~'gen-wkt
                       ~'wkt-b ~'gen-wkt]
         (let [~'a   (~'rpc/read-jts ~'wkt-a)
               ~'b   (~'rpc/read-jts ~'wkt-b)
               ~'ha  (~'rpc/jts->wasmts ~'a)
               ~'hb  (~'rpc/jts->wasmts ~'b)
               ~'jts-result      (. ~'a ~(symbol method) ~'b)
               ~'wasmts-handle   (~'rpc/call! ~path ~'ha ~'hb)
               ~'wasmts-result   (~'rpc/wasmts->jts ~'wasmts-handle)]
           (try
             (.equalsExact ~'jts-result ~'wasmts-result 1.0e-6)
             (finally
               (~'rpc/release! ~'ha)
               (~'rpc/release! ~'hb)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- unary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt ~'gen-wkt]
         (let [~'g   (~'rpc/read-jts ~'wkt)
               ~'h   (~'rpc/jts->wasmts ~'g)
               ~'jts-result    (. ~'g ~(symbol method))
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt ~'gen-wkt]
         (let [~'g   (~'rpc/read-jts ~'wkt)
               ~'h   (~'rpc/jts->wasmts ~'g)
               ~'jts-result    (double (. ~'g ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (< (~'Math/abs (- ~'jts-result ~'wasmts-result)) 1e-9)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- unary-int-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt ~'gen-wkt]
         (let [~'g   (~'rpc/read-jts ~'wkt)
               ~'h   (~'rpc/jts->wasmts ~'g)
               ~'jts-result    (long (. ~'g ~(symbol method)))
               ~'wasmts-result (long (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- unary-string-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'wkt ~'gen-wkt]
         (let [~'g   (~'rpc/read-jts ~'wkt)
               ~'h   (~'rpc/jts->wasmts ~'g)
               ~'jts-result    (str (. ~'g ~(symbol method)))
               ~'wasmts-result (str (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- unary-geom-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'wkt ~'gen-wkt]
         (let [~'g   (~'rpc/read-jts ~'wkt)
               ~'h   (~'rpc/jts->wasmts ~'g)
               ~'jts-result      (. ~'g ~(symbol method))
               ~'wasmts-handle   (~'rpc/call! ~path ~'h)
               ~'wasmts-result   (~'rpc/wasmts->jts ~'wasmts-handle)]
           (try
             (.equalsExact ~'jts-result ~'wasmts-result 1.0e-6)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "Geometry" "boolean"                          ["org.locationtech.jts.geom.Geometry"]] [k v] (binary-bool-spec   k v))
(defmethod spec-form [:instance "Geometry" "double"                           ["org.locationtech.jts.geom.Geometry"]] [k v] (binary-double-spec k v))
(defmethod spec-form [:instance "Geometry" "org.locationtech.jts.geom.Geometry" ["org.locationtech.jts.geom.Geometry"]] [k v] (binary-geom-spec   k v))
(defmethod spec-form [:instance "Geometry" "boolean"                          []] [k v] (unary-bool-spec    k v))
(defmethod spec-form [:instance "Geometry" "double"                           []] [k v] (unary-double-spec  k v))
(defmethod spec-form [:instance "Geometry" "org.locationtech.jts.geom.Geometry" []] [k v] (unary-geom-spec    k v))
(defmethod spec-form [:instance "Geometry" "int"                              []] [k v] (unary-int-spec     k v))
(defmethod spec-form [:instance "Geometry" "java.lang.String"                 []] [k v] (unary-string-spec  k v))


(defn- env-unary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e ~'gen-envelope]
         (let [~'h   (~'rpc/jts->wasmts-envelope ~'e)
               ~'jts-result    (. ~'e ~(symbol method))
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- env-binary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e1 ~'gen-envelope
                       ~'e2 ~'gen-envelope]
         (let [~'h1 (~'rpc/jts->wasmts-envelope ~'e1)
               ~'h2 (~'rpc/jts->wasmts-envelope ~'e2)
               ~'jts-result    (. ~'e1 ~(symbol method) ~'e2)
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h1 ~'h2))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2))))))))

(defn- env-unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e ~'gen-envelope]
         (let [~'h   (~'rpc/jts->wasmts-envelope ~'e)
               ~'jts-result    (double (. ~'e ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- env-binary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e1 ~'gen-envelope
                       ~'e2 ~'gen-envelope]
         (let [~'h1 (~'rpc/jts->wasmts-envelope ~'e1)
               ~'h2 (~'rpc/jts->wasmts-envelope ~'e2)
               ~'jts-result    (double (. ~'e1 ~(symbol method) ~'e2))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h1 ~'h2))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2))))))))

(defn- env-unary-env-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'e ~'gen-envelope]
         (let [~'h               (~'rpc/jts->wasmts-envelope ~'e)
               ~'jts-result      (. ~'e ~(symbol method))
               ~'wasmts-handle   (~'rpc/call! ~path ~'h)
               ~'wasmts-bounds   (~'rpc/wasmts-envelope-bounds ~'wasmts-handle)]
           (try
             (~'rpc/envelope-bounds-equal? ~'jts-result ~'wasmts-bounds 1e-9)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- env-binary-env-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'e1 ~'gen-envelope
                       ~'e2 ~'gen-envelope]
         (let [~'h1              (~'rpc/jts->wasmts-envelope ~'e1)
               ~'h2              (~'rpc/jts->wasmts-envelope ~'e2)
               ~'jts-result      (. ~'e1 ~(symbol method) ~'e2)
               ~'wasmts-handle   (~'rpc/call! ~path ~'h1 ~'h2)
               ~'wasmts-bounds   (~'rpc/wasmts-envelope-bounds ~'wasmts-handle)]
           (try
             (~'rpc/envelope-bounds-equal? ~'jts-result ~'wasmts-bounds 1e-9)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "Envelope" "boolean"                         []] [k v] (env-unary-bool-spec    k v))
(defmethod spec-form [:instance "Envelope" "boolean"                         ["org.locationtech.jts.geom.Envelope"]] [k v] (env-binary-bool-spec   k v))
(defmethod spec-form [:instance "Envelope" "double"                          []] [k v] (env-unary-double-spec  k v))
(defmethod spec-form [:instance "Envelope" "double"                          ["org.locationtech.jts.geom.Envelope"]] [k v] (env-binary-double-spec k v))
(defmethod spec-form [:instance "Envelope" "org.locationtech.jts.geom.Envelope" []] [k v] (env-unary-env-spec     k v))
(defmethod spec-form [:instance "Envelope" "org.locationtech.jts.geom.Envelope" ["org.locationtech.jts.geom.Envelope"]] [k v] (env-binary-env-spec     k v))

(defn- env-unary-coord-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'e ~'gen-envelope]
         (let [~'h              (~'rpc/jts->wasmts-envelope ~'e)
               ~'jts-result     (. ~'e ~(symbol method))
               ~'wasmts-handle  (~'rpc/call! ~path ~'h)
               [~'wx ~'wy]      (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
           (try
             (~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-9)
                    (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-9))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- env-coord-arg-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e ~'gen-envelope
                        ~'c ~'gen-coordinate]
         (let [~'h              (~'rpc/jts->wasmts-envelope ~'e)
               ~'ch             (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result     (. ~'e ~(symbol method) ~'c)
               ~'wasmts-result  (~'rpc/call! ~path ~'h ~'ch)]
           (try
             (~'= ~'jts-result (~'boolean ~'wasmts-result))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'ch))))))))

(defn- env-double-double-arg-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'e ~'gen-envelope
                        ~'x ~'gen-coord
                        ~'y ~'gen-coord]
         (let [~'h              (~'rpc/jts->wasmts-envelope ~'e)
               ~'jts-result     (. ~'e ~(symbol method) (~'double ~'x) (~'double ~'y))
               ~'wasmts-result  (~'rpc/call! ~path ~'h ~'x ~'y)]
           (try
             (~'= ~'jts-result (~'boolean ~'wasmts-result))
             (finally
               (~'rpc/release! ~'h))))))))

(defmethod spec-form [:instance "Envelope" "org.locationtech.jts.geom.Coordinate" []]                          [k v] (env-unary-coord-spec            k v))
(defmethod spec-form [:instance "Envelope" "boolean"                              ["org.locationtech.jts.geom.Coordinate"]] [k v] (env-coord-arg-bool-spec         k v))
(defmethod spec-form [:instance "Envelope" "boolean"                              ["double" "double"]]         [k v] (env-double-double-arg-bool-spec k v))


(defn- pm-unary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'pm ~'gen-precision-model]
         (let [~'h   (~'rpc/jts->wasmts-pm ~'pm)
               ~'jts-result    (. ~'pm ~(symbol method))
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- pm-unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'pm ~'gen-precision-model]
         (let [~'h   (~'rpc/jts->wasmts-pm ~'pm)
               ~'jts-result    (double (. ~'pm ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- pm-unary-int-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'pm ~'gen-precision-model]
         (let [~'h   (~'rpc/jts->wasmts-pm ~'pm)
               ~'jts-result    (long (. ~'pm ~(symbol method)))
               ~'wasmts-result (long (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- pm-unary-string-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'pm ~'gen-precision-model]
         (let [~'h   (~'rpc/jts->wasmts-pm ~'pm)
               ~'jts-result    (str (. ~'pm ~(symbol method)))
               ~'wasmts-result (str (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- pm-double-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'pm ~'gen-precision-model
                       ~'x  ~'gen-coord]
         (let [~'h   (~'rpc/jts->wasmts-pm ~'pm)
               ~'jts-result    (double (. ~'pm ~(symbol method) (double ~'x)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h ~'x))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h))))))))

(defmethod spec-form [:instance "PrecisionModel" "boolean"          []] [k v] (pm-unary-bool-spec     k v))
(defmethod spec-form [:instance "PrecisionModel" "double"           []] [k v] (pm-unary-double-spec   k v))
(defmethod spec-form [:instance "PrecisionModel" "int"              []] [k v] (pm-unary-int-spec      k v))
(defmethod spec-form [:instance "PrecisionModel" "java.lang.String" []] [k v] (pm-unary-string-spec   k v))
(defmethod spec-form [:instance "PrecisionModel" "double"           ["double"]] [k v] (pm-double-double-spec k v))


(defn- gf-unary-pm-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'gf ~'gen-geomfactory]
         (let [~'h              (~'rpc/jts->wasmts-gf ~'gf)
               ~'jts-pm         (. ~'gf ~(symbol method))
               ~'wasmts-handle  (~'rpc/call! ~path ~'h)
               ~'wasmts-scale   (~'rpc/wasmts-pm-scale ~'wasmts-handle)]
           (try
             (~'rpc/close-enough? (.getScale ~'jts-pm) ~'wasmts-scale 1e-9)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- gf-unary-int-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'gf ~'gen-geomfactory]
         (let [~'h              (~'rpc/jts->wasmts-gf ~'gf)
               ~'jts-result     (long (. ~'gf ~(symbol method)))
               ~'wasmts-result  (long (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- gf-unary-geom-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'gf ~'gen-geomfactory]
         (let [~'h               (~'rpc/jts->wasmts-gf ~'gf)
               ~'jts-result      (. ~'gf ~(symbol method))
               ~'wasmts-handle   (~'rpc/call! ~path ~'h)
               ~'wasmts-result   (~'rpc/wasmts->jts ~'wasmts-handle)]
           (try
             (.equalsExact ~'jts-result ~'wasmts-result 1.0e-6)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- gf-coord-arg-geom-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'gf ~'gen-geomfactory
                        ~'c  ~'gen-coordinate]
         (let [~'h               (~'rpc/jts->wasmts-gf ~'gf)
               ~'ch              (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result      (. ~'gf ~(symbol method) ~'c)
               ~'wasmts-handle   (~'rpc/call! ~path ~'h ~'ch)
               ~'wasmts-result   (~'rpc/wasmts->jts ~'wasmts-handle)]
           (try
             (.equalsExact ~'jts-result ~'wasmts-result 1.0e-6)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'ch)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- gf-coordarray-arg-geom-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'gf     ~'gen-geomfactory
                        ~'coords ~'gen-coord-array]
         (let [~'h               (~'rpc/jts->wasmts-gf ~'gf)
               ~'handles         (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result      (. ~'gf ~(symbol method) ~'coords)
               ~'wasmts-handle   (~'rpc/call! ~path ~'h ~'handles)
               ~'wasmts-result   (~'rpc/wasmts->jts ~'wasmts-handle)]
           (try
             (.equalsExact ~'jts-result ~'wasmts-result 1.0e-6)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release-all-coords! ~'handles)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "GeometryFactory" "org.locationtech.jts.geom.PrecisionModel" []]                                         [k v] (gf-unary-pm-spec           k v))
(defmethod spec-form [:instance "GeometryFactory" "int"                                      []]                                         [k v] (gf-unary-int-spec          k v))
(defmethod spec-form [:instance "GeometryFactory" "org.locationtech.jts.geom.Geometry"       []]                                         [k v] (gf-unary-geom-spec         k v))
(defmethod spec-form [:instance "GeometryFactory" "org.locationtech.jts.geom.Geometry"       ["org.locationtech.jts.geom.Coordinate"]]   [k v] (gf-coord-arg-geom-spec     k v))
(defmethod spec-form [:instance "GeometryFactory" "org.locationtech.jts.geom.Geometry"       ["org.locationtech.jts.geom.Coordinate[]"]] [k v] (gf-coordarray-arg-geom-spec k v))


(defn- coord-unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'c ~'gen-coordinate]
         (let [~'h   (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result    (double (. ~'c ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- coord-binary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'c1 ~'gen-coordinate
                       ~'c2 ~'gen-coordinate]
         (let [~'h1 (~'rpc/jts->wasmts-coordinate ~'c1)
               ~'h2 (~'rpc/jts->wasmts-coordinate ~'c2)
               ~'jts-result    (double (. ~'c1 ~(symbol method) ~'c2))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h1 ~'h2))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-9)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2))))))))

(defn- coord-binary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'c1 ~'gen-coordinate
                       ~'c2 ~'gen-coordinate]
         (let [~'h1 (~'rpc/jts->wasmts-coordinate ~'c1)
               ~'h2 (~'rpc/jts->wasmts-coordinate ~'c2)
               ~'jts-result    (. ~'c1 ~(symbol method) ~'c2)
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h1 ~'h2))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2))))))))

(defn- coord-unary-coord-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'c ~'gen-coordinate]
         (let [~'h              (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result     (. ~'c ~(symbol method))
               ~'wasmts-handle  (~'rpc/call! ~path ~'h)
               [~'wx ~'wy]      (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
           (try
             (and (~'rpc/close-enough? (.getX ~'jts-result) (double ~'wx) 1e-9)
                  (~'rpc/close-enough? (.getY ~'jts-result) (double ~'wy) 1e-9))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "Coordinate" "double"                              []]                                       [k v] (coord-unary-double-spec  k v))
(defmethod spec-form [:instance "Coordinate" "double"                              ["org.locationtech.jts.geom.Coordinate"]] [k v] (coord-binary-double-spec k v))
(defmethod spec-form [:instance "Coordinate" "boolean"                             ["org.locationtech.jts.geom.Coordinate"]] [k v] (coord-binary-bool-spec   k v))
(defmethod spec-form [:instance "Coordinate" "org.locationtech.jts.geom.Coordinate" []]                                       [k v] (coord-unary-coord-spec   k v))


(defn- triangle-unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'t ~'gen-triangle]
         (let [~'h   (~'rpc/jts->wasmts-triangle ~'t)
               ~'jts-result    (double (. ~'t ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- triangle-unary-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'t ~'gen-triangle]
         (let [~'h   (~'rpc/jts->wasmts-triangle ~'t)
               ~'jts-result    (. ~'t ~(symbol method))
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'h))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- triangle-unary-coord-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'t ~'gen-triangle]
         (let [~'h               (~'rpc/jts->wasmts-triangle ~'t)
               ~'jts-result      (. ~'t ~(symbol method))
               ~'wasmts-handle   (~'rpc/call! ~path ~'h)
               [~'wx ~'wy]       (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
           (try
             (and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- triangle-coord-arg-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'t ~'gen-triangle
                       ~'c ~'gen-coordinate]
         (let [~'h   (~'rpc/jts->wasmts-triangle ~'t)
               ~'hc  (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result    (double (. ~'t ~(symbol method) ~'c))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h ~'hc))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'hc))))))))

(defmethod spec-form [:instance "Triangle" "double"                              []]                                       [k v] (triangle-unary-double-spec     k v))
(defmethod spec-form [:instance "Triangle" "boolean"                             []]                                       [k v] (triangle-unary-bool-spec       k v))
(defmethod spec-form [:instance "Triangle" "org.locationtech.jts.geom.Coordinate" []]                                       [k v] (triangle-unary-coord-spec      k v))
(defmethod spec-form [:instance "Triangle" "double"                              ["org.locationtech.jts.geom.Coordinate"]] [k v] (triangle-coord-arg-double-spec k v))


(defn- vector3d-unary-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'v ~'gen-vector3d]
         (let [~'h   (~'rpc/jts->wasmts-vector3d ~'v)
               ~'jts-result    (double (. ~'v ~(symbol method)))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release! ~'h))))))))

(defn- vector3d-unary-vector3d-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'v ~'gen-vector3d]
         (let [~'h               (~'rpc/jts->wasmts-vector3d ~'v)
               ~'jts-result      (. ~'v ~(symbol method))
               ~'wasmts-handle   (~'rpc/call! ~path ~'h)
               [~'wx ~'wy ~'wz]  (~'rpc/wasmts-vector3d-xyz ~'wasmts-handle)]
           (try
             (and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6)
                  (~'rpc/close-enough? (.getZ ~'jts-result) (~'double ~'wz) 1e-6))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- vector3d-vector3d-arg-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'v1 ~'gen-vector3d
                        ~'v2 ~'gen-vector3d]
         (let [~'h1  (~'rpc/jts->wasmts-vector3d ~'v1)
               ~'h2  (~'rpc/jts->wasmts-vector3d ~'v2)
               ~'jts-result    (double (. ~'v1 ~(symbol method) ~'v2))
               ~'wasmts-result (double (~'rpc/call! ~path ~'h1 ~'h2))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2))))))))

(defn- vector3d-vector3d-arg-vector3d-spec [k v]
  (let [path   (js-path k (:js-path v))
        method (:method k)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'v1 ~'gen-vector3d
                        ~'v2 ~'gen-vector3d]
         (let [~'h1               (~'rpc/jts->wasmts-vector3d ~'v1)
               ~'h2               (~'rpc/jts->wasmts-vector3d ~'v2)
               ~'jts-result       (. ~'v1 ~(symbol method) ~'v2)
               ~'wasmts-handle    (~'rpc/call! ~path ~'h1 ~'h2)
               [~'wx ~'wy ~'wz]   (~'rpc/wasmts-vector3d-xyz ~'wasmts-handle)]
           (try
             (and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6)
                  (~'rpc/close-enough? (.getZ ~'jts-result) (~'double ~'wz) 1e-6))
             (finally
               (~'rpc/release! ~'h1)
               (~'rpc/release! ~'h2)
               (~'rpc/release! ~'wasmts-handle))))))))

(defn- vector3d-double-arg-vector3d-spec [k v]
  ;; The double param defaults to gen-coord; `:gen-overrides {0 <sym>}` swaps
  ;; it (e.g. gen-nz-coord for divide(double) to dodge zero divisors).
  (let [path   (js-path k (:js-path v))
        method (:method k)
        d-gen  (or (get (:gen-overrides v) 0) 'gen-coord)]
    `(~'defspec ~(spec-name k) 50
       (~'prop/for-all [~'v ~'gen-vector3d
                        ~'d ~d-gen]
         (let [~'h                (~'rpc/jts->wasmts-vector3d ~'v)
               ~'jts-result       (. ~'v ~(symbol method) (~'double ~'d))
               ~'wasmts-handle    (~'rpc/call! ~path ~'h (~'double ~'d))
               [~'wx ~'wy ~'wz]   (~'rpc/wasmts-vector3d-xyz ~'wasmts-handle)]
           (try
             (and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6)
                  (~'rpc/close-enough? (.getZ ~'jts-result) (~'double ~'wz) 1e-6))
             (finally
               (~'rpc/release! ~'h)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "Vector3D" "double"                          []]                                     [k v] (vector3d-unary-double-spec          k v))
(defmethod spec-form [:instance "Vector3D" "org.locationtech.jts.math.Vector3D" []]                                     [k v] (vector3d-unary-vector3d-spec        k v))
(defmethod spec-form [:instance "Vector3D" "double"                          ["org.locationtech.jts.math.Vector3D"]] [k v] (vector3d-vector3d-arg-double-spec   k v))
(defmethod spec-form [:instance "Vector3D" "org.locationtech.jts.math.Vector3D" ["org.locationtech.jts.math.Vector3D"]] [k v] (vector3d-vector3d-arg-vector3d-spec k v))
(defmethod spec-form [:instance "Vector3D" "org.locationtech.jts.math.Vector3D" ["double"]]                            [k v] (vector3d-double-arg-vector3d-spec   k v))

;; Plane3D has no getters for its private (normal, basePt) fields, so the
;; fixture supplies a [Vector3D Coordinate] pair; each spec constructs the JTS
;; Plane3D inline and ships the pair through jts->wasmts-plane3d.

(defn- plane3d-unary-int-spec [k v]
  (let [klass  (symbol (:class k))
        method (symbol (:method k))
        path   (js-path k (:js-path v))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [[~'n ~'b] ~'gen-plane3d-parts]
         (let [~'p              (new ~klass ~'n ~'b)
               ~'h              (~'rpc/jts->wasmts-plane3d ~'n ~'b)
               ~'jts-result     (. ~'p ~method)
               ~'wasmts-result  (~'rpc/call! ~path ~'h)]
           (try
             (~'= (~'long ~'jts-result) (~'long ~'wasmts-result))
             (finally (~'rpc/release! ~'h))))))))

(defn- plane3d-coord-arg-double-spec [k v]
  (let [klass  (symbol (:class k))
        method (symbol (:method k))
        path   (js-path k (:js-path v))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [[~'n ~'b] ~'gen-plane3d-parts
                        ~'c       ~'gen-coordinate-3d]
         (let [~'p              (new ~klass ~'n ~'b)
               ~'h              (~'rpc/jts->wasmts-plane3d ~'n ~'b)
               ~'ch             (~'rpc/jts->wasmts-coordinate ~'c)
               ~'jts-result     (. ~'p ~method ~'c)
               ~'wasmts-result  (~'rpc/call! ~path ~'h ~'ch)]
           (try
             (~'rpc/close-enough? (~'double ~'jts-result) (~'double ~'wasmts-result) 1e-9)
             (finally
               (~'rpc/release! ~'ch)
               (~'rpc/release! ~'h))))))))

(defmethod spec-form [:instance "Plane3D" "int"    []]                                       [k v] (plane3d-unary-int-spec        k v))
(defmethod spec-form [:instance "Plane3D" "double" ["org.locationtech.jts.geom.Coordinate"]] [k v] (plane3d-coord-arg-double-spec k v))


(defn- coord-array-equal-form
  "Render a Clojure form that compares a JTS Coordinate[] to a vec of
   [x y] pairs read off a wasmts CoordinateArray handle, with
   close-enough? tolerance per ordinate."
  [jts-array wasmts-xys tol]
  `(~'and (~'= (~'count ~jts-array) (~'count ~wasmts-xys))
          (~'every? (~'fn [[~'jc [~'wx ~'wy]]]
                      (~'and (~'rpc/close-enough? (.getX ~'jc) (~'double ~'wx) ~tol)
                             (~'rpc/close-enough? (.getY ~'jc) (~'double ~'wy) ~tol)))
                    (~'map ~'vector ~jts-array ~wasmts-xys))))

(defn- lineseg-param-info
  "Per-param metadata for one element of the param vector of a
   LineSegment receiver method: input symbol, handle symbol, shape's
   generator, and how it crosses the RPC boundary."
  [idx t]
  (let [j      (inc idx)
        in-sym (symbol (str "a" j))
        h-sym  (symbol (str "h" j))]
    (merge {:idx j :in in-sym :h h-sym :type t}
           (case t
             "double" {:gen 'gen-coord         :ship :prim}
             "int"    {:gen 'gen-vertex-index  :ship :prim}
             "org.locationtech.jts.geom.Coordinate"  {:gen 'gen-coordinate :ship :coord}
             "org.locationtech.jts.geom.LineSegment" {:gen 'gen-lineseg    :ship :lineseg}))))

(defn- lineseg-spec
  "Emit a defspec for any LineSegment receiver method whose (params,
   return) is in supported-shapes. Receiver shipped via
   jts->wasmts-lineseg; return handled by type:
     double / int / boolean -> compared scalar-to-scalar
     Coordinate             -> compared via wasmts-coordinate-xy
     LineSegment            -> compared via wasmts-lineseg-points (4 doubles)."
  [k v]
  (let [path   (js-path k (:js-path v))
        method (symbol (:method k))
        params (:params k)
        ret    (:type (:returns v))
        infos  (vec (map-indexed lineseg-param-info params))
        ;; intersection / lineIntersection / project are nullable; random
        ;; gen-lineseg pairs almost never cross, so gen-lineseg-pair mixes 50%
        ;; guaranteed-crossing pairs to exercise the both-non-nil branch.
        paired-input?
        (and (= (:class k) "org.locationtech.jts.geom.LineSegment")
             (= params ["org.locationtech.jts.geom.LineSegment"])
             (#{"intersection" "lineIntersection" "project"} (:method k)))
        for-all (if paired-input?
                  ['[ls a1] 'gen-lineseg-pair]
                  (into ['ls 'gen-lineseg]
                        (mapcat (fn [{:keys [in gen]}] [in gen]) infos)))
        prep-bindings
        (vec (concat
              ['h-ls `(~'rpc/jts->wasmts-lineseg ~'ls)]
              (mapcat
               (fn [{:keys [in h ship]}]
                 (case ship
                   :prim    []
                   :coord   [h `(~'rpc/jts->wasmts-coordinate ~in)]
                   :lineseg [h `(~'rpc/jts->wasmts-lineseg ~in)]))
               infos)))
        jts-args (mapv (fn [{:keys [in type ship]}]
                         (case ship
                           :prim    (if (= "int" type) `(~'int ~in) `(~'double ~in))
                           :coord   in
                           :lineseg in))
                       infos)
        rpc-args (mapv (fn [{:keys [in h ship]}]
                         (if (= :prim ship) in h))
                       infos)
        param-releases
        (vec (for [{:keys [h ship]} infos
                   :when (#{:coord :lineseg} ship)]
               `(~'rpc/release! ~h)))
        coord-return?      (= ret "org.locationtech.jts.geom.Coordinate")
        lineseg-return?    (= ret "org.locationtech.jts.geom.LineSegment")
        coordarray-return? (= ret "org.locationtech.jts.geom.Coordinate[]")
        handle-return?     (or coord-return? lineseg-return? coordarray-return?)
        ;; Handle-returning methods bind the raw handle and decode lazily in
        ;; the comparison's `:else` branch — a nil handle (no intersection)
        ;; shouldn't NPE on decode before the nil-on-JTS-side short-circuit.
        result-bindings
        (if handle-return?
          ['jts-result    `(. ~'ls ~method ~@jts-args)
           'wasmts-handle `(~'rpc/call! ~path ~'h-ls ~@rpc-args)]
          ['jts-result    `(. ~'ls ~method ~@jts-args)
           'wasmts-result `(~'rpc/call! ~path ~'h-ls ~@rpc-args)])
        all-bindings (vec (concat prep-bindings result-bindings))
        all-releases (cond-> (into [`(~'rpc/release! ~'h-ls)] param-releases)
                       handle-return?
                       (conj `(~'rpc/release! ~'wasmts-handle)))
        comparison
        (case ret
          "double"  `(~'rpc/close-enough? (~'double ~'jts-result) (~'double ~'wasmts-result) 1e-6)
          "int"     `(~'= (~'long ~'jts-result) (~'long ~'wasmts-result))
          "boolean" `(~'= ~'jts-result (~'boolean ~'wasmts-result))
          "org.locationtech.jts.geom.Coordinate"
          `(~'cond
             (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
             (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
             :else
             (~'let [[~'wx ~'wy] (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
               (~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                      (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6))))
          "org.locationtech.jts.geom.LineSegment"
          `(~'cond
             (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
             (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
             :else
             (~'let [[[~'wx0 ~'wy0] [~'wx1 ~'wy1]] (~'rpc/wasmts-lineseg-points ~'wasmts-handle)]
               (~'and (~'rpc/close-enough? (.getX (.-p0 ~'jts-result)) (~'double ~'wx0) 1e-6)
                      (~'rpc/close-enough? (.getY (.-p0 ~'jts-result)) (~'double ~'wy0) 1e-6)
                      (~'rpc/close-enough? (.getX (.-p1 ~'jts-result)) (~'double ~'wx1) 1e-6)
                      (~'rpc/close-enough? (.getY (.-p1 ~'jts-result)) (~'double ~'wy1) 1e-6))))
          "org.locationtech.jts.geom.Coordinate[]"
          `(~'cond
             (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
             (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
             :else
             (~'let [~'wasmts-xys (~'rpc/wasmts-coord-array-xys ~'wasmts-handle)]
               ~(coord-array-equal-form 'jts-result 'wasmts-xys 1e-6))))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all ~for-all
         (let ~all-bindings
           (try ~comparison (finally ~@all-releases)))))))

(defmethod spec-form [:instance "LineSegment" "double"                                []]                                          [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "boolean"                               []]                                          [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  []]                                          [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  ["org.locationtech.jts.geom.Coordinate"]]    [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "double"                                ["org.locationtech.jts.geom.Coordinate"]]    [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "int"                                   ["org.locationtech.jts.geom.Coordinate"]]    [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "boolean"                               ["org.locationtech.jts.geom.LineSegment"]]   [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  ["org.locationtech.jts.geom.LineSegment"]]   [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "double"                                ["org.locationtech.jts.geom.LineSegment"]]   [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "int"                                   ["org.locationtech.jts.geom.LineSegment"]]   [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.LineSegment" ["org.locationtech.jts.geom.LineSegment"]]   [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  ["double"]]                                  [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.LineSegment" ["double"]]                                  [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  ["double" "double"]]                         [k v] (lineseg-spec k v))
(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate"  ["int"]]                                     [k v] (lineseg-spec k v))


(defn- static-param-info
  "Per-param metadata for one element of the param vector: generator
   symbol from fixtures, ship mode, and the JTS-side expression form.

   `overrides` is a map of param-idx -> generator symbol pulled from the
   entry's :gen-overrides hint. When the idx is in the map, its symbol
   replaces the default per-type :gen; the :ship mode stays type-driven
   so the override only affects which generator runs, not how the value
   crosses the boundary."
  [idx t overrides]
  (let [j      (inc idx)
        in-sym (symbol (str "a" j))
        g-sym  (symbol (str "g" j))
        h-sym  (symbol (str "h" j))
        defaults (case t
                   "double" {:gen 'gen-coord :ship :prim}
                   "int"    {:gen 'gen-coord :ship :prim}
                   "org.locationtech.jts.geom.Coordinate" {:gen 'gen-coordinate :ship :coord}
                   "org.locationtech.jts.geom.Geometry"   {:gen 'gen-wkt        :ship :geom})]
    (merge {:idx j :in in-sym :g g-sym :h h-sym :type t}
           defaults
           (when-let [g (get overrides idx)] {:gen g}))))

(defn- static-spec
  "Emit a defspec for any static method whose (params, return) is covered
   by codegen-common/supported-shapes. Builds JTS-side call via FQN class
   symbol so the generated test ns needs no extra :imports."
  [k v]
  (let [path      (js-path k (:js-path v))
        klass     (symbol (:class k))
        method    (symbol (:method k))
        params    (:params k)
        ret       (:type (:returns v))
        overrides (or (:gen-overrides v) {})
        tuple-gen (:gen-tuple v)
        infos     (vec (map-indexed #(static-param-info %1 %2 overrides) params))
        for-all  (if tuple-gen
                   [(mapv :in infos) tuple-gen]
                   (vec (mapcat (fn [{:keys [in gen]}] [in gen]) infos)))
        prep-bindings
        (vec (mapcat
              (fn [{:keys [in g h ship]}]
                (case ship
                  :prim  []
                  :coord [h `(~'rpc/jts->wasmts-coordinate ~in)]
                  :geom  [g `(~'rpc/read-jts ~in)
                          h `(~'rpc/jts->wasmts ~g)]))
              infos))
        jts-args (mapv (fn [{:keys [in g ship type]}]
                         (case ship
                           :prim  (if (= "int" type) `(~'int ~in) `(~'double ~in))
                           :coord in
                           :geom  g))
                       infos)
        rpc-args (mapv (fn [{:keys [in h ship]}]
                         (if (= :prim ship) in h))
                       infos)
        releases (vec (for [{:keys [h ship]} infos
                            :when (#{:coord :geom} ship)]
                        `(~'rpc/release! ~h)))
        coord-return?    (= ret "org.locationtech.jts.geom.Coordinate")
        vector3d-return? (= ret "org.locationtech.jts.math.Vector3D")
        handle-return?   (or coord-return? vector3d-return?)
        result-bindings
        (cond
          coord-return?
          ['jts-result    `(. ~klass ~method ~@jts-args)
           'wasmts-handle `(~'rpc/call! ~path ~@rpc-args)
           '[wx wy]       `(~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
          vector3d-return?
          ['jts-result    `(. ~klass ~method ~@jts-args)
           'wasmts-handle `(~'rpc/call! ~path ~@rpc-args)
           '[wx wy wz]    `(~'rpc/wasmts-vector3d-xyz ~'wasmts-handle)]
          :else
          ['jts-result    `(. ~klass ~method ~@jts-args)
           'wasmts-result `(~'rpc/call! ~path ~@rpc-args)])
        all-bindings (vec (concat prep-bindings result-bindings))
        all-releases (cond-> releases
                       handle-return? (conj `(~'rpc/release! ~'wasmts-handle)))
        comparison
        (case ret
          "double"
          `(~'rpc/close-enough? (~'double ~'jts-result) (~'double ~'wasmts-result) 1e-9)
          "int"
          `(~'= (~'long ~'jts-result) (~'long ~'wasmts-result))
          "boolean"
          `(~'= ~'jts-result (~'boolean ~'wasmts-result))
          "org.locationtech.jts.geom.Coordinate"
          `(~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-9)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-9))
          "org.locationtech.jts.math.Vector3D"
          `(~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-9)
                  (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-9)
                  (~'rpc/close-enough? (.getZ ~'jts-result) (~'double ~'wz) 1e-9)))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all ~for-all
         (let ~all-bindings
           ~(if (seq all-releases)
              `(try ~comparison (finally ~@all-releases))
              comparison))))))

(defmethod spec-form [:static "double"                              ["double"]]                                                                                                                                                                          [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["double" "double"]]                                                                                                                                                                 [k v] (static-spec k v))
(defmethod spec-form [:static "int"                                 ["double" "double"]]                                                                                                                                                                 [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate"]]                                                                                                                                            [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                                                                     [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                              [k v] (static-spec k v))
(defmethod spec-form [:static "boolean"                             ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                              [k v] (static-spec k v))
(defmethod spec-form [:static "int"                                 ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                              [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                       [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate" ["org.locationtech.jts.geom.Coordinate" "double" "double"]]                                                                                                                         [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate" ["org.locationtech.jts.geom.Geometry"]]                                                                                                                                             [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate" ["org.locationtech.jts.geom.Coordinate"]]                                                                                                                                           [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.math.Vector3D"   ["org.locationtech.jts.geom.Coordinate"]]                                                                                                                                           [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.math.Vector3D"   ["double" "double" "double"]]                                                                                                                                                       [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate" ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                                                                    [k v] (static-spec k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate" ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                                                             [k v] (static-spec k v))
(defmethod spec-form [:static "boolean"                             ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate"]]                       [k v] (static-spec k v))
(defmethod spec-form [:static "int"                                 ["int" "int"]]                                                                                                                                                                       [k v] (static-spec k v))
(defmethod spec-form [:static "int"                                 ["int" "int" "int"]]                                                                                                                                                                 [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["double" "double" "double"]]                                                                                                                                                        [k v] (static-spec k v))
(defmethod spec-form [:static "double"                              ["double" "double" "double" "double"]]                                                                                                                                               [k v] (static-spec k v))


(defn- static-coordarray-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'coords ~'gen-coord-array]
         (let [~'handles (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result    (. ~klass ~method ~'coords)
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'handles))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release-all-coords! ~'handles))))))))

(defn- static-coord-coordarray-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'p ~'gen-coordinate
                        ~'coords ~'gen-coord-array]
         (let [~'hp      (~'rpc/jts->wasmts-coordinate ~'p)
               ~'handles (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result    (double (. ~klass ~method ~'p ~'coords))
               ~'wasmts-result (double (~'rpc/call! ~path ~'hp ~'handles))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release! ~'hp)
               (~'rpc/release-all-coords! ~'handles))))))))

(defn- static-coordarray-double-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'coords ~'gen-coord-array]
         (let [~'handles (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result    (double (. ~klass ~method ~'coords))
               ~'wasmts-result (double (~'rpc/call! ~path ~'handles))]
           (try
             (~'rpc/close-enough? ~'jts-result ~'wasmts-result 1e-6)
             (finally
               (~'rpc/release-all-coords! ~'handles))))))))

(defn- static-coord-coordarray-bool-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'p ~'gen-coordinate
                        ~'coords ~'gen-coord-array]
         (let [~'hp      (~'rpc/jts->wasmts-coordinate ~'p)
               ~'handles (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result    (. ~klass ~method ~'p ~'coords)
               ~'wasmts-result (boolean (~'rpc/call! ~path ~'hp ~'handles))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'hp)
               (~'rpc/release-all-coords! ~'handles))))))))

(defn- static-coord-coordarray-int-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'p ~'gen-coordinate
                        ~'coords ~'gen-coord-array]
         (let [~'hp      (~'rpc/jts->wasmts-coordinate ~'p)
               ~'handles (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result    (long (. ~klass ~method ~'p ~'coords))
               ~'wasmts-result (long (~'rpc/call! ~path ~'hp ~'handles))]
           (try
             (= ~'jts-result ~'wasmts-result)
             (finally
               (~'rpc/release! ~'hp)
               (~'rpc/release-all-coords! ~'handles))))))))

(defn- static-coordarray-coordarray-spec [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'coords ~'gen-coord-array]
         (let [~'handles        (~'rpc/jts->wasmts-coord-array ~'coords)
               ~'jts-result     (. ~klass ~method ~'coords)
               ~'wasmts-handle  (~'rpc/call! ~path ~'handles)
               ~'wasmts-xys     (~'rpc/wasmts-coord-array-xys ~'wasmts-handle)]
           (try
             ~(coord-array-equal-form 'jts-result 'wasmts-xys 1e-6)
             (finally
               (~'rpc/release-all-coords! ~'handles)
               (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:static "boolean"                             ["org.locationtech.jts.geom.Coordinate[]"]]                                       [k v] (static-coordarray-bool-spec         k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate[]"]]                                       [k v] (static-coordarray-double-spec       k v))
(defmethod spec-form [:static "double"                              ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate[]"]] [k v] (static-coord-coordarray-double-spec k v))
(defmethod spec-form [:static "boolean"                             ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate[]"]] [k v] (static-coord-coordarray-bool-spec   k v))
(defmethod spec-form [:static "int"                                 ["org.locationtech.jts.geom.Coordinate" "org.locationtech.jts.geom.Coordinate[]"]] [k v] (static-coord-coordarray-int-spec    k v))
(defmethod spec-form [:static "org.locationtech.jts.geom.Coordinate[]" ["org.locationtech.jts.geom.Coordinate[]"]]                                    [k v] (static-coordarray-coordarray-spec   k v))


(def ^:private ns-form
  '(ns wasmts.differential.generated-test
     "AUTO-GENERATED by script/emit_tests.clj from registry.edn.
      Do not edit by hand. Run `bb gen:tests` to regenerate.

      One defspec per in-scope JTS method on Geometry. The shared
      `gen-wkt` generator + `wasmts.differential.core` RPC primitives
      live in wasmts.differential.fixtures (hand-written)."
     (:require [clojure.test :refer [use-fixtures]]
               [clojure.test.check.clojure-test :refer [defspec]]
               [clojure.test.check.properties :as prop]
               [wasmts.differential.core :as rpc]
               [wasmts.differential.fixtures
                :refer [gen-wkt gen-envelope gen-coordinate
                        gen-precision-model gen-geomfactory gen-coord
                        gen-triangle gen-lineseg gen-lineseg-pair
                        gen-vertex-index gen-coord-array gen-circle-quad
                        gen-vector3d gen-nz-coord gen-nz-int
                        gen-pos-coord gen-unit-frac gen-unit-signed
                        gen-coordinate-3d gen-distance-point-segment
                        gen-distance-segment-segment
                        gen-line-wkt gen-point-wkt gen-irregular-wkt
                        gen-wkt-3d gen-distinct-coordinate-pair gen-polygon-node
                        gen-plane3d-parts
                        with-runner-once]])))

(def ^:private use-fixtures-form
  '(use-fixtures :once with-runner-once))

(defn- test-skip?
  "True when (manual.edn :test-skip) lists this entry. Class symbol ->
     :all                              ; skip every method on the class
     #{method-name}                    ; skip by name (all overloads)
     #{[method-name params-vec]}       ; skip a specific overload
   Mixed entries are fine — the set can contain both strings and vectors.
   Distinct from :skip in that the dispatch + d.ts surface still install;
   only the differential test is suppressed."
  [{:keys [class method params]} skip-map]
  (let [entry (get skip-map (symbol class))]
    (or (= entry :all)
        (and (set? entry)
             (or (contains? entry method)
                 (contains? entry [method params]))))))

(defn- read-test-skip []
  (let [f (io/file "manual.edn")]
    (if (.exists f)
      (-> f slurp edn/read-string :test-skip (or {}))
      {})))

;; ============================================================
;; Generic template engine — spec-form's :default.
;;
;; Mirrors emit_api's 3 generic dispatch templates ({:kind
;; :receiver-call | :static-call | :ctor}). The hand-written defmethods
;; above cover bespoke shapes; this engine fills the rest: for any
;; {:kind ...}-shaped entry whose receiver and every param have an entry
;; in gen-input-table and whose canonicalised return type is comparable,
;; it assembles a defspec the same way the per-shape builders do
;; (generate JTS inputs, ship to wasmts, call both sides, compare with
;; tolerance, release handles). Any unsupported type returns nil, so it
;; is purely additive — the hand tuples stay authoritative.
;;
;; Deliberately omitted for now (each would otherwise emit throwing or
;; vacuous tests): int/long params (index-out-of-bounds risk — needs
;; index-aware generators), String params (parse-failure risk), constant
;; fields (the :static-*-field shapes need a value-read template, not a
;; method call), and any receiver/param/return with no jts<->wasmts
;; marshaller (PreparedGeometry, IntersectionMatrix, Vector2D, DD,
;; QuadEdge, CoordinateSequence, void mutators, ...).

(def ^:private gen-input-table
  "EXACT JTS input type -> {:gen <generator> :mode <marshalling>}.
   :prim ships a raw double; :obj ships the generated JTS object via the
   named :ship fn; :geom reads a WKT string to a JTS Geometry then ships
   via jts->wasmts; :coord-array ships a JTS Coordinate[] as a handle
   vector. Keyed on the DECLARED (not canonicalised) type: the runtime
   reflection call `(. recv method arg)` resolves on the actual runtime
   type, so a Polygon generated for a LineString-declared receiver/param
   would not match. gen-wkt produces a Polygon, so it serves Geometry and
   Polygon; Point/LineString get their own WKT generators. Geometry
   subtypes with no generator (LinearRing, the Multi*, GeometryCollection)
   are absent on purpose — generic-input returns nil and the entry skips."
  {"double"
   {:gen 'gen-coord :mode :prim}
   "org.locationtech.jts.geom.Coordinate"
   {:gen 'gen-coordinate :mode :obj :ship 'rpc/jts->wasmts-coordinate}
   "org.locationtech.jts.geom.Geometry"
   {:gen 'gen-wkt :mode :geom}
   "org.locationtech.jts.geom.Polygon"
   {:gen 'gen-wkt :mode :geom}
   "org.locationtech.jts.geom.Point"
   {:gen 'gen-point-wkt :mode :geom}
   "org.locationtech.jts.geom.LineString"
   {:gen 'gen-line-wkt :mode :geom}
   "org.locationtech.jts.geom.Envelope"
   {:gen 'gen-envelope :mode :obj :ship 'rpc/jts->wasmts-envelope}
   "org.locationtech.jts.geom.PrecisionModel"
   {:gen 'gen-precision-model :mode :obj :ship 'rpc/jts->wasmts-pm}
   "org.locationtech.jts.geom.Triangle"
   {:gen 'gen-triangle :mode :obj :ship 'rpc/jts->wasmts-triangle}
   "org.locationtech.jts.geom.LineSegment"
   {:gen 'gen-lineseg :mode :obj :ship 'rpc/jts->wasmts-lineseg}
   "org.locationtech.jts.math.Vector3D"
   {:gen 'gen-vector3d :mode :obj :ship 'rpc/jts->wasmts-vector3d}
   "org.locationtech.jts.geom.GeometryFactory"
   {:gen 'gen-geomfactory :mode :obj :ship 'rpc/jts->wasmts-gf}
   "org.locationtech.jts.geom.Coordinate[]"
   {:gen 'gen-coord-array :mode :coord-array :ship 'rpc/jts->wasmts-coord-array}})

(defn- double-gen-for
  "Pick a double generator by the param's LVT name so the generated value
   lands in the method's valid domain, avoiding a JTS-oracle throw on
   out-of-domain input. Ratios/fractions -> (0,1]; tolerances / lengths /
   radii / widths -> strictly positive; everything else (incl. signed
   `distance`, e.g. buffer) -> the signed gen-coord. nil name -> gen-coord."
  [pname]
  (let [n (some-> pname str/lower-case)]
    (cond
      (nil? n)                                     'gen-coord
      (re-find #"ratio|frac" n)                    'gen-unit-frac
      (re-find #"tolerance|length|radius|width" n) 'gen-pos-coord
      ;; a compound distance name (startDistance / endDistance, the
      ;; VariableBuffer widths) is a non-negative width; bare "distance"
      ;; (Geometry.buffer) stays signed so erosion stays in coverage.
      (and (re-find #"distance" n) (not= n "distance")) 'gen-pos-coord
      :else                                        'gen-coord)))

(defn- geom-gen-for
  "For a param declared as the base Geometry, narrow the WKT generator by
   the LVT param name when the method needs a specific subtype: a name
   containing `line` -> gen-line-wkt, `point` -> gen-point-wkt. nil
   otherwise (the gen-input-table default gen-wkt, a Polygon). The narrowed
   subtype is still a valid Geometry, so this never violates the declared
   contract; it rescues methods like VariableBuffer.buffer(line, ...) that
   declare Geometry but internally cast to LineString."
  [pname]
  (let [n (some-> pname str/lower-case)]
    (cond
      (nil? n)             nil
      (re-find #"line" n)  'gen-line-wkt
      (re-find #"point" n) 'gen-point-wkt
      :else                nil)))

(defn- generic-input
  "Marshalling plan for one generated input (receiver or param). `base`
   is the stable symbol stem (\"recv\", \"a1\", ...). `override-gen`, when
   non-nil, replaces the table's generator (used to pick a domain-correct
   double generator by param name). nil when the declared type has no
   gen-input-table entry. Keys: :for-all (binding pair), :prep (let
   bindings to JTS value + wasmts handle), :jts (JTS-call arg), :rpc
   (call! arg), :rel (release forms)."
  [base t override-gen]
  (when-let [{:keys [gen mode ship]} (gen-input-table t)]
    (let [gen (or override-gen gen)
          in (symbol base)
          g  (symbol (str base "-g"))
          h  (symbol (str base "-h"))]
      (case mode
        :prim
        {:for-all [in gen] :prep [] :jts `(~'double ~in) :rpc in :rel []}
        :obj
        {:for-all [in gen] :prep [h `(~ship ~in)]
         :jts in :rpc h :rel [`(~'rpc/release! ~h)]}
        :geom
        {:for-all [in gen]
         :prep [g `(~'rpc/read-jts ~in) h `(~'rpc/jts->wasmts ~g)]
         :jts g :rpc h :rel [`(~'rpc/release! ~h)]}
        :coord-array
        {:for-all [in gen] :prep [h `(~ship ~in)]
         :jts in :rpc h :rel [`(~'rpc/release-all-coords! ~h)]}))))

(defn- handle-return
  "Result plan for a return type whose wasmts result is a handle decoded
   by `else-form`. Null-aware: matching nils pass, a lone nil fails."
  [jts-call wasmts-call else-form]
  {:bindings ['jts-result jts-call 'wasmts-handle wasmts-call]
   :comparison `(~'cond
                 (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
                 (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
                 :else ~else-form)
   :releases [`(~'rpc/release! ~'wasmts-handle)]})

(defn- compare-return
  "Result plan for a method carrying a manual.edn :compare hint. Overrides
   the default geometry compare for construction methods whose port output
   is geometrically correct but not vertex-for-vertex identical to the JVM
   oracle (a near-duplicate vertex, or a tie-broken non-canonical
   representative on a symmetric input). The compared quantity is the one
   the algorithm actually determines:
     :same-shape — symmetric-difference / Hausdorff (port emits the same
                   shape with a differing vertex count: VariableBuffer,
                   OffsetCurve, CubicBezierCurve, Densifier).
     :length     — result LENGTH (the determined scalar): MinimumClearance
                   / SimpleMinimumClearance getLine = clearance distance,
                   LargestEmptyCircle getRadiusLine = circle radius.
     :area       — result AREA: MinimumAreaRectangle getMinimumRectangle,
                   the area-criterion simplifiers (DouglasPeucker / VW).
     :clearance  — distance from the returned center POINT to the obstacles
                   arg (the LEC radius). For LargestEmptyCircle.getCenter,
                   whose center sits on a symmetric locus under regular
                   obstacle/boundary polygons: the two tied centers are
                   genuinely different points (no geometry compare reconciles
                   them) but carry the same clearance. ASSUMES the obstacles
                   geometry is the first geom param (bound `a1-g` by
                   generic-spec); the only :clearance user satisfies this.
   See docs/plans for the divergence investigation that classified these."
  [compare jts-call wasmts-call]
  (handle-return
   jts-call wasmts-call
   (case compare
     :same-shape `(~'rpc/geom-same-shape? ~'jts-result
                   (~'rpc/wasmts->jts ~'wasmts-handle) 1.0e-6)
     :length `(~'rpc/close-enough? (.getLength ~'jts-result)
               (.getLength (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)
     :area `(~'rpc/close-enough? (.getArea ~'jts-result)
             (.getArea (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)
     :clearance `(~'rpc/close-enough? (.distance ~'a1-g ~'jts-result)
                  (.distance ~'a1-g (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6))))

(defn- generic-return
  "Result plan ({:bindings :comparison :releases}) for the canonicalised
   return type, given the assembled JTS-call and wasmts-call forms. A
   non-nil `compare` (manual.edn :compare hint) overrides the default
   compare for the return type. nil for un-comparable return types."
  [ret jts-call wasmts-call compare]
  (if compare
    (compare-return compare jts-call wasmts-call)
    (let [r (canonical-type ret)]
    (cond
      (= r "double")
      {:bindings ['jts-result jts-call 'wasmts-result wasmts-call]
       :comparison `(~'rpc/close-enough? (~'double ~'jts-result) (~'double ~'wasmts-result) 1e-9)
       :releases []}
      (#{"int" "long" "short" "byte"} r)
      {:bindings ['jts-result jts-call 'wasmts-result wasmts-call]
       :comparison `(~'= (~'long ~'jts-result) (~'long ~'wasmts-result))
       :releases []}
      (= r "boolean")
      {:bindings ['jts-result jts-call 'wasmts-result wasmts-call]
       :comparison `(~'= ~'jts-result (~'boolean ~'wasmts-result))
       :releases []}
      (= r "java.lang.String")
      {:bindings ['jts-result jts-call 'wasmts-result wasmts-call]
       :comparison `(~'= (~'str ~'jts-result) (~'str ~'wasmts-result))
       :releases []}
      (= r "org.locationtech.jts.geom.Coordinate")
      (handle-return jts-call wasmts-call
                     `(~'let [[~'wx ~'wy] (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
                        (~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-9)
                               (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-9))))
      (= r "org.locationtech.jts.geom.Coordinate[]")
      (handle-return jts-call wasmts-call
                     `(~'let [~'wasmts-xys (~'rpc/wasmts-coord-array-xys ~'wasmts-handle)]
                        ~(coord-array-equal-form 'jts-result 'wasmts-xys 1e-9)))
      (= r "org.locationtech.jts.geom.Envelope")
      (handle-return jts-call wasmts-call
                     `(~'rpc/envelope-bounds-equal? ~'jts-result
                       (~'rpc/wasmts-envelope-bounds ~'wasmts-handle) 1e-9))
      (= r "org.locationtech.jts.math.Vector3D")
      (handle-return jts-call wasmts-call
                     `(~'let [[~'wx ~'wy ~'wz] (~'rpc/wasmts-vector3d-xyz ~'wasmts-handle)]
                        (~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-9)
                               (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-9)
                               (~'rpc/close-enough? (.getZ ~'jts-result) (~'double ~'wz) 1e-9))))
      (= r "org.locationtech.jts.geom.LineSegment")
      (handle-return jts-call wasmts-call
                     `(~'let [[[~'wx0 ~'wy0] [~'wx1 ~'wy1]] (~'rpc/wasmts-lineseg-points ~'wasmts-handle)]
                        (~'and (~'rpc/close-enough? (.getX (.-p0 ~'jts-result)) (~'double ~'wx0) 1e-9)
                               (~'rpc/close-enough? (.getY (.-p0 ~'jts-result)) (~'double ~'wy0) 1e-9)
                               (~'rpc/close-enough? (.getX (.-p1 ~'jts-result)) (~'double ~'wx1) 1e-9)
                               (~'rpc/close-enough? (.getY (.-p1 ~'jts-result)) (~'double ~'wy1) 1e-9))))
      (= r "org.locationtech.jts.geom.PrecisionModel")
      (handle-return jts-call wasmts-call
                     `(~'rpc/close-enough? (.getScale ~'jts-result)
                       (~'rpc/wasmts-pm-scale ~'wasmts-handle) 1e-9))
      (= r "org.locationtech.jts.geom.Geometry")
      ;; Normalize both sides before equalsExact: .norm gives a canonical
      ;; vertex order/start, so a geometrically-equal result whose vertices
      ;; the wasmts port happens to emit in a different order (common for
      ;; simplify / variable-buffer / min-rectangle, where the order isn't
      ;; canonical) still compares equal. A genuine coordinate divergence
      ;; beyond 1e-6 still fails. (The hand-written geom builders keep their
      ;; plain equalsExact — they cover operations that agree exactly.)
      (handle-return jts-call wasmts-call
                     `(.equalsExact (.norm ~'jts-result)
                                    (.norm (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6))))))

(defn- generic-spec
  "spec-form's :default. Build a defspec for a {:kind ...}-shaped entry
   whose receiver + every param + return are supported; nil otherwise."
  [k v]
  (let [{:keys [class method params]}       k
        {:keys [returns shape param-names]}  v]
    (when (and (map? shape) (#{:receiver-call :static-call :ctor} (:kind shape)))
      (let [kind     (:kind shape)
            recv     (when (= kind :receiver-call) (generic-input "recv" class nil))
            pins     (vec (map-indexed
                           (fn [i t]
                             (generic-input (str "a" (inc i)) t
                                            ;; a per-method :gen-overrides hint (position -> generator
                                            ;; symbol) wins over the type/param-name default. Used to
                                            ;; bound CubicBezierCurve's alpha/skew into their in-domain
                                            ;; band (see manual.edn :hints).
                                            (or (get (:gen-overrides v) i)
                                                (cond
                                                  (= t "double")
                                                  (double-gen-for (get param-names i))
                                                  (= t "org.locationtech.jts.geom.Geometry")
                                                  (geom-gen-for (get param-names i))
                                                  :else nil))))
                           params))
            ret-type (if (= kind :ctor) class (:type returns))]
        (when (and (or (not= kind :receiver-call) recv)
                   (every? some? pins))
          (let [path     (js-path k (:js-path v))
                jts-args (mapv :jts pins)
                rpc-args (mapv :rpc pins)
                jts-call (case kind
                           :receiver-call `(. ~(:jts recv) ~(symbol method) ~@jts-args)
                           :static-call   `(. ~(symbol class) ~(symbol method) ~@jts-args)
                           :ctor          `(new ~(symbol class) ~@jts-args))
                wasmts-call (if (= kind :receiver-call)
                              `(~'rpc/call! ~path ~(:rpc recv) ~@rpc-args)
                              `(~'rpc/call! ~path ~@rpc-args))
                rplan    (generic-return ret-type jts-call wasmts-call (:compare v))]
            (when rplan
              (let [for-all  (if-let [tg (and (not recv) (:gen-tuple v))]
                               ;; a :gen-tuple hint binds the whole param vector
                               ;; from one generator (so it can constrain across
                               ;; params, e.g. a distinct coordinate pair for
                               ;; Octant/Quadrant); the per-param prep/ship still
                               ;; runs off the destructured a1..aN symbols.
                               [(mapv #(symbol (str "a" (inc %))) (range (count params))) tg]
                               (vec (concat (when recv (:for-all recv))
                                            (mapcat :for-all pins))))
                    prep     (vec (concat (when recv (:prep recv))
                                          (mapcat :prep pins)))
                    bindings (vec (concat prep (:bindings rplan)))
                    rel      (vec (concat (when recv (:rel recv))
                                          (mapcat :rel pins)
                                          (:releases rplan)))]
                `(~'defspec ~(spec-name k) 50
                   (~'prop/for-all ~for-all
                    (let ~bindings
                      ~(if (seq rel)
                         `(try ~(:comparison rplan) (finally ~@rel))
                         (:comparison rplan)))))))))))))

(defn emit-file [registry]
  (let [skip-map (read-test-skip)
        resolved (->> registry
                      (filter in-scope?)
                      (remove (fn [[k _]] (test-skip? k skip-map)))
                      dedup-by-path)
        forms    (->> resolved
                      (keep (fn [[k v]] (spec-form k v))))
        sw       (java.io.StringWriter.)
        pp       #(binding [*print-namespace-maps* false]
                    (pprint/pprint % sw))]
    (pp ns-form)
    (.write sw "\n")
    (pp use-fixtures-form)
    (.write sw "\n")
    (doseq [form forms]
      (pp form)
      (.write sw "\n"))
    (.toString sw)))


(def ^:private output-path
  "test/clj/wasmts/differential/generated_test.clj")

(defn -main [& _]
  (let [registry (edn/read-string (slurp "registry.edn"))
        body     (emit-file registry)
        skip-map (read-test-skip)
        in-scope (filter in-scope? registry)
        kept     (remove (fn [[k _]] (test-skip? k skip-map)) in-scope)
        resolved (dedup-by-path kept)
        emitted  (->> resolved (keep (fn [[k v]] (spec-form k v))) count)
        skipped  (- (count in-scope) (count kept))]
    (io/make-parents output-path)
    (spit output-path body)
    (println "Wrote" output-path)
    (println (format "  defspecs emitted:    %d of %d in-scope (%d test-skipped, rest have no template)"
                     emitted (count in-scope) skipped))))
