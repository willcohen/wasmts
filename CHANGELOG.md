# Changelog

All notable changes to this project will be documented in this file. This change
log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added

- Geometry base class: `getDimension()`, `getBoundaryDimension()`, `relate()`, `equalsExact()`, `equalsNorm()`, `isWithinDistance()`, `getSRID()`/`setSRID()`, `union()` (no-arg), `getCoordinate()`, `getFactory()`, `getPrecisionModel()`, `norm()`, `compareTo()`
- Point: `getX()`, `getY()`
- LineString/LinearRing: `getPointN()`, `getStartPoint()`, `getEndPoint()`, `isClosed()`, `isRing()`, `getCoordinateSequence()`
- GeometryFactory: `createLinearRing()`, `createMultiPoint()`, `createMultiLineString()`, `createMultiPolygon()`, `createGeometryCollection()`, `createEmpty()`, `toGeometry()`
- Envelope: `getMinX()`, `getMaxX()`, `getMinY()`, `getMaxY()`, `getWidth()`, `getHeight()`, `getArea()`, `centre()`, `expandBy()`, `expandToInclude()`, `expandToIncludeEnvelope()`, `intersection()`, `covers()`, `coversXY()`, `disjoint()`, `distance()`, `isNull()`, `setToNull()`, `copy()`, `translate()`
- IntersectionMatrix: full DE-9IM class with constructor, predicates, and mutation methods
- Dimension: constants (`P`, `L`, `A`, `FALSE`, `TRUE`, `DONTCARE`) and `toDimensionSymbol()`/`toDimensionValue()`
- GeoJSON: full 1:1 API - `GeoJSONWriter.create()`, `createWithDecimals()`, `setEncodeCRS()`, `setForceCCW()`, `write()`; `GeoJSONReader.create()`, `read()`
- PreparedGeometry: all 11 predicates (`contains`, `containsProperly`, `covers`, `coveredBy`, `crosses`, `disjoint`, `intersects`, `overlaps`, `touches`, `within`, `getGeometry`)
- MinimumDiameter: `getLength()` returns the minimum width
- CoordinateSequence wrapper: `getX(i)`, `getY(i)`, `getZ(i)`, `getM(i)`, `getOrdinate(i, ord)`, `setOrdinate(i, ord, value)`, `getDimension()`, `getMeasures()`, `hasZ()`, `hasM()`, `size()`, `getCoordinate(i)`, `toCoordinateArray()`, `copy()`
- CoordinateSequenceFilter: `geometry.apply(filter)` - filter receives `(seq, i)` matching JTS pattern
- Densifier: `densify(geom, tolerance)` static method and instance API (`create`, `setDistanceTolerance`, `setValidate`, `getResultGeometry`)
- GeometryFixer: `fix(geom)`, `fix(geom, isKeepMulti)` static methods and instance API (`create`, `setKeepCollapsed`, `setKeepMulti`, `getResult`)
- CoverageUnion: `union(geometries)` for fast union of non-overlapping adjacent polygons
- PrecisionModel: `create()`, `create(type)`, `createFixed(scale)`, `getType()`, `getScale()`, `isFloating()`, `makePrecise()`, `getMaximumSignificantDigits()`, `gridSize()`
- GeometryPrecisionReducer: `reduce(geom, pm)`, `reduceKeepCollapsed()`, `reducePointwise()` static methods and instance API (`create`, `setChangePrecisionModel`, `setPointwise`, `setRemoveCollapsedComponents`, `reduceInstance`)

### Changed

- Build uses graal git submodule for bug fixes not yet in GraalVM releases

## [0.1.0-alpha2] - 2025-12-05

### Added

- Geometry factory: `createPoint()`, `createLineString()`, `createPolygon()` from coordinate arrays
- GeoJSON I/O: `GeoJSONReader.read()`, `GeoJSONWriter.write()`
- Polygon accessors: `getExteriorRing()`, `getInteriorRingN()`, `getNumInteriorRing()`
- Distance: `nearestPoints()` returns closest points between geometries
- MinimumBoundingCircle: `getCircle()`, `getCentre()`, `getRadius()`

## [0.1.0-alpha1] - 2025-10-16

### Initial Release

Proof of concept: JTS Topology Suite 1.20.0 compiled to WebAssembly using GraalVM Native Image with web-image backend (GraalVM 26 EA).

- Basic geometry operations and spatial predicates
- STRtree spatial indexing
- WKT/WKB I/O with 2D/3D/4D coordinate support
- Object-oriented and functional JavaScript APIs
- Browser and Node.js compatible
- Interactive demo with Monaco editor

[Unreleased]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha3...HEAD
[0.1.0-alpha3]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha1...0.1.0-alpha2
