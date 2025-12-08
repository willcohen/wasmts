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
import org.graalvm.webimage.api.JSUndefined;
import org.graalvm.webimage.api.JSValue;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.JTSVersion;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.algorithm.MinimumBoundingCircle;
import org.locationtech.jts.algorithm.MinimumAreaRectangle;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.OffsetCurveBuilder;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.locationtech.jts.operation.distance.DistanceOp;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;

/**
 * JavaScript API for JTS Geometry operations.
 * Exports JTS functionality to JavaScript with native object interop.
 */
public class API {

    private static final GeometryFactory factory = new GeometryFactory();
    private static final WKTReader wktReader = new WKTReader(factory);
    private static final WKTWriter wktWriter = new WKTWriter(); // Use default - outputs actual dimensions
    private static final WKBReader wkbReader = new WKBReader(factory);

    // Multiple WKB writers for different coordinate dimensions
    private static final WKBWriter wkbWriter2D = new WKBWriter(2); // XY only
    private static final WKBWriter wkbWriter3D = new WKBWriter(3); // XYZ
    private static final WKBWriter wkbWriter4D = new WKBWriter(4); // XYZM

    // Functional interfaces for JavaScript exports
    // NOTE: Must use Object parameters, not primitives - the Java proxy method lookup
    // requires exact signature match, and JS numbers don't auto-match to Java doubles
    @FunctionalInterface
    interface CreatePointFn {
        Object create(Object x, Object y);
    }

    @FunctionalInterface
    interface CreatePoint3DFn {
        Object create(Object x, Object y, Object z);
    }

    @FunctionalInterface
    interface CreatePoint4DFn {
        Object create(Object x, Object y, Object z, Object m);
    }

    @FunctionalInterface
    interface CreateLineStringFn {
        Object create(Object coords);
    }

    @FunctionalInterface
    interface CreatePolygonFn {
        Object create(Object shell, Object holes);
    }

    // CreateLineFn and other array-based interfaces removed - use WKT/WKB instead

    @FunctionalInterface
    interface BufferFn {
        Object buffer(Object geom, Object distance, Object endCapStyle, Object joinStyle, Object mitreLimit);
    }

    @FunctionalInterface
    interface UnionFn {
        Object union(Object g1, Object g2);
    }

    @FunctionalInterface
    interface IntersectionFn {
        Object intersection(Object g1, Object g2);
    }

    @FunctionalInterface
    interface GetAreaFn {
        Object getArea(Object geom);
    }

    @FunctionalInterface
    interface ContainsFn {
        Object contains(Object g1, Object g2);
    }

    @FunctionalInterface
    interface IntersectsFn {
        Object intersects(Object g1, Object g2);
    }

    @FunctionalInterface
    interface TouchesFn {
        Object touches(Object g1, Object g2);
    }

    @FunctionalInterface
    interface CrossesFn {
        Object crosses(Object g1, Object g2);
    }

    @FunctionalInterface
    interface WithinFn {
        Object within(Object g1, Object g2);
    }

    @FunctionalInterface
    interface OverlapsFn {
        Object overlaps(Object g1, Object g2);
    }

    @FunctionalInterface
    interface DisjointFn {
        Object disjoint(Object g1, Object g2);
    }

    @FunctionalInterface
    interface GeomEqualsFn {
        Object equals(Object g1, Object g2);
    }

    @FunctionalInterface
    interface ReadWKTFn {
        Object read(Object wkt);
    }

    @FunctionalInterface
    interface ReadWKBFn {
        Object read(Object wkb);
    }

    @FunctionalInterface
    interface WriteWKBFn {
        Object write(Object geom);
    }

    // Array-based geometry creation interfaces removed - use WKT/WKB for complex geometries

    // More operations
    @FunctionalInterface
    interface DifferenceFn {
        Object difference(Object g1, Object g2);
    }

    @FunctionalInterface
    interface SymDifferenceFn {
        Object symDifference(Object g1, Object g2);
    }

    @FunctionalInterface
    interface ConvexHullFn {
        Object convexHull(Object geom);
    }

    @FunctionalInterface
    interface SimplifyFn {
        Object simplify(Object geom, Object tolerance);
    }

    @FunctionalInterface
    interface BoundaryFn {
        Object boundary(Object geom);
    }

    @FunctionalInterface
    interface CentroidFn {
        Object centroid(Object geom);
    }

    // Measurements
    @FunctionalInterface
    interface GetLengthFn {
        Object getLength(Object geom);
    }

    @FunctionalInterface
    interface DistanceFn {
        Object distance(Object g1, Object g2);
    }

    @FunctionalInterface
    interface GetNumPointsFn {
        Object getNumPoints(Object geom);
    }

    @FunctionalInterface
    interface GetGeometryTypeFn {
        Object getGeometryType(Object geom);
    }

    @FunctionalInterface
    interface IsEmptyFn {
        Object isEmpty(Object geom);
    }

    @FunctionalInterface
    interface IsValidFn {
        Object isValid(Object geom);
    }

    // Coordinate/geometry access
    @FunctionalInterface
    interface GetCoordinatesFn {
        Object getCoordinates(Object geom);
    }

    @FunctionalInterface
    interface GetNumGeometriesFn {
        Object getNumGeometries(Object geom);
    }

    @FunctionalInterface
    interface GetGeometryNFn {
        Object getGeometryN(Object geom, Object n);
    }

    // WKT output
    @FunctionalInterface
    interface WriteFn {
        Object write(Object geom);
    }

    // Envelope (bounding box)
    @FunctionalInterface
    interface CreateEnvelopeFn {
        Object create(Object minX, Object maxX, Object minY, Object maxY);
    }

    @FunctionalInterface
    interface EnvelopeIntersectsFn {
        Object intersects(Object env1, Object env2);
    }

    @FunctionalInterface
    interface EnvelopeContainsFn {
        Object contains(Object env1, Object env2);
    }

    @FunctionalInterface
    interface EnvelopeExpandToIncludeFn {
        void expand(Object env, Object x, Object y);
    }

    @FunctionalInterface
    interface GetEnvelopeInternalFn {
        Object getEnvelopeInternal(Object geom);
    }

    // STRtree (spatial index)
    @FunctionalInterface
    interface CreateSTRtreeFn {
        Object create();
    }

    @FunctionalInterface
    interface STRtreeInsertFn {
        void insert(Object tree, Object envelope, Object item);
    }

    @FunctionalInterface
    interface STRtreeQueryFn {
        Object query(Object tree, Object envelope);
    }

    @FunctionalInterface
    interface STRtreeRemoveFn {
        Object remove(Object tree, Object envelope, Object item);
    }

    @FunctionalInterface
    interface STRtreeSizeFn {
        Object size(Object tree);
    }

    // PreparedGeometry (optimized spatial predicates)
    @FunctionalInterface
    interface PrepareGeometryFn {
        Object prepare(Object geom);
    }

