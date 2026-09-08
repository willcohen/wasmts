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

(defn canonical-type
  "Normalise any geometry subtype (Point / LineString / Polygon /
   LinearRing / MultiPoint / MultiLineString / MultiPolygon /
   GeometryCollection) to the base Geometry type. classify-shape's
   per-shape rules used `jts-geometry?` (a predicate) for return /
   param matching, so a single shape like `:gf*coord->geom` covered
   methods declaring any Geometry subtype. Mirror that here so one
   spec-form defmethod per logical shape suffices."
  [t]
  (if (geometry-subtypes t) "org.locationtech.jts.geom.Geometry" t))

(defn simple-name [class-fqn]
  (last (str/split class-fqn #"\.")))

(defn- expand-inline-tags
  "Rewrite javadoc inline tags to markdown.

   `{@link}` targets are Java symbols with no TypeScript counterpart, and a
   TSDoc `{@link}` the compiler cannot resolve renders as a broken link, so
   they become inline code. A tag's label wins over its target. Otherwise
   `Foo#bar(int)` reduces to `Foo.bar`, and the same-class `#bar` to `bar`."
  [s]
  (-> s
      (str/replace #"\{@(?:link|linkplain)\s+([^}\s]+)(?:\s+([^}]*))?\}"
                   (fn [[_ target label]]
                     (let [label (str/trim (or label ""))
                           text  (if (seq label)
                                   label
                                   (-> target
                                       (str/replace #"\(.*\)" "")
                                       (str/replace "#" ".")
                                       (str/replace #"^\." "")))]
                       (str "`" text "`"))))
      (str/replace #"\{@(?:code|literal)\s+([^}]*)\}" "`$1`")
      (str/replace #"\{@value\s+([^}]*)\}" "`$1`")
      ;; Anything left ({@inheritDoc}, {@docRoot}) keeps its content and
      ;; drops the braces.
      (str/replace #"\{@\w+\s*([^}]*)\}" "$1")))

(defn- table->markdown
  "Rewrite an HTML table body as a markdown table. JTS uses exactly one
   (Geometry.convexHull). Pipe-joined rows alone do not render as a table.
   Markdown needs the delimiter row under the header."
  [body]
  (let [rows (->> (re-seq #"(?is)<tr[^>]*>(.*?)</tr>" body)
                  (map (fn [[_ row]]
                         {:header? (boolean (re-find #"(?i)<th" row))
                          :cells (->> (re-seq #"(?is)<t[dh][^>]*>(.*?)</t[dh]>" row)
                                      (mapv (fn [[_ c]] (str/trim (str/replace c #"\s+" " ")))))})))
        line (fn [cells] (str "| " (str/join " | " cells) " |"))]
    (when (seq rows)
      (str "\n"
           (str/join "\n"
                     (mapcat (fn [{:keys [header? cells]}]
                               (cons (line cells)
                                     (when header?
                                       [(line (repeat (count cells) "---"))])))
                             rows))
           "\n"))))

(defn- repair-close-tags
  "Add the `>` that a malformed javadoc close tag does not have. JTS writes
   `<code>null</code.`, which leaves the open tag as a backtick with no
   pair, so all the text to the next backtick becomes code.

   The list holds only the tags that become a markdown delimiter. The
   catch-all in `html->markdown` removes any other malformed close tag."
  [s]
  (str/replace s #"(?i)</(code|tt|samp|b|strong|i|em|cite)(?![>a-zA-Z])" "</$1>"))

(defn- paired
  "Put `marker` around the content between an open tag and its close tag.
   Then remove each tag of the same group that has no pair.

   JTS markup is unbalanced. A separate replacement of each tag leaves a
   delimiter with no pair, which changes all the text after it. The inner
   negative lookahead stops a pair from spanning a second open tag, whose
   emphasis would then cover a full paragraph."
  [s names marker]
  (let [open (str "<(?:" names ")>")]
    (-> s
        (str/replace (re-pattern (str "(?is)" open "((?:(?!" open ").)*?)</(?:" names ")>"))
                     (fn [[_ inner]] (str marker (str/trim inner) marker)))
        (str/replace (re-pattern (str "(?i)</?(?:" names ")>")) ""))))

(defn- decode-entities
  "Decode the HTML entities that JTS uses. `&amp;` is last, so `&amp;lt;`
   does not decode two times and become a tag. `test/types-doc.mjs` fails
   on an entity that no rule here decodes."
  [s]
  (-> s
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&alpha;" "α")
      (str/replace #"&phi;" "φ")
      (str/replace #"&#0*39;" "'")
      (str/replace #"&amp;" "&")))

(defn- html->markdown
  "Change the HTML subset that JTS uses in javadoc into markdown.

   Order matters. The repair of malformed close tags is first, so the pair
   operations get correct tags. Tables and `<pre>` blocks come next,
   because each one needs its inner markup as a unit."
  [s]
  (-> s
      repair-close-tags
      (str/replace #"(?is)<table[^>]*>(.*?)</table>"
                   (fn [[whole body]] (or (table->markdown body) whole)))
      ;; Every inline tag, not `<code>` alone. `DD.parse` uses `<tt>`, `<i>`.
      (str/replace #"(?is)<pre>\s*(.*?)\s*</pre>"
                   (fn [[_ body]]
                     (str "\n```\n"
                          (str/replace body #"(?i)</?(?:code|tt|samp|b|strong|i|em|cite|sub|sup)>" "")
                          "\n```\n")))
      ;; The `s` flag: JTS wraps anchor text across lines.
      (str/replace #"(?is)<a\s+href\s*=\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>" "[$2]($1)")
      (str/replace #"(?is)<sup>(.*?)</sup>" "^$1")
      (str/replace #"(?is)<sub>(.*?)</sub>" "$1")
      (paired "code|tt|samp" "`")
      (paired "b|strong" "**")
      (paired "i|em|cite" "_")
      (str/replace #"(?i)<br\s*/?>" "\n")
      (str/replace #"(?i)\s*<p\s*/?>\s*" "\n\n")
      (str/replace #"(?i)\s*</p>" "")
      ;; Consume leading whitespace so this newline is the only one
      ;; before the bullet.
      (str/replace #"(?i)\s*<li>\s*" "\n- ")
      (str/replace #"(?i)\s*</li>" "")
      ;; Must be a blank line. One newline makes markdown fold the next
      ;; paragraph into the last bullet.
      (str/replace #"(?i)\s*</?[uo]l>\s*" "\n\n")
      (str/replace #"(?i)<h[1-6]>" "\n\n**")
      (str/replace #"(?i)</h[1-6]>" "**\n\n")
      ;; Strip every tag the rules above do not name. Must precede the
      ;; entity decode, which would otherwise make `&lt;T&gt;` look like one.
      (str/replace #"(?i)</?[a-z][a-z0-9]*\b[^>]*>" "")
      decode-entities))

(defn- dedent-prose
  "Strip leading whitespace from prose lines, leaving fenced blocks alone.

   Javadoc indents continuation lines, and four leading spaces is an
   indented code block in markdown. Splitting on the fences keeps the
   indentation that `<pre>` blocks depend on.

   Odd segments sit between a pair of fences. That holds because `<pre>` is
   the only rule that makes a fence, and it makes two.
   `test/types-doc.mjs` checks fence pairs on each emitted block."
  [s]
  (->> (str/split s #"(?m)^```$" -1)
       (map-indexed (fn [i seg]
                      (if (odd? i)
                        seg
                        (str/replace seg #"(?m)^[ \t]+" ""))))
       (str/join "```")))

(defn- markdown-text
  "Full javadoc-to-markdown pipeline for a block of prose."
  [s]
  (-> s
      expand-inline-tags
      html->markdown
      dedent-prose
      (str/replace #"[ \t]+\n" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- one-line
  "Same pipeline, flattened. Tag content wraps across source lines with the
   original indentation intact, which would otherwise reach the .d.ts as
   runs of spaces mid-sentence."
  [s]
  (-> s markdown-text (str/replace #"\s+" " ") str/trim))

(defn- javadoc-anchor
  "Anchor for a member on the JTS javadoc site.

   JDK 8 javadoc built the site, so an anchor reads `name-Type1-Type2-`
   and not `name(Type1,Type2)`. Constructors use the simple class name,
   fields the bare name, arrays `Type:A`, and a nested parameter type a dot
   where reflection gives a `$`. `scripts/check-javadoc-links.mjs` checks
   these against the live site."
  [{:keys [class method params]} field?]
  (let [types (str/join "-" (map #(-> % (str/replace #"\[\]" ":A")
                                      (str/replace "$" "."))
                                 params))
        base  (if (= method "<init>") (simple-name class) method)]
    (if field?
      base
      (str base "-" types "-"))))

(defn javadoc-url
  "Deep link to the member's entry on the published JTS javadoc."
  [class-fqn anchor]
  (str "https://locationtech.github.io/jts/javadoc/"
       (str/replace (str/replace class-fqn "$" ".") "." "/")
       ".html#" anchor))

(defn- escape-comment
  "A literal */ inside javadoc would close the TSDoc block early. JTS has
   none today, but a future one would produce a broken .d.ts."
  [s]
  (str/replace s "*/" "*\\/"))

(defn tsdoc-block
  "Render a registry entry's `:doc` as a TSDoc block comment indented by
   `indent` spaces, or nil when the entry carries no doc.

   `renames` is `[[java-name ident] ...]` in declaration order, pairing
   each Java parameter with the identifier the emitted signature uses. The
   functional and OO surfaces number arguments differently, so the @param
   list would otherwise disagree with the signature.

   Matching is by name: JTS documents only some of a method's parameters,
   and a positional map would shift the rest onto the wrong argument.
   Position applies only when there are no names and the counts agree. An
   unmatched @param is dropped.

   The @see link names the class that supplies the text, an ancestor for
   an inherited doc."
  ([indent k v] (tsdoc-block indent k v nil))
  ([indent k {:keys [doc field?]} renames]
   (when doc
     (let [{:keys [desc returns throws from]} doc
           doc-params (:params doc)
           by-name    (into {} (filter first) renames)
           idents     (mapv second renames)
           renamed    (map-indexed
                       (fn [i [jname text]]
                         [(cond
                            (nil? renames)               jname
                            (contains? by-name jname)    (get by-name jname)
                            (= (count doc-params)
                               (count renames))          (nth idents i nil)
                            :else                        nil)
                          text])
                       doc-params)
           body     (some-> desc markdown-text)
           p-lines  (keep (fn [[n text]]
                            (when n
                              (let [t (one-line text)]
                                (str "@param " n (when (seq t) (str " - " t))))))
                          renamed)
           r-line   (when-let [t (some-> returns one-line not-empty)]
                      (str "@returns " t))
           t-lines  (keep (fn [[n text]]
                            (when n
                              (let [t (one-line text)]
                                (str "@throws " n (when (seq t) (str " " t))))))
                          throws)
           src      (or from (:class k))
           see-line (str "@see [" src "." (if (= (:method k) "<init>")
                                            (simple-name src)
                                            (:method k))
                         "](" (javadoc-url src (javadoc-anchor (assoc k :class src) field?)) ")")
           tag-lines (concat p-lines (when r-line [r-line]) t-lines [see-line])
           lines    (concat (when (seq body) (str/split-lines body))
                            (when (seq body) [""])
                            tag-lines)
           pad      (apply str (repeat indent \space))]
       (str pad "/**\n"
            (->> lines
                 (map #(if (str/blank? %)
                         (str pad " *")
                         (str pad " * " (escape-comment %))))
                 (str/join "\n"))
            "\n" pad " */\n")))))

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
