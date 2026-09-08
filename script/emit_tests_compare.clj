(ns emit-tests-compare
  "How a generated defspec decides the port agrees with the JVM oracle.

   Three pieces, in the order emit_tests applies them:

     default-return    the return type's own comparison
     compare-return    a manual.edn :compare hint, replacing it
     jvm-stable-guard  a manual.edn :jvm-stable hint, wrapping either

   Every function here throws rather than returning nil when a hint's
   preconditions are not met. A nil would drop the entry out of the emitted
   suite with no error, no diff (the generated file is gitignored) and no
   count to check it against, so a hint asking for a stronger comparison
   would silently produce no comparison at all."
  (:require [clojure.walk :as walk]
            [codegen-common :refer [canonical-type]]))

(defn- fail [entry msg]
  (throw (ex-info (str "cannot emit a comparison for "
                       (:class entry) "." (:method entry)
                       " " (pr-str (:params entry)) ": " msg)
                  {:entry entry})))

(defn coord-array-equal-form
  "Render a Clojure form that compares a JTS Coordinate[] to a vec of
   [x y z] triples read off a wasmts CoordinateArray handle, with
   close-enough? tolerance per ordinate.

   Z is compared, not skipped: a 3D result (Distance3DOp.nearestPoints)
   would otherwise cross the return leg flattened and the comparison would
   pass on a wrong Z. Verified safe for the 2D specs by reading the raw z
   off every Coordinate[]-returning op through the runner: each one ships
   null, which reads back as NaN on both sides, and close-enough? treats
   two NaNs as equal. No 2D path in the port constructs a Z."
  [jts-array wasmts-xyzs tol]
  `(~'and (~'= (~'count ~jts-array) (~'count ~wasmts-xyzs))
          (~'every? (~'fn [[~'jc [~'wx ~'wy ~'wz]]]
                      (~'and (~'rpc/close-enough? (.getX ~'jc) (~'double ~'wx) ~tol)
                             (~'rpc/close-enough? (.getY ~'jc) (~'double ~'wy) ~tol)
                             (~'rpc/close-enough? (.getZ ~'jc) (~'double ~'wz) ~tol)))
                    (~'map ~'vector ~jts-array ~wasmts-xyzs))))

(defn handle-return
  "Result plan for a return type whose wasmts result is a handle decoded
   by `else-form`. Null-aware: matching nils pass, a lone nil fails."
  [jts-call wasmts-call else-form]
  {:bindings ['jts-result jts-call 'wasmts-handle wasmts-call]
   :comparison `(~'cond
                 (~'and (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) true
                 (~'or  (~'nil? ~'jts-result) (~'nil? ~'wasmts-handle)) false
                 :else ~else-form)
   :releases [`(~'rpc/release! ~'wasmts-handle)]})

(defn compare-return
  "Result plan for a method carrying a manual.edn :compare hint. Overrides the
   default geometry compare with the quantity the algorithm actually
   determines, for construction methods whose port output is geometrically
   correct but not vertex-for-vertex identical to the oracle.

   Each mode's rationale and its users are at the hint in manual.edn :hints.
   :clearance measures against the FIRST geom param, and :pair-distance is
   weaker than the strict compare in two regimes — see
   rpc/nearest-pair-agrees?. The :jvm-stable hint composes with any of these."
  [entry compare jts-call wasmts-call geom-syms]
  (let [else-form
        (case compare
          :same-shape `(~'rpc/geom-same-shape? ~'jts-result
                        (~'rpc/wasmts->jts ~'wasmts-handle) 1.0e-6)
          :length `(~'rpc/close-enough? (.getLength ~'jts-result)
                    (.getLength (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)
          :area `(~'rpc/close-enough? (.getArea ~'jts-result)
                  (.getArea (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)
          :clearance (if-let [obstacles (first geom-syms)]
                       `(~'rpc/close-enough? (.distance ~obstacles ~'jts-result)
                         (.distance ~obstacles (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)
                       (fail entry ":compare :clearance needs a geometry param to measure against, and the entry binds none"))
          :pair-distance (if (= 2 (count geom-syms))
                           `(~'rpc/nearest-pair-agrees?
                             ~'jts-result
                             (~'rpc/wasmts-coord-array-xyzs ~'wasmts-handle)
                             ~(first geom-syms) ~(second geom-syms) 1.0e-6)
                           (fail entry (str ":compare :pair-distance needs exactly two geometry params, entry binds "
                                            (count geom-syms)))))]
    (handle-return jts-call wasmts-call else-form)))

(defn jvm-stable-guard
  "Wrap `comparison` so it asserts only where the JVM answer is a well-defined
   reference, for a manual.edn :jvm-stable hint.

   Where the JVM's own answer moves under a small perturbation of the input,
   comparing the port against it asserts nothing either way, so the case passes
   without being compared. rpc/jvm-stable? holds the perturbation set and
   counts the skips; with-runner-once fails the run if they stop being rare.

   Composes with any :compare mode, since it wraps the finished comparison
   rather than replacing it.

   Every bound geometry is perturbed, not just the first, so a method whose
   second geometry decides the branch cannot report stable without being
   probed for it.

   The sameness test has to match what the comparison then checks: guarding an
   :area compare with a geometry test calls a case unstable whenever a nudge
   reshuffles vertices at constant area. Adding a mode is not mechanical — the
   tolerance has to sit above the largest answer change a same-branch nudge can
   produce, which depends on the method. See rpc/geom-strict-same?."
  [entry comparison jts-call geom-syms compare]
  (when-not (seq geom-syms)
    (fail entry ":jvm-stable needs a geometry param to perturb, and the entry binds none"))
  (let [same?  (case compare
                 nil   'rpc/geom-strict-same?
                 :area 'rpc/geom-area-same?
                 (fail entry (str ":jvm-stable has no sameness test for :compare " (pr-str compare))))
        probes (mapv #(symbol (str "jvm-" %)) geom-syms)
        probe  (walk/postwalk-replace (zipmap geom-syms probes) jts-call)]
    `(if (~'rpc/jvm-stable? ~(vec geom-syms) (fn [~probes] ~probe) ~same?)
       ~comparison
       true)))

(defn default-return
  "Result plan ({:bindings :comparison :releases}) for the canonicalised
   return type, given the assembled JTS-call and wasmts-call forms. nil for
   un-comparable return types — unlike the hint-driven paths above, this is
   the ordinary way an entry declines to produce a spec, not a defect."
  [ret jts-call wasmts-call]
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
                     `(~'let [~'wasmts-xyzs (~'rpc/wasmts-coord-array-xyzs ~'wasmts-handle)]
                        ~(coord-array-equal-form 'jts-result 'wasmts-xyzs 1e-9)))
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
                                    (.norm (~'rpc/wasmts->jts ~'wasmts-handle)) 1.0e-6)))))

(defn generic-return
  "Result plan for one entry, given the assembled JTS-call and wasmts-call
   forms and the manual.edn hints that shape the comparison.

   `compare` picks the compared quantity (compare-return) and defaults to the
   return type's own comparison. `jvm-stable` then wraps whichever one that is
   in the well-defined-reference guard. `geom-syms` names the JTS geometries
   the call binds, which both of those read.

   nil only when the return type has no comparison at all. A hint whose
   preconditions are not met throws instead, so a manual.edn edit that would
   quietly delete a spec fails the build that emits it."
  [entry ret jts-call wasmts-call {:keys [compare jvm-stable]} geom-syms]
  (when-let [plan (if compare
                    (compare-return entry compare jts-call wasmts-call geom-syms)
                    (default-return ret jts-call wasmts-call))]
    (if jvm-stable
      (assoc plan :comparison
             (jvm-stable-guard entry (:comparison plan) jts-call geom-syms compare))
      plan)))
