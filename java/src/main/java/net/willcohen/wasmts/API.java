/*
 * Copyright (c) 2025 Will Cohen
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package net.willcohen.wasmts;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSBoolean;
import org.graalvm.webimage.api.JSNumber;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.graalvm.webimage.api.JSValue;

import net.willcohen.wasmts.API_Generated.Fn0;
import net.willcohen.wasmts.API_Generated.Fn1;
import net.willcohen.wasmts.API_Generated.Fn2;
import net.willcohen.wasmts.API_Generated.Fn3;
import net.willcohen.wasmts.API_Generated.Fn5;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.io.kml.KMLReader;
import org.locationtech.jts.io.twkb.TWKBReader;
import org.locationtech.jts.JTSVersion;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.algorithm.MinimumBoundingCircle;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.coverage.CoverageUnion;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.math.Plane3D;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;

/**
 * JavaScript API for JTS Geometry operations.
 * Exports JTS functionality to JavaScript with native object interop.
 */
public class API {

    private static final GeometryFactory factory = new GeometryFactory();

    // Hand-written exports use the generic FnN interfaces from
    // API_Generated (same package). Object params required: the Java
    // proxy method lookup needs exact signature match, and JS numbers
    // don't auto-match to Java doubles.

    // wasmts-specific bulk-coordinate replacement (no JTS counterpart):
    // rewrites a geometry's coordinates from a flat numeric array.
    @JS.Coerce
    @JS("wasmts.geom.applyCoordinates = (geom, arr, valuesPerCoord) => fn.invoke(geom, arr, valuesPerCoord);")
    private static native void exportApplyCoordinates(Fn3 fn);

    // attachGeometryOverrides — hand-written JS shims that the mechanical
    // wireGeometryMethods (API_Generated) can't express: polymorphic
    // dispatch (relate), default args (equalsExact's tolerance ?? 0),
    // no-arg union → unaryUnion, toString → lazy WKTWriter, and the
    // normalize ↔ norm JS-name alias. Called by API_Generated.createJSGeometry
    // after the wire installs.
    @JS("""
        // Fluent shims for bespoke @JS exports under wasmts.geom.* — the
        // auto-gen wireGeometryMethods doesn't see these because they don't
        // classify into a supported shape (yet). When a bespoke handler
        // migrates, drop its line here; the wire will produce the shim.
        // applyCoordinates stays bespoke (wasmts-specific, no JTS analog).
        obj.applyCoordinates = (...args) => wasmts.geom.applyCoordinates(obj, ...args);

        // JS-name aliases and polymorphic dispatch the auto-gen wire can't
        // express directly.
        obj.normalize = () => wasmts.geom.norm(obj);
        obj.relate = (other, pattern) => {
            if (pattern !== undefined) {
                return wasmts.geom.relatePattern(obj, other, pattern);
            }
            return wasmts.geom.relate(obj, other);
        };
        obj.equalsExact = (other, tolerance) => wasmts.geom.equalsExact(obj, other, tolerance ?? 0);
        const originalUnion = obj.union;
        obj.union = (other) => {
            if (other === undefined) {
                return wasmts.geom.unaryUnion(obj);
            }
            return originalUnion(other);
        };
        obj.toString = () => {
            if (!wasmts.io.WKTWriter._default) {
                wasmts.io.WKTWriter._default = wasmts.io.WKTWriter.create0();
            }
            return wasmts.io.WKTWriter._default.write(obj);
        };
    """)
    static native void attachGeometryOverrides(JSObject obj);

    // attachEnvelopeOverrides — polymorphic 1-arg expandBy → expandByUniform
    // and the JS-name alias covers → coversCoord (the wire installs both
    // names; the alias here keeps the historical `.covers(coord)`).
    @JS("""
        const originalExpandBy = obj.expandBy;
        obj.expandBy = (deltaX, deltaY) => deltaY === undefined
            ? wasmts.geom.Envelope.expandByUniform(obj, deltaX)
            : originalExpandBy(deltaX, deltaY);
        obj.covers = (coord) => wasmts.geom.Envelope.coversCoord(obj, coord);
    """)
    static native void attachEnvelopeOverrides(JSObject obj);