    @FunctionalInterface
    interface PreparedContainsProperlyFn {
        Object containsProperly(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedCoveredByFn {
        Object coveredBy(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedGetGeometryFn {
        Object getGeometry(Object prepGeom);
    }

    @FunctionalInterface
    interface PreparedContainsFn {
        Object contains(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedCoversFn {
        Object covers(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedCrossesFn {
        Object crosses(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedDisjointFn {
        Object disjoint(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedIntersectsFn {
        Object intersects(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedOverlapsFn {
        Object overlaps(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedTouchesFn {
        Object touches(Object prepGeom, Object geom);
    }

    @FunctionalInterface
    interface PreparedWithinFn {
        Object within(Object prepGeom, Object geom);
    }

    // Geometry analysis algorithms
    @FunctionalInterface
    interface MinimumDiameterGetRectFn {
        Object getMinimumRectangle(Object geom);
    }

    @FunctionalInterface
    interface MinimumDiameterGetLengthFn {
        Object getLength(Object geom);
    }

    @FunctionalInterface
    interface MinimumAreaRectangleFn {
        Object getMinimumRectangle(Object geom);
    }

    @FunctionalInterface
    interface MinimumBoundingCircleGetCircleFn {
        Object getCircle(Object geom);
    }

    @FunctionalInterface
    interface MinimumBoundingCircleGetCentreFn {
        Object getCentre(Object geom);
    }

    @FunctionalInterface
    interface MinimumBoundingCircleGetRadiusFn {
        Object getRadius(Object geom);
    }

    // Offset curve
    @FunctionalInterface
    interface OffsetCurveFn {
        Object getOffsetCurve(Object geom, Object distance, Object endCapStyle, Object joinStyle, Object mitreLimit);
    }

    // LineMerger
    @FunctionalInterface
    interface CreateLineMergerFn {
        Object create();
    }

    @FunctionalInterface
    interface LineMergerAddFn {
        void add(Object merger, Object geom);
    }

    @FunctionalInterface
    interface LineMergerGetResultFn {
        Object getMergedLineStrings(Object merger);
    }

    // CascadedPolygonUnion
    @FunctionalInterface
    interface CascadedPolygonUnionFn {
        Object union(Object geometries);
    }

    // New Geometry methods
    @FunctionalInterface
    interface EqualsTopoFn {
        Object equalsTopo(Object g1, Object g2);
    }

    @FunctionalInterface
    interface CoversFn {
        Object covers(Object g1, Object g2);
    }

    @FunctionalInterface
    interface CoveredByFn {
        Object coveredBy(Object g1, Object g2);
    }

    @FunctionalInterface
    interface GetEnvelopeFn {
        Object getEnvelope(Object geom);
    }

    @FunctionalInterface
    interface GetInteriorPointFn {
        Object getInteriorPoint(Object geom);
    }

    @FunctionalInterface
    interface CopyFn {
        Object copy(Object geom);
    }

    @FunctionalInterface
    interface ReverseFn {
        Object reverse(Object geom);
    }

    @FunctionalInterface
    interface NormalizeFn {
        Object normalize(Object geom);
    }

    @FunctionalInterface
    interface IsSimpleFn {
        Object isSimple(Object geom);
    }

    @FunctionalInterface
    interface IsRectangleFn {
        Object isRectangle(Object geom);
    }

    @FunctionalInterface
    interface GetUserDataFn {
        Object getUserData(Object geom);
    }

    @FunctionalInterface
    interface SetUserDataFn {
        void setUserData(Object geom, Object data);
    }

    // GeoJSON I/O interfaces
    @FunctionalInterface
    interface ReadGeoJSONFn {
        Object read(Object geojson);
    }

    @FunctionalInterface
    interface WriteGeoJSONFn {
        Object write(Object geom);
    }

    // Distance operation interfaces
    @FunctionalInterface
    interface NearestPointsFn {
        Object nearestPoints(Object geom1, Object geom2);
    }

    // Polygon accessor interfaces
    @FunctionalInterface
    interface GetExteriorRingFn {
        Object getExteriorRing(Object geom);
    }

    @FunctionalInterface
    interface GetInteriorRingNFn {
        Object getInteriorRingN(Object geom, Object n);
    }

    @FunctionalInterface
    interface GetNumInteriorRingFn {
        Object getNumInteriorRing(Object geom);
    }

    // CoordinateSequenceFilter - matches JTS Geometry.apply(CoordinateSequenceFilter)
    @FunctionalInterface
    interface ApplyFn {
        Object apply(Object geom, Object filterFn);
    }

    // CoordinateSequence wrapper functional interfaces
    @FunctionalInterface
    interface CoordSeqGetXFn {
        Object getX(Object seq, Object i);
    }

    @FunctionalInterface
    interface CoordSeqGetYFn {
        Object getY(Object seq, Object i);
    }

    @FunctionalInterface
    interface CoordSeqGetZFn {
        Object getZ(Object seq, Object i);
    }

    @FunctionalInterface
    interface CoordSeqGetMFn {
        Object getM(Object seq, Object i);
    }

    @FunctionalInterface
    interface CoordSeqSetOrdinateFn {
        void setOrdinate(Object seq, Object i, Object ordinateIndex, Object value);
    }

    @FunctionalInterface
    interface CoordSeqGetDimensionFn {
        Object getDimension(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqGetMeasuresFn {
        Object getMeasures(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqSizeFn {
        Object size(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqGetCoordinateFn {
        Object getCoordinate(Object seq, Object i);
    }

    @FunctionalInterface
    interface CoordSeqGetOrdinateFn {
        Object getOrdinate(Object seq, Object i, Object ordinateIndex);
    }

    @FunctionalInterface
    interface CoordSeqHasZFn {
        Object hasZ(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqHasMFn {
        Object hasM(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqCopyFn {
        Object copy(Object seq);
    }

    @FunctionalInterface
    interface CoordSeqToCoordinateArrayFn {
        Object toCoordinateArray(Object seq);
    }

    // Export methods - WasmTS namespace structure

    // Initialize namespace structure
    @JS("""
        globalThis.wasmts = globalThis.wasmts || {};
        wasmts.geom = wasmts.geom || {};
        wasmts.geom.prep = wasmts.geom.prep || {};
        wasmts.algorithm = wasmts.algorithm || {};
        wasmts.simplify = wasmts.simplify || {};
        wasmts.io = wasmts.io || {};
        wasmts.index = wasmts.index || {};
        wasmts.index.strtree = wasmts.index.strtree || {};
        wasmts.operation = wasmts.operation || {};
        wasmts.operation.buffer = wasmts.operation.buffer || {};
        wasmts.operation.linemerge = wasmts.operation.linemerge || {};
        wasmts.operation.union = wasmts.operation.union || {};
    """)
    private static native void initializeNamespaces();

    // GeometryFactory
    @FunctionalInterface
    interface GetGeometryFactoryFn {
        Object getFactory();
    }

    @JS.Coerce
    @JS("""
        wasmts.geom.GeometryFactory = () => {
            return fn.getFactory();
        };
    """)
    private static native void exportGeometryFactory(GetGeometryFactoryFn fn);

    // wasmts.geom.* - Geometry creation (static methods)
    @JS.Coerce
    @JS("""
        wasmts.geom.createPoint = (x, y, z, m) => {
            if (m !== undefined) return fn4d.create(x, y, z, m);
            if (z !== undefined) return fn3d.create(x, y, z);
            return fn2d.create(x, y);
        };
    """)
    private static native void exportCreatePoint(CreatePointFn fn2d, CreatePoint3DFn fn3d, CreatePoint4DFn fn4d);

    @JS.Coerce
    @JS("wasmts.geom.createLineString = (coords) => fn.create(coords);")
    private static native void exportCreateLineString(CreateLineStringFn fn);

    @JS.Coerce
    @JS("wasmts.geom.createPolygon = (shell, holes) => fn.create(shell, holes ?? null);")
    private static native void exportCreatePolygon(CreatePolygonFn fn);

    @JS.Coerce
    @JS("wasmts.geom.createEnvelope = (minX, maxX, minY, maxY) => fn.create(minX, maxX, minY, maxY);")
    private static native void exportCreateEnvelope(CreateEnvelopeFn fn);

    // Geometry operations (these will become methods on geometry objects later)
    @JS.Coerce
    @JS("wasmts.geom.buffer = (geom, dist, endCapStyle, joinStyle, mitreLimit) => fn.buffer(geom, dist, endCapStyle ?? null, joinStyle ?? null, mitreLimit ?? null);")
    private static native void exportBuffer(BufferFn fn);

    @JS.Coerce
    @JS("wasmts.geom.union = (g1, g2) => fn.union(g1, g2);")
    private static native void exportUnion(UnionFn fn);

    @JS.Coerce
    @JS("wasmts.geom.intersection = (g1, g2) => fn.intersection(g1, g2);")
    private static native void exportIntersection(IntersectionFn fn);

    // Geometry measurements and predicates (on wasmts.geom for now, will become methods)
    @JS.Coerce
    @JS("wasmts.geom.getArea = (geom) => fn.getArea(geom);")
    private static native void exportGetArea(GetAreaFn fn);

    @JS.Coerce
    @JS("wasmts.geom.contains = (g1, g2) => fn.contains(g1, g2);")
    private static native void exportContains(ContainsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.intersects = (g1, g2) => fn.intersects(g1, g2);")
    private static native void exportIntersects(IntersectsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.touches = (g1, g2) => fn.touches(g1, g2);")
    private static native void exportTouches(TouchesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.crosses = (g1, g2) => fn.crosses(g1, g2);")
    private static native void exportCrosses(CrossesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.within = (g1, g2) => fn.within(g1, g2);")
    private static native void exportWithin(WithinFn fn);

    @JS.Coerce
    @JS("wasmts.geom.overlaps = (g1, g2) => fn.overlaps(g1, g2);")
    private static native void exportOverlaps(OverlapsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.disjoint = (g1, g2) => fn.disjoint(g1, g2);")
    private static native void exportDisjoint(DisjointFn fn);

    @JS.Coerce
    @JS("wasmts.geom.equals = (g1, g2) => fn.equals(g1, g2);")
    private static native void exportGeomEquals(GeomEqualsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.equalsTopo = (g1, g2) => fn.equalsTopo(g1, g2);")
    private static native void exportEqualsTopo(EqualsTopoFn fn);

    @JS.Coerce
    @JS("wasmts.geom.covers = (g1, g2) => fn.covers(g1, g2);")
    private static native void exportCovers(CoversFn fn);

    @JS.Coerce
    @JS("wasmts.geom.coveredBy = (g1, g2) => fn.coveredBy(g1, g2);")
    private static native void exportCoveredBy(CoveredByFn fn);

    // More operations
    @JS.Coerce
    @JS("wasmts.geom.difference = (g1, g2) => fn.difference(g1, g2);")
    private static native void exportDifference(DifferenceFn fn);

    @JS.Coerce
    @JS("wasmts.geom.symDifference = (g1, g2) => fn.symDifference(g1, g2);")
    private static native void exportSymDifference(SymDifferenceFn fn);

    @JS.Coerce
    @JS("wasmts.geom.convexHull = (geom) => fn.convexHull(geom);")
    private static native void exportConvexHull(ConvexHullFn fn);

    @JS.Coerce
    @JS("wasmts.simplify.DouglasPeuckerSimplifier = wasmts.simplify.DouglasPeuckerSimplifier || {}; wasmts.simplify.DouglasPeuckerSimplifier.simplify = (geom, tolerance) => fn.simplify(geom, tolerance);")
    private static native void exportSimplify(SimplifyFn fn);

    @JS.Coerce
    @JS("wasmts.geom.boundary = (geom) => fn.boundary(geom);")
    private static native void exportBoundary(BoundaryFn fn);

    @JS.Coerce
    @JS("wasmts.geom.centroid = (geom) => fn.centroid(geom);")
    private static native void exportCentroid(CentroidFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getEnvelope = (geom) => fn.getEnvelope(geom);")
    private static native void exportGetEnvelope(GetEnvelopeFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getInteriorPoint = (geom) => fn.getInteriorPoint(geom);")
    private static native void exportGetInteriorPoint(GetInteriorPointFn fn);

    @JS.Coerce
    @JS("wasmts.geom.copy = (geom) => fn.copy(geom);")
    private static native void exportCopy(CopyFn fn);

    @JS.Coerce
    @JS("wasmts.geom.reverse = (geom) => fn.reverse(geom);")
    private static native void exportReverse(ReverseFn fn);

    @JS.Coerce
    @JS("wasmts.geom.normalize = (geom) => fn.normalize(geom);")
    private static native void exportNormalize(NormalizeFn fn);

    // Measurements
    @JS.Coerce
    @JS("wasmts.geom.getLength = (geom) => fn.getLength(geom);")
    private static native void exportGetLength(GetLengthFn fn);

    @JS.Coerce
    @JS("wasmts.geom.distance = (g1, g2) => fn.distance(g1, g2);")
    private static native void exportDistance(DistanceFn fn);

    @JS.Coerce
    @JS("wasmts.geom.nearestPoints = (g1, g2) => fn.nearestPoints(g1, g2);")
    private static native void exportNearestPoints(NearestPointsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getNumPoints = (geom) => fn.getNumPoints(geom);")
    private static native void exportGetNumPoints(GetNumPointsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getGeometryType = (geom) => fn.getGeometryType(geom);")
    private static native void exportGetGeometryType(GetGeometryTypeFn fn);

    @JS.Coerce
    @JS("wasmts.geom.isEmpty = (geom) => fn.isEmpty(geom);")
    private static native void exportIsEmpty(IsEmptyFn fn);

    @JS.Coerce
    @JS("wasmts.geom.isValid = (geom) => fn.isValid(geom);")
    private static native void exportIsValid(IsValidFn fn);

    @JS.Coerce
    @JS("wasmts.geom.isSimple = (geom) => fn.isSimple(geom);")
    private static native void exportIsSimple(IsSimpleFn fn);

    @JS.Coerce
    @JS("wasmts.geom.isRectangle = (geom) => fn.isRectangle(geom);")
    private static native void exportIsRectangle(IsRectangleFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getUserData = (geom) => fn.getUserData(geom);")
    private static native void exportGetUserData(GetUserDataFn fn);

    @JS.Coerce
    @JS("wasmts.geom.setUserData = (geom, data) => fn.setUserData(geom, data);")
    private static native void exportSetUserData(SetUserDataFn fn);

    // Coordinate/geometry access
    @JS.Coerce
    @JS("wasmts.geom.getCoordinates = (geom) => fn.getCoordinates(geom);")
    private static native void exportGetCoordinates(GetCoordinatesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getNumGeometries = (geom) => fn.getNumGeometries(geom);")
    private static native void exportGetNumGeometries(GetNumGeometriesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getGeometryN = (geom, n) => fn.getGeometryN(geom, n);")
    private static native void exportGetGeometryN(GetGeometryNFn fn);

    // Polygon accessors
    @JS.Coerce
    @JS("wasmts.geom.getExteriorRing = (geom) => fn.getExteriorRing(geom);")
    private static native void exportGetExteriorRing(GetExteriorRingFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getInteriorRingN = (geom, n) => fn.getInteriorRingN(geom, n);")
    private static native void exportGetInteriorRingN(GetInteriorRingNFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getNumInteriorRing = (geom) => fn.getNumInteriorRing(geom);")
    private static native void exportGetNumInteriorRing(GetNumInteriorRingFn fn);

    // CoordinateSequenceFilter - matches JTS Geometry.apply()
    @JS.Coerce
    @JS("wasmts.geom.apply = (geom, filterFn) => fn.apply(geom, filterFn);")
    private static native void exportApply(ApplyFn fn);

    // WKT/WKB I/O (wasmts.io.*)
    @JS.Coerce
    @JS("wasmts.io.WKTReader = wasmts.io.WKTReader || {}; wasmts.io.WKTReader.read = (wkt) => fn.read(wkt);")
    private static native void exportReadWKT(ReadWKTFn fn);

    @JS.Coerce
    @JS("wasmts.io.WKTWriter = wasmts.io.WKTWriter || {}; wasmts.io.WKTWriter.write = (geom) => fn.write(geom);")
    private static native void exportWriteWKT(WriteFn fn);

    @JS.Coerce
    @JS("wasmts.io.WKBReader = wasmts.io.WKBReader || {}; wasmts.io.WKBReader.read = (wkb) => fn.read(wkb);")
    private static native void exportReadWKB(ReadWKBFn fn);

    @JS.Coerce
    @JS("wasmts.io.WKBWriter = wasmts.io.WKBWriter || {}; wasmts.io.WKBWriter.write = (geom) => fn.write(geom);")
    private static native void exportWriteWKB(WriteWKBFn fn);

    @JS.Coerce
    @JS("wasmts.io.GeoJSONReader = wasmts.io.GeoJSONReader || {}; wasmts.io.GeoJSONReader.read = (geojson) => fn.read(geojson);")
    private static native void exportReadGeoJSON(ReadGeoJSONFn fn);

    @JS.Coerce
    @JS("wasmts.io.GeoJSONWriter = wasmts.io.GeoJSONWriter || {}; wasmts.io.GeoJSONWriter.write = (geom) => fn.write(geom);")
    private static native void exportWriteGeoJSON(WriteGeoJSONFn fn);

    // Envelope exports (already defined earlier, keeping near createEnvelope at line 310)
    @JS.Coerce
    @JS("wasmts.geom.envelopeIntersects = (env1, env2) => fn.intersects(env1, env2);")
    private static native void exportEnvelopeIntersects(EnvelopeIntersectsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.envelopeContains = (env1, env2) => fn.contains(env1, env2);")
    private static native void exportEnvelopeContains(EnvelopeContainsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.envelopeExpandToInclude = (env, x, y) => fn.expand(env, x, y);")
    private static native void exportEnvelopeExpandToInclude(EnvelopeExpandToIncludeFn fn);

    @JS.Coerce
    @JS("wasmts.geom.getEnvelopeInternal = (geom) => fn.getEnvelopeInternal(geom);")
    private static native void exportGetEnvelopeInternal(GetEnvelopeInternalFn fn);

    // STRtree exports (wasmts.index.strtree.STRtree.*)
    @JS.Coerce
    @JS("wasmts.index.strtree.STRtree = wasmts.index.strtree.STRtree || {}; wasmts.index.strtree.STRtree.create = () => fn.create();")
    private static native void exportCreateSTRtree(CreateSTRtreeFn fn);

    @JS.Coerce
    @JS("wasmts.index.strtree.STRtree.insert = (tree, envelope, item) => fn.insert(tree, envelope, item);")
    private static native void exportSTRtreeInsert(STRtreeInsertFn fn);

    @JS.Coerce
    @JS("wasmts.index.strtree.STRtree.query = (tree, envelope) => fn.query(tree, envelope);")
    private static native void exportSTRtreeQuery(STRtreeQueryFn fn);

    @JS.Coerce
    @JS("wasmts.index.strtree.STRtree.remove = (tree, envelope, item) => fn.remove(tree, envelope, item);")
    private static native void exportSTRtreeRemove(STRtreeRemoveFn fn);

    @JS.Coerce
    @JS("wasmts.index.strtree.STRtree.size = (tree) => fn.size(tree);")
    private static native void exportSTRtreeSize(STRtreeSizeFn fn);

    // PreparedGeometry exports (wasmts.geom.prep.*)
    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometryFactory = wasmts.geom.prep.PreparedGeometryFactory || {}; wasmts.geom.prep.PreparedGeometryFactory.prepare = (geom) => fn.prepare(geom);")
    private static native void exportPrepareGeometry(PrepareGeometryFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry = wasmts.geom.prep.PreparedGeometry || {}; wasmts.geom.prep.PreparedGeometry.containsProperly = (prepGeom, geom) => fn.containsProperly(prepGeom, geom);")
    private static native void exportPreparedContainsProperly(PreparedContainsProperlyFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.coveredBy = (prepGeom, geom) => fn.coveredBy(prepGeom, geom);")
    private static native void exportPreparedCoveredBy(PreparedCoveredByFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.getGeometry = (prepGeom) => fn.getGeometry(prepGeom);")
    private static native void exportPreparedGetGeometry(PreparedGetGeometryFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.contains = (prepGeom, geom) => fn.contains(prepGeom, geom);")
    private static native void exportPreparedContains(PreparedContainsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.covers = (prepGeom, geom) => fn.covers(prepGeom, geom);")
    private static native void exportPreparedCovers(PreparedCoversFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.crosses = (prepGeom, geom) => fn.crosses(prepGeom, geom);")
    private static native void exportPreparedCrosses(PreparedCrossesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.disjoint = (prepGeom, geom) => fn.disjoint(prepGeom, geom);")
    private static native void exportPreparedDisjoint(PreparedDisjointFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.intersects = (prepGeom, geom) => fn.intersects(prepGeom, geom);")
    private static native void exportPreparedIntersects(PreparedIntersectsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.overlaps = (prepGeom, geom) => fn.overlaps(prepGeom, geom);")
    private static native void exportPreparedOverlaps(PreparedOverlapsFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.touches = (prepGeom, geom) => fn.touches(prepGeom, geom);")
    private static native void exportPreparedTouches(PreparedTouchesFn fn);

    @JS.Coerce
    @JS("wasmts.geom.prep.PreparedGeometry.within = (prepGeom, geom) => fn.within(prepGeom, geom);")
    private static native void exportPreparedWithin(PreparedWithinFn fn);

    // Geometry analysis algorithm exports (wasmts.algorithm.*)
    @JS.Coerce
    @JS("wasmts.algorithm.MinimumDiameter = wasmts.algorithm.MinimumDiameter || {}; wasmts.algorithm.MinimumDiameter.getMinimumRectangle = (geom) => fn.getMinimumRectangle(geom);")
    private static native void exportMinimumDiameterGetRect(MinimumDiameterGetRectFn fn);

    @JS.Coerce
    @JS("wasmts.algorithm.MinimumDiameter.getLength = (geom) => fn.getLength(geom);")
    private static native void exportMinimumDiameterGetLength(MinimumDiameterGetLengthFn fn);

    @JS.Coerce
    @JS("wasmts.algorithm.MinimumAreaRectangle = wasmts.algorithm.MinimumAreaRectangle || {}; wasmts.algorithm.MinimumAreaRectangle.getMinimumRectangle = (geom) => fn.getMinimumRectangle(geom);")
    private static native void exportMinimumAreaRectangle(MinimumAreaRectangleFn fn);

    @JS.Coerce
    @JS("wasmts.algorithm.MinimumBoundingCircle = wasmts.algorithm.MinimumBoundingCircle || {}; wasmts.algorithm.MinimumBoundingCircle.getCircle = (geom) => fn.getCircle(geom);")
    private static native void exportMinimumBoundingCircleGetCircle(MinimumBoundingCircleGetCircleFn fn);

    @JS.Coerce
    @JS("wasmts.algorithm.MinimumBoundingCircle.getCentre = (geom) => fn.getCentre(geom);")
    private static native void exportMinimumBoundingCircleGetCentre(MinimumBoundingCircleGetCentreFn fn);

    @JS.Coerce
    @JS("wasmts.algorithm.MinimumBoundingCircle.getRadius = (geom) => fn.getRadius(geom);")
    private static native void exportMinimumBoundingCircleGetRadius(MinimumBoundingCircleGetRadiusFn fn);

    // Offset curve exports (wasmts.operation.buffer.OffsetCurveBuilder.*)
    @JS.Coerce
    @JS("wasmts.operation.buffer.OffsetCurveBuilder = wasmts.operation.buffer.OffsetCurveBuilder || {}; wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve = (geom, distance, endCapStyle, joinStyle, mitreLimit) => fn.getOffsetCurve(geom, distance, endCapStyle ?? null, joinStyle ?? null, mitreLimit ?? null);")
    private static native void exportOffsetCurve(OffsetCurveFn fn);

    // LineMerger exports (wasmts.operation.linemerge.LineMerger.*)
    @JS.Coerce
    @JS("wasmts.operation.linemerge.LineMerger = wasmts.operation.linemerge.LineMerger || {}; wasmts.operation.linemerge.LineMerger.create = () => fn.create();")
    private static native void exportCreateLineMerger(CreateLineMergerFn fn);

    @JS.Coerce
    @JS("wasmts.operation.linemerge.LineMerger.add = (merger, geom) => fn.add(merger, geom);")
    private static native void exportLineMergerAdd(LineMergerAddFn fn);

    @JS.Coerce
    @JS("wasmts.operation.linemerge.LineMerger.getMergedLineStrings = (merger) => fn.getMergedLineStrings(merger);")
    private static native void exportLineMergerGetResult(LineMergerGetResultFn fn);

    // CascadedPolygonUnion export (wasmts.operation.union.CascadedPolygonUnion.*)
    @JS.Coerce
    @JS("wasmts.operation.union.CascadedPolygonUnion = wasmts.operation.union.CascadedPolygonUnion || {}; wasmts.operation.union.CascadedPolygonUnion.union = (geometries) => fn.union(geometries);")
    private static native void exportCascadedPolygonUnion(CascadedPolygonUnionFn fn);

    // Helper to create JavaScript geometry object with instance methods
    // Only includes methods that exist on JTS Geometry class
    @JS("""
        const g = { type: type, _jtsGeom: geom };

        // Geometry operations (from JTS Geometry)
        g.buffer = (distance, endCapStyle, joinStyle, mitreLimit) => wasmts.geom.buffer(g, distance, endCapStyle, joinStyle, mitreLimit);
        g.union = (other) => wasmts.geom.union(g, other);
        g.intersection = (other) => wasmts.geom.intersection(g, other);
        g.difference = (other) => wasmts.geom.difference(g, other);
        g.symDifference = (other) => wasmts.geom.symDifference(g, other);
        g.convexHull = () => wasmts.geom.convexHull(g);
        g.getBoundary = () => wasmts.geom.boundary(g);
        g.getCentroid = () => wasmts.geom.centroid(g);
        g.getEnvelope = () => wasmts.geom.getEnvelope(g);
        g.getInteriorPoint = () => wasmts.geom.getInteriorPoint(g);
        g.copy = () => wasmts.geom.copy(g);
        g.reverse = () => wasmts.geom.reverse(g);
        g.normalize = () => wasmts.geom.normalize(g);
        g.apply = (filterFn) => wasmts.geom.apply(g, filterFn);

        // Predicates (from JTS Geometry)
        g.contains = (other) => wasmts.geom.contains(g, other);
        g.intersects = (other) => wasmts.geom.intersects(g, other);
        g.touches = (other) => wasmts.geom.touches(g, other);
        g.crosses = (other) => wasmts.geom.crosses(g, other);
        g.within = (other) => wasmts.geom.within(g, other);
        g.overlaps = (other) => wasmts.geom.overlaps(g, other);
        g.disjoint = (other) => wasmts.geom.disjoint(g, other);
        g.equals = (other) => wasmts.geom.equals(g, other);
        g.equalsTopo = (other) => wasmts.geom.equalsTopo(g, other);
        g.covers = (other) => wasmts.geom.covers(g, other);
        g.coveredBy = (other) => wasmts.geom.coveredBy(g, other);

        // Measurements (from JTS Geometry)
        g.getArea = () => wasmts.geom.getArea(g);
        g.getLength = () => wasmts.geom.getLength(g);
        g.distance = (other) => wasmts.geom.distance(g, other);
        g.getNumPoints = () => wasmts.geom.getNumPoints(g);
        g.getGeometryType = () => wasmts.geom.getGeometryType(g);
        g.isEmpty = () => wasmts.geom.isEmpty(g);
        g.isValid = () => wasmts.geom.isValid(g);
        g.isSimple = () => wasmts.geom.isSimple(g);
        g.isRectangle = () => wasmts.geom.isRectangle(g);

        // Coordinate/geometry access (from JTS Geometry)
        g.getCoordinates = () => wasmts.geom.getCoordinates(g);
        g.getNumGeometries = () => wasmts.geom.getNumGeometries(g);
        g.getGeometryN = (n) => wasmts.geom.getGeometryN(g, n);
        g.getEnvelopeInternal = () => wasmts.geom.getEnvelopeInternal(g);

        // Polygon accessors (from JTS Polygon)
        g.getExteriorRing = () => wasmts.geom.getExteriorRing(g);
        g.getInteriorRingN = (n) => wasmts.geom.getInteriorRingN(g, n);
        g.getNumInteriorRing = () => wasmts.geom.getNumInteriorRing(g);

        // User data (from JTS Geometry)
        g.getUserData = () => wasmts.geom.getUserData(g);
        g.setUserData = (data) => wasmts.geom.setUserData(g, data);

        // String representation (from JTS Geometry.toString())
        g.toString = () => wasmts.io.WKTWriter.write(g);

        return g;
    """)
    private static native JSObject createJSGeometry(JSString type, Geometry geom);

    // Helper to extract JTS geometry from JS object
    private static Geometry extractGeometry(Object obj) {
        if (obj instanceof Geometry) {
            return (Geometry) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return jsObj.get("_jtsGeom", Geometry.class);
    }

    // Helper to create JavaScript envelope object
    @JS("return { _jtsEnvelope: env };")
    private static native JSObject createJSEnvelope(Envelope env);

    // Helper to extract Envelope from JS object
    private static Envelope extractEnvelope(Object obj) {
        if (obj instanceof Envelope) {
            return (Envelope) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return jsObj.get("_jtsEnvelope", Envelope.class);
    }

    // Helper to create JavaScript STRtree object
    @JS("return { _jtsSTRtree: tree };")
    private static native JSObject createJSSTRtree(STRtree tree);

    // Helper to extract STRtree from JS object
    private static STRtree extractSTRtree(Object obj) {
        if (obj instanceof STRtree) {
            return (STRtree) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return jsObj.get("_jtsSTRtree", STRtree.class);
    }

    // Helper to create JavaScript PreparedGeometry object
    @JS("return { _jtsPreparedGeometry: prepGeom };")
    private static native JSObject createJSPreparedGeometry(PreparedGeometry prepGeom);

    // Helper to extract PreparedGeometry from JS object
    private static PreparedGeometry extractPreparedGeometry(Object obj) {
        if (obj instanceof PreparedGeometry) {
            return (PreparedGeometry) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return jsObj.get("_jtsPreparedGeometry", PreparedGeometry.class);
    }

    // Helper methods for coordinate extraction removed
    // Complex geometries should be created using WKT/WKB instead

    // Helper to create GeometryFactory JavaScript object
    @JS("""
        return {
            createPoint: (x, y) => wasmts.geom.createPoint(x, y),
            toGeometry: (envelope) => {
                // Convert envelope to Polygon using WKT
                const wkt = `POLYGON ((${envelope.minX} ${envelope.minY}, ${envelope.maxX} ${envelope.minY}, ${envelope.maxX} ${envelope.maxY}, ${envelope.minX} ${envelope.maxY}, ${envelope.minX} ${envelope.minY}))`;
                return wasmts.io.readWKT(wkt);
            }
        };
    """)
    private static native JSObject createJSGeometryFactory();

    private static Object getGeometryFactoryJS() {
        return createJSGeometryFactory();
    }

    // Implementation methods
    private static Object createPointJS(Object x, Object y) {
        double xVal = ((JSValue) x).asDouble();
        double yVal = ((JSValue) y).asDouble();
        Coordinate coord = new Coordinate(xVal, yVal);
        Point point = factory.createPoint(coord);
        return createJSGeometry(JSString.of("Point"), point);
    }

    private static Object createPoint3DJS(Object x, Object y, Object z) {
        double xVal = ((JSValue) x).asDouble();
        double yVal = ((JSValue) y).asDouble();
        double zVal = ((JSValue) z).asDouble();
        Coordinate coord = new Coordinate(xVal, yVal, zVal);
        Point point = factory.createPoint(coord);
        return createJSGeometry(JSString.of("Point"), point);
    }

    private static Object createPoint4DJS(Object x, Object y, Object z, Object m) {
        double xVal = ((JSValue) x).asDouble();
        double yVal = ((JSValue) y).asDouble();
        double zVal = ((JSValue) z).asDouble();
        double mVal = ((JSValue) m).asDouble();
        // Use CoordinateXYZM for 4D coordinates
        Coordinate coord = new CoordinateXYZM(xVal, yVal, zVal, mVal);
        Point point = factory.createPoint(coord);
        return createJSGeometry(JSString.of("Point"), point);
    }

    // Extract coordinate parts from JS object {x, y, z?, m?}
    @JS("return { x: obj.x, y: obj.y, hasZ: obj.z !== undefined && obj.z !== null, hasM: obj.m !== undefined && obj.m !== null, z: obj.z || 0, m: obj.m || 0 };")
    private static native JSObject extractCoordParts(JSObject obj);

    private static Coordinate[] extractCoordinateArray(Object coordsArray) {
        int len = getJSArrayLength(coordsArray).asInt();
        Coordinate[] coords = new Coordinate[len];
        for (int i = 0; i < len; i++) {
            JSObject p = extractCoordParts((JSObject) getJSArrayElement(coordsArray, i));
            double x = ((JSValue) p.get("x")).asDouble();
            double y = ((JSValue) p.get("y")).asDouble();
            boolean hasZ = ((JSValue) p.get("hasZ")).asBoolean();
            boolean hasM = ((JSValue) p.get("hasM")).asBoolean();
            if (hasZ && hasM)
                coords[i] = new CoordinateXYZM(x, y, ((JSValue) p.get("z")).asDouble(), ((JSValue) p.get("m")).asDouble());
            else if (hasZ)
                coords[i] = new Coordinate(x, y, ((JSValue) p.get("z")).asDouble());
            else
                coords[i] = new Coordinate(x, y);
        }
        return coords;
    }

    private static Object createLineStringJS(Object coords) {
        return createJSGeometry(JSString.of("LineString"), factory.createLineString(extractCoordinateArray(coords)));
    }

    private static Object createPolygonJS(Object shell, Object holes) {
        LinearRing shellRing = factory.createLinearRing(extractCoordinateArray(shell));
        LinearRing[] holeRings = null;
        if (holes != null) {
            int n = getJSArrayLength(holes).asInt();
            holeRings = new LinearRing[n];
            for (int i = 0; i < n; i++)
                holeRings[i] = factory.createLinearRing(extractCoordinateArray(getJSArrayElement(holes, i)));
        }
        return createJSGeometry(JSString.of("Polygon"), factory.createPolygon(shellRing, holeRings));
    }

    private static Object bufferJS(Object geom, Object distance, Object endCapStyle, Object joinStyle, Object mitreLimit) {
        Geometry g = extractGeometry(geom);
        double dist = ((JSValue) distance).asDouble();

        // If optional parameters are provided (not null), use BufferOp with parameters
        if (endCapStyle != null) {
            int endCap = ((JSValue) endCapStyle).asInt();
            int join = ((JSValue) joinStyle).asInt();
            double mitre = ((JSValue) mitreLimit).asDouble();

            BufferParameters params = new BufferParameters();
            params.setEndCapStyle(endCap);
            params.setJoinStyle(join);
            params.setMitreLimit(mitre);

            Geometry buffered = BufferOp.bufferOp(g, dist, params);
            return createJSGeometry(JSString.of(buffered.getGeometryType()), buffered);
        } else {
            // Use standard buffer with default parameters
            Geometry buffered = g.buffer(dist);
            return createJSGeometry(JSString.of(buffered.getGeometryType()), buffered);
        }
    }

    private static Object unionJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);

        Geometry result = geom1.union(geom2);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object intersectionJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);

        Geometry result = geom1.intersection(geom2);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object getAreaJS(Object geom) {
        Geometry g = extractGeometry(geom);
        double area = g.getArea();
        return JSNumber.of(area);
    }

    private static Object containsJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);

        boolean result = geom1.contains(geom2);
        return JSBoolean.of(result);
    }

    private static Object readWKTJS(Object wkt) {
        try {
            String wktString = ((JSValue) wkt).asString();
            Geometry geom = wktReader.read(wktString);
            return createJSGeometry(JSString.of(geom.getGeometryType()), geom);
        } catch (ParseException e) {
            throw new RuntimeException("WKT parse error: " + e.getMessage(), e);
        }
    }

    // Array-based geometry creation methods removed
    // Use wasmts.io.readWKT() or wasmts.io.readWKB() to create complex geometries

    // Spatial predicates
    private static Object intersectsJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.intersects(geom2));
    }

    private static Object touchesJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.touches(geom2));
    }

    private static Object crossesJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.crosses(geom2));
    }

    private static Object withinJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.within(geom2));
    }

    private static Object overlapsJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.overlaps(geom2));
    }

    private static Object disjointJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.disjoint(geom2));
    }

    private static Object geomEqualsJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSBoolean.of(geom1.equals(geom2));
    }

    // More operations
    private static Object differenceJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        Geometry result = geom1.difference(geom2);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object symDifferenceJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        Geometry result = geom1.symDifference(geom2);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object convexHullJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry result = g.convexHull();
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object simplifyJS(Object geom, Object tolerance) {
        Geometry g = extractGeometry(geom);
        double tol = ((JSValue) tolerance).asDouble();
        Geometry result = DouglasPeuckerSimplifier.simplify(g, tol);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object boundaryJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry result = g.getBoundary();
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    private static Object centroidJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Point centroid = g.getCentroid();
        return createJSGeometry(JSString.of("Point"), centroid);
    }

    // Measurements
    private static Object getLengthJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSNumber.of(g.getLength());
    }

    private static Object distanceJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        return JSNumber.of(geom1.distance(geom2));
    }

    private static Object nearestPointsJS(Object g1, Object g2) {
        Geometry geom1 = extractGeometry(g1);
        Geometry geom2 = extractGeometry(g2);
        Coordinate[] coords = DistanceOp.nearestPoints(geom1, geom2);
        // Return array of {x, y, z?} coordinate objects (like getCoordinates)
        JSObject result = createJSArray();
        for (Coordinate c : coords) {
            JSNumber xNum = JSNumber.of(c.getX());
            JSNumber yNum = JSNumber.of(c.getY());
            boolean hasZ = !Double.isNaN(c.getZ());
            JSObject coordObj = hasZ ? createCoordObject3D(xNum, yNum, JSNumber.of(c.getZ()))
                                     : createCoordObject(xNum, yNum);
            pushToJSArray(result, coordObj);
        }
        return result;
    }

    private static Object getNumPointsJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSNumber.of(g.getNumPoints());
    }

    private static Object getGeometryTypeJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSString.of(g.getGeometryType());
    }

    private static Object isEmptyJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(g.isEmpty());
    }

    private static Object isValidJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(g.isValid());
    }

    // Coordinate/geometry access
    @JS("return [];")
    private static native JSObject createJSArray();

    @JS("arr.push(item);")
    private static native void pushToJSArray(JSObject arr, Object item);

    @JS("return {x: x, y: y};")
    private static native JSObject createCoordObject(JSNumber x, JSNumber y);

    @JS("return {x: x, y: y, z: z};")
    private static native JSObject createCoordObject3D(JSNumber x, JSNumber y, JSNumber z);

    @JS("return {x: x, y: y, z: z, m: m};")
    private static native JSObject createCoordObject4D(JSNumber x, JSNumber y, JSNumber z, JSNumber m);

    private static Object getCoordinatesJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Coordinate[] coords = g.getCoordinates();
        JSObject result = createJSArray();

        for (int i = 0; i < coords.length; i++) {
            Coordinate c = coords[i];
            JSNumber xNum = JSNumber.of(c.getX());
            JSNumber yNum = JSNumber.of(c.getY());

            // Check if coordinate has Z and/or M values
            boolean hasZ = !Double.isNaN(c.getZ());
            boolean hasM = !Double.isNaN(c.getM());

            JSObject coordObj;
            if (hasZ && hasM) {
                // 4D coordinate (XYZM)
                JSNumber zNum = JSNumber.of(c.getZ());
                JSNumber mNum = JSNumber.of(c.getM());
                coordObj = createCoordObject4D(xNum, yNum, zNum, mNum);
            } else if (hasZ) {
                // 3D coordinate (XYZ)
                JSNumber zNum = JSNumber.of(c.getZ());
                coordObj = createCoordObject3D(xNum, yNum, zNum);
            } else {
                // 2D coordinate (XY)
                coordObj = createCoordObject(xNum, yNum);
            }

            pushToJSArray(result, coordObj);
        }

        return result;
    }

    private static Object getNumGeometriesJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSNumber.of(g.getNumGeometries());
    }

    private static Object getGeometryNJS(Object geom, Object n) {
        Geometry g = extractGeometry(geom);
        int index = ((JSValue) n).asInt();
        Geometry subGeom = g.getGeometryN(index);
        return createJSGeometry(JSString.of(subGeom.getGeometryType()), subGeom);
    }

    // WKT output
    private static Object writeWKTJS(Object geom) {
        Geometry g = extractGeometry(geom);
        String wkt = wktWriter.write(g);
        return JSString.of(wkt);
    }

    // WKB I/O - Helper to create Uint8Array
    @JS("return new Uint8Array(length);")
    private static native JSObject createUint8Array(JSNumber length);

    @JS("arr[index] = value;")
    private static native void setByteInArray(JSObject arr, JSNumber index, JSNumber value);

    private static Object writeWKBJS(Object geom) {
        Geometry g = extractGeometry(geom);

        // Determine dimension by checking first coordinate
        Coordinate coord = g.getCoordinate();
        WKBWriter writer;
        if (coord == null) {
            writer = wkbWriter2D; // Empty geometry, use 2D
        } else {
            boolean hasZ = !Double.isNaN(coord.getZ());
            boolean hasM = !Double.isNaN(coord.getM());

            if (hasZ && hasM) {
                writer = wkbWriter4D; // XYZM
            } else if (hasZ) {
                writer = wkbWriter3D; // XYZ
            } else {
                writer = wkbWriter2D; // XY
            }
        }

        byte[] wkb = writer.write(g);

        // Create Uint8Array and fill it byte by byte
        JSObject arr = createUint8Array(JSNumber.of(wkb.length));
        for (int i = 0; i < wkb.length; i++) {
            setByteInArray(arr, JSNumber.of(i), JSNumber.of(wkb[i] & 0xFF));
        }
        return arr;
    }

    private static Object readWKBJS(Object wkb) {
        try {
            // Convert JavaScript Uint8Array to Java byte[]
            // NOTE: JSObject.asByteArray() doesn't work in web-image (returns proxy, not usable array)
            // So we manually iterate through the TypedArray
            JSObject jsArray = (JSObject) wkb;
            int length = ((JSValue) jsArray.get("length")).asInt();
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                int val = ((JSValue) jsArray.get(i)).asInt();
                bytes[i] = (byte) val;
            }

            Geometry geom = wkbReader.read(bytes);
            return createJSGeometry(JSString.of(geom.getGeometryType()), geom);
        } catch (ParseException e) {
            throw new RuntimeException("WKB parse error: " + e.getMessage(), e);
        }
    }

    // GeoJSON I/O - uses JS JSON.parse/stringify to avoid Java JSON library dependencies
    // Converts GeoJSON <-> WKT internally

    @JS("return JSON.parse(str);")
    private static native JSObject parseJSON(JSString str);

    @JS("return JSON.stringify(obj);")
    private static native JSString stringifyJSON(JSObject obj);

    @JS("return obj.type;")
    private static native JSString getJSONType(JSObject obj);

    @JS("return obj.coordinates;")
    private static native Object getJSONCoordinates(JSObject obj);

    @JS("return obj.geometries;")
    private static native Object getJSONGeometries(JSObject obj);

    @JS("return arr.length;")
    private static native JSValue getGeoJSONArrayLength(Object arr);

    @JS("return arr[i];")
    private static native Object getGeoJSONArrayElement(Object arr, int i);

    @JS("return typeof val === 'number';")
    private static native JSValue isJSNumberValue(Object val);

    @JS("return Number(val);")
    private static native JSValue asJSDoubleValue(Object val);

    // Wrapper helpers
    private static int getArrayLength(Object arr) {
        return getGeoJSONArrayLength(arr).asInt();
    }

    private static Object getArrayElement(Object arr, int i) {
        return getGeoJSONArrayElement(arr, i);
    }

    private static boolean isJSNumber(Object val) {
        return isJSNumberValue(val).asBoolean();
    }

    private static double asJSDouble(Object val) {
        return asJSDoubleValue(val).asDouble();
    }

    @JS("return { type: type, coordinates: coords };")
    private static native JSObject createGeoJSONGeom(JSString type, Object coords);

    @JS("return { type: 'GeometryCollection', geometries: geoms };")
    private static native JSObject createGeoJSONCollection(Object geoms);

    // Convert GeoJSON coordinates array to WKT coordinate string
    private static String coordsToWkt(Object coords) {
        int len = getArrayLength(coords);
        if (len == 0) return "";

        Object first = getArrayElement(coords, 0);
        // Check if first element is a number (single coord) or array (nested)
        if (isJSNumber(first)) {
            // Single coordinate: [x, y] or [x, y, z]
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(" ");
                sb.append(asJSDouble(getArrayElement(coords, i)));
            }
            return sb.toString();
        } else {
            // Array of coordinates or nested arrays
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(", ");
                sb.append(coordsToWkt(getArrayElement(coords, i)));
            }
            return sb.toString();
        }
    }

    // Convert GeoJSON ring to WKT ring string (with parentheses)
    private static String ringToWkt(Object ring) {
        return "(" + coordsToWkt(ring) + ")";
    }

    // Convert GeoJSON polygon rings to WKT
    private static String polygonRingsToWkt(Object rings) {
        int len = getArrayLength(rings);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ringToWkt(getArrayElement(rings, i)));
        }
        return sb.toString();
    }

    // Convert parsed GeoJSON object to WKT string
    private static String geoJsonToWkt(JSObject geom) {
        String type = getJSONType(geom).asString();
        Object coords = getJSONCoordinates(geom);

        switch (type) {
            case "Point":
                return "POINT (" + coordsToWkt(coords) + ")";

            case "MultiPoint": {
                int len = getArrayLength(coords);
                StringBuilder sb = new StringBuilder("MULTIPOINT (");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("(").append(coordsToWkt(getArrayElement(coords, i))).append(")");
                }
                sb.append(")");
                return sb.toString();
            }

            case "LineString":
                return "LINESTRING (" + coordsToWkt(coords) + ")";

            case "MultiLineString": {
                int len = getArrayLength(coords);
                StringBuilder sb = new StringBuilder("MULTILINESTRING (");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ringToWkt(getArrayElement(coords, i)));
                }
                sb.append(")");
                return sb.toString();
            }

            case "Polygon":
                return "POLYGON (" + polygonRingsToWkt(coords) + ")";

            case "MultiPolygon": {
                int len = getArrayLength(coords);
                StringBuilder sb = new StringBuilder("MULTIPOLYGON (");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("(").append(polygonRingsToWkt(getArrayElement(coords, i))).append(")");
                }
                sb.append(")");
                return sb.toString();
            }

            case "GeometryCollection": {
                Object geometries = getJSONGeometries(geom);
                int len = getArrayLength(geometries);
                StringBuilder sb = new StringBuilder("GEOMETRYCOLLECTION (");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(geoJsonToWkt((JSObject) getArrayElement(geometries, i)));
                }
                sb.append(")");
                return sb.toString();
            }

            default:
                throw new RuntimeException("Unknown GeoJSON geometry type: " + type);
        }
    }

    // Convert JTS Coordinate array to JS array for GeoJSON
    @JS("return [];")
    private static native JSObject createEmptyArray();

    @JS("arr.push(val);")
    private static native void arrayPush(JSObject arr, Object val);

    private static JSObject coordToJSArray(Coordinate c) {
        JSObject arr = createEmptyArray();
        arrayPush(arr, JSNumber.of(c.getX()));
        arrayPush(arr, JSNumber.of(c.getY()));
        if (!Double.isNaN(c.getZ())) {
            arrayPush(arr, JSNumber.of(c.getZ()));
        }
        return arr;
    }

    private static JSObject coordsToJSArray(Coordinate[] coords) {
        JSObject arr = createEmptyArray();
        for (Coordinate c : coords) {
            arrayPush(arr, coordToJSArray(c));
        }
        return arr;
    }

    // Convert JTS Geometry to GeoJSON JS object
    private static JSObject geometryToGeoJson(Geometry geom) {
        String type = geom.getGeometryType();

        switch (type) {
            case "Point": {
                Point p = (Point) geom;
                JSObject coords = coordToJSArray(p.getCoordinate());
                return createGeoJSONGeom(JSString.of("Point"), coords);
            }

            case "MultiPoint": {
                MultiPoint mp = (MultiPoint) geom;
                JSObject coords = createEmptyArray();
                for (int i = 0; i < mp.getNumGeometries(); i++) {
                    Point p = (Point) mp.getGeometryN(i);
                    arrayPush(coords, coordToJSArray(p.getCoordinate()));
                }
                return createGeoJSONGeom(JSString.of("MultiPoint"), coords);
            }

            case "LineString": {
                LineString ls = (LineString) geom;
                JSObject coords = coordsToJSArray(ls.getCoordinates());
                return createGeoJSONGeom(JSString.of("LineString"), coords);
            }

            case "LinearRing": {
                LinearRing lr = (LinearRing) geom;
                JSObject coords = coordsToJSArray(lr.getCoordinates());
                return createGeoJSONGeom(JSString.of("LineString"), coords); // GeoJSON has no LinearRing
            }

            case "MultiLineString": {
                MultiLineString mls = (MultiLineString) geom;
                JSObject coords = createEmptyArray();
                for (int i = 0; i < mls.getNumGeometries(); i++) {
                    LineString ls = (LineString) mls.getGeometryN(i);
                    arrayPush(coords, coordsToJSArray(ls.getCoordinates()));
                }
                return createGeoJSONGeom(JSString.of("MultiLineString"), coords);
            }

            case "Polygon": {
                Polygon poly = (Polygon) geom;
                JSObject rings = createEmptyArray();
                // Exterior ring
                arrayPush(rings, coordsToJSArray(poly.getExteriorRing().getCoordinates()));
                // Interior rings (holes)
                for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                    arrayPush(rings, coordsToJSArray(poly.getInteriorRingN(i).getCoordinates()));
                }
                return createGeoJSONGeom(JSString.of("Polygon"), rings);
            }

            case "MultiPolygon": {
                MultiPolygon mpoly = (MultiPolygon) geom;
                JSObject polys = createEmptyArray();
                for (int i = 0; i < mpoly.getNumGeometries(); i++) {
                    Polygon poly = (Polygon) mpoly.getGeometryN(i);
                    JSObject rings = createEmptyArray();
                    arrayPush(rings, coordsToJSArray(poly.getExteriorRing().getCoordinates()));
                    for (int j = 0; j < poly.getNumInteriorRing(); j++) {
                        arrayPush(rings, coordsToJSArray(poly.getInteriorRingN(j).getCoordinates()));
                    }
                    arrayPush(polys, rings);
                }
                return createGeoJSONGeom(JSString.of("MultiPolygon"), polys);
            }

            case "GeometryCollection": {
                GeometryCollection gc = (GeometryCollection) geom;
                JSObject geoms = createEmptyArray();
                for (int i = 0; i < gc.getNumGeometries(); i++) {
                    arrayPush(geoms, geometryToGeoJson(gc.getGeometryN(i)));
                }
                return createGeoJSONCollection(geoms);
            }

            default:
                throw new RuntimeException("Unknown geometry type for GeoJSON: " + type);
        }
    }

    private static Object readGeoJSONJS(Object geojsonStr) {
        try {
            String jsonStr = ((JSValue) geojsonStr).asString();
            JSObject geojson = parseJSON(JSString.of(jsonStr));
            String wkt = geoJsonToWkt(geojson);
            Geometry geom = wktReader.read(wkt);
            return createJSGeometry(JSString.of(geom.getGeometryType()), geom);
        } catch (ParseException e) {
            throw new RuntimeException("GeoJSON parse error: " + e.getMessage(), e);
        }
    }

    private static Object writeGeoJSONJS(Object geom) {
        Geometry g = extractGeometry(geom);
        JSObject geojson = geometryToGeoJson(g);
        return stringifyJSON(geojson);
    }

    // Envelope methods
    private static Object createEnvelopeJS(Object minX, Object maxX, Object minY, Object maxY) {
        double minXVal = ((JSValue) minX).asDouble();
        double maxXVal = ((JSValue) maxX).asDouble();
        double minYVal = ((JSValue) minY).asDouble();
        double maxYVal = ((JSValue) maxY).asDouble();
        Envelope envelope = new Envelope(minXVal, maxXVal, minYVal, maxYVal);
        return createJSEnvelope(envelope);
    }

    private static Object envelopeIntersectsJS(Object env1, Object env2) {
        Envelope envelope1 = extractEnvelope(env1);
        Envelope envelope2 = extractEnvelope(env2);
        return JSBoolean.of(envelope1.intersects(envelope2));
    }

    private static Object envelopeContainsJS(Object env1, Object env2) {
        Envelope envelope1 = extractEnvelope(env1);
        Envelope envelope2 = extractEnvelope(env2);
        return JSBoolean.of(envelope1.contains(envelope2));
    }

    private static void envelopeExpandToIncludeJS(Object env, Object x, Object y) {
        Envelope envelope = extractEnvelope(env);
        double xVal = ((JSValue) x).asDouble();
        double yVal = ((JSValue) y).asDouble();
        envelope.expandToInclude(xVal, yVal);
    }

    private static Object getEnvelopeInternalJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Envelope envelope = g.getEnvelopeInternal();
        return createJSEnvelope(envelope);
    }

    // STRtree methods
    private static Object createSTRtreeJS() {
        STRtree tree = new STRtree();
        return createJSSTRtree(tree);
    }

    private static void strtreeInsertJS(Object tree, Object envelope, Object item) {
        STRtree strtree = extractSTRtree(tree);
        Envelope env = extractEnvelope(envelope);
        strtree.insert(env, item);
    }

    private static Object strtreeQueryJS(Object tree, Object envelope) {
        STRtree strtree = extractSTRtree(tree);
        Envelope env = extractEnvelope(envelope);
        List<?> results = strtree.query(env);

        // Convert List to JavaScript array
        JSObject jsArray = createJSArray();
        for (Object item : results) {
            pushToJSArray(jsArray, item);
        }
        return jsArray;
    }

    private static Object strtreeRemoveJS(Object tree, Object envelope, Object item) {
        STRtree strtree = extractSTRtree(tree);
        Envelope env = extractEnvelope(envelope);
        boolean removed = strtree.remove(env, item);
        return JSBoolean.of(removed);
    }

    private static Object strtreeSizeJS(Object tree) {
        STRtree strtree = extractSTRtree(tree);
        return JSNumber.of(strtree.size());
    }

    // PreparedGeometry methods
    private static Object prepareGeometryJS(Object geom) {
        Geometry g = extractGeometry(geom);
        PreparedGeometry prepGeom = PreparedGeometryFactory.prepare(g);
        return createJSPreparedGeometry(prepGeom);
    }

    private static Object preparedContainsProperlyJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        boolean result = pg.containsProperly(g);
        return JSBoolean.of(result);
    }

    private static Object preparedCoveredByJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        boolean result = pg.coveredBy(g);
        return JSBoolean.of(result);
    }

    private static Object preparedGetGeometryJS(Object prepGeom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = pg.getGeometry();
        return createJSGeometry(JSString.of(g.getGeometryType()), g);
    }

    private static Object preparedContainsJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.contains(g));
    }

    private static Object preparedCoversJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.covers(g));
    }

    private static Object preparedCrossesJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.crosses(g));
    }

    private static Object preparedDisjointJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.disjoint(g));
    }

    private static Object preparedIntersectsJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.intersects(g));
    }

    private static Object preparedOverlapsJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.overlaps(g));
    }

    private static Object preparedTouchesJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.touches(g));
    }

    private static Object preparedWithinJS(Object prepGeom, Object geom) {
        PreparedGeometry pg = extractPreparedGeometry(prepGeom);
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(pg.within(g));
    }

    // Geometry analysis algorithm methods
    private static Object minimumDiameterGetRectJS(Object geom) {
        Geometry g = extractGeometry(geom);
        MinimumDiameter md = new MinimumDiameter(g);
        Geometry rect = md.getMinimumRectangle();
        return createJSGeometry(JSString.of(rect.getGeometryType()), rect);
    }

    private static Object minimumDiameterGetLengthJS(Object geom) {
        Geometry g = extractGeometry(geom);
        MinimumDiameter md = new MinimumDiameter(g);
        return JSNumber.of(md.getLength());
    }

    private static Object minimumAreaRectangleJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry rect = MinimumAreaRectangle.getMinimumRectangle(g);
        return createJSGeometry(JSString.of(rect.getGeometryType()), rect);
    }

    private static Object minimumBoundingCircleGetCircleJS(Object geom) {
        Geometry g = extractGeometry(geom);
        MinimumBoundingCircle mbc = new MinimumBoundingCircle(g);
        Geometry circle = mbc.getCircle();
        return createJSGeometry(JSString.of(circle.getGeometryType()), circle);
    }

    private static Object minimumBoundingCircleGetCentreJS(Object geom) {
        Geometry g = extractGeometry(geom);
        MinimumBoundingCircle mbc = new MinimumBoundingCircle(g);
        Coordinate centre = mbc.getCentre();
        JSNumber x = JSNumber.of(centre.getX());
        JSNumber y = JSNumber.of(centre.getY());
        return createCoordObject(x, y);
    }

    private static Object minimumBoundingCircleGetRadiusJS(Object geom) {
        Geometry g = extractGeometry(geom);
        MinimumBoundingCircle mbc = new MinimumBoundingCircle(g);
        return JSNumber.of(mbc.getRadius());
    }

    // Offset curve
    private static Object offsetCurveJS(Object geom, Object distance, Object endCapStyle, Object joinStyle, Object mitreLimit) {
        Geometry g = extractGeometry(geom);
        double dist = ((JSValue) distance).asDouble();

        // Create BufferParameters for offset curve
        BufferParameters params = new BufferParameters();
        if (endCapStyle != null) {
            int endCap = ((JSValue) endCapStyle).asInt();
            int join = ((JSValue) joinStyle).asInt();
            double mitre = ((JSValue) mitreLimit).asDouble();

            params.setEndCapStyle(endCap);
            params.setJoinStyle(join);
            params.setMitreLimit(mitre);
        }

        // OffsetCurveBuilder needs PrecisionModel from GeometryFactory
        OffsetCurveBuilder builder = new OffsetCurveBuilder(
            g.getFactory().getPrecisionModel(),
            params
        );

        // Get offset curve coordinates and create LineString
        Coordinate[] coords = builder.getOffsetCurve(g.getCoordinates(), dist);
        Geometry offsetLine = factory.createLineString(coords);

        return createJSGeometry(JSString.of(offsetLine.getGeometryType()), offsetLine);
    }

    // LineMerger methods
    private static Object createLineMergerJS() {
        LineMerger merger = new LineMerger();
        // Wrap the LineMerger in a JS object that we can pass around
        return merger;
    }

    private static void lineMergerAddJS(Object mergerObj, Object geom) {
        LineMerger merger = (LineMerger) mergerObj;
        Geometry g = extractGeometry(geom);
        merger.add(g);
    }

    @SuppressWarnings("unchecked")
    private static Object lineMergerGetResultJS(Object mergerObj) {
        LineMerger merger = (LineMerger) mergerObj;
        Collection<Geometry> mergedLines = (Collection<Geometry>) merger.getMergedLineStrings();

        // Convert Collection to JavaScript array of geometry objects
        return convertGeometryCollectionToJS(mergedLines);
    }

    // Helper to convert a Collection<Geometry> to a JavaScript array
    private static Object convertGeometryCollectionToJS(Collection<Geometry> geometries) {
        JSObject jsArray = createJSArray();
        for (Geometry geom : geometries) {
            Object jsGeom = createJSGeometry(JSString.of(geom.getGeometryType()), geom);
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

    // CascadedPolygonUnion method
    private static Object cascadedPolygonUnionJS(Object geometriesArray) {
        // Convert JavaScript array to Collection of Geometries
        int length = getJSArrayLength(geometriesArray).asInt();

        Collection<Geometry> geometries = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            Object item = getJSArrayElement(geometriesArray, i);
            Geometry geom = extractGeometry(item);
            geometries.add(geom);
        }

        // Perform cascaded union
        Geometry result = CascadedPolygonUnion.union(geometries);
        return createJSGeometry(JSString.of(result.getGeometryType()), result);
    }

    // New Geometry methods implementations

    private static Object equalsTopoJS(Object geom1, Object geom2) {
        Geometry g1 = extractGeometry(geom1);
        Geometry g2 = extractGeometry(geom2);
        return JSBoolean.of(g1.equalsTopo(g2));
    }

    private static Object coversJS(Object geom1, Object geom2) {
        Geometry g1 = extractGeometry(geom1);
        Geometry g2 = extractGeometry(geom2);
        return JSBoolean.of(g1.covers(g2));
    }

    private static Object coveredByJS(Object geom1, Object geom2) {
        Geometry g1 = extractGeometry(geom1);
        Geometry g2 = extractGeometry(geom2);
        return JSBoolean.of(g1.coveredBy(g2));
    }

    private static Object getEnvelopeJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry envelope = g.getEnvelope();
        return createJSGeometry(JSString.of(envelope.getGeometryType()), envelope);
    }

    private static Object getInteriorPointJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Point interiorPoint = g.getInteriorPoint();
        return createJSGeometry(JSString.of("Point"), interiorPoint);
    }

    private static Object copyJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry copy = g.copy();
        return createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    private static Object reverseJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry reversed = g.reverse();
        return createJSGeometry(JSString.of(reversed.getGeometryType()), reversed);
    }

