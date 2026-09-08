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
   [codegen-common :refer [js-path in-scope? dedup-by-path simple-name canonical-type]]
   [emit-tests-compare :refer [generic-return]]))

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

;; The main path. The generic template engine (below) builds a defspec for
;; {:kind ...}-shaped entries whose receiver + every param has a
;; generator/shipper and whose return has a comparator, mirroring emit_api's
;; 3 generic dispatch templates. It emits 418 of the 428 specs. The five
;; tuples below it are the exceptions, kept because the engine returns nil
;; for them; between them they cover the remaining 10 entries (the two
;; :static "int" tuples match 5 and 2 registry entries each).
(defmethod spec-form :default [k v] (generic-spec k v))

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

(defn- lineseg-coordinate-spec
  "LineSegment.getCoordinate(int). The index comes from gen-vertex-index.
   The returned handle is decoded only after both nil checks, so a nil
   handle does not throw in the decode."
  [k v]
  (let [path   (js-path k (:js-path v))
        method (symbol (:method k))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all [~'ls ~'gen-lineseg ~'a1 ~'gen-vertex-index]
         (let [~'h-ls          (~'rpc/jts->wasmts-lineseg ~'ls)
               ~'jts-result    (. ~'ls ~method (~'int ~'a1))
               ~'wasmts-handle (~'rpc/call! ~path ~'h-ls ~'a1)]
           (try
             (~'cond
               (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
               (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
               :else
               (~'let [[~'wx ~'wy] (~'rpc/wasmts-coordinate-xy ~'wasmts-handle)]
                 (~'and (~'rpc/close-enough? (.getX ~'jts-result) (~'double ~'wx) 1e-6)
                        (~'rpc/close-enough? (.getY ~'jts-result) (~'double ~'wy) 1e-6))))
             (finally (~'rpc/release! ~'h-ls) (~'rpc/release! ~'wasmts-handle))))))))

(defmethod spec-form [:instance "LineSegment" "org.locationtech.jts.geom.Coordinate" ["int"]] [k v] (lineseg-coordinate-spec k v))

