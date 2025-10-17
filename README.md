# WasmTS

Spatial operations and computational geometry for WebAssembly.

A WebAssembly port of [JTS (Java Topology Suite)](https://github.com/locationtech/jts) using [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/) with [web-image backend](https://github.com/oracle/graal/tree/master/web-image) (GraalVM 26 Early Access preview).

**[Try the interactive demo →](https://willcohen.github.io/wasmts/)**

## Status

**Current**: JTS 1.20.0 compiled to WebAssembly with JavaScript API

**Note**: The Java wrapper (`API.java`) currently exposes only a partial set of JTS operations - commonly-used geometry operations, predicates, and spatial indexing. The underlying WASM binary includes the full JTS library. Additional JTS features can be added by extending the wrapper, though care is needed to properly handle the Java-JavaScript interop boundary (type conversions, object wrapping, etc.).

Currently available:
- **Object-oriented API** - Call methods directly on geometries: `point.buffer(10)`, `poly.union(other)`
- **Functional API** - Also available: `wasmts.geom.buffer(point, 10)`
- WASM binary compiled from JTS source
- Automatic memory management via JavaScript GC
- Geometry types: Point, LineString, Polygon, Multi*, GeometryCollection
- Geometry operations: buffer, union, intersection, difference, convexHull, simplify, copy, reverse, normalize
- Geometry properties: getBoundary, getCentroid, getEnvelope, getInteriorPoint
- Advanced buffering with custom parameters (cap/join styles, erosion)
- Offset curves for parallel line generation via OffsetCurveBuilder
- LineMerger for combining connected linestrings
- CascadedPolygonUnion for efficient multi-polygon union
- Spatial predicates: contains, intersects, touches, crosses, within, overlaps, covers, coveredBy, equalsTopo
- Validation: isSimple, isRectangle, isEmpty, isValid
- 2D, 3D (XYZ), and 4D (XYZM) coordinate support
- PreparedGeometry for optimized repeated predicates
- Geometry analysis algorithms (minimum bounding rectangles and circles)
- STRtree spatial indexing
- WKT and WKB I/O with 3D/4D support
- User data attachment (getUserData/setUserData)

**API Design**: The functional API (`wasmts.geom.*`) is the primary implementation. The OO API (`geom.buffer()`) is syntactic sugar that delegates to the functional API.

## Quick Start

### Prerequisites

- [GraalVM 26 EA](https://www.graalvm.org/downloads/) (26-dev+13.1 or later)
- Node.js 24+ (for testing, optional)

### Build

**Prerequisites:**
- [GraalVM 26 EA](https://www.graalvm.org/downloads/) with `native-image` installed
- Maven 3.6+ (or use the provided wrapper)

**Build command:**

```bash
# Clone the repository
git clone <your-repo-url> wasmts
cd wasmts

# Build WASM with Maven (downloads all dependencies automatically)
mvn clean package
```

**What Maven does:**
1. Downloads JTS 1.20.0 from Maven Central
2. Downloads GraalVM webimage-preview API
3. Compiles Java source code
4. Runs `native-image --tool:svm-wasm` to build WebAssembly
5. Copies output files to project root

**Output:**
- `dist/wasmts.js` - WASM loader (107KB)
- `dist/wasmts.js.wasm` - WASM binary (5.7MB)
- `target/wasmts.js.wat` - WebAssembly text format (debug only)

### Usage in Node.js

```javascript
import('wasmts').then(() => {
    // Wait for WASM to initialize
    setTimeout(() => {
        // Create geometries
        const point = wasmts.geom.createPoint(5, 10);
        const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');

        // Operations
        const buffered = point.buffer(50);
        const intersection = poly.intersection(buffered);

        // Results
        console.log('Buffer area:', buffered.getArea().toFixed(2));
        console.log('Intersection area:', intersection.getArea().toFixed(2));
        console.log('Contains point:', poly.contains(point));
    }, 1000);
});
```

### Usage in Browser

The generated WASM works in **any modern browser** with no modifications needed.

**Method 1: Serve locally with a simple HTTP server:**

```bash
# Python 3
python3 -m http.server 8000

# Node.js
npx http-server -p 8000

# Then open: http://localhost:8000/docs/
```

**Method 2: Use in your web app:**

```html
<!DOCTYPE html>
<html>
<head>
    <title>WasmTS Browser App</title>
</head>
<body>
    <!-- Load the WASM module -->
    <script src="wasmts.js"></script>

    <script>
        // Wait for WASM to initialize
        function waitForWasmTS() {
            if (typeof wasmts !== 'undefined' && wasmts.geom) {
                // WASM is ready
                runApp();
            } else {
                // Check again in 100ms
                setTimeout(waitForWasmTS, 100);
            }
        }
        waitForWasmTS();

        function runApp() {
            // Create geometries
            const point = wasmts.geom.createPoint(5, 10);
            const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');

            // Operations using OO API
            const buffered = point.buffer(50);
            const intersection = poly.intersection(buffered);

            // Display results
            // Note: toFixed(2) rounds to 2 decimal places
            console.log('Buffer area:', buffered.getArea().toFixed(2));
            console.log('Intersection area:', intersection.getArea().toFixed(2));
            console.log('Contains point:', poly.contains(point));

            // Use STRtree for spatial indexing
            const index = wasmts.index.strtree.STRtree.create();
            const envelope = buffered.getEnvelopeInternal();
            wasmts.index.strtree.STRtree.insert(index, envelope, {id: 1, name: 'buffer'});

            const results = wasmts.index.strtree.STRtree.query(index, envelope);
            console.log('Found:', results.length, 'geometries');
        }
    </script>
</body>
</html>
```

**Important notes**:
- Use `<script src="wasmts.js"></script>` (NOT `import()`) so the loader can find the `.wasm` file
- WASM initialization is asynchronous - wait for `wasmts` namespace before calling functions
- Both `wasmts.js` and `wasmts.js.wasm` must be in the same directory
- See [docs/index.html](docs/index.html) for a complete working example

**Interactive demo**: Open [docs/index.html](docs/index.html) to test geometry creation, operations, and spatial indexing.

**Browser compatibility**:
- Chrome/Edge 90+
- Firefox 89+
- Safari 15.4+
- Any browser with WebAssembly + ES6 modules support

### API Examples

**Basic Usage:**

```javascript
// Create 2D point
const point = wasmts.geom.createPoint(5, 10);
console.log('Point created');

// Create polygon from WKT
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
console.log('Polygon area:', poly.getArea());

// Buffer operation
const buffered = point.buffer(5);
console.log('Buffer area:', buffered.getArea().toFixed(2));

// Spatial predicates
console.log('Intersects:', poly.intersects(point));
console.log('Contains:', poly.contains(point));

// Boolean operations
const union = poly.union(buffered);
const intersection = poly.intersection(buffered);
console.log('Union area:', union.getArea().toFixed(2));
```

**3D/4D Coordinates:**

```javascript
// Create 3D point (XYZ - with elevation)
const point3d = wasmts.geom.createPoint(5, 10, 15);
const coords = point3d.getCoordinates();
console.log('3D Point:', coords[0]); // {x: 5, y: 10, z: 15}

// Create 4D point (XYZM - with measure)
const point4d = wasmts.geom.createPoint(5, 10, 15, 20);
const coords4d = point4d.getCoordinates();
console.log('4D Point:', coords4d[0]); // {x: 5, y: 10, z: 15, m: 20}

// Create 3D LineString from WKT
const line3d = wasmts.io.WKTReader.read('LINESTRING Z (0 0 0, 1 1 10, 2 0 20)');
const lineCoords = line3d.getCoordinates();
console.log('Elevation at point 1:', lineCoords[1].z); // 10

// WKB preserves all dimensions
const wkb = wasmts.io.WKBWriter.write(point4d);
const parsed = wasmts.io.WKBReader.read(wkb);
const parsedCoords = parsed.getCoordinates();
console.log('Round-trip preserved M:', parsedCoords[0].m); // 20
```

**WKT/WKB I/O:**

```javascript
// WKT - human readable text format
const wkt = wasmts.io.WKTWriter.write(poly);
console.log('WKT:', wkt); // "POLYGON ((0 0, 10 0, ...))"

const geomFromWKT = wasmts.io.WKTReader.read('POINT (5 10)');

// WKB - compact binary format
const wkb = wasmts.io.WKBWriter.write(poly);
console.log('WKB size:', wkb.length, 'bytes');

const geomFromWKB = wasmts.io.WKBReader.read(wkb);
console.log('Round-trip:', geomFromWKB.equals(poly));
```

**Spatial Indexing with STRtree:**

```javascript
const index = wasmts.index.strtree.STRtree.create();
const envelope = poly.getEnvelopeInternal();
wasmts.index.strtree.STRtree.insert(index, envelope, {id: 1, data: poly});

const searchEnv = wasmts.geom.createEnvelope(0, 20, 0, 20);
const results = wasmts.index.strtree.STRtree.query(index, searchEnv);
console.log('Found:', results.length, 'geometries');
```

See full examples: [test/test-node.mjs](test/test-node.mjs), [docs/index.html](docs/index.html)

## Current API Reference

### GeometryFactory (`wasmts.geom.GeometryFactory`)

**Standard JTS factory pattern** - Creates a factory instance:

```javascript
const factory = wasmts.geom.GeometryFactory();
const point = factory.createPoint(x, y);
const envelope = factory.toGeometry(envelopeObject);
```

Available methods:
- `createPoint(x, y)` - Create 2D point from coordinates
- `createPoint(x, y, z)` - Create 3D point (XYZ)
- `createPoint(x, y, z, m)` - Create 4D point (XYZM)
- `toGeometry(envelope)` - Convert envelope to polygon

For complex geometries (LineString, Polygon, Multi*), use WKT or WKB instead:
```javascript
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10, 20 0)');
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
```

### Geometry Creation (`wasmts.geom.*` static methods)

All creation methods return geometry objects with shape `{type: string, _jtsGeom: JavaObject}`.

**Basic Geometries:**
- `wasmts.geom.createPoint(x, y)` - Create 2D point
- `wasmts.geom.createPoint(x, y, z)` - Create 3D point (XYZ)
- `wasmts.geom.createPoint(x, y, z, m)` - Create 4D point (XYZM with measure)
- `wasmts.geom.createEnvelope(minX, maxX, minY, maxY)` - Create bounding box

**Complex geometries** (LineString, Polygon, Multi*) use WKT or WKB:
- `wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10)')` - 2D LineString
- `wasmts.io.WKTReader.read('LINESTRING Z (0 0 0, 1 1 10)')` - 3D LineString
- `wasmts.io.WKTReader.read('LINESTRING ZM (0 0 0 100, 1 1 10 200)')` - 4D LineString
- `wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))')` - 2D Polygon

**3D/4D Coordinates:**

Coordinates returned by `getCoordinates()` include Z and M values when present:
```javascript
const point3d = wasmts.geom.createPoint(5, 10, 15);
const coords = point3d.getCoordinates();
console.log(coords[0]); // {x: 5, y: 10, z: 15}

const point4d = wasmts.geom.createPoint(5, 10, 15, 20);
const coords = point4d.getCoordinates();
console.log(coords[0]); // {x: 5, y: 10, z: 15, m: 20}
```

### I/O (`wasmts.io.*`)

**WKT (Well-Known Text)** - Human-readable text format:
- `wasmts.io.WKTReader.read(wkt)` - Parse WKT string
  - 2D: `'POINT (5 10)'`
  - 3D: `'POINT Z (5 10 15)'`
  - 4D: `'POINT ZM (5 10 15 20)'`
- `wasmts.io.WKTWriter.write(geometry)` - Convert geometry to WKT string

**WKB (Well-Known Binary)** - Compact binary format:
- `wasmts.io.WKBReader.read(uint8Array)` - Parse WKB bytes (Uint8Array)
- `wasmts.io.WKBWriter.write(geometry)` - Convert geometry to WKB (returns Uint8Array)
  - Automatically detects 2D/3D/4D based on coordinate dimensions
  - Preserves Z and M values in round-trip

WKB is typically 2-3x more compact than WKT and faster to parse.

### Operations (`wasmts.geom.*`)

**Boolean Operations:**
- `wasmts.geom.union(geom1, geom2)` - Union of two geometries
- `wasmts.geom.intersection(geom1, geom2)` - Intersection of two geometries
- `wasmts.geom.difference(geom1, geom2)` - Difference (geom1 minus geom2)
- `wasmts.geom.symDifference(geom1, geom2)` - Symmetric difference

**Geometric Operations:**
- `wasmts.geom.buffer(geometry, distance)` - Create buffer around geometry
- `wasmts.geom.bufferWithParams(geometry, distance, endCapStyle, joinStyle, mitreLimit)` - Buffer with custom parameters
- `wasmts.geom.convexHull(geometry)` - Compute convex hull
- `wasmts.geom.simplify(geometry, tolerance)` - Douglas-Peucker simplification
- `wasmts.geom.getBoundary(geometry)` - Get boundary of geometry
- `wasmts.geom.getCentroid(geometry)` - Get centroid as Point
- `wasmts.geom.getEnvelope(geometry)` - Get envelope as Geometry
- `wasmts.geom.getInteriorPoint(geometry)` - Get point guaranteed to be inside
- `wasmts.geom.copy(geometry)` - Create deep copy of geometry
- `wasmts.geom.reverse(geometry)` - Reverse coordinate order
- `wasmts.geom.normalize(geometry)` - Normalize to canonical form

### Predicates (`wasmts.geom.*`)

All predicates return booleans:

- `wasmts.geom.contains(geom1, geom2)` - Test if geom1 contains geom2
- `wasmts.geom.intersects(geom1, geom2)` - Test if geometries intersect
- `wasmts.geom.touches(geom1, geom2)` - Test if geometries touch at boundary
- `wasmts.geom.crosses(geom1, geom2)` - Test if geometries cross
- `wasmts.geom.within(geom1, geom2)` - Test if geom1 is within geom2
- `wasmts.geom.overlaps(geom1, geom2)` - Test if geometries overlap
- `wasmts.geom.disjoint(geom1, geom2)` - Test if geometries don't intersect
- `wasmts.geom.equals(geom1, geom2)` - Test if geometries are spatially equal
- `wasmts.geom.equalsTopo(geom1, geom2)` - Test topological equality (same shape, ignoring coordinate order)
- `wasmts.geom.covers(geom1, geom2)` - Test if geom1 covers geom2 (every point of geom2 is inside or on boundary of geom1)
- `wasmts.geom.coveredBy(geom1, geom2)` - Test if geom1 is covered by geom2

### Measurements & Validation (`wasmts.geom.*`)

All return numbers (except isEmpty/isValid/isSimple/isRectangle which return booleans):

- `wasmts.geom.getArea(geometry)` - Calculate area
- `wasmts.geom.getLength(geometry)` - Calculate length (for linear geometries)
- `wasmts.geom.distance(geom1, geom2)` - Distance between geometries
- `wasmts.geom.getNumPoints(geometry)` - Count points in geometry
- `wasmts.geom.isEmpty(geometry)` - Check if geometry is empty
- `wasmts.geom.isValid(geometry)` - Check if geometry is topologically valid
- `wasmts.geom.isSimple(geometry)` - Check if geometry has no self-intersections
- `wasmts.geom.isRectangle(geometry)` - Check if polygon is a rectangle

### Coordinate & Geometry Access (`wasmts.geom.*`)

- `wasmts.geom.getCoordinates(geometry)` - Extract all coordinates
  - 2D: Returns array of `{x, y}` objects
  - 3D: Returns array of `{x, y, z}` objects
  - 4D: Returns array of `{x, y, z, m}` objects
- `wasmts.geom.getNumGeometries(geometry)` - Get number of sub-geometries (for collections)
- `wasmts.geom.getGeometryN(geometry, n)` - Get nth sub-geometry (0-indexed)
- `wasmts.geom.getUserData(geometry)` - Get user-defined data attached to geometry
- `wasmts.geom.setUserData(geometry, data)` - Attach user-defined data to geometry

**Note**: To get a point's coordinates, use `getCoordinates(point)[0].x` and `getCoordinates(point)[0].y`

### Envelopes (Bounding Boxes) (`wasmts.geom.*`)

- `wasmts.geom.createEnvelope(minX, maxX, minY, maxY)` - Create envelope from bounds
- `wasmts.geom.getEnvelopeInternal(geometry)` - Get geometry's bounding box
- `wasmts.geom.envelopeIntersects(env1, env2)` - Test if envelopes intersect
- `wasmts.geom.envelopeContains(env1, env2)` - Test if env1 contains env2
- `wasmts.geom.expandToInclude(envelope, x, y)` - Expand envelope to include point

### Spatial Indexing (`wasmts.index.strtree.*`)

**STRtree** - Sort-Tile-Recursive tree for fast spatial queries (10-100x speedup):

- `wasmts.index.strtree.STRtree.create()` - Create new STRtree index
- `wasmts.index.strtree.STRtree.insert(tree, envelope, item)` - Insert item with bounding box
- `wasmts.index.strtree.STRtree.query(tree, searchEnvelope)` - Find all items intersecting search area
- `wasmts.index.strtree.STRtree.remove(tree, envelope, item)` - Remove item from index
- `wasmts.index.strtree.STRtree.size(tree)` - Get number of items in index

### PreparedGeometry (`wasmts.geom.prep.*`)

**PreparedGeometry** - Optimized for repeated spatial predicates against the same geometry:

```javascript
// Prepare a geometry for repeated predicate tests
const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');
const prepared = wasmts.geom.prep.PreparedGeometryFactory.prepare(polygon);

// Test many points against the prepared polygon (faster than regular predicates)
const testPoints = [
    wasmts.geom.createPoint(50, 50),
    wasmts.geom.createPoint(150, 150),
    wasmts.geom.createPoint(0, 0)
];

for (const point of testPoints) {
    const result = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, point);
    console.log(result); // true, false, false
}

// Extract underlying geometry
const original = wasmts.geom.prep.PreparedGeometry.getGeometry(prepared);
```

Available methods:
- `wasmts.geom.prep.PreparedGeometryFactory.prepare(geometry)` - Create PreparedGeometry from any geometry
- `wasmts.geom.prep.PreparedGeometry.containsProperly(prepGeom, testGeom)` - True if testGeom is fully inside (not touching boundary)
- `wasmts.geom.prep.PreparedGeometry.coveredBy(prepGeom, testGeom)` - True if prepGeom is covered by testGeom
- `wasmts.geom.prep.PreparedGeometry.getGeometry(prepGeom)` - Extract the underlying Geometry object

Use PreparedGeometry when testing many geometries against a single fixed geometry for better performance.

### Geometry Analysis Algorithms (`wasmts.algorithm.*`)

**Minimum bounding rectangles** - Find optimal bounding rectangles for geometries:

```javascript
// Create an irregular polygon
const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 2, 12 10, 2 12, 0 0))');

// Find minimum-width bounding rectangle
const minDiamRect = wasmts.algorithm.MinimumDiameter.getMinimumRectangle(polygon);
console.log('Minimum-width rectangle area:', minDiamRect.getArea());

// Find minimum-area bounding rectangle
const minAreaRect = wasmts.algorithm.MinimumBoundingCircle.getMinimumRectangle(polygon);
console.log('Minimum-area rectangle area:', minAreaRect.getArea());
```

Available algorithms:
- `wasmts.algorithm.MinimumDiameter.getMinimumRectangle(geometry)` - Returns rectangle with minimum width (based on minimum diameter)
- `wasmts.algorithm.MinimumBoundingCircle.getMinimumRectangle(geometry)` - Returns rectangle with minimum area enclosing the geometry

### Advanced Buffering

**Custom buffer parameters** - The `buffer()` method accepts optional parameters to control cap style, join style, and mitre limit:

```javascript
// Buffer parameter constants (from JTS BufferParameters)
const CAP_ROUND = 1, CAP_FLAT = 2, CAP_SQUARE = 3;
const JOIN_ROUND = 1, JOIN_MITRE = 2, JOIN_BEVEL = 3;

const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0)');

// Standard buffer
const standardBuffer = line.buffer(2);

// Flat cap buffer (no rounded ends)
const flatBuffer = line.buffer(2, CAP_FLAT, JOIN_ROUND, 5.0);

// Square cap buffer (extended square ends)
const squareBuffer = line.buffer(2, CAP_SQUARE, JOIN_ROUND, 5.0);

// Mitre join for sharp corners
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 1, 5 1, 5 10, 0 10, 0 0))');
const mitreBuffer = poly.buffer(0.5, CAP_ROUND, JOIN_MITRE, 10.0);

// Negative buffer for erosion
const eroded = poly.buffer(-0.5, CAP_ROUND, JOIN_ROUND, 5.0);
```

**Signature:** `geometry.buffer(distance, endCapStyle?, joinStyle?, mitreLimit?)`

**Parameters:**
- `distance`: Buffer distance (positive for expansion, negative for erosion)
- `endCapStyle` (optional): 1=ROUND, 2=FLAT, 3=SQUARE
- `joinStyle` (optional): 1=ROUND, 2=MITRE, 3=BEVEL
- `mitreLimit` (optional): Maximum ratio of mitre length to buffer width (typically 5.0)

### Offset Curves

**Parallel line generation** - The `offsetCurve()` method creates a parallel line at a specified distance from the input geometry:

```javascript
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0, 10 10)');

// Standard offset (positive = right side, negative = left side)
const rightOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 2);
const leftOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, -2);

// Custom parameters for corner style
const CAP_FLAT = 2, JOIN_MITRE = 2;
const sharpOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 2, CAP_FLAT, JOIN_MITRE, 10.0);

// Offset curves on polygons (offsets exterior ring)
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))');
const polyOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(poly, 3);
```

**Signature:** `wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(geometry, distance, endCapStyle?, joinStyle?, mitreLimit?)`

**Parameters:** Same as `buffer()` - see Advanced Buffering section above.

### LineMerger

**Merge connected linestrings** - Combines contiguous line segments into longer linestrings:

```javascript
// Create separate line segments that connect end-to-end
const line1 = wasmts.io.WKTReader.read('LINESTRING (0 0, 5 0)');
const line2 = wasmts.io.WKTReader.read('LINESTRING (5 0, 10 0)');
const line3 = wasmts.io.WKTReader.read('LINESTRING (10 0, 10 5)');

// Disconnected line
const line4 = wasmts.io.WKTReader.read('LINESTRING (20 20, 25 25)');

// Create merger and add lines
const merger = wasmts.operation.linemerge.LineMerger.create();
wasmts.operation.linemerge.LineMerger.add(merger, line1);
wasmts.operation.linemerge.LineMerger.add(merger, line2);
wasmts.operation.linemerge.LineMerger.add(merger, line3);
wasmts.operation.linemerge.LineMerger.add(merger, line4);

// Get merged result (returns array of linestrings)
const merged = wasmts.operation.linemerge.LineMerger.getMergedLineStrings(merger);
// Result: 2 lines (line1+line2+line3 merged into one, line4 separate)
```

**API:**
- `wasmts.operation.linemerge.LineMerger.create()` - Create a new LineMerger instance
- `wasmts.operation.linemerge.LineMerger.add(merger, geometry)` - Add line(s) to merge
- `wasmts.operation.linemerge.LineMerger.getMergedLineStrings(merger)` - Get array of merged linestrings

## Build System

**Commands:**
- `npm run build:wasm` - Compile Java to WASM with Maven
- `npm run build:js` - Copy artifacts to dist/
- `npm run build` - Full build
- `node test/test-node.mjs` - Run tests

**Output:**
- `dist/wasmts.js` - WASM loader (~109KB)
- `dist/wasmts.js.wasm` - Binary (~5.7MB)

**Build time:** ~25 seconds

## Technical Notes

### Adding Features

To add new JTS functionality:
1. Add method to `java/src/main/java/net/willcohen/wasmts/API.java`
2. Export to JavaScript namespace using `@JS` annotations
3. Add to geometry wrapper object if needed
4. Test and document

Example: Adding a new geometry operation follows this pattern - functional interface, export declaration, implementation method, and export call in main().

### Updating JTS

Update `<jts.version>` in `pom.xml` and rebuild. No source patches required.

### Browser Loading

Use `<script src="wasmts.js">` not `import()` - the loader needs proper path resolution for finding `.wasm` file.

## License

This project contains and distributes the full source code of **JTS (Java Topology Suite) 1.20.0**, which is dual-licensed under:

- **Eclipse Public License v2.0** ([LICENSE_EPLv2.txt](LICENSE_EPLv2.txt))
- **Eclipse Distribution License v1.0** ([LICENSE_EDLv1.txt](LICENSE_EDLv1.txt))

You may use JTS under either license. See the [JTS project](https://github.com/locationtech/jts) for more information.

The npm distribution also includes the **GraalVM WebAssembly loader** (the JavaScript runtime code in `wasmts.js`), which is part of GraalVM and is licensed under:

- **GNU General Public License v2.0 with Classpath Exception**

**The wrapper code** (Java interop layer in `java/src/main/java/net/willcohen/wasmts/API.java`) is licensed under EPL-2.0 OR EDL-1.0 to match JTS.
