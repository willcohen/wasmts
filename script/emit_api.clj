(ns emit-api
  "Emit java/src/main/java/net/willcohen/wasmts/API_Generated.java from
   registry.edn.

   Output structure:

     - License header.
     - Imports (JTS + Web Image annotations).
     - One FnN functional interface per arity used.
     - Per-entry `@JS.Coerce @JS(\"<js-path> = (...) => fn.invoke(...);\")`
       native declaration with a unique stable name.
     - A static `register()` method that wires each native to a lambda
       computing the JTS dispatch.

   API.main() calls API_Generated.register() as its last statement, so
   codegen registrations install last and overwrite any hand-written @JS
   export at a shared path. The emitted register() header documents this.

   Per-shape emission lives behind a multimethod keyed on :shape. Add a
   defmethod when classify-shape grows a new category."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [codegen-common :refer [js-path in-scope? simple-name geometry-subtypes]]))


(def ^:private js-wrapper-classes
  "JTS classes whose `createJS<X>Raw` + `createJS<X>` + `extract<X>` helpers
   are emitted into API_Generated.java. Each entry:

     :class     fully-qualified Java class name
     :js-field  key on the JS-side wrapper object
     :helper    suffix used in helper names (createJS<helper> / extract<helper>
                / wire<helper>Methods). Often the simple class name; the
                abbreviated exceptions are MBC for MinimumBoundingCircle
                and MDiam for MinimumDiameter.
     :wire?     true when createJS<X> calls wire<X>Methods after the raw
                wrap (readers / writers / PrecisionModel). False for value
                / builder / utility wrappers with no method-shim surface.
     :attach?   true when createJS<X> additionally calls
                API.attach<X>Overrides (hand-written in API.java).
     :extra-params  ctor params beyond the underlying JTS class — used by
                    Geometry to add a `type: JSString` slot.

   Geometry, CoordinateSequence, Envelope, IntersectionMatrix appear here
   with `:attach? true`; their createJS<X> bodies mix raw + wire +
   hand-written attach<X>Overrides (relate-with-pattern,
   equalsExact-with-tolerance, polymorphic set / expandBy, etc.).

   Excluded entirely: Coordinate — irreducible JS-literal {x,y,z?,m?} vs
   Java-handle polymorphism. Stays hand-written."
  [
   {:class "org.locationtech.jts.algorithm.Centroid" :js-field "_jtsCentroid" :helper "Centroid"}
   {:class "org.locationtech.jts.algorithm.construct.LargestEmptyCircle" :js-field "_jtsLargestEmptyCircle" :helper "LargestEmptyCircle"}
   {:class "org.locationtech.jts.algorithm.construct.MaximumInscribedCircle" :js-field "_jtsMaximumInscribedCircle" :helper "MaximumInscribedCircle"}
   {:class "org.locationtech.jts.algorithm.ConvexHull" :js-field "_jtsConvexHull" :helper "ConvexHull"}
   {:class "org.locationtech.jts.algorithm.distance.DiscreteFrechetDistance" :js-field "_jtsDiscreteFrechetDistance" :helper "DiscreteFrechetDistance"}
   {:class "org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance" :js-field "_jtsDiscreteHausdorffDistance" :helper "DiscreteHausdorffDistance"}
   {:class "org.locationtech.jts.algorithm.distance.PointPairDistance" :js-field "_jtsPointPairDistance" :helper "PointPairDistance"}
   {:class "org.locationtech.jts.algorithm.HCoordinate" :js-field "_jtsHCoordinate" :helper "HCoordinate"}
   {:class "org.locationtech.jts.algorithm.hull.ConcaveHull" :js-field "_jtsConcaveHull" :helper "ConcaveHull"}
   {:class "org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons" :js-field "_jtsConcaveHullOfPolygons" :helper "ConcaveHullOfPolygons"}
   {:class "org.locationtech.jts.algorithm.InteriorPointArea" :js-field "_jtsInteriorPointArea" :helper "InteriorPointArea"}
   {:class "org.locationtech.jts.algorithm.InteriorPointLine" :js-field "_jtsInteriorPointLine" :helper "InteriorPointLine"}
   {:class "org.locationtech.jts.algorithm.InteriorPointPoint" :js-field "_jtsInteriorPointPoint" :helper "InteriorPointPoint"}
   {:class "org.locationtech.jts.algorithm.LineIntersector" :js-field "_jtsLineIntersector" :helper "LineIntersector"}
   {:class "org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator" :js-field "_jtsIndexedPointInAreaLocator" :helper "IndexedPointInAreaLocator"}
   {:class "org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator" :js-field "_jtsSimplePointInAreaLocator" :helper "SimplePointInAreaLocator"}
   {:class "org.locationtech.jts.algorithm.match.AreaSimilarityMeasure" :js-field "_jtsAreaSimilarityMeasure" :helper "AreaSimilarityMeasure"}
   {:class "org.locationtech.jts.algorithm.match.FrechetSimilarityMeasure" :js-field "_jtsFrechetSimilarityMeasure" :helper "FrechetSimilarityMeasure"}
   {:class "org.locationtech.jts.algorithm.match.HausdorffSimilarityMeasure" :js-field "_jtsHausdorffSimilarityMeasure" :helper "HausdorffSimilarityMeasure"}
   {:class "org.locationtech.jts.algorithm.MinimumBoundingCircle" :js-field "_jtsMBC" :helper "MBC"}
   {:class "org.locationtech.jts.algorithm.MinimumDiameter" :js-field "_jtsMDiam" :helper "MDiam"}
   {:class "org.locationtech.jts.algorithm.PointLocator" :js-field "_jtsPointLocator" :helper "PointLocator"}
   {:class "org.locationtech.jts.algorithm.RayCrossingCounter" :js-field "_jtsRayCrossingCounter" :helper "RayCrossingCounter"}
   {:class "org.locationtech.jts.algorithm.RectangleLineIntersector" :js-field "_jtsRectangleLineIntersector" :helper "RectangleLineIntersector"}
   {:class "org.locationtech.jts.algorithm.RobustLineIntersector" :js-field "_jtsRobustLineIntersector" :helper "RobustLineIntersector"}
   {:class "org.locationtech.jts.awt.GeometryCollectionShape" :js-field "_jtsGeometryCollectionShape" :helper "GeometryCollectionShape"}
   {:class "org.locationtech.jts.awt.IdentityPointTransformation" :js-field "_jtsIdentityPointTransformation" :helper "IdentityPointTransformation"}
   {:class "org.locationtech.jts.awt.PolygonShape" :js-field "_jtsPolygonShape" :helper "PolygonShape"}
   {:class "org.locationtech.jts.awt.ShapeWriter" :js-field "_jtsShapeWriter" :helper "ShapeWriter"}
   {:class "org.locationtech.jts.coverage.CoverageGapFinder" :js-field "_jtsCoverageGapFinder" :helper "CoverageGapFinder"}
   {:class "org.locationtech.jts.coverage.CoveragePolygonValidator" :js-field "_jtsCoveragePolygonValidator" :helper "CoveragePolygonValidator"}
   {:class "org.locationtech.jts.coverage.CoverageSimplifier" :js-field "_jtsCoverageSimplifier" :helper "CoverageSimplifier"}
   {:class "org.locationtech.jts.coverage.CoverageValidator" :js-field "_jtsCoverageValidator" :helper "CoverageValidator"}
   {:class "org.locationtech.jts.densify.Densifier" :js-field "_jtsDensifier" :helper "Densifier"}
   {:class "org.locationtech.jts.dissolve.LineDissolver" :js-field "_jtsLineDissolver" :helper "LineDissolver"}
   {:class "org.locationtech.jts.edgegraph.EdgeGraph" :js-field "_jtsEdgeGraph" :helper "EdgeGraph"}
   {:class "org.locationtech.jts.edgegraph.EdgeGraphBuilder" :js-field "_jtsEdgeGraphBuilder" :helper "EdgeGraphBuilder"}
   {:class "org.locationtech.jts.edgegraph.HalfEdge" :js-field "_jtsHalfEdge" :helper "HalfEdge"}
   {:class "org.locationtech.jts.edgegraph.MarkHalfEdge" :js-field "_jtsMarkHalfEdge" :helper "MarkHalfEdge"}
   {:class "org.locationtech.jts.geom.CoordinateList" :js-field "_jtsCoordinateList" :helper "CoordinateList"}
   {:class "org.locationtech.jts.geom.CoordinateSequenceComparator" :js-field "_jtsCoordinateSequenceComparator" :helper "CoordinateSequenceComparator"}
   {:class "org.locationtech.jts.geom.GeometryCollectionIterator" :js-field "_jtsGeometryCollectionIterator" :helper "GeometryCollectionIterator"}
   {:class "org.locationtech.jts.geom.impl.CoordinateArraySequence" :js-field "_jtsCoordinateArraySequence" :helper "CoordinateArraySequence"}
   {:class "org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory" :js-field "_jtsPackedCoordinateSequenceFactory" :helper "PackedCoordinateSequenceFactory"}
   {:class "org.locationtech.jts.geom.LineSegment" :js-field "_jtsLineSegment" :helper "LineSegment"}
   {:class "org.locationtech.jts.geom.OctagonalEnvelope" :js-field "_jtsOctagonalEnvelope" :helper "OctagonalEnvelope"}
   {:class "org.locationtech.jts.geom.prep.PreparedGeometry" :js-field "_jtsPreparedGeometry" :helper "PreparedGeometry"}
   {:class "org.locationtech.jts.geom.prep.PreparedGeometryFactory" :js-field "_jtsPreparedGeometryFactory" :helper "PreparedGeometryFactory"}
   {:class "org.locationtech.jts.geom.TopologyException" :js-field "_jtsTopologyException" :helper "TopologyException"}
   {:class "org.locationtech.jts.geom.Triangle" :js-field "_jtsTriangle" :helper "Triangle"}
   {:class "org.locationtech.jts.geom.util.AffineTransformation" :js-field "_jtsAffineTransformation" :helper "AffineTransformation"}
   {:class "org.locationtech.jts.geom.util.AffineTransformationBuilder" :js-field "_jtsAffineTransformationBuilder" :helper "AffineTransformationBuilder"}
   {:class "org.locationtech.jts.geom.util.GeometryCombiner" :js-field "_jtsGeometryCombiner" :helper "GeometryCombiner"}
   {:class "org.locationtech.jts.geom.util.GeometryEditor" :js-field "_jtsGeometryEditor" :helper "GeometryEditor"}
   {:class "org.locationtech.jts.geom.util.GeometryFixer" :js-field "_jtsGeometryFixer" :helper "GeometryFixer"}
   {:class "org.locationtech.jts.geom.util.GeometryTransformer" :js-field "_jtsGeometryTransformer" :helper "GeometryTransformer"}
   {:class "org.locationtech.jts.geom.util.ShortCircuitedGeometryVisitor" :js-field "_jtsShortCircuitedGeometryVisitor" :helper "ShortCircuitedGeometryVisitor"}
   {:class "org.locationtech.jts.geom.util.SineStarFactory" :js-field "_jtsSineStarFactory" :helper "SineStarFactory"}
   {:class "org.locationtech.jts.index.ArrayListVisitor" :js-field "_jtsArrayListVisitor" :helper "ArrayListVisitor"}
   {:class "org.locationtech.jts.index.bintree.Bintree" :js-field "_jtsBintree" :helper "Bintree"}
   {:class "org.locationtech.jts.index.chain.MonotoneChainOverlapAction" :js-field "_jtsMonotoneChainOverlapAction" :helper "MonotoneChainOverlapAction"}
   {:class "org.locationtech.jts.index.chain.MonotoneChainSelectAction" :js-field "_jtsMonotoneChainSelectAction" :helper "MonotoneChainSelectAction"}
   {:class "org.locationtech.jts.index.hprtree.HilbertEncoder" :js-field "_jtsHilbertEncoder" :helper "HilbertEncoder"}
   {:class "org.locationtech.jts.index.hprtree.HPRtree" :js-field "_jtsHPRtree" :helper "HPRtree"}
   {:class "org.locationtech.jts.index.intervalrtree.IntervalRTreeNode" :js-field "_jtsIntervalRTreeNode" :helper "IntervalRTreeNode"}
   {:class "org.locationtech.jts.index.intervalrtree.SortedPackedIntervalRTree" :js-field "_jtsSortedPackedIntervalRTree" :helper "SortedPackedIntervalRTree"}
   {:class "org.locationtech.jts.index.kdtree.KdTree" :js-field "_jtsKdTree" :helper "KdTree"}
   {:class "org.locationtech.jts.index.quadtree.DoubleBits" :js-field "_jtsDoubleBits" :helper "DoubleBits"}
   {:class "org.locationtech.jts.index.quadtree.Key" :js-field "_jtsKey" :helper "Key"}
   {:class "org.locationtech.jts.index.quadtree.Node" :js-field "_jtsNode" :helper "Node"}
   {:class "org.locationtech.jts.index.quadtree.Quadtree" :js-field "_jtsQuadtree" :helper "Quadtree"}
   {:class "org.locationtech.jts.index.strtree.AbstractNode" :js-field "_jtsAbstractNode" :helper "AbstractNode"}
   {:class "org.locationtech.jts.index.strtree.AbstractSTRtree" :js-field "_jtsAbstractSTRtree" :helper "AbstractSTRtree"}
   {:class "org.locationtech.jts.index.strtree.BoundablePairDistanceComparator" :js-field "_jtsBoundablePairDistanceComparator" :helper "BoundablePairDistanceComparator"}
   {:class "org.locationtech.jts.index.strtree.GeometryItemDistance" :js-field "_jtsGeometryItemDistance" :helper "GeometryItemDistance"}
   {:class "org.locationtech.jts.index.strtree.SIRtree" :js-field "_jtsSIRtree" :helper "SIRtree"}
   {:class "org.locationtech.jts.index.strtree.STRtree" :js-field "_jtsSTRtree" :helper "STRtree"}
   {:class "org.locationtech.jts.index.sweepline.SweepLineIndex" :js-field "_jtsSweepLineIndex" :helper "SweepLineIndex"}
   {:class "org.locationtech.jts.index.sweepline.SweepLineInterval" :js-field "_jtsSweepLineInterval" :helper "SweepLineInterval"}
   {:class "org.locationtech.jts.index.VertexSequencePackedRtree" :js-field "_jtsVertexSequencePackedRtree" :helper "VertexSequencePackedRtree"}
   {:class "org.locationtech.jts.io.ByteArrayInStream" :js-field "_jtsByteArrayInStream" :helper "ByteArrayInStream"}
   {:class "org.locationtech.jts.io.ByteOrderDataInStream" :js-field "_jtsByteOrderDataInStream" :helper "ByteOrderDataInStream"}
   {:class "org.locationtech.jts.io.OrdinateFormat" :js-field "_jtsOrdinateFormat" :helper "OrdinateFormat"}
   {:class "org.locationtech.jts.io.twkb.TWKBWriter" :js-field "_jtsTWKBWriter" :helper "TWKBWriter"}
   {:class "org.locationtech.jts.linearref.LengthIndexedLine" :js-field "_jtsLengthIndexedLine" :helper "LengthIndexedLine"}
   {:class "org.locationtech.jts.linearref.LengthLocationMap" :js-field "_jtsLengthLocationMap" :helper "LengthLocationMap"}
   {:class "org.locationtech.jts.linearref.LinearIterator" :js-field "_jtsLinearIterator" :helper "LinearIterator"}
   {:class "org.locationtech.jts.linearref.LinearLocation" :js-field "_jtsLinearLocation" :helper "LinearLocation"}
   {:class "org.locationtech.jts.linearref.LocationIndexedLine" :js-field "_jtsLocationIndexedLine" :helper "LocationIndexedLine"}
   {:class "org.locationtech.jts.math.DD" :js-field "_jtsDD" :helper "DD"}
   {:class "org.locationtech.jts.math.Plane3D" :js-field "_jtsPlane3D" :helper "Plane3D"}
   {:class "org.locationtech.jts.math.Vector2D" :js-field "_jtsVector2D" :helper "Vector2D"}
   {:class "org.locationtech.jts.math.Vector3D" :js-field "_jtsVector3D" :helper "Vector3D"}
   {:class "org.locationtech.jts.operation.BoundaryOp" :js-field "_jtsBoundaryOp" :helper "BoundaryOp"}
   {:class "org.locationtech.jts.operation.buffer.BufferCurveSetBuilder" :js-field "_jtsBufferCurveSetBuilder" :helper "BufferCurveSetBuilder"}
   {:class "org.locationtech.jts.operation.buffer.BufferInputLineSimplifier" :js-field "_jtsBufferInputLineSimplifier" :helper "BufferInputLineSimplifier"}
   {:class "org.locationtech.jts.operation.buffer.BufferOp" :js-field "_jtsBufferOp" :helper "BufferOp"}
   {:class "org.locationtech.jts.operation.buffer.BufferParameters" :js-field "_jtsBufferParameters" :helper "BufferParameters"}
   {:class "org.locationtech.jts.operation.buffer.OffsetCurve" :js-field "_jtsOffsetCurve" :helper "OffsetCurve"}
   {:class "org.locationtech.jts.operation.buffer.OffsetCurveBuilder" :js-field "_jtsOffsetCurveBuilder" :helper "OffsetCurveBuilder"}
   {:class "org.locationtech.jts.operation.buffer.validate.BufferCurveMaximumDistanceFinder" :js-field "_jtsBufferCurveMaximumDistanceFinder" :helper "BufferCurveMaximumDistanceFinder"}
   {:class "org.locationtech.jts.operation.buffer.validate.BufferDistanceValidator" :js-field "_jtsBufferDistanceValidator" :helper "BufferDistanceValidator"}
   {:class "org.locationtech.jts.operation.buffer.validate.BufferResultValidator" :js-field "_jtsBufferResultValidator" :helper "BufferResultValidator"}
   {:class "org.locationtech.jts.operation.distance.DistanceOp" :js-field "_jtsDistanceOp" :helper "DistanceOp"}
   {:class "org.locationtech.jts.operation.distance.FacetSequence" :js-field "_jtsFacetSequence" :helper "FacetSequence"}
   {:class "org.locationtech.jts.operation.distance.GeometryLocation" :js-field "_jtsGeometryLocation" :helper "GeometryLocation"}
   {:class "org.locationtech.jts.operation.distance.IndexedFacetDistance" :js-field "_jtsIndexedFacetDistance" :helper "IndexedFacetDistance"}
   {:class "org.locationtech.jts.operation.distance3d.Distance3DOp" :js-field "_jtsDistance3DOp" :helper "Distance3DOp"}
   {:class "org.locationtech.jts.operation.IsSimpleOp" :js-field "_jtsIsSimpleOp" :helper "IsSimpleOp"}
   {:class "org.locationtech.jts.operation.linemerge.LineMerger" :js-field "_jtsLineMerger" :helper "LineMerger"}
   {:class "org.locationtech.jts.operation.linemerge.LineSequencer" :js-field "_jtsLineSequencer" :helper "LineSequencer"}
   {:class "org.locationtech.jts.operation.overlay.OverlayNodeFactory" :js-field "_jtsOverlayNodeFactory" :helper "OverlayNodeFactory"}
   {:class "org.locationtech.jts.operation.overlay.OverlayOp" :js-field "_jtsOverlayOp" :helper "OverlayOp"}
   {:class "org.locationtech.jts.operation.overlay.snap.GeometrySnapper" :js-field "_jtsGeometrySnapper" :helper "GeometrySnapper"}
   {:class "org.locationtech.jts.operation.overlay.snap.LineStringSnapper" :js-field "_jtsLineStringSnapper" :helper "LineStringSnapper"}
   {:class "org.locationtech.jts.operation.overlay.snap.SnapIfNeededOverlayOp" :js-field "_jtsSnapIfNeededOverlayOp" :helper "SnapIfNeededOverlayOp"}
   {:class "org.locationtech.jts.operation.overlay.snap.SnapOverlayOp" :js-field "_jtsSnapOverlayOp" :helper "SnapOverlayOp"}
   {:class "org.locationtech.jts.operation.overlay.validate.FuzzyPointLocator" :js-field "_jtsFuzzyPointLocator" :helper "FuzzyPointLocator"}
   {:class "org.locationtech.jts.operation.overlay.validate.OffsetPointGenerator" :js-field "_jtsOffsetPointGenerator" :helper "OffsetPointGenerator"}
   {:class "org.locationtech.jts.operation.overlay.validate.OverlayResultValidator" :js-field "_jtsOverlayResultValidator" :helper "OverlayResultValidator"}
   {:class "org.locationtech.jts.operation.overlayng.FastOverlayFilter" :js-field "_jtsFastOverlayFilter" :helper "FastOverlayFilter"}
   {:class "org.locationtech.jts.operation.overlayng.LineLimiter" :js-field "_jtsLineLimiter" :helper "LineLimiter"}
   {:class "org.locationtech.jts.operation.overlayng.OverlayNG" :js-field "_jtsOverlayNG" :helper "OverlayNG"}
   {:class "org.locationtech.jts.operation.overlayng.RingClipper" :js-field "_jtsRingClipper" :helper "RingClipper"}
   {:class "org.locationtech.jts.operation.polygonize.Polygonizer" :js-field "_jtsPolygonizer" :helper "Polygonizer"}
   {:class "org.locationtech.jts.operation.predicate.RectangleContains" :js-field "_jtsRectangleContains" :helper "RectangleContains"}
   {:class "org.locationtech.jts.operation.predicate.RectangleIntersects" :js-field "_jtsRectangleIntersects" :helper "RectangleIntersects"}
   {:class "org.locationtech.jts.operation.relate.EdgeEndBuilder" :js-field "_jtsEdgeEndBuilder" :helper "EdgeEndBuilder"}
   {:class "org.locationtech.jts.operation.relate.RelateNodeGraph" :js-field "_jtsRelateNodeGraph" :helper "RelateNodeGraph"}
   {:class "org.locationtech.jts.operation.relate.RelateOp" :js-field "_jtsRelateOp" :helper "RelateOp"}
   {:class "org.locationtech.jts.operation.relateng.RelateNG" :js-field "_jtsRelateNG" :helper "RelateNG"}
   {:class "org.locationtech.jts.operation.union.OverlapUnion" :js-field "_jtsOverlapUnion" :helper "OverlapUnion"}
   {:class "org.locationtech.jts.operation.union.UnaryUnionOp" :js-field "_jtsUnaryUnionOp" :helper "UnaryUnionOp"}
   {:class "org.locationtech.jts.operation.union.UnionInteracting" :js-field "_jtsUnionInteracting" :helper "UnionInteracting"}
   {:class "org.locationtech.jts.operation.valid.IsValidOp" :js-field "_jtsIsValidOp" :helper "IsValidOp"}
   {:class "org.locationtech.jts.operation.valid.RepeatedPointTester" :js-field "_jtsRepeatedPointTester" :helper "RepeatedPointTester"}
   {:class "org.locationtech.jts.operation.valid.TopologyValidationError" :js-field "_jtsTopologyValidationError" :helper "TopologyValidationError"}
   {:class "org.locationtech.jts.precision.CommonBits" :js-field "_jtsCommonBits" :helper "CommonBits"}
   {:class "org.locationtech.jts.precision.CommonBitsOp" :js-field "_jtsCommonBitsOp" :helper "CommonBitsOp"}
   {:class "org.locationtech.jts.precision.CommonBitsRemover" :js-field "_jtsCommonBitsRemover" :helper "CommonBitsRemover"}
   {:class "org.locationtech.jts.precision.CoordinatePrecisionReducerFilter" :js-field "_jtsCoordinatePrecisionReducerFilter" :helper "CoordinatePrecisionReducerFilter"}
   {:class "org.locationtech.jts.precision.GeometryPrecisionReducer" :js-field "_jtsGeometryPrecisionReducer" :helper "GeometryPrecisionReducer"}
   {:class "org.locationtech.jts.precision.MinimumClearance" :js-field "_jtsMinimumClearance" :helper "MinimumClearance"}
   {:class "org.locationtech.jts.precision.PrecisionReducerCoordinateOperation" :js-field "_jtsPrecisionReducerCoordinateOperation" :helper "PrecisionReducerCoordinateOperation"}
   {:class "org.locationtech.jts.precision.SimpleGeometryPrecisionReducer" :js-field "_jtsSimpleGeometryPrecisionReducer" :helper "SimpleGeometryPrecisionReducer"}
   {:class "org.locationtech.jts.precision.SimpleMinimumClearance" :js-field "_jtsSimpleMinimumClearance" :helper "SimpleMinimumClearance"}
   {:class "org.locationtech.jts.shape.fractal.KochSnowflakeBuilder" :js-field "_jtsKochSnowflakeBuilder" :helper "KochSnowflakeBuilder"}
   {:class "org.locationtech.jts.shape.fractal.SierpinskiCarpetBuilder" :js-field "_jtsSierpinskiCarpetBuilder" :helper "SierpinskiCarpetBuilder"}
   {:class "org.locationtech.jts.shape.random.RandomPointsBuilder" :js-field "_jtsRandomPointsBuilder" :helper "RandomPointsBuilder"}
   {:class "org.locationtech.jts.shape.random.RandomPointsInGridBuilder" :js-field "_jtsRandomPointsInGridBuilder" :helper "RandomPointsInGridBuilder"}
   {:class "org.locationtech.jts.simplify.LinkedLine" :js-field "_jtsLinkedLine" :helper "LinkedLine"}
   {:class "org.locationtech.jts.simplify.PolygonHullSimplifier" :js-field "_jtsPolygonHullSimplifier" :helper "PolygonHullSimplifier"}
   {:class "org.locationtech.jts.simplify.TopologyPreservingSimplifier" :js-field "_jtsTopologyPreservingSimplifier" :helper "TopologyPreservingSimplifier"}
   {:class "org.locationtech.jts.simplify.VWSimplifier" :js-field "_jtsVWSimplifier" :helper "VWSimplifier"}
   {:class "org.locationtech.jts.triangulate.ConformingDelaunayTriangulationBuilder" :js-field "_jtsConformingDelaunayTriangulationBuilder" :helper "ConformingDelaunayTriangulationBuilder"}
   {:class "org.locationtech.jts.triangulate.ConstraintEnforcementException" :js-field "_jtsConstraintEnforcementException" :helper "ConstraintEnforcementException"}
   {:class "org.locationtech.jts.triangulate.ConstraintVertex" :js-field "_jtsConstraintVertex" :helper "ConstraintVertex"}
   {:class "org.locationtech.jts.triangulate.DelaunayTriangulationBuilder" :js-field "_jtsDelaunayTriangulationBuilder" :helper "DelaunayTriangulationBuilder"}
   {:class "org.locationtech.jts.triangulate.MidpointSplitPointFinder" :js-field "_jtsMidpointSplitPointFinder" :helper "MidpointSplitPointFinder"}
   {:class "org.locationtech.jts.triangulate.NonEncroachingSplitPointFinder" :js-field "_jtsNonEncroachingSplitPointFinder" :helper "NonEncroachingSplitPointFinder"}
   {:class "org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator" :js-field "_jtsConstrainedDelaunayTriangulator" :helper "ConstrainedDelaunayTriangulator"}
   {:class "org.locationtech.jts.triangulate.polygon.PolygonHoleJoiner" :js-field "_jtsPolygonHoleJoiner" :helper "PolygonHoleJoiner"}
   {:class "org.locationtech.jts.triangulate.polygon.PolygonTriangulator" :js-field "_jtsPolygonTriangulator" :helper "PolygonTriangulator"}
   {:class "org.locationtech.jts.triangulate.quadedge.EdgeConnectedTriangleTraversal" :js-field "_jtsEdgeConnectedTriangleTraversal" :helper "EdgeConnectedTriangleTraversal"}
   {:class "org.locationtech.jts.triangulate.quadedge.LocateFailureException" :js-field "_jtsLocateFailureException" :helper "LocateFailureException"}
   {:class "org.locationtech.jts.triangulate.quadedge.QuadEdge" :js-field "_jtsQuadEdge" :helper "QuadEdge"}
   {:class "org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision" :js-field "_jtsQuadEdgeSubdivision" :helper "QuadEdgeSubdivision"}
   {:class "org.locationtech.jts.triangulate.quadedge.Vertex" :js-field "_jtsVertex" :helper "Vertex"}
   {:class "org.locationtech.jts.triangulate.Segment" :js-field "_jtsSegment" :helper "Segment"}
   {:class "org.locationtech.jts.triangulate.SplitSegment" :js-field "_jtsSplitSegment" :helper "SplitSegment"}
   {:class "org.locationtech.jts.triangulate.tri.Tri" :js-field "_jtsTri" :helper "Tri"}
   {:class "org.locationtech.jts.triangulate.VertexTaggedGeometryDataMapper" :js-field "_jtsVertexTaggedGeometryDataMapper" :helper "VertexTaggedGeometryDataMapper"}
   {:class "org.locationtech.jts.triangulate.VoronoiDiagramBuilder" :js-field "_jtsVoronoiDiagramBuilder" :helper "VoronoiDiagramBuilder"}
   {:class "org.locationtech.jts.util.CoordinateArrayFilter" :js-field "_jtsCoordinateArrayFilter" :helper "CoordinateArrayFilter"}
   {:class "org.locationtech.jts.util.CoordinateCountFilter" :js-field "_jtsCoordinateCountFilter" :helper "CoordinateCountFilter"}
   {:class "org.locationtech.jts.util.GeometricShapeFactory" :js-field "_jtsGeometricShapeFactory" :helper "GeometricShapeFactory"}
   {:class "org.locationtech.jts.util.IntArrayList" :js-field "_jtsIntArrayList" :helper "IntArrayList"}
   {:class "org.locationtech.jts.util.ObjectCounter" :js-field "_jtsObjectCounter" :helper "ObjectCounter"}
   {:class "org.locationtech.jts.util.PriorityQueue" :js-field "_jtsPriorityQueue" :helper "PriorityQueue"}
   {:class "org.locationtech.jts.util.Stopwatch" :js-field "_jtsStopwatch" :helper "Stopwatch"}
   {:class "org.locationtech.jts.util.UniqueCoordinateArrayFilter" :js-field "_jtsUniqueCoordinateArrayFilter" :helper "UniqueCoordinateArrayFilter"}
   ;; Attach-override classes (createJS<X> calls wire<X>Methods + API.attach<X>Overrides;
   ;; the attach<X>Overrides @JS body stays hand-written in API.java). Geometry adds an
   ;; extra `type: JSString` ctor param + matching `type` field on the JS wrapper.
   {:class "org.locationtech.jts.geom.CoordinateSequence" :js-field "_jtsCoordSeq" :helper "CoordinateSequence" :wire? true :attach? true}
   {:class "org.locationtech.jts.geom.Envelope" :js-field "_jtsEnvelope" :helper "Envelope" :wire? true :attach? true}
   {:class "org.locationtech.jts.geom.Geometry" :js-field "_jtsGeom" :helper "Geometry" :wire? true :attach? true :extra-params [{:type "JSString", :name "type"}]}
   {:class "org.locationtech.jts.geom.IntersectionMatrix" :js-field "_jtsIntersectionMatrix" :helper "IntersectionMatrix" :wire? true :attach? true}
   {:class "org.locationtech.jts.geom.PrecisionModel" :js-field "_jtsPrecisionModel" :helper "PrecisionModel" :wire? true}
   {:class "org.locationtech.jts.io.geojson.GeoJsonReader" :js-field "_jtsGeoJsonReader" :helper "GeoJsonReader" :wire? true}
   {:class "org.locationtech.jts.io.geojson.GeoJsonWriter" :js-field "_jtsGeoJsonWriter" :helper "GeoJsonWriter" :wire? true}
   {:class "org.locationtech.jts.io.gml2.GMLWriter" :js-field "_jtsGMLWriter" :helper "GMLWriter" :wire? true}
   {:class "org.locationtech.jts.io.kml.KMLReader" :js-field "_jtsKMLReader" :helper "KMLReader" :wire? true}
   {:class "org.locationtech.jts.io.kml.KMLWriter" :js-field "_jtsKMLWriter" :helper "KMLWriter" :wire? true}
   {:class "org.locationtech.jts.io.twkb.TWKBReader" :js-field "_jtsTWKBReader" :helper "TWKBReader" :wire? true}
   {:class "org.locationtech.jts.io.WKBReader" :js-field "_jtsWKBReader" :helper "WKBReader" :wire? true}
   {:class "org.locationtech.jts.io.WKBWriter" :js-field "_jtsWKBWriter" :helper "WKBWriter" :wire? true}
   {:class "org.locationtech.jts.io.WKTReader" :js-field "_jtsWKTReader" :helper "WKTReader" :wire? true}
   {:class "org.locationtech.jts.io.WKTWriter" :js-field "_jtsWKTWriter" :helper "WKTWriter" :wire? true}
   ])

(def ^:private geometry-subtype-extractors
  "Geometry subtypes that need a downcast extractor in API_Generated.java.
   Each entry's `extract<Helper>` calls `extractGeometry` first, throws
   on type mismatch, and casts. Unlike `js-wrapper-classes`, there's no
   createJS<X> companion (subtypes share the Geometry wrapper shape) —
   only extract is emitted."
  [   {:class "org.locationtech.jts.geom.GeometryCollection" :helper "GeometryCollection"}
   {:class "org.locationtech.jts.geom.LinearRing" :helper "LinearRing"}
   {:class "org.locationtech.jts.geom.LineString" :helper "LineString"}
   {:class "org.locationtech.jts.geom.MultiLineString" :helper "MultiLineString"}
   {:class "org.locationtech.jts.geom.MultiPoint" :helper "MultiPoint"}
   {:class "org.locationtech.jts.geom.MultiPolygon" :helper "MultiPolygon"}
   {:class "org.locationtech.jts.geom.Point" :helper "Point"}
   {:class "org.locationtech.jts.geom.Polygon" :helper "Polygon"}])

(def ^:private js-wrapper-helper-names
  "Lookup set of :helper slugs for fast `contains?` checks when deciding
   whether a dispatch-body emitter should target `API.<helper>` (legacy)
   or unqualified `<helper>` / `API_Generated.<helper>` (post-migration).
   Includes both js-wrapper-classes (createJS+extract) and
   geometry-subtype-extractors (extract-only) so `strip-api-prefix-from-helpers` flips
   `API.extractPoint(...)` etc. inside dispatch bodies."
  (into #{} (map :helper) (concat js-wrapper-classes geometry-subtype-extractors)))

(def ^:private js-helper-by-class
  "Class FQN -> :helper slug. Built from js-wrapper-classes; missing entries
   fall back to simple-name(class) so JS-literal types (Coordinate) and
   not-yet-wrapped types still produce a usable createJS<Name> /
   extract<Name> handle. Helper diverges from simple-name only for the
   abbreviated wrappers (MBC for MinimumBoundingCircle, MDiam for
   MinimumDiameter)."
  (into {} (map (juxt :class :helper)) js-wrapper-classes))

(defn- js-helper-for [class]
  (or (js-helper-by-class class) (last (str/split class #"\."))))

(defn- strip-api-prefix-from-helpers
  "Within API_Generated.java, the wrapped-class helpers (createJS<X> /
   extract<X>) live unqualified. Rewrite dispatch-body output from
   `API.createJS<X>` / `API.extract<X>` to `createJS<X>` / `extract<X>`
   for every entry in `js-wrapper-helper-names`. Word-boundary aware so longer
   names like `API.createJSLineSegmentOrNull` (still in API.java for
   now) stay intact."
  [body]
  (reduce (fn [acc helper]
            (-> acc
                (str/replace (re-pattern (str "API\\.createJS" helper "\\b"))
                             (str "createJS" helper))
                (str/replace (re-pattern (str "API\\.extract" helper "\\b"))
                             (str "extract" helper))))
          body
          js-wrapper-helper-names))


(defn- fn-interface-decl
  "Emit `interface FnN { Object invoke(Object a1, ..., Object aN); }` for
   the given arity."
  [arity]
  (let [args (->> (range arity) (map #(str "Object a" (inc %))) (str/join ", "))]
    (format "    @FunctionalInterface\n    interface Fn%d { Object invoke(%s); }\n" arity args)))


(defn- js-arglist
  "(a1, a2, ..., aN)"
  [arity]
  (format "(%s)" (->> (range arity) (map #(str "a" (inc %))) (str/join ", "))))

(defn- js-fn-invoke
  "fn.invoke(a1, a2, ..., aN)"
  [arity]
  (format "fn.invoke(%s)" (->> (range arity) (map #(str "a" (inc %))) (str/join ", "))))

(defn- installer-decl
  "Emit one generic `installFn<N>(String path, FnN fn)` native for the
   given arity. The @JS body walks the `path` dot-segments down from
   the top-level `wasmts` namespace (created by setupNamespaces) and
   assigns the leaf to an arrow that delegates to `fn.invoke`."
  [arity]
  (format
   (str "    @JS.Coerce\n"
        "    @JS(\"var ps=path.split('.');var o=wasmts;for(var i=1;i<ps.length-1;i++){o=o[ps[i]];}o[ps[ps.length-1]]=%s=>%s;\")\n"
        "    private static native void installFn%d(String path, Fn%d fn);\n")
   (js-arglist arity) (js-fn-invoke arity) arity arity))

(def ^:private constant-installer-decls
  "Per-type constant installers. Same parent-namespace walk as
   installFnN, but the leaf is set to a plain primitive instead of an
   arrow. Char values cross the boundary as int and get coerced to a
   single-character string via String.fromCharCode."
  (str
   "    @JS.Coerce\n"
   "    @JS(\"var ps=path.split('.');var o=wasmts;for(var i=1;i<ps.length-1;i++){o=o[ps[i]];}o[ps[ps.length-1]]=value;\")\n"
   "    private static native void installConstantInt(String path, int value);\n"
   "\n"
   "    @JS.Coerce\n"
   "    @JS(\"var ps=path.split('.');var o=wasmts;for(var i=1;i<ps.length-1;i++){o=o[ps[i]];}o[ps[ps.length-1]]=String.fromCharCode(value);\")\n"
   "    private static native void installConstantChar(String path, int value);\n"
   "\n"
   "    @JS.Coerce\n"
   "    @JS(\"var ps=path.split('.');var o=wasmts;for(var i=1;i<ps.length-1;i++){o=o[ps[i]];}o[ps[ps.length-1]]=value;\")\n"
   "    private static native void installConstantDouble(String path, double value);\n"))

(defmulti dispatch-body
  "Return the Java expression that, given a1..aN, computes the JS
   result for one entry. Wrapped in a `JSBoolean.of(...)` etc. as
   appropriate for the return type.

   Dispatch precedence:
     1. `:kind` field on the entry's `:shape` map (`:receiver-call` /
        `:static-call` / `:ctor`). Set by the hoisted template-engine
        rules at the top of classify-shape for entries whose class is
        in `templated-*-classes`. Routes through one of the 3 generic
        templates below.
     2. Legacy keyword `:shape` (e.g. `:env->bool`). Per-shape
        defmethod fires."
  (fn [_k v]
    (let [s (:shape v)]
      (if (map? s) (:kind s) s))))

(defmethod dispatch-body :default [_k _v] nil)

;; Geometry receivers route through :receiver-call generically; the bespoke
;; defmethods below cover cases the generic template can't:
;;   - getUserData / setUserData pass the raw Object through;
;;   - getCoordinate uses createJSCoordinateOrNull for empty geometries;
;;   - getCoordinateSequence dispatches polymorphically Point/LineString;
;;   - 4 apply(*Filter) callbacks wrap the JS callback into a JTS filter.
;;
;; equals(Object) / compareTo(Object) route through :receiver-call too
;; via a :param-override hint in manual.edn that narrows the Object position
;; to Geometry; coerce-arg-expr picks the extractGeometry case for that
;; position instead of the (Object) aN fallback.

;; Point and LineString collapse to wasmts.geom.getCoordinateSequence under
;; the geometry-subtypes path collapse, so a single handler does the
;; instanceof dispatch internally (LinearRing flows through the LineString
;; branch via Java inheritance).
(defmethod dispatch-body :geometry-get-coordinate-sequence [{:keys [method]} _]
  (format (str "{ org.locationtech.jts.geom.Geometry g = API.extractGeometry(a1); "
               "if (g instanceof org.locationtech.jts.geom.Point) "
               "return API.createJSCoordinateSequence(((org.locationtech.jts.geom.Point) g).%s()); "
               "if (g instanceof org.locationtech.jts.geom.LineString) "
               "return API.createJSCoordinateSequence(((org.locationtech.jts.geom.LineString) g).%s()); "
               "throw new IllegalArgumentException(\"getCoordinateSequence requires Point or LineString, got \" + g.getClass().getName()); }")
          method method))


(defmethod dispatch-body :strtree-insert [{:keys [method]} _]
  (format "{ API.extractSTRtree(a1).%s(API.extractEnvelope(a2), (Object) a3); return null; }" method))

(defmethod dispatch-body :strtree-remove [{:keys [method]} _]
  (format "JSBoolean.of(API.extractSTRtree(a1).%s(API.extractEnvelope(a2), (Object) a3))" method))

;; STRtree.query returns a List<?> of arbitrary items. Build a JS array
;; inline; the items are passed straight through (no extraction/wrapping).
(defmethod dispatch-body :strtree-query [{:keys [method]} _]
  (format
   (str "{ org.graalvm.webimage.api.JSObject jsArray = API.createJSArray(); "
        "for (Object item : API.extractSTRtree(a1).%s(API.extractEnvelope(a2))) { "
        "API.pushToJSArray(jsArray, item); } return jsArray; }")
   method))

(defmethod dispatch-body :strtree-size [{:keys [method]} _]
  (format "JSNumber.of(API.extractSTRtree(a1).%s())" method))


(defmethod dispatch-body :linemerger-add [{:keys [method]} _]
  (format "{ API.extractLineMerger(a1).%s(API.extractGeometry(a2)); return null; }" method))

;; LineMerger.getMergedLineStrings returns Collection<LineString>; wrap each
;; item with createJSGeometry and assemble a JS array.
(defmethod dispatch-body :linemerger-get-merged-line-strings [{:keys [method]} _]
  (format
   "API.convertGeometryCollectionToJS((java.util.Collection<org.locationtech.jts.geom.Geometry>) (java.util.Collection<?>) API.extractLineMerger(a1).%s())"
   method))

;; Geometry.getUserData() / setUserData(Object) route through
;; {:kind :receiver-call}: the "java.lang.Object" return-type-wrappers
;; entry is identity (Web Image auto-wraps the raw reference for JS) and
;; coerce-arg-expr emits `(Object) aN` for the setter. Object return /
;; Object param aren't coercible, so the hoisted generic rule skips them;
;; the user-data cond rules in build_registry produce the shape directly.

;; Geometry.apply(*Filter): wraps the JS callback in the matching
;; JSCallback<X>Filter, copies the receiver so JTS's in-place mutation
;; doesn't surprise the JS caller, and returns the modified copy as a
;; JS geometry. The 4 filter overloads (CoordinateSequenceFilter /
;; CoordinateFilter / GeometryComponentFilter / GeometryFilter) live
;; at distinct JS paths via manual.edn :js-path hints; each dispatches
;; through API.geometryApply<X>Filter, selected by the filter param's type.
(def ^:private apply-filter-helper-suffix
  {"org.locationtech.jts.geom.CoordinateSequenceFilter" "CSFilter"
   "org.locationtech.jts.geom.CoordinateFilter"         "CoordFilter"
   "org.locationtech.jts.geom.GeometryComponentFilter"  "ComponentFilter"
   "org.locationtech.jts.geom.GeometryFilter"           "GeometryFilter"})

(defmethod dispatch-body :geometry-apply-filter [{:keys [params]} _]
  (let [suffix (apply-filter-helper-suffix (first params))]
    (format "API.geometryApply%s(API.extractGeometry(a1), (JSValue) a2)" suffix)))

;; The extractPoint / extractLineString / extractPolygon helpers do an
;; instanceof check after extractGeometry, throwing IllegalArgumentException
;; on type mismatch (same contract as the hand-written exports).


;; The lambda is `(Object aN) -> ...`. Each declared param picks its
;; own coercion: primitives unbox via JSValue, JTS reference types route
;; through the matching `API.extract<X>`.

(defn- coerce-arg-expr
  "Render the i-th lambda parameter (`a<idx>`) as a Java expression of the
   right type for a ctor or static-method invocation. Falls back to a raw
   cast for any reference type whose extract helper isn't wired — the
   classifier should have prevented those, so an emitted cast is a loud
   failure."
  [idx t]
  (let [v (str "a" idx)]
    (case t
      "double"   (format "((JSValue) %s).asDouble()" v)
      "int"      (format "((JSValue) %s).asInt()" v)
      ;; long via asDouble() + double unbox + narrow. JSValue
      ;; has no asLong; JSValue.asDouble appears to return a boxed Double
      ;; (javac rejects a direct primitive (long) cast). Two-step cast
      ;; forces the unbox before the narrow. JS Number's safe-integer
      ;; range (2^53) covers JTS long usages (Memory + Stopwatch).
      "long"     (format "(long) (double) ((JSValue) %s).asDouble()" v)
      "org.locationtech.jts.geom.Envelope"       (format "API.extractEnvelope(%s)" v)
      "org.locationtech.jts.geom.PrecisionModel" (format "API.extractPrecisionModel(%s)" v)
      "org.locationtech.jts.geom.Coordinate"     (format "API.extractCoordinate(%s)" v)
      "org.locationtech.jts.geom.Geometry"       (format "API.extractGeometry(%s)" v)
      "org.locationtech.jts.geom.Triangle"       (format "API.extractTriangle(%s)" v)
      "org.locationtech.jts.geom.LineSegment"    (format "API.extractLineSegment(%s)" v)
      "org.locationtech.jts.geom.Coordinate[]"   (format "API.extractCoordinateArray(%s)" v)
      "org.locationtech.jts.geom.Geometry[]"     (format "API.extractGeometryArray(%s)" v)
      "org.locationtech.jts.geom.LinearRing"     (format "API.extractLinearRing(%s)" v)
      "org.locationtech.jts.geom.LinearRing[]"   (format "API.extractLinearRingArray(%s)" v)
      "org.locationtech.jts.geom.Point[]"        (format "API.extractPointArray(%s)" v)
      "org.locationtech.jts.geom.LineString[]"   (format "API.extractLineStringArray(%s)" v)
      "org.locationtech.jts.geom.Polygon[]"      (format "API.extractPolygonArray(%s)" v)
      "byte[]"   (format "API.extractByteArray(%s)" v)
      ;; primitive-array param extractors. Mirror
      ;; extractByteArray's "JS array or primitive array" tolerance.
      "double[]" (format "API.extractDoubleArray(%s)" v)
      "int[]"    (format "API.extractIntArray(%s)" v)
      "boolean"  (format "((JSValue) %s).asBoolean()" v)
      "char"     (format "((JSValue) %s).asString().charAt(0)" v)
      "java.lang.String" (format "((JSValue) %s).asString()" v)
      ;; Object param: no-op cast, raw value passed through. Only
      ;; Geometry.setUserData(Object) reaches here (other Object params
      ;; route through :param-override). Emits `(Object) aN` (not the FQN)
      ;; to match the legacy :geometry-set-user-data emit.
      "java.lang.Object" (format "(Object) %s" v)
      "org.locationtech.jts.math.Vector3D"       (format "API.extractVector3D(%s)" v)
      "org.locationtech.jts.geom.IntersectionMatrix" (format "API.extractIntersectionMatrix(%s)" v)
      "org.locationtech.jts.geom.PrecisionModel$Type" (format "API.precisionModelTypeFromName(((JSValue) %s).asString())" v)
      "org.locationtech.jts.operation.buffer.BufferParameters" (format "API.extractBufferParameters(%s)" v)
      ;; CoordinateSequence param for Area / Orientation /
      ;; PointLocation static shapes.
      "org.locationtech.jts.geom.CoordinateSequence" (format "API.extractCoordinateSequence(%s)" v)
      ;; GeometryFactory + geometry subtypes. All extract<X>
      ;; helpers already exist (GeometryFactory in API.java, subtypes
      ;; via geometry-subtype-extractors in API_Generated.java).
      "org.locationtech.jts.geom.GeometryFactory"    (format "API.extractGeometryFactory(%s)" v)
      "org.locationtech.jts.geom.Point"              (format "API.extractPoint(%s)" v)
      "org.locationtech.jts.geom.LineString"         (format "API.extractLineString(%s)" v)
      "org.locationtech.jts.geom.Polygon"            (format "API.extractPolygon(%s)" v)
      "org.locationtech.jts.geom.MultiPoint"         (format "API.extractMultiPoint(%s)" v)
      "org.locationtech.jts.geom.MultiLineString"    (format "API.extractMultiLineString(%s)" v)
      "org.locationtech.jts.geom.MultiPolygon"       (format "API.extractMultiPolygon(%s)" v)
      "org.locationtech.jts.geom.GeometryCollection" (format "API.extractGeometryCollection(%s)" v)
      ;; generic List/Collection params. Use
      ;; java.util.Arrays.asList over the matching extract<X>Array
      ;; helper to convert a JS array of wrapped elements into a Java
      ;; List<X>. Collection is super of List so the same expression
      ;; satisfies either param shape; we still emit per-type cases so
      ;; the registry-side param-types-with-extractors set has explicit
      ;; entries (no implicit subtype-matching at the classifier).
      "java.util.List<org.locationtech.jts.geom.Geometry>"
      (format "java.util.Arrays.asList(API.extractGeometryArray(%s))" v)
      "java.util.Collection<org.locationtech.jts.geom.Geometry>"
      (format "java.util.Arrays.asList(API.extractGeometryArray(%s))" v)
      "java.util.List<org.locationtech.jts.geom.Coordinate>"
      (format "java.util.Arrays.asList(API.extractCoordinateArray(%s))" v)
      "java.util.Collection<org.locationtech.jts.geom.Coordinate>"
      (format "java.util.Arrays.asList(API.extractCoordinateArray(%s))" v)
      "java.util.List<org.locationtech.jts.geom.LineString>"
      (format "java.util.Arrays.asList(API.extractLineStringArray(%s))" v)
      "java.util.Collection<org.locationtech.jts.geom.LineString>"
      (format "java.util.Arrays.asList(API.extractLineStringArray(%s))" v)
      ;; wildcard fallback for any class in js-helper-by-class
      ;; (i.e. anything in `js-wrapper-classes` above). Lets generic
      ;; classifier accept same-class auto-ctor params (DD.add(DD),
      ;; Vector2D.angleTo(Vector2D), QuadEdgeSubdivision.connect(QuadEdge),
      ;; etc.) without per-type case branches. Non-wrapped reference
      ;; types still fall through to the raw cast as a loud failure.
      (if-let [helper (js-helper-by-class t)]
        (format "API.extract%s(%s)" helper v)
        (format "(%s) %s" t v)))))


;; PrecisionModel.getType -> friendly string. Dispatch goes
;; through API.precisionModelTypeFriendlyName which maps JTS's enum-style
;; toString to the wasmts friendly form.
(defmethod dispatch-body :pm->type-friendly [_ _]
  "JSString.of(API.precisionModelTypeFriendlyName(API.extractPrecisionModel(a1)))")


;; Reader.read(String|byte[]) on the 5 reader classes routes through
;; {:kind :receiver-call :throws-translate "ParseException"}: the
;; receiver-call template emits the block lambda that catches
;; ParseException and rethrows as RuntimeException. See build_registry's
;; reader-read classify rule.

;; Generic across any writer class with write(Geometry) -> String; resolves
;; API.extract<ClassName> from the entry's :class.


;; Static Collection<Geometry> arg: bespoke (Arrays.asList(extractGeometryArray(a1))).
;; Stays per-shape because the generic template doesn't model the
;; Collection-from-Coord[] adaptation.
(defmethod dispatch-body :static-collection->geom [{:keys [class method]} _]
  (let [call (format "%s.%s(java.util.Arrays.asList(API.extractGeometryArray(a1)))" class method)]
    (format "API.createJSGeometry(JSString.of(%s.getGeometryType()), %s)" call call)))


;; Three generic templates collapse the per-shape vocabulary into the
;; structured-shape map `{:kind :receiver-call | :static-call | :ctor}`;
;; classifier rules in build_registry opt classes in via the
;; *-dispatch-classes sets.

(def ^:private return-type-wrappers
  "Map of return-type FQN -> (fn [expr-string]) that produces the
   JS-side wrap of a Java expression evaluating to that type.

   The Geometry wrap substitutes `expr` twice (once for the type tag,
   once for the value) — every existing :geom->geom / :wrapped->geom
   / :static-geom->geom defmethod does the same. The expressions used
   in dispatch bodies are always pure method calls / ctor calls, so
   the double evaluation is semantics-preserving.

   Wrapped-class returns (Triangle, LineSegment, Vector3D, Plane3D,
   MBC, MDiam, OffsetCurve, ...) aren't enumerated here; `wrap-return`
   falls back to deriving `API.createJS<js-helper-for(class)>(<expr>)`
   for any return type with a known helper. The `strip-api-prefix-from-helpers`
   post-pass on the final dispatch body strips `API.` from any
   helper present in `js-wrapper-helper-names`, so emitting the `API.` prefix
   here keeps the table uniform across primitive and wrapped types."
  {"boolean"                                  (fn [e] (format "JSBoolean.of(%s)" e))
   "int"                                      (fn [e] (format "JSNumber.of(%s)" e))
   "double"                                   (fn [e] (format "JSNumber.of(%s)" e))
   ;; long via JSNumber.of((double) v). Lossy outside the
   ;; JS safe-integer range; JTS long usages stay well within it.
   "long"                                     (fn [e] (format "JSNumber.of((double) %s)" e))
   "void"                                     (fn [e] (format "{ %s; return null; }" e))
   "char"                                     (fn [e] (format "JSString.of(String.valueOf(%s))" e))
   "java.lang.String"                         (fn [e] (format "JSString.of(%s)" e))
   ;; Object return: identity pass-through. Web Image auto-wraps the raw
   ;; Java reference for JS. Only Geometry.getUserData() reaches here
   ;; (other Object returns aren't coercible). Matches the legacy
   ;; :geometry-get-user-data emit.
   "java.lang.Object"                         (fn [e] e)
   "byte[]"                                   (fn [e] (format "API.byteArrayToJSUint8Array(%s)" e))
   ;; primitive-array return wraps. Plain JS arrays of
   ;; numbers / strings (not typed arrays — Coordinate-of-coord style).
   "double[]"                                 (fn [e] (format "API.createJSDoubleArray(%s)" e))
   "int[]"                                    (fn [e] (format "API.createJSIntArray(%s)" e))
   "java.lang.String[]"                       (fn [e] (format "API.createJSStringArray(%s)" e))
   "org.locationtech.jts.geom.Coordinate"     (fn [e] (format "API.createJSCoordinate(%s)" e))
   "org.locationtech.jts.geom.Coordinate[]"   (fn [e] (format "API.createJSCoordinateArray(%s)" e))
   "org.locationtech.jts.geom.Geometry[]"     (fn [e] (format "API.createJSGeometryArray(%s)" e))
   ;; wrapped-class array returns. Both element types are in
   ;; auto-ctor-classes; the helpers in API.java wrap each
   ;; non-null element via API_Generated.createJS<Helper>(elem).
   "org.locationtech.jts.operation.distance.GeometryLocation[]" (fn [e] (format "API.createJSGeometryLocationArray(%s)" e))
   "org.locationtech.jts.linearref.LinearLocation[]"            (fn [e] (format "API.createJSLinearLocationArray(%s)" e))
   ;; generic List/Collection returns. Helpers
   ;; createJS<X>List in API.java iterate the Java list and wrap each
   ;; element through createJS<X>. List and Collection share the same
   ;; helper since the helper just iterates.
   "java.util.List<org.locationtech.jts.geom.Geometry>"
   (fn [e] (format "API.createJSGeometryList(%s)" e))
   "java.util.Collection<org.locationtech.jts.geom.Geometry>"
   (fn [e] (format "API.createJSGeometryList(%s)" e))
   "java.util.List<org.locationtech.jts.geom.Coordinate>"
   (fn [e] (format "API.createJSCoordList(%s)" e))
   "java.util.Collection<org.locationtech.jts.geom.Coordinate>"
   (fn [e] (format "API.createJSCoordList(%s)" e))
   "java.util.List<org.locationtech.jts.geom.LineString>"
   (fn [e] (format "API.createJSLineStringList(%s)" e))
   "java.util.Collection<org.locationtech.jts.geom.LineString>"
   (fn [e] (format "API.createJSLineStringList(%s)" e))
   ;; generic List<Object> pass-through. Items pushed
   ;; straight to the JS array; spatial-index .query() callers get
   ;; back whatever they inserted (typically Geometry wrappers).
   "java.util.List<java.lang.Object>"
   (fn [e] (format "API.createJSObjectList(%s)" e))
   ;; List<QuadEdge> + Collection<Vertex> family for
   ;; QuadEdgeSubdivision return methods. Both helpers iterate the
   ;; Collection and wrap each non-null element via the auto-emitted
   ;; createJSQuadEdge / createJSVertex from API_Generated.
   "java.util.List<org.locationtech.jts.triangulate.quadedge.QuadEdge>"
   (fn [e] (format "API.createJSQuadEdgeList(%s)" e))
   "java.util.Collection<org.locationtech.jts.triangulate.quadedge.QuadEdge>"
   (fn [e] (format "API.createJSQuadEdgeList(%s)" e))
   "java.util.List<org.locationtech.jts.triangulate.quadedge.Vertex>"
   (fn [e] (format "API.createJSVertexList(%s)" e))
   "java.util.Collection<org.locationtech.jts.triangulate.quadedge.Vertex>"
   (fn [e] (format "API.createJSVertexList(%s)" e))
   ;; nested-array list returns. Helpers iterate the outer
   ;; List and wrap each inner array via the matching createJSXArray.
   "java.util.List<org.locationtech.jts.geom.Coordinate[]>"
   (fn [e] (format "API.createJSCoordinateArrayList(%s)" e))
   "java.util.List<org.locationtech.jts.triangulate.quadedge.QuadEdge[]>"
   (fn [e] (format "API.createJSQuadEdgeArrayList(%s)" e))
   "java.util.List<org.locationtech.jts.triangulate.quadedge.Vertex[]>"
   (fn [e] (format "API.createJSVertexArrayList(%s)" e))
   "org.locationtech.jts.geom.Envelope"       (fn [e] (format "API.createJSEnvelope(%s)" e))
   "org.locationtech.jts.geom.CoordinateSequence" (fn [e] (format "API.createJSCoordinateSequence(%s)" e))
   "org.locationtech.jts.geom.IntersectionMatrix" (fn [e] (format "API.createJSIntersectionMatrix(%s)" e))
   "org.locationtech.jts.geom.LineSegment"    (fn [e] (format "API.createJSLineSegment(%s)" e))
   "org.locationtech.jts.geom.Triangle"       (fn [e] (format "API.createJSTriangle(%s)" e))
   "org.locationtech.jts.geom.PrecisionModel" (fn [e] (format "API.createJSPrecisionModel(%s)" e))
   "org.locationtech.jts.math.Vector3D"       (fn [e] (format "API.createJSVector3D(%s)" e))
   "org.locationtech.jts.geom.prep.PreparedGeometry" (fn [e] (format "API.createJSPreparedGeometry(%s)" e))
   ;; GeometryFactory return wrap. createJSGeometryFactory in
   ;; API.java delegates to createJSGeometryFactoryFromInstance.
   "org.locationtech.jts.geom.GeometryFactory"        (fn [e] (format "API.createJSGeometryFactory(%s)" e))
   ;; Geometry uses dynamic getGeometryType() because the runtime
   ;; concrete type is unknown when the static return is Geometry.
   "org.locationtech.jts.geom.Geometry"
   (fn [e] (format "API.createJSGeometry(JSString.of(%s.getGeometryType()), %s)" e e))
   ;; Subtypes hard-code the type literal — the static return type IS the
   ;; runtime type.
   "org.locationtech.jts.geom.Point"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"Point\"), %s)" e))
   "org.locationtech.jts.geom.LineString"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"LineString\"), %s)" e))
   "org.locationtech.jts.geom.LinearRing"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"LinearRing\"), %s)" e))
   "org.locationtech.jts.geom.Polygon"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"Polygon\"), %s)" e))
   "org.locationtech.jts.geom.MultiPoint"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"MultiPoint\"), %s)" e))
   "org.locationtech.jts.geom.MultiLineString"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"MultiLineString\"), %s)" e))
   "org.locationtech.jts.geom.MultiPolygon"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"MultiPolygon\"), %s)" e))
   "org.locationtech.jts.geom.GeometryCollection"
   (fn [e] (format "API.createJSGeometry(JSString.of(\"GeometryCollection\"), %s)" e))})

(defn- wrap-return
  "Wrap an inner Java expression `expr` in the appropriate JS-side
   coercion / createJS<X> call for `return-type`. Falls back to
   `API.createJS<js-helper-for(return-type)>(<expr>)` for any wrapped
   class not listed in `return-type-wrappers`."
  [return-type expr]
  (if-let [f (get return-type-wrappers return-type)]
    (f expr)
    (format "API.createJS%s(%s)" (js-helper-for return-type) expr)))

(defn- coerce-args-from
  "Like `coerce-args` but starts the lambda-parameter index at
   `start-idx` instead of 1. Receiver-call dispatch uses `start-idx`
   2 (a1 is the receiver; explicit params begin at a2). Static-call
   and ctor dispatch use `start-idx` 1."
  [params start-idx]
  (->> params
       (map-indexed (fn [i p] (coerce-arg-expr (+ start-idx i) p)))
       (str/join ", ")))

(defmethod dispatch-body :receiver-call
  [{:keys [class method params]} {:keys [returns generic-params param-override shape]}]
  ;; prefer :generic-params (auto-captured + manual elem-type
  ;; merged in build_registry) over :params for the per-param coercer
  ;; choice, and prefer :returns :generic-type over :returns :type for
  ;; wrap-return. Non-generic entries are unaffected (or-fallback).
  ;; :param-override (0-indexed position -> type FQN, from manual.edn :hints)
  ;; is applied last so equals(Object) / compareTo(Object) route through
  ;; extractGeometry(aN) instead of the (Object) aN fallback.
  ;; :return-null-safe? on the shape map post-processes the wrap to
  ;; the OrNull variant — covers Geometry.getCoordinate() and the three
  ;; LineSegment intersection/projection methods that return null at
  ;; the JTS level.
  ;; :throws-translate <SimpleException> wraps the call in a block lambda
  ;; that catches the checked org.locationtech.jts.io.<Exception> and
  ;; rethrows as RuntimeException — covers the 5 reader.read(String|byte[])
  ;; entries (Fn2 doesn't declare throws).
  (let [helper          (js-helper-for class)
        base-params      (or generic-params params)
        effective-params (cond->> base-params
                           param-override
                           (map-indexed (fn [i p] (get param-override i p))))
        extra-args      (coerce-args-from effective-params 2)
        effective-ret    (or (:generic-type returns) (:type returns))
        ;; Emit the API. prefix uniformly; `strip-api-prefix-from-helpers` strips
        ;; it for helpers in `js-wrapper-helper-names` and leaves it for ones
        ;; that aren't (e.g. Coordinate). This matches the per-shape
        ;; defmethods' output across both cases.
        call-expr  (format "API.extract%s(a1).%s(%s)" helper method extra-args)]
    (cond
      ;; Bind the call to a local `g`, wrap it, and translate the checked
      ;; exception. wrap-return on "g" produces the same body the per-shape
      ;; emitted (Geometry double-substitutes `g` for type tag + value).
      (:throws-translate shape)
      (format (str "{ try { %s g = %s; return %s; } "
                   "catch (org.locationtech.jts.io.%s e) { "
                   "throw new RuntimeException(\"%s parse error: \" + e.getMessage(), e); } }")
              effective-ret call-expr (wrap-return effective-ret "g")
              (:throws-translate shape) (simple-name class))

      (:return-null-safe? shape)
      (str/replace (wrap-return effective-ret call-expr)
                   #"API\.createJS([A-Z]\w+)\("
                   "API.createJS$1OrNull(")

      :else
      (wrap-return effective-ret call-expr))))

(defmethod dispatch-body :static-call
  [{:keys [class method params]} {:keys [returns generic-params]}]
  (let [effective-params (or generic-params params)
        effective-ret    (or (:generic-type returns) (:type returns))
        call-expr (format "%s.%s(%s)" class method (coerce-args-from effective-params 1))]
    (wrap-return effective-ret call-expr)))

(defmethod dispatch-body :ctor
  [{:keys [class params]} {:keys [generic-params]}]
  ;; ctors mirror :receiver-call / :static-call in preferring
  ;; effective generic params over erased — future promotion to the auto-ctor sets
  ;; with generic Collection<X> ctors land directly.
  (let [helper           (js-helper-for class)
        effective-params (or generic-params params)]
    ;; Emit `API.createJS<helper>` uniformly; strip-api-prefix-from-helpers strips
    ;; the API. prefix for helpers in js-wrapper-helper-names (so the auto-emitted
    ;; helpers in API_Generated.java are called unqualified) and leaves
    ;; it for hand-written helpers in API.java (Coordinate, GeometryFactory).
    (format "API.createJS%s(new %s(%s))" helper class (coerce-args-from effective-params 1))))


(defn- effective-arity
  "How many arguments the JS-side wrapper actually receives. Instance
   methods get an implicit receiver as the first argument (so arity =
   params + 1). Static methods and constructors take only their
   declared params."
  [[k v]]
  (+ (count (:params k))
     (if (:static? v) 0 1)))

(defn- dedup-by-path
  "When two in-scope entries install at the same JS path (Geometry.union()
   vs Geometry.union(Geometry), etc.), keep the highest-arity variant.
   Same-arity ties between Geometry base and a geometry-subtype prefer
   the Geometry base — JS dispatch is polymorphic on the JTS object, so
   the Geometry-base lambda handles every subtype via extractGeometry.

   Returns [resolved-entries warnings] where warnings is a vec of
   {:path s, :kept k, :dropped [k ...]} describing each collision."
  [entries]
  (let [grouped (group-by (fn [[k v]] (js-path k (:js-path v))) entries)
        warnings (atom [])]
    [(->> grouped
          (map (fn [[path bucket]]
                 (if (= 1 (count bucket))
                   (first bucket)
                   (let [sorted  (sort-by (fn [[k _]]
                                            [(count (:params k))
                                             (if (= (:class k) "org.locationtech.jts.geom.Geometry") 1 0)])
                                          bucket)
                         kept    (last sorted)
                         dropped (butlast sorted)]
                     (swap! warnings conj
                            {:path    path
                             :kept    (first kept)
                             :dropped (mapv first dropped)})
                     kept))))
          (sort-by (fn [[k _]] [(:class k) (:method k) (:params k)])))
     @warnings]))


(def ^:private header
  "/*
 * AUTO-GENERATED by script/emit_api.clj from registry.edn.
 *
 * Do not edit by hand. Run `bb gen:api` to regenerate.
 *
 * Wired into API.main() as the LAST registration call so its installs
 * overwrite hand-written exports at any shared JS path. Methods where
 * the auto-gen and hand-written signatures actually diverge are kept
 * out of scope via manual.edn :skip (see equalsExact for the canonical
 * case).
 *
 * Calls API.extractGeometry / API.createJSGeometry — both are
 * package-private helpers in API.java; do not duplicate them here.
 */

package net.willcohen.wasmts;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSBoolean;
import org.graalvm.webimage.api.JSNumber;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.graalvm.webimage.api.JSValue;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.Triangle;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.math.Plane3D;
import org.locationtech.jts.algorithm.MinimumBoundingCircle;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.IntersectionMatrix;

public class API_Generated {
")

(defn- parent-namespaces
  "Given the in-scope entries, return the sorted set of intermediate
   namespace paths that need to exist before per-entry @JS lines can
   assign into them. `wasmts.geom.Envelope.getMinX` requires both
   `wasmts.geom` and `wasmts.geom.Envelope`. Hand-written API.java
   creates `wasmts.geom` and `wasmts.algorithm`, but newer packages
   (`wasmts.math`, `wasmts.quadedge`, ...) aren't bootstrapped anywhere
   else — without them, `wasmts.math.MathUtil = ...` throws TypeError
   because `wasmts.math` is undefined, and the entire setupNamespaces
   block fails silently. Including depth-2 namespaces is idempotent
   (`||= {}`), so re-asserting `wasmts.geom` is harmless."
  [entries]
  (->> entries
       (map (fn [[k v]] (js-path k (:js-path v))))
       (mapcat (fn [path]
                 ;; "wasmts.a.b.c.d" -> ["wasmts.a", "wasmts.a.b", "wasmts.a.b.c"]
                 (let [parts (str/split path #"\.")]
                   (for [n (range 1 (dec (count parts)))]
                     (str/join "." (take (inc n) parts))))))
       (into (sorted-set))))

(defn- namespace-setup-decl
  "Emit a single native that ensures every intermediate namespace exists
   before any register call assigns into it. Idempotent — uses `||= {}`.
   Package-private so API.java can call it at the top of main() to set up
   `globalThis.wasmts` and the depth-1+ namespaces before the hand-written
   exports run. The first body statement is `globalThis.wasmts = ... || {}`
   so the rest of the namespace assignments (bare `wasmts.x = ...`) have a
   live binding to write into."
  [namespaces]
  (let [body (->> (cons "globalThis.wasmts" namespaces)
                  (map #(format "%s = %s || {};" % %))
                  (str/join " "))]
    (format
     (str "    @JS(\"%s\")\n"
          "    static native void setupNamespaces();\n")
     body)))

(defn- wire-decl
  "Emit a `static native void wire<Name>Methods(JSObject obj)` whose @JS
   body attaches one shim per in-scope instance method whose receiver
   matches `class-pred` (a predicate over the entry's `:class`). For
   the Geometry wire, pass `geometry-subtypes` so methods declared on
   subtypes (Polygon.getExteriorRing, LineString.isClosed, etc.) come
   along — they install at the collapsed `wasmts.geom.*` paths and
   belong on the same JS handle.

   The createJS<Name> wrapper in API.java calls this to replace ~50
   lines of hand-written shims with one auto-gen line. Hand-written
   specials (polymorphic dispatch, default args, JS-name aliases) live
   in a separate `attach*` @JS native in API.java and run after the
   wires."
  [name class-pred resolved]
  (let [shims (->> resolved
                   (filter (fn [[k v]]
                             (and (class-pred (:class k))
                                  (not (:static? v)))))
                   (map (fn [[k v]]
                          (let [path (js-path k (:js-path v))
                                method-name (last (str/split path #"\."))]
                            (format "obj.%s = (...args) => %s(obj, ...args);"
                                    method-name path)))))]
    (when (seq shims)
      (format (str "    @JS(\"\"\"%n"
                   "        %s%n"
                   "    \"\"\")%n"
                   "    static native void wire%sMethods(JSObject obj);%n")
              (str/join "\n        " shims) name))))

(defn- wrapped-class-decl
  "Emit `createJS<X>Raw` + `createJS<X>` + `extract<X>` for one
   `js-wrapper-classes` entry. Slots beyond the basics:

     :wire?         when true, createJS<X> calls wire<X>Methods(obj)
                    after the raw wrap.
     :attach?       when true, createJS<X> calls API.attach<X>Overrides(obj)
                    after the wire (cross-class call — attach<X>Overrides
                    bodies are irreducibly hand-written and live in
                    API.java; this slot lets the wire / auto-ctor template
                    cover the four attach-override classes too: Geometry,
                    CoordinateSequence, Envelope, IntersectionMatrix).
     :extra-params  optional vector of {:type :name} maps prepended to
                    the createJS<X>Raw / createJS<X> Java parameter
                    list. Each extra param's JS-side identity passes
                    through to the @JS body unchanged (`name: name`) and
                    becomes the public JS field on the wrapper. The
                    main param is always called `x` in the emitted
                    code; the @JS body ends with `<js-field>: x`. Used
                    for Geometry, whose signature is `createJSGeometry(
                    JSString type, Geometry x)` with @JS body
                    `{type: type, _jtsGeom: x}`. Entries without
                    `:extra-params` degenerate to the single-arg form
                    that 24 of the 28 wrapped classes use.

   entries with `:wire?` true (`:wire?` true) depend on the corresponding
   `wire<X>Methods` declaration being emitted earlier in this file; if
   every method on a wrapped class gets `:skip`'d in manual.edn the
   wire method disappears and the createJS<X> call site fails to
   compile. Latent fragility rather than a real risk today."
  [{:keys [class js-field helper wire? attach? extra-params]}]
  (let [extras       (or extra-params [])
        java-params  (str/join ", " (concat (for [{:keys [type name]} extras]
                                              (str type " " name))
                                            [(str class " x")]))
        js-body      (str/join ", " (concat (for [{:keys [name]} extras]
                                              (str name ": " name))
                                            [(str js-field ": x")]))
        call-args    (str/join ", " (concat (map :name extras) ["x"]))
        wire-line    (if wire?
                       (format "        wire%sMethods(obj);%n" helper)
                       "")
        attach-line  (if attach?
                       (format "        API.attach%sOverrides(obj);%n" helper)
                       "")]
    (format
     (str "    @JS(\"return { %s };\")%n"
          "    static native JSObject createJS%sRaw(%s);%n"
          "%n"
          "    static JSObject createJS%s(%s) {%n"
          "        JSObject obj = createJS%sRaw(%s);%n"
          "%s"
          "%s"
          "        return obj;%n"
          "    }%n"
          "%n"
          "    static %s extract%s(Object obj) {%n"
          "        if (obj instanceof %s) {%n"
          "            return (%s) obj;%n"
          "        }%n"
          "        JSObject jsObj = (JSObject) obj;%n"
          "        return jsObj.get(\"%s\", %s.class);%n"
          "    }%n")
     js-body
     helper java-params
     helper java-params
     helper call-args
     wire-line
     attach-line
     class helper
     class
     class
     js-field class)))

(defn- subtype-extractor-decl
  "Emit `extract<Subtype>(Object)` for one geometry-subtype-extractors entry.
   The body extracts a Geometry via `extractGeometry` (unqualified,
   same file), instanceof-checks the result, throws on mismatch, and
   casts. Same contract the hand-written extractors used."
  [{:keys [class helper]}]
  (let [simple (last (str/split class #"\."))]
    (format
     (str "    static %s extract%s(Object obj) {%n"
          "        Geometry g = extractGeometry(obj);%n"
          "        if (!(g instanceof %s)) {%n"
          "            throw new IllegalArgumentException(\"expected %s, got \" + g.getGeometryType());%n"
          "        }%n"
          "        return (%s) g;%n"
          "    }%n")
     class helper class simple class)))

(defn emit-file
  "Returns [java-source warnings]. Caller decides what to do with warnings."
  [registry]
  (let [[resolved warnings] (dedup-by-path (filter in-scope? registry))
        ;; Seed with 0..5 so API.java's hand-written exports (Fn5 for
        ;; buffer/offsetCurve, plus any arity not currently registry-used)
        ;; can reference FnN without redeclaring.
        arities    (->> resolved (map effective-arity) (into (sorted-set 0 1 2 3 4 5)))
        namespaces (parent-namespaces resolved)
        register-stmts
        (for [[k v :as entry] resolved
              :let [shape   (:shape v)
                    field-kind (case shape
                                 :static-int-field    "Int"
                                 :static-char-field   "Char"
                                 :static-double-field "Double"
                                 nil)
                    body    (when-not field-kind
                              (some-> (dispatch-body k v) strip-api-prefix-from-helpers))]
              :when (or field-kind body)]
          (if field-kind
            ;; emit a plain constant install (no lambda). Doubles route
            ;; through `format-const-double` because Java's source
            ;; representation of NaN / +/-Infinity is `Double.NaN` etc.,
            ;; not the bare token Clojure prints.
            (format "        installConstant%s(\"%s\", %s);"
                    field-kind
                    (js-path k (:js-path v))
                    (if (= field-kind "Double")
                      (cond
                        (Double/isNaN (:value v)) "Double.NaN"
                        (Double/isInfinite (:value v))
                        (if (pos? (:value v))
                          "Double.POSITIVE_INFINITY"
                          "Double.NEGATIVE_INFINITY")
                        :else (:value v))
                      (:value v)))
            (let [arity (effective-arity entry)
                  path  (js-path k (:js-path v))
                  ;; Java requires parens around typed lambda params at every
                  ;; arity, including arity 0 ( () -> ... ) and arity 1
                  ;; ( (Object a1) -> ... ).
                  args  (->> (range arity)
                             (map #(str "Object a" (inc %)))
                             (str/join ", "))]
              (format "        installFn%d(\"%s\", (%s) -> %s);" arity path args body))))
        sb (StringBuilder.)]
    (.append sb header)
    (.append sb "\n    // ---- per-arity functional interfaces ----\n\n")
    (doseq [n arities] (.append sb (fn-interface-decl n)))
    (.append sb "\n    // ---- namespace bootstrap ----\n\n")
    (.append sb (namespace-setup-decl namespaces))
    (.append sb "\n    // ---- generic per-arity installers ----\n\n")
    (doseq [n arities] (.append sb (installer-decl n)))
    (.append sb "\n    // ---- constant installers ----\n\n")
    (.append sb constant-installer-decls)
    (.append sb "\n    // ---- instance-method wires ----\n\n")
    (.append sb (or (wire-decl "Geometry" geometry-subtypes resolved) ""))
    (.append sb (or (wire-decl "CoordinateSequence"
                               #{"org.locationtech.jts.geom.CoordinateSequence"}
                               resolved) ""))
    (.append sb (or (wire-decl "Envelope"
                               #{"org.locationtech.jts.geom.Envelope"}
                               resolved) ""))
    (.append sb (or (wire-decl "IntersectionMatrix"
                               #{"org.locationtech.jts.geom.IntersectionMatrix"}
                               resolved) ""))
    (.append sb (or (wire-decl "PrecisionModel"
                               #{"org.locationtech.jts.geom.PrecisionModel"}
                               resolved) ""))
    ;; Reader / writer fluent shims. Each createJS<Name> in API.java
    ;; calls wire<Name>Methods to install instance-method shims (e.g. `.read`,
    ;; `.write`, `.setEncodeCRS`) on the wrapped JS handle. Replaces the
    ;; hand-written `r.read = (...args) => ...` blocks. Any new auto-gen
    ;; install on these classes auto-propagates to the fluent JS handle.
    (doseq [[wire-name fqn]
            [["GeoJsonReader" "org.locationtech.jts.io.geojson.GeoJsonReader"]
             ["GeoJsonWriter" "org.locationtech.jts.io.geojson.GeoJsonWriter"]
             ["WKTReader"     "org.locationtech.jts.io.WKTReader"]
             ["WKBReader"     "org.locationtech.jts.io.WKBReader"]
             ["WKTWriter"     "org.locationtech.jts.io.WKTWriter"]
             ["GMLWriter"     "org.locationtech.jts.io.gml2.GMLWriter"]
             ["KMLWriter"     "org.locationtech.jts.io.kml.KMLWriter"]
             ["KMLReader"     "org.locationtech.jts.io.kml.KMLReader"]
             ["TWKBReader"    "org.locationtech.jts.io.twkb.TWKBReader"]
             ["WKBWriter"     "org.locationtech.jts.io.WKBWriter"]]]
      (.append sb (or (wire-decl wire-name #{fqn} resolved) "")))
    (.append sb "\n    // ---- wrapped-class wrappers ----\n\n")
    (doseq [entry js-wrapper-classes]
      (.append sb (wrapped-class-decl entry))
      (.append sb "\n"))
    (.append sb "\n    // ---- geometry subtype extractors ----\n\n")
    (doseq [entry geometry-subtype-extractors]
      (.append sb (subtype-extractor-decl entry))
      (.append sb "\n"))
    (.append sb "\n    // ---- entry point ----\n\n")
    (.append sb "    public static void register() {\n")
    (.append sb "        setupNamespaces();\n")
    (doseq [s register-stmts] (.append sb s) (.append sb "\n"))
    (.append sb "    }\n}\n")
    [(.toString sb) warnings]))


(def ^:private output-path
  "java/src/main/java/net/willcohen/wasmts/API_Generated.java")

(defn -main [& _]
  (let [registry         (edn/read-string (slurp "registry.edn"))
        in-scope         (filter in-scope? registry)
        [file warnings]  (emit-file registry)]
    (io/make-parents output-path)
    (spit output-path file)
    (println "Wrote" output-path)
    (println "  in-scope shapes:    " (count in-scope) "of" (count registry))
    (let [shape-label (fn [s] (cond (keyword? s) (name s)
                                    (map? s)     (name (:kind s))
                                    :else        (str s)))
          by-shape    (->> in-scope (map (comp :shape second)) (map shape-label) frequencies (into (sorted-map)))]
      (doseq [[s n] by-shape]
        (println (format "  %-22s %4d" s n))))
    (when (seq warnings)
      (println "\nJS path collisions resolved (kept highest arity):")
      (doseq [{:keys [path kept dropped]} warnings]
        (println (format "  %s" path))
        (println (format "    kept    %s" (pr-str kept)))
        (doseq [d dropped]
          (println (format "    dropped %s" (pr-str d))))))))