    // Wraps a JTS Coordinate as a JS object with eager x/y/z/m fields plus
    // the OO accessor surface. The unpacked ordinates are computed Java-side
    // once at wrap time and stamped onto the JS literal so JS callers can
    // touch `c.x`/`c.y` without round-tripping through the WASM boundary.
    // Web Image marshals JSNumber across the @JS boundary as a plain JS
    // number; raw `double` parameters arrive as Java thunks and don't
    // resolve to a primitive inside JS scope, so the ordinates are
    // wrapped in JSNumber.of(...) before being passed.
    static Object createJSCoordinate(Coordinate coord) {
        // Null-safe: JTS methods that return a Coordinate-or-null (e.g.
        // Intersection.intersection / lineSegment, CGAlgorithmsDD.intersection
        // on parallel/collinear input) must pass null through to JS as null,
        // matching the JVM oracle, instead of NPEing on coord.getX(). This
        // makes createJSCoordinateOrNull redundant for new callers, but it
        // stays for explicitness at sites already using it.
        if (coord == null) {
            return null;
        }
        return makeJSCoordinate(coord,
                                JSNumber.of(coord.getX()),
                                JSNumber.of(coord.getY()),
                                JSNumber.of(coord.getZ()),
                                JSNumber.of(coord.getM()));
    }

    @JS("""
        const c = { _jtsCoord: coord, x: x, y: y, z: z, m: m };
        c.getX = () => x;
        c.getY = () => y;
        c.getZ = () => z;
        c.getM = () => m;
        c.copy = () => wasmts.geom.Coordinate.copy(c);
        c.distance = (other) => wasmts.geom.Coordinate.distance(c, other);
        c.distance3D = (other) => wasmts.geom.Coordinate.distance3D(c, other);
        c.equals2D = (other) => wasmts.geom.Coordinate.equals2D(c, other);
        c.equals3D = (other) => wasmts.geom.Coordinate.equals3D(c, other);
        return c;
        """)
    static native JSObject makeJSCoordinate(Coordinate coord, JSNumber x, JSNumber y, JSNumber z, JSNumber m);

    // Extract the wasmts-handle Coordinate or build one from a plain JS
    // {x, y, z?, m?} literal. coordParts probes for `_jtsCoord` JS-side
    // and surfaces the result plus an `xyzm` flag back to Java so the
    // Java code can take the fast path on handles without throwing.
    @JS("""
        if (obj && obj._jtsCoord !== undefined) {
            return { handle: obj._jtsCoord, isHandle: true, x: 0, y: 0, z: 0, m: 0, hasZ: false, hasM: false };
        }
        return {
            handle: null, isHandle: false,
            x: obj.x, y: obj.y,
            hasZ: obj.z !== undefined && obj.z !== null,
            hasM: obj.m !== undefined && obj.m !== null,
            z: obj.z || 0, m: obj.m || 0
        };
        """)
    private static native JSObject classifyCoord(JSObject obj);

    // Unwrap a Coordinate from any of three accepted shapes:
    //   1. a raw Java Coordinate (when crossing Java→Java internally)
    //   2. a wasmts handle {_jtsCoord: ...} produced by createJSCoordinate
    //   3. a plain JS literal {x, y, z?, m?} passed directly by JS callers
    static Coordinate extractCoordinate(Object obj) {
        if (obj instanceof Coordinate) {
            return (Coordinate) obj;
        }
        JSObject p = classifyCoord((JSObject) obj);
        if (((JSValue) p.get("isHandle")).asBoolean()) {
            return p.get("handle", Coordinate.class);
        }
        double x = ((JSValue) p.get("x")).asDouble();
        double y = ((JSValue) p.get("y")).asDouble();
        boolean hasZ = ((JSValue) p.get("hasZ")).asBoolean();
        boolean hasM = ((JSValue) p.get("hasM")).asBoolean();
        if (hasZ && hasM) {
            return new CoordinateXYZM(x, y, ((JSValue) p.get("z")).asDouble(), ((JSValue) p.get("m")).asDouble());
        } else if (hasZ) {
            return new Coordinate(x, y, ((JSValue) p.get("z")).asDouble());
        } else {
            return new Coordinate(x, y);
        }
    }

    // Wrapped-class helpers (Triangle / LineSegment / Vector3D / Plane3D /
    // MBC / MDiam, the 10 reader/writer wrappers, the 3 builders, STRtree /
    // LineMerger / PreparedGeometry / BufferParameters / WKBWriter /
    // PrecisionModel) live in API_Generated.java now. See emit_api.clj's
    // `wrapped-classes` data for the registry that drives the emission.
    // Both classes share the wasmts package, so the package-private
    // helpers are reachable as `API_Generated.<helper>` from API.java's
    // hand-written code (createJS<X>OrNull, JS callback shims, etc.).