    private static Object normalizeJS(Object geom) {
        Geometry g = extractGeometry(geom);
        Geometry normalized = g.copy();  // normalize() modifies in place, so copy first
        normalized.normalize();
        return createJSGeometry(JSString.of(normalized.getGeometryType()), normalized);
    }

    private static Object isSimpleJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(g.isSimple());
    }

    private static Object isRectangleJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return JSBoolean.of(g.isRectangle());
    }

    private static Object getUserDataJS(Object geom) {
        Geometry g = extractGeometry(geom);
        return g.getUserData();
    }

    private static void setUserDataJS(Object geom, Object data) {
        Geometry g = extractGeometry(geom);
        g.setUserData(data);
    }

    // Polygon accessor implementations
    private static Object getExteriorRingJS(Object geom) {
        Geometry g = extractGeometry(geom);
        if (!(g instanceof Polygon)) {
            throw new RuntimeException("getExteriorRing() requires a Polygon geometry, got: " + g.getGeometryType());
        }
        Polygon poly = (Polygon) g;
        LineString ring = poly.getExteriorRing();
        return createJSGeometry(JSString.of(ring.getGeometryType()), ring);
    }

    private static Object getInteriorRingNJS(Object geom, Object n) {
        Geometry g = extractGeometry(geom);
        if (!(g instanceof Polygon)) {
            throw new RuntimeException("getInteriorRingN() requires a Polygon geometry, got: " + g.getGeometryType());
        }
        Polygon poly = (Polygon) g;
        int index = ((JSValue) n).asInt();
        LineString ring = poly.getInteriorRingN(index);
        return createJSGeometry(JSString.of(ring.getGeometryType()), ring);
    }

    private static Object getNumInteriorRingJS(Object geom) {
        Geometry g = extractGeometry(geom);
        if (!(g instanceof Polygon)) {
            throw new RuntimeException("getNumInteriorRing() requires a Polygon geometry, got: " + g.getGeometryType());
        }
        Polygon poly = (Polygon) g;
        return JSNumber.of(poly.getNumInteriorRing());
    }

    // Helper to create JS CoordinateSequence wrapper - matches JTS CoordinateSequence API
    @JS("""
        const s = { _jtsCoordSeq: seq };
        s.getX = (i) => getXFn(s, i);
        s.getY = (i) => getYFn(s, i);
        s.getZ = (i) => getZFn(s, i);
        s.getM = (i) => getMFn(s, i);
        s.getOrdinate = (i, ord) => getOrdFn(s, i, ord);
        s.setOrdinate = (i, ord, val) => setOrdFn(s, i, ord, val);
        s.getDimension = () => getDimFn(s);
        s.getMeasures = () => getMeasuresFn(s);
        s.hasZ = () => hasZFn(s);
        s.hasM = () => hasMFn(s);
        s.size = () => sizeFn(s);
        s.getCoordinate = (i) => getCoordFn(s, i);
        s.toCoordinateArray = () => toArrayFn(s);
        s.copy = () => copyFn(s);
        return s;
    """)
    private static native JSObject createJSCoordinateSequenceWithFns(
        CoordinateSequence seq,
        CoordSeqGetXFn getXFn,
        CoordSeqGetYFn getYFn,
        CoordSeqGetZFn getZFn,
        CoordSeqGetMFn getMFn,
        CoordSeqGetOrdinateFn getOrdFn,
        CoordSeqSetOrdinateFn setOrdFn,
        CoordSeqGetDimensionFn getDimFn,
        CoordSeqGetMeasuresFn getMeasuresFn,
        CoordSeqHasZFn hasZFn,
        CoordSeqHasMFn hasMFn,
        CoordSeqSizeFn sizeFn,
        CoordSeqGetCoordinateFn getCoordFn,
        CoordSeqToCoordinateArrayFn toArrayFn,
        CoordSeqCopyFn copyFn
    );

    // Helper to extract CoordinateSequence from JS wrapper
    private static CoordinateSequence extractCoordinateSequence(Object obj) {
        if (obj instanceof CoordinateSequence) {
            return (CoordinateSequence) obj;
        }
        JSObject jsObj = (JSObject) obj;
        return (CoordinateSequence) jsObj.get("_jtsCoordSeq");
    }

    // Create JS wrapper for CoordinateSequence
    private static JSObject createJSCoordinateSequence(CoordinateSequence seq) {
        return createJSCoordinateSequenceWithFns(
            seq,
            API::coordSeqGetXJS,
            API::coordSeqGetYJS,
            API::coordSeqGetZJS,
            API::coordSeqGetMJS,
            API::coordSeqGetOrdinateJS,
            API::coordSeqSetOrdinateJS,
            API::coordSeqGetDimensionJS,
            API::coordSeqGetMeasuresJS,
            API::coordSeqHasZJS,
            API::coordSeqHasMJS,
            API::coordSeqSizeJS,
            API::coordSeqGetCoordinateJS,
            API::coordSeqToCoordinateArrayJS,
            API::coordSeqCopyJS
        );
    }

    // CoordinateSequence implementation methods
    private static Object coordSeqGetXJS(Object seq, Object i) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        return JSNumber.of(cs.getX(index));
    }

    private static Object coordSeqGetYJS(Object seq, Object i) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        return JSNumber.of(cs.getY(index));
    }

    private static Object coordSeqGetZJS(Object seq, Object i) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        return JSNumber.of(cs.getZ(index));
    }

    private static Object coordSeqGetMJS(Object seq, Object i) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        return JSNumber.of(cs.getM(index));
    }

    private static Object coordSeqGetOrdinateJS(Object seq, Object i, Object ordinateIndex) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        int ord = ((JSValue) ordinateIndex).asInt();
        return JSNumber.of(cs.getOrdinate(index, ord));
    }

    private static void coordSeqSetOrdinateJS(Object seq, Object i, Object ordinateIndex, Object value) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        int ord = ((JSValue) ordinateIndex).asInt();
        double val = ((JSValue) value).asDouble();
        cs.setOrdinate(index, ord, val);
    }

    private static Object coordSeqGetDimensionJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        return JSNumber.of(cs.getDimension());
    }

    private static Object coordSeqGetMeasuresJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        return JSNumber.of(cs.getMeasures());
    }

    private static Object coordSeqHasZJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        return JSBoolean.of(cs.hasZ());
    }

    private static Object coordSeqHasMJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        return JSBoolean.of(cs.hasM());
    }

    private static Object coordSeqSizeJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        return JSNumber.of(cs.size());
    }

    private static Object coordSeqGetCoordinateJS(Object seq, Object i) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        int index = ((JSValue) i).asInt();
        Coordinate c = cs.getCoordinate(index);
        JSNumber xNum = JSNumber.of(c.getX());
        JSNumber yNum = JSNumber.of(c.getY());
        boolean hasZ = !Double.isNaN(c.getZ());
        boolean hasM = !Double.isNaN(c.getM());
        if (hasZ && hasM) {
            return createCoordObject4D(xNum, yNum, JSNumber.of(c.getZ()), JSNumber.of(c.getM()));
        } else if (hasZ) {
            return createCoordObject3D(xNum, yNum, JSNumber.of(c.getZ()));
        } else {
            return createCoordObject(xNum, yNum);
        }
    }

    private static Object coordSeqToCoordinateArrayJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        Coordinate[] coords = cs.toCoordinateArray();
        JSObject result = createJSArray();
        for (Coordinate c : coords) {
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
        return result;
    }

    private static Object coordSeqCopyJS(Object seq) {
        CoordinateSequence cs = extractCoordinateSequence(seq);
        CoordinateSequence copy = cs.copy();
        return createJSCoordinateSequence(copy);
    }

    // Helper to invoke JS filter callback matching JTS CoordinateSequenceFilter.filter(seq, i)
    @JS.Coerce
    @JS("fun(seq, i);")
    private static native void invokeFilterFn(JSValue fun, JSObject seq, int i);

    private static class JSCallbackCoordinateFilter implements CoordinateSequenceFilter {
        private final JSValue filterFn;
        private JSObject currentJSSeq;
        private CoordinateSequence currentSeq;

        JSCallbackCoordinateFilter(JSValue filterFn) {
            this.filterFn = filterFn;
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            // Create or reuse JS wrapper for this CoordinateSequence
            // Note: the same seq instance is passed for all coordinates in a ring
            if (currentSeq != seq) {
                currentSeq = seq;
                currentJSSeq = createJSCoordinateSequence(seq);
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

    // Apply CoordinateSequenceFilter with JS callback - matches JTS Geometry.apply()
    private static Object applyJS(Object geom, Object filterFn) {
        Geometry g = extractGeometry(geom);
        Geometry copy = g.copy();
        JSValue jsFn = (JSValue) filterFn;

        JSCallbackCoordinateFilter filter = new JSCallbackCoordinateFilter(jsFn);
        copy.apply(filter);
        copy.geometryChanged();

        return createJSGeometry(JSString.of(copy.getGeometryType()), copy);
    }

    public static void main(String[] args) {
        System.out.println("WasmTS - JTS " + JTSVersion.CURRENT_VERSION + " for WebAssembly");

        // Initialize namespace structure
        initializeNamespaces();

        // Export GeometryFactory
        exportGeometryFactory(API::getGeometryFactoryJS);

        // Export geometry creation functions
        exportCreatePoint(API::createPointJS, API::createPoint3DJS, API::createPoint4DJS);
        exportCreateLineString(API::createLineStringJS);
        exportCreatePolygon(API::createPolygonJS);
        exportCreateEnvelope(API::createEnvelopeJS);

        // Export I/O
        exportReadWKT(API::readWKTJS);

        // Export operations
        exportBuffer(API::bufferJS);
        exportUnion(API::unionJS);
        exportIntersection(API::intersectionJS);
        exportDifference(API::differenceJS);
        exportSymDifference(API::symDifferenceJS);
        exportConvexHull(API::convexHullJS);
        exportSimplify(API::simplifyJS);
        exportBoundary(API::boundaryJS);
        exportCentroid(API::centroidJS);

        // Export predicates
        exportContains(API::containsJS);
        exportIntersects(API::intersectsJS);
        exportTouches(API::touchesJS);
        exportCrosses(API::crossesJS);
        exportWithin(API::withinJS);
        exportOverlaps(API::overlapsJS);
        exportDisjoint(API::disjointJS);
        exportGeomEquals(API::geomEqualsJS);
        exportEqualsTopo(API::equalsTopoJS);
        exportCovers(API::coversJS);
        exportCoveredBy(API::coveredByJS);

        // Export geometry operations
        exportGetEnvelope(API::getEnvelopeJS);
        exportGetInteriorPoint(API::getInteriorPointJS);
        exportCopy(API::copyJS);
        exportReverse(API::reverseJS);
        exportNormalize(API::normalizeJS);

        // Export measurements
        exportGetArea(API::getAreaJS);
        exportGetLength(API::getLengthJS);
        exportDistance(API::distanceJS);
        exportNearestPoints(API::nearestPointsJS);
        exportGetNumPoints(API::getNumPointsJS);
        exportGetGeometryType(API::getGeometryTypeJS);
        exportIsEmpty(API::isEmptyJS);
        exportIsValid(API::isValidJS);
        exportIsSimple(API::isSimpleJS);
        exportIsRectangle(API::isRectangleJS);
        exportGetUserData(API::getUserDataJS);
        exportSetUserData(API::setUserDataJS);

        // Export coordinate/geometry access
        exportGetCoordinates(API::getCoordinatesJS);
        exportGetNumGeometries(API::getNumGeometriesJS);
        exportGetGeometryN(API::getGeometryNJS);

        // Export polygon accessors
        exportGetExteriorRing(API::getExteriorRingJS);
        exportGetInteriorRingN(API::getInteriorRingNJS);
        exportGetNumInteriorRing(API::getNumInteriorRingJS);

        // Export CoordinateSequenceFilter - matches JTS Geometry.apply()
        exportApply(API::applyJS);

        // Export WKT I/O
        exportReadWKT(API::readWKTJS);
        exportWriteWKT(API::writeWKTJS);
        exportReadWKB(API::readWKBJS);
        exportWriteWKB(API::writeWKBJS);
        exportReadGeoJSON(API::readGeoJSONJS);
        exportWriteGeoJSON(API::writeGeoJSONJS);

        // Export Envelope
        exportCreateEnvelope(API::createEnvelopeJS);
        exportEnvelopeIntersects(API::envelopeIntersectsJS);
        exportEnvelopeContains(API::envelopeContainsJS);
        exportEnvelopeExpandToInclude(API::envelopeExpandToIncludeJS);
        exportGetEnvelopeInternal(API::getEnvelopeInternalJS);

        // Export STRtree
        exportCreateSTRtree(API::createSTRtreeJS);
        exportSTRtreeInsert(API::strtreeInsertJS);
        exportSTRtreeQuery(API::strtreeQueryJS);
        exportSTRtreeRemove(API::strtreeRemoveJS);
        exportSTRtreeSize(API::strtreeSizeJS);

        // Export PreparedGeometry
        exportPrepareGeometry(API::prepareGeometryJS);
        exportPreparedContainsProperly(API::preparedContainsProperlyJS);
        exportPreparedCoveredBy(API::preparedCoveredByJS);
        exportPreparedGetGeometry(API::preparedGetGeometryJS);
        exportPreparedContains(API::preparedContainsJS);
        exportPreparedCovers(API::preparedCoversJS);
        exportPreparedCrosses(API::preparedCrossesJS);
        exportPreparedDisjoint(API::preparedDisjointJS);
        exportPreparedIntersects(API::preparedIntersectsJS);
        exportPreparedOverlaps(API::preparedOverlapsJS);
        exportPreparedTouches(API::preparedTouchesJS);
        exportPreparedWithin(API::preparedWithinJS);

        // Export geometry analysis algorithms
        exportMinimumDiameterGetRect(API::minimumDiameterGetRectJS);
        exportMinimumDiameterGetLength(API::minimumDiameterGetLengthJS);
        exportMinimumAreaRectangle(API::minimumAreaRectangleJS);
        exportMinimumBoundingCircleGetCircle(API::minimumBoundingCircleGetCircleJS);
        exportMinimumBoundingCircleGetCentre(API::minimumBoundingCircleGetCentreJS);
        exportMinimumBoundingCircleGetRadius(API::minimumBoundingCircleGetRadiusJS);

        // Export offset curve
        exportOffsetCurve(API::offsetCurveJS);

        // Export LineMerger
        exportCreateLineMerger(API::createLineMergerJS);
        exportLineMergerAdd(API::lineMergerAddJS);
        exportLineMergerGetResult(API::lineMergerGetResultJS);

        // Export CascadedPolygonUnion
        exportCascadedPolygonUnion(API::cascadedPolygonUnionJS);

        System.out.println("\nWasmTS API exported to JavaScript!");
    }
}
