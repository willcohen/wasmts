(ns wasmts.differential.core
  "Subprocess-driven differential test harness.

   Spawns a long-running Node process running test/node_runner.mjs and
   exchanges line-delimited JSON over its stdin/stdout. Each test case
   runs the JTS oracle directly on the JVM (no IPC needed) and the
   wasmts equivalent over RPC, then asserts results agree.

   Geometry handles cross the wire as WKB encoded in base64. JTS-side
   geometries stay JTS objects; wasmts-side geometries are referenced
   by opaque handle strings registered with the Node runner.

   Usage:

     (start!)                                ; once per JVM
     (let [a (read-jts \"POINT (5 5)\")
           b (read-jts \"POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))\")
           ha (jts->wasmts a)
           hb (jts->wasmts b)]
       (= (.contains b a)
          (boolean (call! \"wasmts.geom.contains\" hb ha))))
     (stop!)

   Synchronous one-call-at-a-time. test.check shrinking is
   single-threaded so this is fine."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter Writer]
           [java.nio.charset StandardCharsets]
           [java.util Base64]
           [org.locationtech.jts.geom Coordinate CoordinateFilter Envelope Geometry GeometryFactory LineSegment PrecisionModel Triangle]
           [org.locationtech.jts.io WKTReader WKBReader WKBWriter]
           [org.locationtech.jts.algorithm.distance DiscreteHausdorffDistance]
           [org.locationtech.jts.math Vector3D]
           [org.locationtech.jts.operation.distance3d Distance3DOp]))

;; JTS oracle helpers (no RPC)

(def ^GeometryFactory factory (GeometryFactory.))
(def ^ThreadLocal wkt-reader-tl
  (proxy [ThreadLocal] [] (initialValue [] (WKTReader. factory))))
(def ^ThreadLocal wkb-reader-tl
  (proxy [ThreadLocal] [] (initialValue [] (WKBReader. factory))))
(def ^ThreadLocal wkb-writer-tl
  (proxy [ThreadLocal] [] (initialValue [] (WKBWriter.))))
;; A dim-3 WKB writer for geometries that carry Z. The default WKBWriter is
;; dim-2 (drops Z); shipping a 3D geometry through it loses the Z ordinate, so
;; Distance3DOp would see a flattened input. The port's WKBReader auto-detects
;; dimension, so a dim-3-written geometry round-trips its Z. jts->wasmts picks
;; the writer by the geometry's actual dimension, so 2D geometries still ship
;; byte-identically through the dim-2 writer.
(def ^ThreadLocal wkb-writer-3d-tl
  (proxy [ThreadLocal] [] (initialValue [] (WKBWriter. 3))))

(defn read-jts ^Geometry [wkt]
  (.read ^WKTReader (.get wkt-reader-tl) ^String wkt))

;; Subprocess lifecycle

(defonce ^:private state (atom nil))

(defn- node-cmd
  "Build the argv used to spawn the Node runner. Override via
   *node-binary* or the WASMTS_NODE env var when needed (e.g. nvm)."
  []
  (let [node (or (System/getenv "WASMTS_NODE") "node")
        runner (str (io/file "test/node_runner.mjs"))]
    [node "--experimental-wasm-exnref" runner]))

(defn- blank-or-not-json? [^String line]
  (or (str/blank? line)
      (not (str/starts-with? (str/triml line) "{"))))

(defn- await-ready [^BufferedReader reader timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Node runner did not announce ready" {})))
      (let [line (.readLine reader)]
        (cond
          (nil? line)
          (throw (ex-info "Node runner closed stdout before ready" {}))

          (blank-or-not-json? line)
          (recur)

          :else
          (let [m (json/read-str line :key-fn keyword)]
            (if (:ready m) :ok (recur))))))))

(defn start!
  "Spawn the Node runner subprocess. Idempotent."
  ([] (start! {}))
  ([{:keys [timeout-ms] :or {timeout-ms 30000}}]
   (when (nil? @state)
     (let [pb     (doto (ProcessBuilder. ^java.util.List (node-cmd))
                    (.redirectErrorStream false))
           proc   (.start pb)
           writer (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
           reader (BufferedReader. (InputStreamReader. (.getInputStream proc)
                                                       StandardCharsets/UTF_8))]
       (await-ready reader timeout-ms)
       (reset! state {:process proc
                      :writer  writer
                      :reader  reader
                      :next-id (atom 0)})
       (println "Node runner ready (pid"
                (.pid proc) ", wasmts loaded)")))))

(defn stop!
  "Tear down the Node runner subprocess if running."
  []
  (when-let [{:keys [^Process process ^Writer writer ^BufferedReader reader]} @state]
    (try (.close writer) (catch Throwable _))
    (try (.close reader) (catch Throwable _))
    (try (.destroy process) (catch Throwable _))
    (reset! state nil)))

;; RPC

(defn- send-request! [{:keys [^Writer writer next-id]} op args]
  (let [id  (swap! next-id inc)
        msg (json/write-str {:id id :op op :args args})]
    (.write writer msg)
    (.write writer "\n")
    (.flush writer)
    id))

(defn- read-response! [{:keys [^BufferedReader reader]}]
  (loop []
    (let [line (.readLine reader)]
      (cond
        (nil? line) (throw (ex-info "Node runner closed stdout" {}))
        (blank-or-not-json? line) (recur)
        :else (json/read-str line :key-fn keyword)))))

(defn- decode-special-float
  "Reverse the runner's NaN/Infinity tagging. JSON has no native
   representation for these values; the runner ships them as
   {:__num \"NaN\"} (etc.) so the JVM side can reconstruct."
  [v]
  (if (and (map? v) (contains? v :__num))
    (case (:__num v)
      "NaN"       Double/NaN
      "Infinity"  Double/POSITIVE_INFINITY
      "-Infinity" Double/NEGATIVE_INFINITY
      v)
    v))

(defn call!
  "Synchronously dispatch one op to the Node runner. Returns the
   decoded :ok value, or throws ex-info with :err on the err path."
  [op & args]
  (let [s @state]
    (when (nil? s) (throw (ex-info "Harness not started — call (start!) first" {})))
    (let [req-id (send-request! s op (vec args))
          resp   (read-response! s)]
      (when (not= (:id resp) req-id)
        (throw (ex-info "Mismatched RPC id"
                        {:expected req-id :got (:id resp)})))
      (if-let [err (:err resp)]
        (throw (ex-info (str "Node RPC error: " err) {:op op :args args}))
        (decode-special-float (:ok resp))))))

;; Geometry boundary crossing (WKB via base64)

(defn jts->wasmts
  "Ship a JTS Geometry to the runner via WKB. Returns the {__handle ...}
   wrapper the runner uses to refer to the wasmts geometry. The caller
   is responsible for calling (release! handle) when done.

   Dimension comes off the FIRST coordinate, so a geometry whose leading
   vertex is 2D and whose later ones carry Z would ship flattened. Every 3D
   generator gives every vertex a Z, so nothing today can be shaped that way."
  [^Geometry g]
  (let [c    (.getCoordinate g)
        dim3 (and c (not (Double/isNaN (.getZ ^Coordinate c))))
        w    (if dim3 (.get wkb-writer-3d-tl) (.get wkb-writer-tl))
        wkb  (.write ^WKBWriter w g)
        b64  (.encodeToString (Base64/getEncoder) wkb)]
    (call! "_readWKB" {:__b64 b64})))

(defn wasmts->jts
  "Round-trip a wasmts handle back to a JTS Geometry. Used in tests
   that compare wasmts results back against JTS structurally."
  ^Geometry [handle]
  (let [{:keys [__b64]} (call! "_writeWKB" handle)
        bytes (.decode (Base64/getDecoder) ^String __b64)]
    (.read ^WKBReader (.get wkb-reader-tl) bytes)))

(defn release!
  "Drop a handle from the runner's table. Optional but recommended for
   long-running suites with high handle churn. nil handle is a no-op so
   callers can release a slot that may legitimately have been a null
   return (e.g. LineSegment.intersection on non-overlapping segments)."
  [handle]
  (when (some? handle)
    (call! "_release" handle)))

;; Non-Geometry boundary crossing (numeric ctors)

(defn jts->wasmts-envelope
  "Ship a JTS Envelope to the runner via the auto-gen `create4(minX, maxX,
   minY, maxY)` ctor. Returns the {__handle ...} wrapper for a wasmts
   Envelope. Caller releases when done."
  [^Envelope e]
  (call! "wasmts.geom.Envelope.create4"
         (.getMinX e) (.getMaxX e) (.getMinY e) (.getMaxY e)))

(defn jts->wasmts-coordinate
  "Ship a JTS Coordinate as a wasmts Coordinate handle. Uses the auto-
   gen `create3(x, y, z)` ctor when the source has a finite Z so 3D
   consumers (CGAlgorithms3D.distancePointSegment, etc.) see the same
   ordinates on both sides. Falls back to `create2(x, y)` when Z is NaN
   so the existing 2D corpus's wasmts-side Coordinates retain whatever
   default Z the wasmts port assigns (matching the pre-3D behavior)."
  [^Coordinate c]
  (let [z (.getZ c)]
    (if (Double/isNaN z)
      (call! "wasmts.geom.Coordinate.create2" (.getX c) (.getY c))
      (call! "wasmts.geom.Coordinate.create3" (.getX c) (.getY c) z))))

(defn jts->wasmts-pm
  "Ship a JTS PrecisionModel as a wasmts PrecisionModel handle via the
   auto-gen `fromScale(scale)` ctor. Only scale-based (FIXED) models
   cross today; FLOATING and FLOATING_SINGLE need a Type-param ctor
   that isn't classified yet."
  [^PrecisionModel pm]
  (call! "wasmts.geom.PrecisionModel.fromScale" (.getScale pm)))

(defn jts->wasmts-triangle
  "Ship a JTS Triangle as a wasmts Triangle handle via the auto-gen
   `create3(c1, c2, c3)` ctor. Each vertex Coordinate is shipped first
   so the wasmts ctor sees Coordinate handles, not raw {x,y} objects."
  [^Triangle t]
  (let [h1 (jts->wasmts-coordinate (.-p0 t))
        h2 (jts->wasmts-coordinate (.-p1 t))
        h3 (jts->wasmts-coordinate (.-p2 t))
        handle (call! "wasmts.geom.Triangle.create3" h1 h2 h3)]
    (release! h1) (release! h2) (release! h3)
    handle))

(defn jts->wasmts-lineseg
  "Ship a JTS LineSegment as a wasmts LineSegment handle via the auto-gen
   `create4(x0, y0, x1, y1)` ctor. JTS exposes p0/p1 as the actual fields;
   x0/y0/x1/y1 are constructor parameters only, not accessible at runtime."
  [^LineSegment ls]
  (call! "wasmts.geom.LineSegment.create4"
         (.getX ^Coordinate (.-p0 ls)) (.getY ^Coordinate (.-p0 ls))
         (.getX ^Coordinate (.-p1 ls)) (.getY ^Coordinate (.-p1 ls))))

(defn jts->wasmts-vector3d
  "Ship a JTS Vector3D as a wasmts Vector3D handle via the auto-gen
   `create3(x, y, z)` ctor."
  [^Vector3D v]
  (call! "wasmts.math.Vector3D.create3" (.getX v) (.getY v) (.getZ v)))

(defn jts->wasmts-plane3d
  "Build a wasmts Plane3D handle from a (Vector3D normal, Coordinate basePt)
   pair. Plane3D's fields are private with no getters, so the differential
   spec keeps the pair around instead of passing a constructed JTS Plane3D.
   Intermediate Vector3D and Coordinate handles are released here; only the
   Plane3D handle survives."
  [^Vector3D normal ^Coordinate base-pt]
  (let [vh (jts->wasmts-vector3d normal)
        ch (jts->wasmts-coordinate base-pt)
        ph (call! "wasmts.math.Plane3D.create2" vh ch)]
    (release! vh)
    (release! ch)
    ph))

(defn jts->wasmts-coord-array
  "Ship a JTS Coordinate[] as a vector of wasmts Coordinate handles.
   Each handle is created via wasmts.geom.Coordinate.create2; the
   returned vector is passed as a single argument to call! which the
   runner's recursive deref unpacks back to a JS array of Coordinate
   JS objects. extractCoordinateArray reads that array on the Java side.
   Caller is responsible for releasing each handle via release-all-coords!."
  [coords]
  (mapv jts->wasmts-coordinate coords))

(defn release-all-coords!
  "Companion to jts->wasmts-coord-array: releases every Coordinate
   handle in the vector."
  [handles]
  (doseq [h handles] (release! h)))

(defn jts->wasmts-gf
  "Ship a JTS GeometryFactory as a wasmts GeometryFactory handle via the
   auto-gen `create2(pm, srid)` ctor. The intermediate PrecisionModel
   handle is released after the ctor returns — JTS retains its own
   reference to the PM as a field of the GeometryFactory, so the JS-side
   handle table no longer needs to map it."
  [^GeometryFactory gf]
  (let [pm-handle (jts->wasmts-pm (.getPrecisionModel gf))
        handle    (call! "wasmts.geom.GeometryFactory.create2" pm-handle (.getSRID gf))]
    (release! pm-handle)
    handle))

(defn wasmts-pm-scale
  "Read the scale off a wasmts PrecisionModel handle. Today's gen-precision-
   model only emits FIXED scale-based PMs, so scale is the identifying
   property; FLOATING and FLOATING_SINGLE comparison would need the type
   string too once those generators land."
  [handle]
  (double (call! "wasmts.geom.PrecisionModel.getScale" handle)))

(defn wasmts-envelope-bounds
  "Read [minX maxX minY maxY] off a wasmts Envelope handle."
  [handle]
  [(double (call! "wasmts.geom.Envelope.getMinX" handle))
   (double (call! "wasmts.geom.Envelope.getMaxX" handle))
   (double (call! "wasmts.geom.Envelope.getMinY" handle))
   (double (call! "wasmts.geom.Envelope.getMaxY" handle))])

(defn wasmts-coordinate-xy
  "Read [x y] off a wasmts Coordinate handle."
  [handle]
  [(double (call! "wasmts.geom.Coordinate.getX" handle))
   (double (call! "wasmts.geom.Coordinate.getY" handle))])

(defn wasmts-vector3d-xyz
  "Read [x y z] off a wasmts Vector3D handle via the auto-gen
   getX/getY/getZ instance methods."
  [handle]
  [(double (call! "wasmts.math.Vector3D.getX" handle))
   (double (call! "wasmts.math.Vector3D.getY" handle))
   (double (call! "wasmts.math.Vector3D.getZ" handle))])

(defn wasmts-lineseg-points
  "Read [[x0 y0] [x1 y1]] off a wasmts LineSegment handle. Goes via
   getCoordinate(0)/(1), releasing the intermediate Coordinate handles."
  [handle]
  (let [h0 (call! "wasmts.geom.LineSegment.getCoordinate" handle 0)
        h1 (call! "wasmts.geom.LineSegment.getCoordinate" handle 1)
        p0 (wasmts-coordinate-xy h0)
        p1 (wasmts-coordinate-xy h1)]
    (release! h0)
    (release! h1)
    [p0 p1]))

(defn wasmts-coord-array-xyzs
  "Read [[x0 y0 z0] [x1 y1 z1] ...] off a wasmts CoordinateArray handle.

   Any ordinate with no JSON representation arrives as null — an absent or NaN
   z, and a NaN or infinite x or y that JSON.stringify coerces. All read back
   as NaN, which is what JTS uses for `no Z`, so a 2D result compares as 2D on
   both sides and close-enough? treats the pair as equal. decode-special-float
   does not reach these; it decodes only the top level of a response."
  [handle]
  (let [ord (fn [v] (if (some? v) (double v) Double/NaN))]
    (mapv (fn [coord]
            [(ord (nth coord 0 nil)) (ord (nth coord 1 nil)) (ord (nth coord 2 nil))])
          (call! "_coordArrayXys" handle))))

(defn close-enough?
  "Tolerance comparison for the differential numeric checks. Combined
   absolute + relative tolerance (numpy.isclose form): pass when
   |a - b| < tol + tol * max(|a|, |b|). Pure absolute tolerance fails
   on results that grow with input magnitude (Triangle.circumradius
   for thin triangles, etc.), while pure relative fails near zero.
   NaN-aware: two NaNs compare equal so identical-by-definition results
   (a null Envelope's getMinX, for example) don't false-fail. Exact
   equality short-circuit covers +-Infinity, where the |Inf - Inf| = NaN
   case would otherwise sink the inequality test."
  [^double a ^double b ^double tol]
  (cond
    (and (Double/isNaN a) (Double/isNaN b)) true
    (== a b)                                true
    :else (< (Math/abs (- a b))
             (+ tol (* tol (Math/max (Math/abs a) (Math/abs b)))))))

(defn envelope-bounds-equal?
  "Compare a JTS Envelope to a wasmts bounds vector with tolerance.
   Handles null-envelope sentinel values via close-enough?'s NaN
   short-circuit."
  [^Envelope e [wmin-x wmax-x wmin-y wmax-y] tol]
  (and (close-enough? (.getMinX e) (double wmin-x) tol)
       (close-enough? (.getMaxX e) (double wmax-x) tol)
       (close-enough? (.getMinY e) (double wmin-y) tol)
       (close-enough? (.getMaxY e) (double wmax-y) tol)))

(defn geom-same-shape?
  "True when two JTS geometries describe the same shape within tol, even if
   their vertex counts differ. The differential suite's default geometry
   compare (.norm + .equalsExact) is vertex-count-strict: it false-fails
   when the WASM port emits a geometrically-identical result carrying a
   near-coincident vertex the JVM JTS collapses. For areal results compares
   symmetric-difference area against
   tol*area; for linear/point results uses discrete Hausdorff against
   tol*extent. The fast path keeps the strict compare for the common case.
   A genuinely different result (e.g. a tie-broken min-clearance LINE) is
   still rejected — Hausdorff there is on the order of the extent, not tol."
  [^Geometry a ^Geometry b ^double tol]
  (cond
    (or (nil? a) (nil? b)) (and (nil? a) (nil? b))
    (.equalsExact (.norm a) (.norm b) tol) true
    :else
    (if (>= (max (.getDimension a) (.getDimension b)) 2)
      (let [ar (max (.getArea a) (.getArea b))]
        (try (<= (.getArea (.symDifference a b)) (* tol (max 1.0 ar)))
             (catch Throwable _ false)))
      (let [env (.getEnvelopeInternal a)
            ext (Math/hypot (.getWidth env) (.getHeight env))]
        (try (<= (DiscreteHausdorffDistance/distance a b) (* tol (max 1.0 ext)))
             (catch Throwable _ false))))))

(defn- coord-on-geometry?
  "True when `c` lies on `g` to within tol, scaled by g's extent the same
   way geom-same-shape? scales its Hausdorff bound.

   `.distance` is zero anywhere inside an areal `g`, not only on its
   boundary. That costs nothing while the two geometries are disjoint, since
   an interior point cannot span the minimum separation — the segment from it
   to the other point crosses the boundary first, at a strictly shorter
   distance. It matters when they overlap; see nearest-pair-agrees?."
  [^Geometry g ^Coordinate c ^double tol dim3?]
  (let [p   (.createPoint factory c)
        env (.getEnvelopeInternal g)
        ext (Math/hypot (.getWidth env) (.getHeight env))
        d   (if dim3? (Distance3DOp/distance g p) (.distance g p))]
    (<= d (* tol (max 1.0 ext)))))

(defn nearest-pair-agrees?
  "Compare a JTS nearest-point pair (Coordinate[2]) against the port's as a
   PAIR rather than coordinate-for-coordinate.

   Where the nearest approach is not unique, neither is the pair: on parallel
   edges both sides return a valid pair at the same separation, slid along the
   parallel by the same delta, which an order-strict ordinate compare rejects.
   So this asserts the two properties that define the answer instead — the
   separation agrees, and each point lies on the geometry it came from. Both
   halves are needed; separation alone accepts a correctly-spaced pair anywhere
   in the plane. When the JTS pair carries a real Z (Distance3DOp) both go
   through the 3D operations, so a wrong Z still fails.

   Accepting the tie costs two regimes the strict compare pinned. The
   separation goes through the relative close-enough?, so the positional error
   accepted scales as tol/sin(theta) and is unbounded as the approach angle
   goes to zero. And where the two geometries overlap the JTS distance is zero,
   so any coincident pair inside the overlap passes."
  ;; tol is deliberately un-hinted: a fn taking a primitive is capped at four
  ;; args, and this one needs five.
  [^"[Lorg.locationtech.jts.geom.Coordinate;" jts-pair wasmts-xyzs
   ^Geometry g0 ^Geometry g1 tol]
  (and (= 2 (alength jts-pair))
       (= 2 (count wasmts-xyzs))
       (let [^Coordinate j0 (aget jts-pair 0)
             ^Coordinate j1 (aget jts-pair 1)
             dim3? (and (not (Double/isNaN (.getZ j0)))
                        (not (Double/isNaN (.getZ j1))))
             mk    (fn [[x y z]] (Coordinate. (double x) (double y) (double z)))
             w0    (mk (nth wasmts-xyzs 0))
             w1    (mk (nth wasmts-xyzs 1))
             sep   (fn [^Coordinate a ^Coordinate b]
                     (if dim3? (.distance3D a b) (.distance a b)))]
         (and (close-enough? (sep j0 j1) (sep w0 w1) tol)
              (coord-on-geometry? g0 w0 tol dim3?)
              (coord-on-geometry? g1 w1 tol dim3?)))))

(def jvm-unstable-skips
  "Comparisons skipped because the JVM did not hold its answer under
   nudge-probes. A skip passes without asserting anything, so the count is
   both reported and gated; see with-runner-once."
  (atom 0))

(def jvm-stable-checks
  "Comparisons that consulted jvm-stable? at all, skipped or not. The
   denominator for the skip-rate ceiling in with-runner-once."
  (atom 0))

(defn- nudged
  "`g` with every coordinate equal to the one at `idx` moved up `width` ULPs
   along `axis`.

   All matching coordinates move together so a ring stays closed. The first
   and last vertex of a LinearRing hold equal values, and moving only one
   would make the geometry invalid."
  ^Geometry [^Geometry g idx axis ^long width]
  (let [^Coordinate target (aget (.getCoordinates g) idx)
        tx   (.getX target)
        ty   (.getY target)
        bump (fn [^double v]
               (loop [v v i 0] (if (>= i width) v (recur (Math/nextUp v) (inc i)))))
        c    (.copy g)]
    (.apply c (reify CoordinateFilter
                (filter [_ coord]
                  (let [^Coordinate coord coord]
                    (when (and (== (.getX coord) tx) (== (.getY coord) ty))
                      (if (= :x axis)
                        (.setX coord (bump (.getX coord)))
                        (.setY coord (bump (.getY coord)))))))))
    (.geometryChanged c)
    c))

(def geom-strict-same?
  "jvm-stable?'s sameness test for the strict geometry compare.

   Held at 1e-9 against the compare's 1e-6, so the guard is never looser than
   what it guards. Two things keep that gap empty, and both are easy to break:
   TopologyPreservingSimplifier, its only user, SELECTS input vertices rather
   than computing new ones, so the answer moves by exactly the nudge (a method
   that computes vertices amplifies, and 1e-9 would call it unstable
   everywhere); and gen-coord's magnitude ceiling keeps the widest probe, 1024
   ULPs, barely under 1e-9. Raising that ceiling an order of magnitude takes
   every case out of comparison at once."
  (fn [^Geometry a ^Geometry b] (.equalsExact (.norm a) (.norm b) 1.0e-9)))

(def geom-area-same?
  "jvm-stable?'s sameness test for an :area compare.

   Only the area has to hold, because only the area is compared. Testing
   geometry equality instead would call a case unstable whenever a nudge
   reshuffles vertices at constant area, which is most of them, and would skip
   far more than the ties the guard is there to catch."
  (fn [^Geometry a ^Geometry b] (close-enough? (.getArea a) (.getArea b) 1.0e-6)))

(def ^:private nudge-probes
  "The [axis ULP-width] perturbations the stability probe applies to each
   vertex. Both axes, since a tie can turn on either.

   1 and 4 catch the last-bit ties; 1024 catches the wide-basin ties, where the
   JVM holds its pick against any small nudge. Each width is fitted to one
   observed case, and a basin has no characteristic width, so the next one has
   no reason to flip below 1024 either. Only TopologyPreservingSimplifier needs
   1024; the two :area users pay six probes per vertex for it.

   Widening is a one-way trade — it raises the skip rate and strengthens
   nothing about the cases still compared — so with-runner-once caps the rate."
  (for [axis [:x :y] width [1 4 1024]] [axis width]))

(defn- distinct-vertex-indices
  "One index per distinct (x, y) in `g`, first occurrence.

   `nudged` moves every coordinate matching the target's (x, y), so probing a
   repeated vertex reproduces the variant its first occurrence already
   produced. Every ring repeats its start vertex at the end, so there is
   always at least one such duplicate to drop."
  [^Geometry g]
  (->> (.getCoordinates g)
       (map-indexed (fn [i ^Coordinate c] [i [(.getX c) (.getY c)]]))
       (reduce (fn [[seen out] [i xy]]
                 (if (seen xy) [seen out] [(conj seen xy) (conj out i)]))
               [#{} []])
       second))

(defn jvm-stable?
  "True when `run` returns the same geometry for `geoms` and for every
   single-vertex perturbation of any one of them (see nudge-probes).

   Some JTS algorithms carry more than one stable output branch and select
   between them on the last bit of an intermediate. Where the JVM itself flips,
   its result is not a well-defined reference, so the caller skips that case.

   `geoms` is every geometry the call binds, and `run` takes that same vector.
   Perturbing only the first would leave a method's sensitivity to its second
   geometry untested while still reporting stable. `same?` must match whatever
   the caller goes on to compare; see geom-area-same?.

   Costs 6*sum(distinct vertices)+1 JVM calls per case, so it stays cheap only
   while the generators keep vertex counts small (<= 9 today).

   This says nothing about the port: an input the JVM answers stably is still
   compared, and a disagreement there is still a failure."
  ([geoms run] (jvm-stable? geoms run geom-strict-same?))
  ([geoms run same?]
   (let [geoms    (vec geoms)
         base     (run geoms)
         variants (for [i (range (count geoms))
                        :let [^Geometry g (nth geoms i)]
                        j (distinct-vertex-indices g)
                        [axis width] nudge-probes]
                    (assoc geoms i (nudged g j axis width)))
         ok       (every? (fn [v]
                            (try (same? base (run v))
                                 ;; A perturbation the oracle cannot process at
                                 ;; all is not a stable reference either.
                                 (catch Throwable _ false)))
                          variants)]
     (swap! jvm-stable-checks inc)
     (when-not ok (swap! jvm-unstable-skips inc))
     ok)))

;; Diagnostics

(defn ping
  "Sanity-check round-trip. Returns \"pong\" when the subprocess is healthy."
  []
  (call! "_ping"))
