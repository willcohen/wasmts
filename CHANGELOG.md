# Changelog

All notable changes to this project will be documented in this file. This change
log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added

- `dist/wasmts.d.ts` carries the JTS documentation as TSDoc, so editors
  show argument meanings on hover.
- `bb check:javadoc-links` checks each `@see` anchor against the
  published javadoc. Needs network access, so it is not in `bb gen:all`
  or CI.

### Changed

- Wrapper methods moved to one shared prototype per wrapped type
  (`wasmts._protos.*`). A wrapper instance now carries only data
  properties (the Java handle, `type` on Geometry, `x`/`y`/`z`/`m` on
  Coordinate) instead of ~80 per-instance method closures. Behavior
  changes:
  - Detached method references (`const f = g.buffer; f(1)`) throw. Write
    `() => g.isEmpty()`, `g.isEmpty.bind(g)`, or
    `wasmts.geom.buffer(g, 1)`.
  - `Object.keys(wrapper)` and spread see only data properties;
    `for...in` still sees the methods.
  - Assignment to `wasmts._protos.<Type>` extends every wrapper of
    that type.
- `bb test` and `clj-kondo` block CI now.
- The buffer, simplifier and closest-point methods are back under
  differential test against JVM JTS, and the `Coordinate[]` comparison
  now covers Z. Both are test-suite changes; no shipped behavior moved.

### Fixed

- `dist/wasmts.d.ts` type-checks. It referenced `CoordinateSequence`,
  `Densifier`, `GeometryFixer` and `GeometryPrecisionReducer` without
  declaring them.

## [0.1.0-alpha6] - 2026-07-22

Adds a typed-array geometry surface for consumers holding coordinates in flat
buffers, makes the spatial indexes usable from JavaScript, and surfaces Java
exceptions as real JS `Error`s. Test coverage of the generated surface is still
growing; this remains an alpha.

### Added

- Flat-buffer geometry surface (`wasmts.geom.fromFlat`, `toFlat`,
  `getCoordinatesFlat`) for building and extracting geometries through
  `Float64Array` / `Int32Array` buffers, skipping the GeoJSON serialize/parse
  round trip. `dim` selects the ordinates that carry meaning (2/3/4); `stride`
  sets the step between coordinates with zero-filled padding. Malformed input (a
  buffer length that is not a multiple of `dim`, or offsets that are absent, out
  of range, or out of order) is rejected with a contract error.
- Bulk coordinate and byte transfer across the JS<->WASM boundary: a JS numeric
  array fills a Java primitive array in one crossing rather than per element.
- Spatial indexes usable from JavaScript: `STRtree` / `Quadtree` `query`
  (returns a JS array of the inserted items), `queryVisit` (a per-match
  callback), and the `STRtree.nearestNeighbour` variants; `Polygonizer`
  `getPolygons` and the diagnostic accessors (`getDangles`, `getCutEdges`,
  `getInvalidRingLines`).
- `GeometryFactory.buildGeometry` over a JS array of geometries.
- Java exceptions crossing to JavaScript surface as real `Error`s (`.message`,
  `instanceof Error`), structured-clone safe so a caught error can cross a
  worker / `postMessage` boundary; the original throwable is kept on
  `.javaError` and a `.getMessage()` compatibility shim is retained.

### Changed

- `applyCoordinates` (experimental, since alpha4) now takes a `stride` in place
  of `valuesPerCoord`. It pairs with `getCoordinatesFlat`: a buffer written at a
  given stride reads back at the same stride.
- The WebAssembly image is built from the graal-pinned labsjdk (fetched with
  `mx fetch-jdk` per the graal submodule's `common.json`), and `mvn package`
  assembles the consumable `dist/` package.

## [0.1.0-alpha5] - 2026-06-24

The API is now code-generated from a JTS reflection registry. Test coverage of
the generated surface is still growing; this remains an alpha.

### Added

- Generated API surface across the algorithm, coverage, densify, geom, index,
  io, math, operation, and precision packages, emitted from a JTS reflection
  registry along with the TypeScript declarations (`wasmts.d.ts`).
- Generative differential test suite that cross-checks the WebAssembly build
  against JVM JTS with property-based tests.

### Changed

- Replaced the hand-maintained JavaScript bridge with code generated from the
  registry.

## [0.1.0-alpha4] - 2026-03-11

### Added

- EXPERIMENTAL, may be removed: CoordinateSequenceFilter: add an additional `applyCoordinates()` function to streamline operations

## [0.1.0-alpha3] - 2026-03-08

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

[Unreleased]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha6...HEAD
[0.1.0-alpha6]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha5...0.1.0-alpha6
[0.1.0-alpha5]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha4...0.1.0-alpha5
[0.1.0-alpha4]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha3...0.1.0-alpha4
[0.1.0-alpha3]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha2...0.1.0-alpha3
[0.1.0-alpha2]: https://github.com/willcohen/wasmts/compare/0.1.0-alpha1...0.1.0-alpha2
[0.1.0-alpha1]: https://github.com/willcohen/wasmts/releases/tag/0.1.0-alpha1