    // byte[] coercion for ctor / static dispatch. Accepts either a Java
    // byte[] or a JS array/Uint8Array of numbers in [0,255]; mirrors the
    // hand-written readWKBJS conversion that this replaces.
    static byte[] extractByteArray(Object obj) {
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        int len = getJSArrayLength(obj).asInt();
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            int v = ((JSValue) getJSArrayElement(obj, i)).asInt();
            result[i] = (byte) v;
        }
        return result;
    }

    static Object createJSCoordinateOrNull(Coordinate c) {
        return c == null ? null : createJSCoordinate(c);
    }

    static Object createJSLineSegmentOrNull(LineSegment ls) {
        return ls == null ? null : API_Generated.createJSLineSegment(ls);
    }

    static Object createJSVector3DOrNull(Vector3D vec) {
        return vec == null ? null : API_Generated.createJSVector3D(vec);
    }

    // Unified Coordinate[] return shape. Returns a real JS
    // array of plain {x, y, z?, m?} objects so JS callers can use native
    // `.length` and `arr[i]` access. The original Java Coordinate[] is
    // stashed under a non-enumerable `_jtsCoordArray` property on the
    // array; `extractCoordinateArray` probes for that property to enable
    // O(1) round-trip back into JTS when the array is passed unmodified
    // to another wasmts method. JS operations that copy the array (.map,
    // .filter, .slice, spread) drop the property; extractCoordinateArray
    // falls back to per-element extraction in that case (correct, slower).
    static JSObject createJSCoordinateArray(Coordinate[] arr) {
        JSObject result = createJSArray();
        for (Coordinate c : arr) {
            JSNumber xNum = JSNumber.of(c.getX());
            JSNumber yNum = JSNumber.of(c.getY());
            boolean hasZ = !Double.isNaN(c.getZ());
            boolean hasM = !Double.isNaN(c.getM());
            JSObject coordObj;
            if (hasZ && hasM) {
                coordObj = createCoordObject4D(xNum, yNum, JSNumber.of(c.getZ()), JSNumber.of(c.getM()));
            } else if (hasZ) {
                coordObj = createCoordObject3D(xNum, yNum, JSNumber.of(c.getZ()));
            } else {
                coordObj = createCoordObject(xNum, yNum);
            }
            pushToJSArray(result, coordObj);
        }
        attachJtsCoordArrayHandle(result, arr);
        return result;
    }

    // Stash the Java Coordinate[] reference on the JS array as a
    // non-enumerable property. Survives most JS operations on the array
    // itself but is dropped by anything that produces a new array
    // (.map, .filter, .slice, spread) — extractCoordinateArray handles
    // that case via its iteration fallback.
    @JS("Object.defineProperty(arr, '_jtsCoordArray', { value: handle, enumerable: false });")
    private static native void attachJtsCoordArrayHandle(JSObject arr, Coordinate[] handle);

    // Generic JS-array accessors. Used by extractCoordinateArray to walk
    // a JS array of Coordinate-literal elements when the optimized
    // `_jtsCoordArray` handle isn't present (e.g. array constructed in
    // user code, or transformed via .map / .slice / spread).
    @JS("return arr.length;")
    private static native JSValue jsArrayLength(Object arr);

    @JS("return arr[i];")
    private static native Object jsArrayElement(Object arr, int i);

    // Probe for the stashed Java Coordinate[] handle. Returns null if
    // the array doesn't carry one (user-constructed array, or one that
    // was transformed and lost the property).
    @JS("return arr._jtsCoordArray !== undefined ? arr._jtsCoordArray : null;")
    private static native Object readJtsCoordArrayHandle(Object arr);

    // Accept any of: a Java Coordinate[] (Java→Java fast path); a JS
    // array carrying the stashed _jtsCoordArray handle from
    // createJSCoordinateArray (O(1) handle reuse); or a JS array of
    // Coordinate-literal elements (per-element extraction). User code
    // and downstream transforms hit the slow path correctly.
    static Coordinate[] extractCoordinateArray(Object obj) {
        if (obj instanceof Coordinate[]) {
            return (Coordinate[]) obj;
        }
        Object stashed = readJtsCoordArrayHandle(obj);
        if (stashed instanceof Coordinate[]) {
            return (Coordinate[]) stashed;
        }
        int len = jsArrayLength(obj).asInt();
        Coordinate[] result = new Coordinate[len];
        for (int i = 0; i < len; i++) {
            result[i] = extractCoordinate(jsArrayElement(obj, i));
        }
        return result;
    }

    static Point[] extractPointArray(Object obj) {
        int len = getJSArrayLength(obj).asInt();
        Point[] result = new Point[len];
        for (int i = 0; i < len; i++) {
            result[i] = API_Generated.extractPoint(getJSArrayElement(obj, i));
        }
        return result;
    }

    static LineString[] extractLineStringArray(Object obj) {
        int len = getJSArrayLength(obj).asInt();
        LineString[] result = new LineString[len];
        for (int i = 0; i < len; i++) {
            result[i] = API_Generated.extractLineString(getJSArrayElement(obj, i));
        }
        return result;
    }

    static Polygon[] extractPolygonArray(Object obj) {
        int len = getJSArrayLength(obj).asInt();
        Polygon[] result = new Polygon[len];
        for (int i = 0; i < len; i++) {
            result[i] = API_Generated.extractPolygon(getJSArrayElement(obj, i));
        }
        return result;
    }

    static LinearRing[] extractLinearRingArray(Object obj) {
        int len = getJSArrayLength(obj).asInt();
        LinearRing[] result = new LinearRing[len];
        for (int i = 0; i < len; i++) {
            result[i] = API_Generated.extractLinearRing(getJSArrayElement(obj, i));
        }
        return result;
    }

    static Geometry[] extractGeometryArray(Object obj) {
        int len = getJSArrayLength(obj).asInt();
        Geometry[] result = new Geometry[len];
        for (int i = 0; i < len; i++) {
            result[i] = API_Generated.extractGeometry(getJSArrayElement(obj, i));
        }
        return result;
    }

    static JSObject createJSGeometryArray(Geometry[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (Geometry geom : arr) {
                if (geom == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result,
                                  API_Generated.createJSGeometry(JSString.of(geom.getGeometryType()), geom));
                }
            }
        }
        return result;
    }

    // array-return helpers following the createJSGeometryArray
    // shape for wrapped-class element types. Unlock the partial-class leak
    // pair Distance3DOp.nearestLocations() -> GeometryLocation[] and
    // LocationIndexedLine.indicesOf(Geometry) -> LinearLocation[]. Both
    // element types are in pattern-b-wrap-ctor-classes; the per-element
    // wrap is the standard createJS<Helper>(elem) shape (no dynamic type
    // tag like Geometry needs).

    static JSObject createJSGeometryLocationArray(org.locationtech.jts.operation.distance.GeometryLocation[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (org.locationtech.jts.operation.distance.GeometryLocation loc : arr) {
                if (loc == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSGeometryLocation(loc));
                }
            }
        }
        return result;
    }

    static JSObject createJSLinearLocationArray(org.locationtech.jts.linearref.LinearLocation[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (org.locationtech.jts.linearref.LinearLocation loc : arr) {
                if (loc == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSLinearLocation(loc));
                }
            }
        }
        return result;
    }

    //
    // Iterate any java.util.Collection (List + Collection super) and
    // emit a JS array whose elements are wrapped via the appropriate
    // createJS<X> helper. Same null-handling shape as the *Array
    // helpers above. The Coordinate variant uses createJSCoordinate
    // (hand-written in API.java) since Coordinate isn't in the
    // wrapped-classes universe.

    static JSObject createJSGeometryList(java.util.Collection<? extends Geometry> coll) {
        JSObject result = createJSArray();
        if (coll != null) {
            for (Geometry geom : coll) {
                if (geom == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result,
                                  API_Generated.createJSGeometry(JSString.of(geom.getGeometryType()), geom));
                }
            }
        }
        return result;
    }

    // Named createJSCoordList (not createJSCoordinateList) to avoid
    // colliding with the auto-ctor helper auto-emitted for the wrapped
    // class org.locationtech.jts.geom.CoordinateList.
    static JSObject createJSCoordList(java.util.Collection<Coordinate> coll) {
        JSObject result = createJSArray();
        if (coll != null) {
            for (Coordinate c : coll) {
                if (c == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, createJSCoordinate(c));
                }
            }
        }
        return result;
    }

    static JSObject createJSLineStringList(java.util.Collection<? extends Geometry> coll) {
        JSObject result = createJSArray();
        if (coll != null) {
            for (Geometry geom : coll) {
                if (geom == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result,
                                  API_Generated.createJSGeometry(JSString.of("LineString"), geom));
                }
            }
        }
        return result;
    }

    // primitive-array catalog extensions. byte[] already had
    // both directions wired via createUint8Array; double[] / int[] /
    // String[] use plain JS arrays (createJSArray + pushToJSArray) since
    // they don't need typed-array semantics on the JS side. Param-side
    // extractors mirror extractByteArray's JS-or-Java tolerance — if a
    // caller passes the underlying primitive array directly (less common
    // but possible from JVM call sites), we accept it; otherwise treat
    // the value as a JS array and unbox per-element.

    static double[] extractDoubleArray(Object obj) {
        if (obj instanceof double[]) {
            return (double[]) obj;
        }
        int len = getJSArrayLength(obj).asInt();
        double[] result = new double[len];
        for (int i = 0; i < len; i++) {
            result[i] = ((JSValue) getJSArrayElement(obj, i)).asDouble();
        }
        return result;
    }

    static int[] extractIntArray(Object obj) {
        if (obj instanceof int[]) {
            return (int[]) obj;
        }
        int len = getJSArrayLength(obj).asInt();
        int[] result = new int[len];
        for (int i = 0; i < len; i++) {
            result[i] = ((JSValue) getJSArrayElement(obj, i)).asInt();
        }
        return result;
    }

    static JSObject createJSDoubleArray(double[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (double d : arr) {
                pushToJSArray(result, JSNumber.of(d));
            }
        }
        return result;
    }

    static JSObject createJSIntArray(int[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (int i : arr) {
                pushToJSArray(result, JSNumber.of(i));
            }
        }
        return result;
    }

    static JSObject createJSStringArray(String[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (String s : arr) {
                if (s == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, JSString.of(s));
                }
            }
        }
        return result;
    }

    // generic List<Object> pass-through wrap. JTS spatial-
    // index classes (STRtree, Quadtree, SIRtree) store arbitrary user
    // objects keyed by spatial extent and return them via .query() /
    // .queryAll() as raw List with Object element type. Each item is
    // pushed straight through — typically a Geometry wrapper inserted
    // by the user, which round-trips back to JS as the same handle.
    // Mirrors the inline body of the legacy :strtree*env->list shape.

    static JSObject createJSObjectList(java.util.List<?> list) {
        JSObject result = createJSArray();
        if (list != null) {
            for (Object item : list) {
                pushToJSArray(result, item);
            }
        }
        return result;
    }

    // QuadEdge + Vertex collection wraps. Both element types
    // are in wrapped-classes via auto-ctor; per-element wrap goes through
    // the auto-emitted createJS<X> in API_Generated. Same null-handling
    // shape as createJSGeometryList / createJSCoordList from AAC.

    static JSObject createJSQuadEdgeList(java.util.Collection<? extends org.locationtech.jts.triangulate.quadedge.QuadEdge> coll) {
        JSObject result = createJSArray();
        if (coll != null) {
            for (org.locationtech.jts.triangulate.quadedge.QuadEdge e : coll) {
                if (e == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSQuadEdge(e));
                }
            }
        }
        return result;
    }

    static JSObject createJSVertexList(java.util.Collection<? extends org.locationtech.jts.triangulate.quadedge.Vertex> coll) {
        JSObject result = createJSArray();
        if (coll != null) {
            for (org.locationtech.jts.triangulate.quadedge.Vertex v : coll) {
                if (v == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSVertex(v));
                }
            }
        }
        return result;
    }

    // typed-array element wrappers + nested-array list
    // helpers for QuadEdgeSubdivision's getTriangle{Coordinates, Edges,
    // Vertices}(boolean) returns. Each returns a List<X[]> where the
    // outer list contains arrays of 3 elements (triangle vertices /
    // edges / coordinates). The nested-list helper iterates the outer
    // list, wraps each non-null inner array via createJSXArray, and
    // pushes the resulting JS array.

    static JSObject createJSQuadEdgeArray(org.locationtech.jts.triangulate.quadedge.QuadEdge[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (org.locationtech.jts.triangulate.quadedge.QuadEdge e : arr) {
                if (e == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSQuadEdge(e));
                }
            }
        }
        return result;
    }

    static JSObject createJSVertexArray(org.locationtech.jts.triangulate.quadedge.Vertex[] arr) {
        JSObject result = createJSArray();
        if (arr != null) {
            for (org.locationtech.jts.triangulate.quadedge.Vertex v : arr) {
                if (v == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, API_Generated.createJSVertex(v));
                }
            }
        }
        return result;
    }

    static JSObject createJSCoordinateArrayList(java.util.List<Coordinate[]> list) {
        JSObject result = createJSArray();
        if (list != null) {
            for (Coordinate[] arr : list) {
                if (arr == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, createJSCoordinateArray(arr));
                }
            }
        }
        return result;
    }

    static JSObject createJSQuadEdgeArrayList(java.util.List<org.locationtech.jts.triangulate.quadedge.QuadEdge[]> list) {
        JSObject result = createJSArray();
        if (list != null) {
            for (org.locationtech.jts.triangulate.quadedge.QuadEdge[] arr : list) {
                if (arr == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, createJSQuadEdgeArray(arr));
                }
            }
        }
        return result;
    }

    static JSObject createJSVertexArrayList(java.util.List<org.locationtech.jts.triangulate.quadedge.Vertex[]> list) {
        JSObject result = createJSArray();
        if (list != null) {
            for (org.locationtech.jts.triangulate.quadedge.Vertex[] arr : list) {
                if (arr == null) {
                    pushToJSArray(result, null);
                } else {
                    pushToJSArray(result, createJSVertexArray(arr));
                }
            }
        }
        return result;
    }

    // attachIntersectionMatrixOverrides — polymorphic set / setAtLeast
    // (typeof-string check picks setFromString vs the (int, int, int)
    // form) and the matches → matchesPattern JS-name alias.
    @JS("""
        obj.set = (row, col, value) => {
            if (typeof row === 'string') {
                wasmts.geom.IntersectionMatrix.setFromString(obj, row);
            } else {
                wasmts.geom.IntersectionMatrix.set(obj, row, col, value);
            }
        };
        obj.setAtLeast = (row, col, min) => {
            if (typeof row === 'string') {
                wasmts.geom.IntersectionMatrix.setAtLeastFromString(obj, row);
            } else {
                wasmts.geom.IntersectionMatrix.setAtLeast(obj, row, col, min);
            }
        };
        obj.matches = (pattern) => wasmts.geom.IntersectionMatrix.matchesPattern(obj, pattern);
    """)
    static native void attachIntersectionMatrixOverrides(JSObject obj);

    @JS("return [];")
    static native JSObject createJSArray();

    @JS("arr.push(item);")
    static native void pushToJSArray(JSObject arr, Object item);

    @JS("return {x: x, y: y};")
    private static native JSObject createCoordObject(JSNumber x, JSNumber y);

    @JS("return {x: x, y: y, z: z};")
    private static native JSObject createCoordObject3D(JSNumber x, JSNumber y, JSNumber z);

    @JS("return {x: x, y: y, z: z, m: m};")
    private static native JSObject createCoordObject4D(JSNumber x, JSNumber y, JSNumber z, JSNumber m);

    // WKB byte[] -> JS Uint8Array. Called by the auto-gen
    // :writer*geom->bytes dispatch (WKBWriter.write).
    @JS("return new Uint8Array(length);")
    private static native JSObject createUint8Array(JSNumber length);

    @JS("arr[index] = value;")
    private static native void setByteInArray(JSObject arr, JSNumber index, JSNumber value);

    static JSObject byteArrayToJSUint8Array(byte[] bytes) {
        JSObject arr = createUint8Array(JSNumber.of(bytes.length));
        for (int i = 0; i < bytes.length; i++) {
            setByteInArray(arr, JSNumber.of(i), JSNumber.of(bytes[i] & 0xFF));
        }
        return arr;
    }

    // Helper to convert a Collection<Geometry> to a JavaScript array
    static Object convertGeometryCollectionToJS(Collection<Geometry> geometries) {
        JSObject jsArray = createJSArray();
        for (Geometry geom : geometries) {
            Object jsGeom = API_Generated.createJSGeometry(JSString.of(geom.getGeometryType()), geom);
            pushToJSArray(jsArray, jsGeom);
        }
        return jsArray;
    }

    // Helper to get array length from JavaScript
    @JS("return arr.length;")
    private static native JSValue getJSArrayLength(Object arr);

    // Helper to get array element from JavaScript
    @JS("return arr[index];")
    private static native Object getJSArrayElement(Object arr, int index);

    // Maps JTS's PrecisionModel.Type#toString ("FLOATING" / "FLOATING SINGLE"
    // / "FIXED") to the wasmts JS contract's friendly form. Called from the
    // auto-gen :pm->type-friendly dispatch.
    static String precisionModelTypeFriendlyName(PrecisionModel pm) {
        return switch (pm.getType().toString()) {
            case "FLOATING" -> "Floating";
            case "FLOATING SINGLE" -> "Floating-Single";
            case "FIXED" -> "Fixed";
            default -> pm.getType().toString();
        };
    }

    // Inverse of precisionModelTypeFriendlyName — used by the auto-gen
    // PrecisionModel(Type) ctor dispatch to coerce a JS string
    // ("FLOATING" / "Floating" / "FIXED" / "Floating-Single" / ...) into the
    // matching JTS Type constant.
    static PrecisionModel.Type precisionModelTypeFromName(String name) {
        return switch (name) {
            case "FLOATING", "Floating" -> PrecisionModel.FLOATING;
            case "FLOATING_SINGLE", "Floating-Single", "FLOATING SINGLE" ->
                PrecisionModel.FLOATING_SINGLE;
            case "FIXED", "Fixed" -> PrecisionModel.FIXED;
            default -> PrecisionModel.FLOATING;
        };
    }

    @JS("""
        const f = { _jtsGeometryFactory: gf };
        f.createPoint = (x, y, z, m) => wasmts.geom.GeometryFactory.createPoint(f, { x, y, z, m });
        return f;
        """)
    static native JSObject createJSGeometryFactoryFromInstance(GeometryFactory gf);

    // Alias used by the auto-emitted :ctor template (new GeometryFactory(...)).
    static JSObject createJSGeometryFactory(GeometryFactory gf) {
        return createJSGeometryFactoryFromInstance(gf);
    }

    static GeometryFactory extractGeometryFactory(Object obj) {
        if (obj instanceof GeometryFactory) {
            return (GeometryFactory) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return jsObj.get("_jtsGeometryFactory", GeometryFactory.class);
    }

    // attachCoordinateSequenceOverrides — getCoordinate(i) and
    // toCoordinateArray() return plain {x, y, z?, m?} objects (per-coord
    // NaN check), not wrapped Coordinate handles, matching the
    // `wasmts.geom.getCoordinates` contract on Geometry.
    @JS("""
        const ns = wasmts.geom.CoordinateSequence;
        obj.getCoordinate = (i) => {
            const o = { x: ns.getX(obj, i), y: ns.getY(obj, i) };
            if (obj.hasZ()) {
                const z = ns.getZ(obj, i);
                if (!Number.isNaN(z)) o.z = z;
            }
            if (obj.hasM()) {
                const m = ns.getM(obj, i);
                if (!Number.isNaN(m)) o.m = m;
            }
            return o;
        };
        obj.toCoordinateArray = () => {
            const n = ns.size(obj);
            const result = new Array(n);
            for (let i = 0; i < n; i++) result[i] = obj.getCoordinate(i);
            return result;
        };
    """)
    static native void attachCoordinateSequenceOverrides(JSObject obj);

    // Helper to invoke JS filter callback matching JTS CoordinateSequenceFilter.filter(seq, i)
    @JS.Coerce
    @JS("fun(seq, i);")
    private static native void invokeFilterFn(JSValue fun, JSObject seq, int i);

    private static class JSCallbackCoordinateSequenceFilter implements CoordinateSequenceFilter {
        private final JSValue filterFn;
        private JSObject currentJSSeq;
        private CoordinateSequence currentSeq;

        JSCallbackCoordinateSequenceFilter(JSValue filterFn) {
            this.filterFn = filterFn;
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            // Create or reuse JS wrapper for this CoordinateSequence
            // Note: the same seq instance is passed for all coordinates in a ring
            if (currentSeq != seq) {
                currentSeq = seq;
                currentJSSeq = API_Generated.createJSCoordinateSequence(seq);
            }
            // Call JS filter matching JTS signature: filter(seq, i)
            invokeFilterFn(filterFn, currentJSSeq, i);
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean isGeometryChanged() {
            return true;
        }
    }

    // EXPERIMENTAL — not a standard JTS pattern, may be removed or changed.
    // Reads x/y from a JS flat array (e.g. Float64Array) at dimensional offsets so
    // that setOrdinate calls stay in Java. Reduces JS<->WASM boundary crossings
    // from 4N (callback + 2 setOrdinate + return) to 2N (array element reads).
    private static class FlatArrayCoordinateSequenceFilter implements CoordinateSequenceFilter {
        private final Object jsArray;
        private final int valuesPerCoord;
        private int idx = 0;

        FlatArrayCoordinateSequenceFilter(Object jsArray, int valuesPerCoord) {
            this.jsArray = jsArray;
            this.valuesPerCoord = valuesPerCoord;
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            int base = idx * valuesPerCoord;
            double x = ((JSValue) getJSArrayElement(jsArray, base)).asDouble();
            double y = ((JSValue) getJSArrayElement(jsArray, base + 1)).asDouble();
            seq.setOrdinate(i, 0, x);
            seq.setOrdinate(i, 1, y);
            idx++;
        }

        @Override
        public boolean isDone() { return false; }

        @Override
        public boolean isGeometryChanged() { return true; }
    }

    // Apply CoordinateSequenceFilter with JS callback, returning the modified
    // copy (the wasmts.geom.apply JS contract). Called from the auto-gen
    // :geom*csfilter-callback->void dispatch.
    static Object geometryApplyCSFilter(Geometry g, JSValue filterFn) {
        Geometry copy = g.copy();
        JSCallbackCoordinateSequenceFilter filter = new JSCallbackCoordinateSequenceFilter(filterFn);
        copy.apply(filter);
        copy.geometryChanged();
        return API_Generated.createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    // CoordinateFilter / GeometryComponentFilter /
    //                  GeometryFilter callback overloads of Geometry.apply.
    //
    // The remaining 3 apply overloads each take a distinct JTS filter
    // interface but share the same JS-side shape: a single callback
    // receiving one argument per visit (a Coordinate for CoordinateFilter,
    // a Geometry for the other two). The two Geometry-arg interfaces
    // collapse onto a single JSCallbackGeomFilter that implements both.

    @JS("fun(arg);")
    private static native void invokeFilter1ArgFn(JSValue fun, Object arg);

    private static class JSCallbackCoordinateFilter implements CoordinateFilter {
        private final JSValue filterFn;

        JSCallbackCoordinateFilter(JSValue filterFn) {
            this.filterFn = filterFn;
        }

        @Override
        public void filter(Coordinate coord) {
            invokeFilter1ArgFn(filterFn, createJSCoordinate(coord));
        }
    }

    static Object geometryApplyCoordFilter(Geometry g, JSValue filterFn) {
        Geometry copy = g.copy();
        JSCallbackCoordinateFilter filter = new JSCallbackCoordinateFilter(filterFn);
        copy.apply(filter);
        copy.geometryChanged();
        return API_Generated.createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    // Shared filter for GeometryFilter and GeometryComponentFilter — both
    // interfaces have a single `filter(Geometry)` method, so one class
    // satisfies both. Disambiguating cast at the apply call site selects
    // which JTS overload runs (component-recursive vs collection-level).
    private static class JSCallbackGeomFilter implements GeometryFilter, GeometryComponentFilter {
        private final JSValue filterFn;

        JSCallbackGeomFilter(JSValue filterFn) {
            this.filterFn = filterFn;
        }

        @Override
        public void filter(Geometry geom) {
            invokeFilter1ArgFn(filterFn,
                               API_Generated.createJSGeometry(JSString.of(geom.getGeometryType()), geom));
        }
    }

    static Object geometryApplyComponentFilter(Geometry g, JSValue filterFn) {
        Geometry copy = g.copy();
        JSCallbackGeomFilter filter = new JSCallbackGeomFilter(filterFn);
        copy.apply((GeometryComponentFilter) filter);
        return API_Generated.createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    static Object geometryApplyGeometryFilter(Geometry g, JSValue filterFn) {
        Geometry copy = g.copy();
        JSCallbackGeomFilter filter = new JSCallbackGeomFilter(filterFn);
        copy.apply((GeometryFilter) filter);
        return API_Generated.createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    // EXPERIMENTAL — may be removed or changed.
    // Bulk coordinate replacement from a flat JS array. Avoids per-coordinate
    // JS callback overhead of applyJS by reading array elements on the Java side.
    private static Object applyCoordinatesJS(Object geom, Object flatArray, Object valuesPerCoord) {
        Geometry g = API_Generated.extractGeometry(geom);
        Geometry copy = g.copy();
        int s = ((JSValue) valuesPerCoord).asInt();
        FlatArrayCoordinateSequenceFilter filter = new FlatArrayCoordinateSequenceFilter(flatArray, s);
        copy.apply(filter);
        copy.geometryChanged();
        return API_Generated.createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    // Geometry base class - getDimension

    // Geometry base class - getBoundaryDimension



    public static void main(String[] args) {
        // Set up globalThis.wasmts + every intermediate namespace from the
        // registry. Idempotent — also called again from the trailing
        // API_Generated.register(). Done up front so the hand-written
        // @JS exports below have live `wasmts.geom`, `wasmts.io`, etc.
        API_Generated.setupNamespaces();

        // applyCoordinates: wasmts-specific bulk-coordinate replacement from a
        // flat numeric array. No JTS analog.
        exportApplyCoordinates(API::applyCoordinatesJS);

        // Auto-generated dispatch table (see API_Generated.java). Installed
        // last so its registrations overwrite hand-written exports at any
        // shared JS path — the codegen is the canonical source of truth for
        // Geometry-receiver shapes. Methods where the auto-gen signature
        // diverges from hand-written (e.g. equalsExact's tolerance form)
        // are kept out of scope via manual.edn :skip.
        API_Generated.register();
    }
}
