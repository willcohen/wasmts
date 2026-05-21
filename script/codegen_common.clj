(ns codegen-common
  "Shared helpers for the emit scripts (emit-api / emit-dts / emit-tests).

   These were duplicated across each script until the third one
   landed and the pattern was clearly paying for itself. The same
   `in-scope?` filter, `dedup-by-path` rule, and `js-path` mapping
   must hold across all three emitters or the generated outputs
   diverge."
  (:require [clojure.string :as str]))

(def geometry-subtypes
  "JTS classes the codegen treats as part of the same `Geometry`
   surface. Methods declared on the subclasses are filtered out of
   the registry's shape categories (they're polymorphic overrides
   of the base Geometry methods) and JS paths drop the class
   disambiguation for any class in this set."
  #{"org.locationtech.jts.geom.Geometry"
    "org.locationtech.jts.geom.Point"
    "org.locationtech.jts.geom.LineString"
    "org.locationtech.jts.geom.LinearRing"
    "org.locationtech.jts.geom.Polygon"
    "org.locationtech.jts.geom.MultiPoint"
    "org.locationtech.jts.geom.MultiLineString"
    "org.locationtech.jts.geom.MultiPolygon"
    "org.locationtech.jts.geom.GeometryCollection"})

(defn simple-name [class-fqn]
  (last (str/split class-fqn #"\.")))

(defn package-leaf
  "The package path under org.locationtech.jts, joined with dots.
   Used to compose the namespace segment(s) of a JS path
   (`wasmts.<leaf>.…`). Returns a single segment for one-deep packages
   (`algorithm` for MinimumBoundingCircle), and joins sub-packages
   (`geom.prep` for PreparedGeometry)."
  [class-fqn]
  (->> (-> class-fqn
           (str/replace "org.locationtech.jts." "")
           (str/split #"\."))
       butlast
       (str/join ".")))

(defn js-path
  "Compute the JS path where a method should be installed. The Geometry
   hierarchy collapses class disambiguation (so `wasmts.geom.contains`,
   not `wasmts.geom.Geometry.contains`) because every subclass shares
   the same surface and dispatches polymorphically. Other classes
   keep the class name for unambiguous paths.

   Constructors (`<init>`) become `createN` where N is the arity; the
   raw method name is invalid JS. Same-arity ctor collisions are resolved
   by a per-entry `:js-path` hint in `manual.edn`."
  [{:keys [class method params]} hint]
  (or hint
      (let [pkg (package-leaf class)
            m   (if (= method "<init>") (str "create" (count params)) method)]
        (if (geometry-subtypes class)
          (format "wasmts.%s.%s" pkg m)
          (format "wasmts.%s.%s.%s" pkg (simple-name class) m)))))

(def supported-shapes
  "Shapes that all three emit scripts know how to render. Add a
   shape here only after every emit-script defmethod for it lands."
  #{:geom->bool :geom*geom->bool
    :geom->geom :geom*geom->geom
    :geom->env
    :geom->double :geom*geom->double
    :geom*geom*string->bool :geom*geom*double->bool
    :geometry-equals-object :geometry-compare-to-object
    :geom*geom->im
    :geom->int :geom->string
    :geom->coordarray :geom*int->geom
    :geom*double->geom :geom*double*int->geom :geom*double*int*int->geom
    :geometry-get-factory :geom->pm
    :geom*int->void
    :geometry-apply-filter
    :static-int-field :static-char-field :static-double-field
    :cs*int->double :cs*int*int->double
    :cs*int*int*double->void
    :cs->int :cs->bool :cs->cs
    :ls->point :ls->bool :ls*int->point
    ;; Point + LineString share the wasmts.geom.getCoordinateSequence JS path
    ;; (geometry-subtypes class collapse). One lambda dispatches polymorphically
    ;; via instanceof, covering both receivers (and LinearRing via LineString).
    :geometry-get-coordinate-sequence
    :polygon->geom :polygon*int->geom :polygon->int
    :env->bool :env*env->bool
    :env->double :env*env->double
    :env->env :env*env->env
    :env->coord
    :env*coord->bool :env*double*double->bool
    :env->void :env*double->void :env*double*double->void
    :env*coord->void :env*env->void
    :pm->bool :pm->double :pm->int :pm->string :pm->type-friendly
    :pm*double->double
    :gf->pm :gf->int :gf->geom :gf*coord->geom :gf*coordarray->geom
    :gf*int->geom :gf*env->geom
    :gf*pointarray->geom :gf*linestringarray->geom :gf*polygonarray->geom
    :gf*geomarray->geom
    :gf*linearring*linearringarray->geom
    :coord->double :coord*coord->double
    :coord*coord->bool :coord->coord
    :ctor-env :ctor-pm :ctor-coord :ctor-gf
    :ctor-im
    :strtree-insert :strtree-remove
    :strtree-query :strtree-size
    :linemerger-add :linemerger-get-merged-line-strings
    :triangle->double :triangle->coord :triangle->bool
    :triangle*coord->double
    :prep*geom->bool :prep->geom
    :static-geom->prep :static-int->bool
    ;; Geometry-receiver ctors for js-wrappers-built-from-geometry (Centroid,
    ;; InteriorPoint*) and PGF instance create.
    :ctor-wrapped-from-geom
    :pgf*geom->prep
    :static-cs->double :static-cs->bool :static-coord*cs->bool
    :geom*int->bool
    ;; Generic shapes for js-wrapper-classes. Dispatch derives extract<Helper> /
    ;; createJS<Helper> at emit time via js-helper-for-class against
    ;; js-wrapper-classes (helper == simple-name except for MBC and MDiam).
    :ctor-wrapped
    :wrapped->coord :wrapped->double :wrapped->geom :wrapped->lineseg
    :wrapped*geom->geom :wrapped*geom->string :wrapped*geom->bytes
    :wrapped*double->geom
    :wrapped*bool->void :wrapped*int->void
    :wrapped*double->void :wrapped*string->void
    :wrapped->int :wrapped->bool :wrapped->wrapped
    :cs*int->coord :cs->coord :cs*env->env
    :pm*coord->coord :static-pm*pm->pm
    :coord->bool :coord*coord->int :coord*coord*double->bool
    :env*coord*coord->bool
    :static-int->double
    :im->bool :im->string :im->im :im*int*int->bool
    :im*string->bool
    :im*int*int->int :im*int*int*int->void :im*string->void
    :im*int->void :im*im->void
    :vector3d->double :vector3d->vector3d
    :vector3d*vector3d->double :vector3d*vector3d->vector3d
    :vector3d*double->vector3d
    :plane3d->int :plane3d*coord->double
    :lineseg->double :lineseg->bool :lineseg->coord
    :lineseg*coord->coord :lineseg*coord->double :lineseg*coord->int
    :lineseg*lineseg->bool
    :lineseg*lineseg->double :lineseg*lineseg->int
    :lineseg*double->coord :lineseg*double->lineseg
    :lineseg*double*double->coord
    :lineseg*int->coord
    :static-double->double
    :static-double*double->double
    :static-double*double->int
    :static-coord->double
    :static-coord*coord->double
    :static-coord*coord*coord->double
    :static-coord*coord*coord->bool
    :static-coord*coord*coord->int
    :static-coord*coord*coord*coord->double
    :static-coord*double*double->coord
    :static-geom->coord
    :static-geom->geom
    :static-geom*double->geom :static-geom*pm->geom
    :static-geom*bool->geom :static-geomarray->geom
    :static-collection->geom
    :static-geom*geom->coordarray :static-geom*geom->double
    :static-geom*geom*double->bool
    :static-geom*double*bufferparams->geom
    :static-geom*double*int*int*double->geom
    :static-int->char :static-char->int :static-int*char->bool
    :static-coord->coord
    :static-coord->vector3d
    :static-double*double*double->vector3d
    :static-coord*coord->coord
    :static-coord*coord*coord->coord
    :static-coord*coord*coord*coord->bool
    :static-int*int->int
    :static-int*int*int->int
    :static-double*double*double->double
    :static-double*double*double*double->double
    :static-coordarray->bool
    :static-coordarray->double
    :static-coord*coordarray->double
    :static-coord*coordarray->bool
    :static-coord*coordarray->int
    :static-coordarray->coordarray
    :lineseg*lineseg->coordarray
    ;; Polygon.isRectangle + Point.getCoordinateSequence are intentionally
    ;; absent — see the skip comments in manual.edn for why
    ;; :polygon->bool and :point->cs aren't needed.
    :ls*int->coord :ls*coord->bool
    :gf*linearring->geom
    :static-coordarray->int :static-coordarray->env
    :static-coordarray*coordarray->bool
    :static-coordarray*coordarray->int
    :static-coordarray*env->coordarray
    :static-coordarray*int*int->coordarray})

(def templated-kinds
  "structured-shape `:kind` values that the three emitters
   render through the template engine. Accepted by `in-scope?` alongside
   the legacy keyword shapes in `supported-shapes`."
  #{:receiver-call :static-call :ctor})

(defn in-scope? [[_ v]]
  (let [s (:shape v)]
    (if (map? s)
      (boolean (templated-kinds (:kind s)))
      (boolean (supported-shapes s)))))

(defn dedup-by-path
  "When two in-scope entries install at the same JS path
   (Geometry.union() vs Geometry.union(Geometry), etc.) keep the
   highest-arity variant. Tiebreak between same-arity entries prefers
   the Geometry base receiver over a geometry-subtype override —
   JS dispatch is polymorphic on the JTS object, so the Geometry-base
   lambda handles every subtype via extractGeometry. emit-api wraps
   this with a warnings collector; the other emitters discard the
   dropped entries silently since they only matter for the Java
   wrapper."
  [entries]
  (->> entries
       (group-by (fn [[k v]] (js-path k (:js-path v))))
       (map (fn [[_ bucket]]
              (->> bucket
                   (sort-by (fn [[k _]]
                              [(count (:params k))
                               (if (= (:class k) "org.locationtech.jts.geom.Geometry") 1 0)]))
                   last)))
       (sort-by (fn [[k _]] [(:class k) (:method k) (:params k)]))))