(defn- static-int-spec
  "A static int method over int params. Each param draws from gen-coord,
   or from the generator a :gen-overrides hint names for that position
   (gen-nz-int for a divisor)."
  [k v]
  (let [path   (js-path k (:js-path v))
        klass  (symbol (:class k))
        method (symbol (:method k))
        ins    (mapv #(symbol (str "a" (inc %))) (range (count (:params k))))
        gens   (mapv #(get (:gen-overrides v) % 'gen-coord) (range (count ins)))]
    `(~'defspec ~(spec-name k) 100
       (~'prop/for-all ~(vec (interleave ins gens))
         (let [~'jts-result    (. ~klass ~method ~@(map (fn [in] `(~'int ~in)) ins))
               ~'wasmts-result (~'rpc/call! ~path ~@ins)]
           (~'= (~'long ~'jts-result) (~'long ~'wasmts-result)))))))

(defmethod spec-form [:static "int" ["int" "int"]]       [k v] (static-int-spec k v))
(defmethod spec-form [:static "int" ["int" "int" "int"]] [k v] (static-int-spec k v))

(defn- referred-generators
  "Fixture generators the emitted defspecs bind from, sorted.

   Read out of the generator positions of each `prop/for-all` binding vector,
   not by scanning every symbol for a `gen-` prefix. A prefix scan also
   catches a local binding in an emitted body that happens to be named
   `gen-something`, and puts it in the `:refer` list, where it resolves to
   nothing and fails the build. Binding position is what actually makes a
   symbol a generator here."
  [forms]
  (->> forms
       (mapcat #(tree-seq coll? seq %))
       (filter #(and (seq? %) (= 'prop/for-all (first %)) (vector? (second %))))
       (mapcat #(take-nth 2 (rest (second %))))
       (filter #(and (symbol? %) (nil? (namespace %))))
       (into (sorted-set))))

(defn- ns-form
  "The generated file's ns form.

   The fixtures `:refer` list is derived from the emitted defspecs rather
   than hardcoded. A hardcoded list drifts whenever a generator stops being
   selected or a new `:gen-overrides` one appears, and the result is an
   unresolved symbol or an unused refer, which fails the clj-kondo gate
   instead of this build."
  [forms]
  (list 'ns 'wasmts.differential.generated-test
        "AUTO-GENERATED by script/emit_tests.clj from registry.edn.
      Do not edit by hand. Run `bb gen:tests` to regenerate.

      One defspec per in-scope JTS method on Geometry. The shared
      `gen-wkt` generator + `wasmts.differential.core` RPC primitives
      live in wasmts.differential.fixtures (hand-written)."
        (list :require
              '[clojure.test :refer [use-fixtures]]
              '[clojure.test.check.clojure-test :refer [defspec]]
              '[clojure.test.check.properties :as prop]
              '[wasmts.differential.core :as rpc]
              ['wasmts.differential.fixtures
               :refer (conj (vec (referred-generators forms)) 'with-runner-once)])))

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
;; :receiver-call | :static-call | :ctor}) and emits nearly every spec:
;; for any {:kind ...}-shaped entry whose receiver and every param have
;; an entry in gen-input-table and whose canonicalised return type is
;; comparable, it assembles the defspec (generate JTS inputs, ship to
;; wasmts, call both sides, compare with tolerance, release handles).
;; An unsupported receiver, param or return type returns nil and the
;; entry emits nothing.
;;
;; The five hand-written tuples above exist only because the engine
;; returns nil for them: Plane3D has no getters for its private fields
;; so there is no receiver generator, and the other three need int
;; params, which gen-input-table deliberately omits (below). Add a type
;; to gen-input-table and the engine covers it; a new hand tuple is a
;; last resort, not the normal way to add a spec.
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

(defn- generic-input
  "Marshalling plan for one generated input (receiver or param). `base`
   is the stable symbol stem (\"recv\", \"a1\", ...). `override-gen`, when
   non-nil, replaces the table's generator (used to pick a domain-correct
   double generator by param name). nil when the declared type has no
   gen-input-table entry. Keys: :for-all (binding pair), :prep (let
   bindings to JTS value + wasmts handle), :jts (JTS-call arg), :rpc
   (call! arg), :rel (release forms), :geom-sym (the bound JTS Geometry,
   for the geometry-relative :compare modes; absent for non-geometries)."
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
         :jts g :rpc h :rel [`(~'rpc/release! ~h)] :geom-sym g}
        :coord-array
        {:for-all [in gen] :prep [h `(~ship ~in)]
         :jts in :rpc h :rel [`(~'rpc/release-all-coords! ~h)]}))))

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
                                            ;; symbol) wins over the param-name default.
                                            (or (get (:gen-overrides v) i)
                                                (when (= t "double")
                                                  (double-gen-for (get param-names i))))))
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
                ;; The JTS-side symbol of every geometry the call binds, in
                ;; param order (the receiver first when there is one). The
                ;; geometry-relative :compare modes read these instead of
                ;; hardcoding a1-g.
                geom-syms (vec (keep :geom-sym (cons recv pins)))
                rplan    (generic-return k ret-type jts-call wasmts-call v geom-syms)]
            (when rplan
              (let [for-all  (if-let [tg (:gen-tuple v)]
                               ;; a :gen-tuple hint binds the receiver (if any)
                               ;; and every param from one generator, so it can
                               ;; constrain them together: a distinct coordinate
                               ;; pair for Octant/Quadrant, a crossing segment
                               ;; pair for LineSegment.intersection. The prep and
                               ;; ship steps still run off the destructured symbols.
                               [(mapv (comp first :for-all) (remove nil? (cons recv pins))) tg]
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
    (pp (ns-form forms))
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
    ;; Counted in REGISTRY ENTRIES throughout, so the three numbers share a
    ;; denominator. manual.edn's :test-skip is written in method names, and
    ;; one name covers every overload, so its entry count is smaller and the
    ;; two are not comparable — say which is which wherever either is quoted.
    (println (format "  defspecs emitted:    %d of %d in-scope registry entries (%d removed by manual.edn :test-skip, rest have no template)"
                     emitted (count in-scope) skipped))))
