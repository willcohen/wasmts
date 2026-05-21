(ns wasmts.differential.fixtures
  "Hand-written companion to the generated test file. Provides:

     gen-wkt           — a test.check generator producing WKT strings
                         for closed simple polygons. Inputs are
                         deliberately tame: small finite coordinates,
                         no NaN/Infinity, never empty. Build out into
                         richer corpora (lines, multis, degenerate
                         cases) as edge-case coverage grows.

     with-runner-once  — a clojure.test :once fixture that spawns the
                         Node runner before any defspec, tears it down
                         after the last."
  (:require [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [wasmts.differential.core :as rpc])
  (:import [org.locationtech.jts.geom Coordinate Envelope GeometryFactory LineSegment PrecisionModel Triangle]
           [org.locationtech.jts.math Vector3D]))

;; WKT generator

(def gen-coord
  ;; Finite, no NaN, modest magnitude, and no subnormal-near-zero values.
  ;; gen/double* with min/max bounds still produces subnormals (down to
  ;; ~1e-308); clamping non-zero outputs to >= 1e-10 magnitude avoids the
  ;; class of failures where JS and JVM atan2 / circumradius / similar
  ;; angle-or-radius math diverge at near-degenerate inputs.
  (gen/fmap (fn [^double d]
              (cond
                (zero? d)                       0.0
                (< (Math/abs d) 1.0e-10)        (* (Math/signum d) 1.0e-10)
                :else                            d))
            (gen/double* {:min -1000.0 :max 1000.0 :NaN? false :infinite? false})))

(def gen-wkt
  (gen/let [cx     gen-coord
            cy     gen-coord
            radius (gen/double* {:min 0.1 :max 100.0 :NaN? false :infinite? false})
            n-pts  (gen/choose 3 8)]
    (let [pts (for [i (range n-pts)]
                (let [theta (* i (/ (* 2.0 Math/PI) n-pts))]
                  (str (+ cx (* radius (Math/cos theta))) " "
                       (+ cy (* radius (Math/sin theta))))))
          closed (concat pts [(first pts)])]
      (str "POLYGON ((" (clojure.string/join ", " closed) "))"))))

(def gen-irregular-wkt
  ;; A jittered-radius star polygon: equal angles, independent per-vertex
  ;; radii (traversed in angular order, so always simple/non-self-intersecting).
  ;; Breaks the radial symmetry of gen-wkt's regular n-gons. The tie-break
  ;; construction families return a non-canonical representative under regular
  ;; n-gons (every candidate feature tied) but agree once the symmetry is
  ;; broken — TopologyPreservingSimplifier's minimal-triangle collapse at
  ;; tolerance >> extent is the one whose tied triangles differ in both shape
  ;; AND area, so only an irregular input (not a scalar invariant) recovers it.
  ;; Pointed at a Geometry param via a manual.edn :gen-overrides hint.
  (gen/let [cx    gen-coord
            cy    gen-coord
            n-pts (gen/choose 4 8)
            radii (gen/vector (gen/double* {:min 0.3 :max 100.0 :NaN? false :infinite? false}) n-pts)]
    (let [pts (map-indexed
               (fn [i ^double r]
                 (let [theta (* i (/ (* 2.0 Math/PI) n-pts))]
                   (str (+ cx (* r (Math/cos theta))) " "
                        (+ cy (* r (Math/sin theta))))))
               radii)]
      (str "POLYGON ((" (clojure.string/join ", " (concat pts [(first pts)])) "))"))))

(def gen-line-wkt
  ;; A LINESTRING WKT with 2-5 uncorrelated vertices. Targets methods
  ;; that need a non-empty LineString — InteriorPointLine.getInteriorPoint
  ;; returns null on a non-line input. Avoids the circular layout used
  ;; by gen-wkt because InteriorPointLine's algorithm picks the vertex
  ;; closest to the line's centroid X (ties break by insertion order);
  ;; evenly-spaced points on a circle produce X-symmetric vertex pairs
  ;; whose tie-break is sensitive to JS↔JVM floating-point rounding,
  ;; making the differential comparison flake.
  (gen/let [n-pts (gen/choose 2 5)
            xs    (gen/vector gen-coord n-pts)
            ys    (gen/vector gen-coord n-pts)]
    (let [pts (map (fn [x y] (str x " " y)) xs ys)]
      (str "LINESTRING (" (clojure.string/join ", " pts) ")"))))

(def gen-point-wkt
  ;; A POINT WKT. Targets InteriorPointPoint.getInteriorPoint, which
  ;; returns null on a non-point input.
  (gen/let [x gen-coord, y gen-coord]
    (str "POINT (" x " " y ")")))

(def gen-line-wkt-3d
  ;; A 3D LINESTRING (2-5 vertices, each with a real Z). For Distance3DOp,
  ;; whose distance depends on the Z ordinate. jts->wasmts ships a Z-carrying
  ;; geometry via the dim-3 WKB writer (see differential.core); the harness
  ;; default dim-2 ship would flatten the Z. Pointed at a Geometry param via a
  ;; manual.edn :gen-overrides hint.
  (gen/let [n  (gen/choose 2 5)
            xs (gen/vector gen-coord n)
            ys (gen/vector gen-coord n)
            zs (gen/vector gen-coord n)]
    (str "LINESTRING Z ("
         (clojure.string/join ", " (map (fn [x y z] (str x " " y " " z)) xs ys zs))
         ")")))

(def gen-point-wkt-3d
  (gen/let [x gen-coord, y gen-coord, z gen-coord]
    (str "POINT Z (" x " " y " " z ")")))

(def gen-wkt-3d
  ;; Mix of 3D points and lines. Both are always valid for 3D distance; a 3D
  ;; polygon would need planarity, so it is excluded.
  (gen/one-of [gen-line-wkt-3d gen-point-wkt-3d]))

;; Non-Geometry receiver generators

(def gen-envelope
  ;; JTS's Envelope(x1, x2, y1, y2) ctor normalises so min<=max. Same
  ;; range as gen-coord keeps the property failures legible.
  (gen/let [xa gen-coord, xb gen-coord, ya gen-coord, yb gen-coord]
    (Envelope. (double xa) (double xb) (double ya) (double yb))))

(def gen-coordinate
  (gen/let [x gen-coord, y gen-coord]
    (Coordinate. (double x) (double y))))

(def gen-coordinate-3d
  ;; Three-ordinate Coordinate (x, y, z) with finite z. The default
  ;; gen-coordinate uses the (x, y) ctor which leaves Z=NaN; some JTS
  ;; methods (CGAlgorithms3D.distancePointSegment / distanceSegmentSegment)
  ;; need a real Z to compute 3D distance correctly.
  (gen/let [x gen-coord, y gen-coord, z gen-coord]
    (Coordinate. (double x) (double y) (double z))))

(defn- coord-3d-equal? [^Coordinate a ^Coordinate b]
  (and (== (.getX a) (.getX b))
       (== (.getY a) (.getY b))
       (== (.getZ a) (.getZ b))))

(defn- bump-z [^Coordinate c dz]
  (Coordinate. (.getX c) (.getY c) (+ (.getZ c) dz)))

(def gen-distance-point-segment
  ;; Triple [p A B] of 3D Coordinates for
  ;; CGAlgorithms3D.distancePointSegment. The segment AB must be
  ;; non-degenerate (JTS throws IllegalArgumentException when A == B);
  ;; bumps B.z by 1.0 on a coincident draw.
  (gen/let [p gen-coordinate-3d
            a gen-coordinate-3d
            b gen-coordinate-3d]
    (if (coord-3d-equal? a b)
      [p a (bump-z b 1.0)]
      [p a b])))

(def gen-distance-segment-segment
  ;; Quad [A B C D] of 3D Coordinates for
  ;; CGAlgorithms3D.distanceSegmentSegment. Both segments must be
  ;; non-degenerate; bumps the second endpoint of each degenerate
  ;; pair by a distinct z-offset so the post-fix points remain
  ;; pairwise distinct across both segments.
  (gen/let [a gen-coordinate-3d
            b gen-coordinate-3d
            c gen-coordinate-3d
            d gen-coordinate-3d]
    (let [b* (if (coord-3d-equal? a b) (bump-z b 1.0) b)
          d* (if (coord-3d-equal? c d) (bump-z d 2.0) d)]
      [a b* c d*])))

(def gen-triangle
  ;; Three Coordinates with a non-zero signed area, so circumcentre and
  ;; circumradius are well-defined. Tries `gen-coordinate` up to a few
  ;; times; if the candidate is collinear, perturbs the third point by
  ;; +1.0 in y to break collinearity.
  (gen/let [p1 gen-coordinate, p2 gen-coordinate, p3 gen-coordinate]
    (let [area2 (Math/abs (- (* (- (.getX p2) (.getX p1))
                                (- (.getY p3) (.getY p1)))
                             (* (- (.getX p3) (.getX p1))
                                (- (.getY p2) (.getY p1)))))]
      (if (> area2 1.0e-6)
        (Triangle. p1 p2 p3)
        (Triangle. p1 p2 (Coordinate. (.getX p3) (+ (.getY p3) 1.0)))))))

(def gen-vector3d
  ;; A Vector3D from three clamped doubles. Substitutes a unit-x vector
  ;; for the all-zero draw so normalize() doesn't divide by zero
  ;; (length()=0 → NaN ordinates; JTS and the wasmts port both produce
  ;; NaN but close-enough? treats NaN≠NaN as a failure).
  (gen/let [x gen-coord, y gen-coord, z gen-coord]
    (if (and (zero? x) (zero? y) (zero? z))
      (Vector3D. 1.0 0.0 0.0)
      (Vector3D. (double x) (double y) (double z)))))

(def gen-plane3d-parts
  ;; A [normal basePt] pair for constructing a Plane3D. Plane3D's fields
  ;; are private with no getters, so the spec keeps the pair around and
  ;; builds the JTS Plane3D inline (basePt also routes through
  ;; jts->wasmts-coordinate's 3D-aware path on the wasmts side).
  ;; orientedDistance(Coordinate) throws on NaN ordinates, so basePt is
  ;; drawn from the finite-Z gen-coordinate-3d generator. Normal length
  ;; non-zero is already ensured by gen-vector3d's unit-x fallback.
  (gen/let [normal gen-vector3d
            base   gen-coordinate-3d]
    [normal base]))

(def gen-nz-coord
  ;; Non-zero variant of gen-coord. Used for divisor parameters where
  ;; zero produces +/-Infinity or NaN (Vector3D.divide(double),
  ;; MathUtil.ceil's precScale, MathUtil.wrap's max). Substitutes a
  ;; sign-preserving 1.0 for any zero draw so the magnitude floor
  ;; (1.0e-10) and finite-range guarantees from gen-coord still hold.
  (gen/fmap (fn [^double d] (if (zero? d) 1.0 d))
            gen-coord))

(def gen-distinct-coordinate-pair
  ;; A [c0 c1] pair of Coordinates guaranteed distinct: c1 = c0 + (dx, dy) with
  ;; dx non-zero, so c0 and c1 always differ. Octant.octant / Quadrant.quadrant
  ;; throw on identical points, and two independent gen-coordinate draws can
  ;; collide via gen-coord's 1e-10 magnitude clamp. Supplied as the whole arg
  ;; tuple via a manual.edn :gen-tuple hint.
  (gen/let [x0 gen-coord, y0 gen-coord, dx gen-nz-coord, dy gen-coord]
    [(Coordinate. (double x0) (double y0))
     (Coordinate. (double (+ x0 dx)) (double (+ y0 dy)))]))

(def gen-polygon-node
  ;; Five coordinates forming a valid polygon node: a shared node point with
  ;; two edges (a0 -> node -> a1) and (b0 -> node -> b1) radiating from it.
  ;; Each edge endpoint is node + a non-zero-x offset, so it differs from the
  ;; node. Independent random coords make JTS's Quadrant throw (the edges must
  ;; actually meet at the node). Supplied as the whole arg tuple via a
  ;; :gen-tuple hint to PolygonNodeTopology.isCrossing.
  (gen/let [nx gen-coord, ny gen-coord
            a0x gen-nz-coord, a0y gen-coord, a1x gen-nz-coord, a1y gen-coord
            b0x gen-nz-coord, b0y gen-coord, b1x gen-nz-coord, b1y gen-coord]
    (let [n (Coordinate. (double nx) (double ny))]
      [n
       (Coordinate. (double (+ nx a0x)) (double (+ ny a0y)))
       (Coordinate. (double (+ nx a1x)) (double (+ ny a1y)))
       (Coordinate. (double (+ nx b0x)) (double (+ ny b0y)))
       (Coordinate. (double (+ nx b1x)) (double (+ ny b1y)))])))

(def gen-pos-coord
  ;; Strictly-positive double in (0, 1000]. For tolerance / distance /
  ;; length / radius / width params that JTS validates as > 0 (densify
  ;; distanceTolerance, simplifier tolerance, MaximumInscribedCircle
  ;; tolerance, ConcaveHull maxLength, ...). The generic spec-form engine
  ;; selects this by param name; a signed gen-coord makes the JTS oracle
  ;; throw "Tolerance must be positive" before any comparison.
  (gen/fmap (fn [^double d] (max 1.0e-3 (Math/abs d)))
            gen-coord))

(def gen-unit-frac
  ;; Double in (0, 1.0]. For ratio / fraction params (ConcaveHull
  ;; lengthRatio, DiscreteHausdorffDistance densifyFrac) where JTS
  ;; requires (0,1]; zero throws and >1 is out of contract.
  (gen/double* {:min 1.0e-3 :max 1.0 :NaN? false :infinite? false}))

(def gen-unit-signed
  ;; Double in [-1.0, 1.0]. For signed shape-tension params that stay
  ;; well-behaved only in a small symmetric band — CubicBezierCurve's skew
  ;; (control-point shift). Unbounded gen-coord (+-1000) pushes the cubic
  ;; into self-intersecting / degenerate curves whose symmetric-difference
  ;; compare flakes; [-1,1] keeps the curve in-domain. Pointed at a param
  ;; position via a manual.edn :gen-overrides hint.
  (gen/double* {:min -1.0 :max 1.0 :NaN? false :infinite? false}))

(def gen-nz-int
  ;; Non-zero int in [-1000, 1000]. Used where the JTS method divides
  ;; by an int (MathUtil.ceil's precScale, MathUtil.wrap's max). The
  ;; range matches gen-coord's; the static-spec's :prim "int" path
  ;; truncates the bound symbol to int, so any int generator works
  ;; here so long as zero is excluded.
  (gen/such-that (complement zero?) (gen/choose -1000 1000)))

(def gen-vertex-index
  ;; LineSegment.getCoordinate(int) accepts 0 or 1; any other value
  ;; throws IllegalArgumentException JVM-side.
  (gen/choose 0 1))

(def gen-coord-array
  ;; A closed ring of 4 to 7 Coordinates (3-6 unique vertices + a closing
  ;; copy of the first). Closed-ring shape satisfies the strictest
  ;; consumer (GeometryFactory.createLinearRing / createPolygon, which
  ;; throw IllegalArgumentException on open sequences) while still
  ;; working for the looser ones — Orientation.isCCW already documents
  ;; closed input as required; Distance.pointToSegmentString and
  ;; CoordinateArrays.* tolerate either form.
  (gen/let [n  (gen/choose 3 6)
            cs (gen/vector gen-coordinate n)]
    (into-array Coordinate (conj (vec cs) (first cs)))))

(def gen-circle-quad
  ;; Four Coordinates [p1 p2 p3 q] for isInCircle* predicates. p1/p2/p3
  ;; must form a non-degenerate triangle so the circumcircle is well-
  ;; defined; q must be distinct from each vertex so it doesn't sit on
  ;; the circumcircle exactly. At gen-coord's 1e-10 clamp floor
  ;; independent draws can yield coincident points (the
  ;; TrianglePredicate.isInCircleCC flake came from p2 == q both at
  ;; (1e-10, -1e-10)), pushing the predicate to its singularity where
  ;; JS and JVM can disagree on sign. Bumps duplicates by distinct
  ;; y-offsets so the post-fix points stay pairwise distinct.
  (gen/let [p1 gen-coordinate
            p2 gen-coordinate
            p3 gen-coordinate
            q  gen-coordinate]
    (let [coincident? (fn [^Coordinate a ^Coordinate b]
                        (and (== (.getX a) (.getX b))
                             (== (.getY a) (.getY b))))
          bump        (fn [^Coordinate c dy]
                        (Coordinate. (.getX c) (+ (.getY c) dy)))
          p2*  (if (coincident? p1 p2) (bump p2 1.0) p2)
          p3*  (if (or (coincident? p1 p3) (coincident? p2* p3))
                 (bump p3 2.0) p3)
          area2 (Math/abs (- (* (- (.getX p2*) (.getX p1))
                                (- (.getY p3*) (.getY p1)))
                             (* (- (.getX p3*) (.getX p1))
                                (- (.getY p2*) (.getY p1)))))
          p3** (if (> area2 1.0e-6) p3* (bump p3* 3.0))
          q*   (if (or (coincident? q p1)
                       (coincident? q p2*)
                       (coincident? q p3**))
                 (bump q 4.0) q)]
      [p1 p2* p3** q*])))

(def gen-lineseg
  ;; Two Coordinates as a non-degenerate segment. Perturbs the endpoint
  ;; by +1.0 in y if p1 == p2 so length() > 0 and the projection / angle
  ;; / orientation methods stay defined.
  (gen/let [p1 gen-coordinate, p2 gen-coordinate]
    (if (and (== (.getX p1) (.getX p2))
             (== (.getY p1) (.getY p2)))
      (LineSegment. p1 (Coordinate. (.getX p2) (+ (.getY p2) 1.0)))
      (LineSegment. p1 p2))))

(def gen-intersecting-lineseg-pair
  ;; Two LineSegments guaranteed to cross at a known center. Generates
  ;; (cx, cy), a base angle, an angular separation (>0.05 PI, <0.5 PI
  ;; so the two segments are clearly non-parallel), and two half-lengths.
  ;; Both segments pass through (cx, cy), so JTS's intersection /
  ;; lineIntersection / project methods return non-null and the
  ;; differential tests exercise the actual numerical comparison branch
  ;; rather than the agree-on-nil short-circuit.
  (gen/let [cx       gen-coord
            cy       gen-coord
            angle1   (gen/double* {:min 0.0 :max (* 0.99 Math/PI)
                                   :NaN? false :infinite? false})
            d-angle  (gen/double* {:min 0.05 :max 0.49
                                   :NaN? false :infinite? false})
            r1       (gen/double* {:min 0.1 :max 100.0
                                   :NaN? false :infinite? false})
            r2       (gen/double* {:min 0.1 :max 100.0
                                   :NaN? false :infinite? false})]
    (let [angle2 (+ angle1 (* d-angle Math/PI))
          h1x    (* r1 (Math/cos angle1))
          h1y    (* r1 (Math/sin angle1))
          h2x    (* r2 (Math/cos angle2))
          h2y    (* r2 (Math/sin angle2))
          seg1   (LineSegment. (Coordinate. (double (- cx h1x)) (double (- cy h1y)))
                               (Coordinate. (double (+ cx h1x)) (double (+ cy h1y))))
          seg2   (LineSegment. (Coordinate. (double (- cx h2x)) (double (- cy h2y)))
                               (Coordinate. (double (+ cx h2x)) (double (+ cy h2y))))]
      [seg1 seg2])))

(def gen-lineseg-pair
  ;; Mixed strategy for LineSegment-pair inputs to nullable methods
  ;; (intersection / lineIntersection / project). 50% intersecting,
  ;; 50% independent random. The intersecting half exercises the
  ;; both-non-nil comparison branch of the null-aware lineseg-spec;
  ;; the random half exercises the agree-on-nil short-circuit (since
  ;; random pairs almost never overlap).
  (gen/one-of [gen-intersecting-lineseg-pair
               (gen/let [a gen-lineseg, b gen-lineseg] [a b])]))

(def gen-precision-model
  ;; Scale > 0 keeps PrecisionModel in FIXED mode with a well-defined
  ;; gridSize. JTS rejects scale == 0 for the FIXED ctor (it would
  ;; produce a divide-by-zero in makePrecise). FLOATING / FLOATING_SINGLE
  ;; need the Type-param ctor, not classified yet.
  (gen/let [scale (gen/double* {:min 0.001 :max 1.0e6 :NaN? false :infinite? false})]
    (PrecisionModel. (double scale))))

(def gen-geomfactory
  ;; A PM + SRID GeometryFactory. JTS retains a reference to the
  ;; supplied PrecisionModel; SRID is plain int storage. The (PM, int)
  ;; ctor is the most informative variant covered by the auto-gen
  ;; receivers — getPrecisionModel and getSRID both have observable
  ;; output for a property test.
  (gen/let [pm   gen-precision-model
            srid (gen/choose 0 32768)]
    (GeometryFactory. pm srid)))

;; Subprocess fixture

(defn with-runner-once [f]
  (if-not (.exists (io/file "dist/wasmts.js.wasm"))
    (do (println "  skipping differential tests: dist/wasmts.js.wasm not present")
        (f))
    (try
      (rpc/start!)
      (f)
      (finally
        (rpc/stop!)))))
