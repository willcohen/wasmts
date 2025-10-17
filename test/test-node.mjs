#!/usr/bin/env node

/**
 * WasmTS Node.js Test Suite
 * Tests all functionality including 2D, 3D, and 4D geometry support
 */

import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

console.log('=== WasmTS Node.js Test Suite ===\n');

// Load WASM binary directly using fs since Node.js fetch doesn't support file:// well
const wasmJsFile = join(__dirname, '../dist/wasmts.js');
const wasmPath = join(__dirname, '../dist/wasmts.js.wasm');
const wasmBinary = readFileSync(wasmPath);

// Set __filename globally so wasmts.js can find the .wasm file
// This needs to be set BEFORE importing wasmts.js
globalThis.__filename = wasmJsFile;

// Patch fetch to handle .wasm files from our cache
const originalFetch = globalThis.fetch;
globalThis.fetch = function(url, ...args) {
    if (url && (url.endsWith('.wasm') || url.includes('wasmts.js.wasm'))) {
        return Promise.resolve({
            ok: true,
            arrayBuffer: () => Promise.resolve(wasmBinary.buffer.slice(wasmBinary.byteOffset, wasmBinary.byteOffset + wasmBinary.byteLength))
        });
    }
    return originalFetch(url, ...args);
};

import('../dist/wasmts.js').then(() => {
    console.log('PASS: WASM module loaded\n');

    // Wait for initialization
    setTimeout(() => {
        try {
            runAllTests();
        } catch (err) {
            console.error('Test execution failed:', err);
            process.exit(1);
        }
    }, 2000);
}).catch(err => {
    console.error('Failed to load WASM module:', err);
    process.exit(1);
});

function runAllTests() {
    console.log('--- API Namespace Tests ---\n');
    testNamespaces();

    console.log('\n--- 2D Geometry Tests ---\n');
    test2DGeometry();

    console.log('\n--- Buffer Operations ---\n');
    testBufferOperations();

    console.log('\n--- Intersecting Circles ---\n');
    testIntersectingCircles();

    console.log('\n--- WKT I/O Tests ---\n');
    testWKTIO();

    console.log('\n--- WKB I/O Tests ---\n');
    testWKBIO();

    console.log('\n--- STRtree Spatial Index ---\n');
    testSTRtree();

    console.log('\n--- 3D Geometry Tests (XYZ) ---\n');
    test3DGeometry();

    console.log('\n--- 4D Geometry Tests (XYZM) ---\n');
    test4DGeometry();

    console.log('\n--- PreparedGeometry Tests ---\n');
    testPreparedGeometry();

    console.log('\n--- Geometry Analysis Algorithms ---\n');
    testRectangleAlgorithms();

    console.log('\n--- Advanced Buffering ---\n');
    testAdvancedBuffering();

    console.log('\n--- Offset Curves ---\n');
    testOffsetCurves();

    console.log('\n--- LineMerger ---\n');
    testLineMerger();

    console.log('\n--- CascadedPolygonUnion ---\n');
    testCascadedPolygonUnion();

    console.log('\n--- New Geometry Methods ---\n');
    testNewGeometryMethods();

    console.log('\n=== All Tests Passed PASS: ===\n');
}

function testNamespaces() {
    assert(typeof wasmts !== 'undefined', 'wasmts namespace exists');
    assert(typeof wasmts.geom !== 'undefined', 'wasmts.geom namespace exists');
    assert(typeof wasmts.io !== 'undefined', 'wasmts.io namespace exists');
    assert(typeof wasmts.index !== 'undefined', 'wasmts.index namespace exists');
    assert(typeof wasmts.index.strtree !== 'undefined', 'wasmts.index.strtree namespace exists');
    console.log('PASS: All namespaces found');
}

function test2DGeometry() {
    // Create Point
    const point = wasmts.geom.createPoint(5, 10);
    assert(point.type === 'Point', 'Point created');
    const coords = point.getCoordinates();
    assert(coords.length === 1, 'Point has 1 coordinate');
    assert(coords[0].x === 5 && coords[0].y === 10, 'Point coordinates correct');
    console.log('PASS: Point creation:', coords[0]);

    // Create LineString via WKT (Node.js doesn't support array parameters with GraalVM webimage)
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10, 20 0)');
    assert(line.type === 'LineString', 'LineString created');
    const lineCoords = line.getCoordinates();
    assert(lineCoords.length === 3, 'LineString has 3 coordinates');
    console.log('PASS: LineString creation: 3 points');

    // Create Polygon via WKT
    const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');
    assert(polygon.type === 'Polygon', 'Polygon created');
    const area = polygon.getArea();
    assert(area === 10000, 'Polygon area is 10000');
    console.log('PASS: Polygon creation: area =', area);

    // Create MultiPoint via WKT
    const multiPoint = wasmts.io.WKTReader.read('MULTIPOINT ((0 0), (10 10))');
    assert(multiPoint.type === 'MultiPoint', 'MultiPoint created');
    console.log('PASS: MultiPoint creation');
}

function testBufferOperations() {
    // Create a point and buffer it
    const point = wasmts.geom.createPoint(0, 0);
    const buffered = point.buffer(10);

    // Get area (should be π * r² ≈ 314.16)
    const area = buffered.getArea();
    const expectedArea = Math.PI * 100;
    const diff = Math.abs(area - expectedArea);
    console.log('  Buffer area:', area.toFixed(2), 'Expected:', expectedArea.toFixed(2), 'Diff:', diff.toFixed(2));
    assert(Math.abs(area - expectedArea) < 3, 'Buffer area approximately correct (within 3 units)');
    console.log('PASS: Buffer area:', area.toFixed(2));

    // Convert to WKT
    const wkt = buffered.toString();
    assert(wkt.startsWith('POLYGON'), 'Buffered geometry is POLYGON');
    console.log('PASS: WKT output:', wkt.substring(0, 50) + '...');

    // Try union with another buffer
    const point2 = wasmts.geom.createPoint(15, 0);
    const buffered2 = point2.buffer(10);
    const union = buffered.union(buffered2);
    const unionArea = union.getArea();
    assert(unionArea > area, 'Union area is larger than single buffer');
    console.log('PASS: Union area:', unionArea.toFixed(2));
}

function testIntersectingCircles() {
    // Create two overlapping circles
    const circle1 = wasmts.geom.createPoint(0, 0).buffer(10);
    const circle2 = wasmts.geom.createPoint(15, 0).buffer(10);

    // Check if they intersect
    const intersects = circle1.intersects(circle2);
    assert(intersects === true, 'Circles intersect');
    console.log('PASS: Circles intersect:', intersects);

    // Get the intersection geometry
    const intersection = circle1.intersection(circle2);
    const intersectionArea = intersection.getArea();
    assert(intersectionArea > 0, 'Intersection has area');
    console.log('PASS: Intersection area:', intersectionArea.toFixed(2));

    // Get the union
    const union = circle1.union(circle2);
    const unionArea = union.getArea();
    console.log('PASS: Union area:', unionArea.toFixed(2));

    // Calculate overlap percentage
    const overlapPercent = (intersectionArea / unionArea * 100).toFixed(1);
    console.log('PASS: Overlap:', overlapPercent + '%');

    // Output as WKT and WKB
    const wkt = wasmts.io.WKTWriter.write(intersection);
    assert(wkt.startsWith('POLYGON'), 'Intersection is POLYGON');
    const wkb = wasmts.io.WKBWriter.write(intersection);
    assert(wkb instanceof Uint8Array, 'WKB is Uint8Array');
    console.log('PASS: WKB length:', wkb.length, 'bytes');
}

function testWKTIO() {
    // Read WKT
    const wkt = 'POINT (5 10)';
    const geom = wasmts.io.WKTReader.read(wkt);
    assert(geom.type === 'Point', 'WKT parsed as Point');
    console.log('PASS: readWKT:', wkt);

    // Write WKT
    const output = wasmts.io.WKTWriter.write(geom);
    assert(output.includes('5') && output.includes('10'), 'WKT contains coordinates');
    console.log('PASS: writeWKT:', output);

    // Test LineString WKT
    const lineWKT = 'LINESTRING (0 0, 10 10, 20 0)';
    const line = wasmts.io.WKTReader.read(lineWKT);
    assert(line.type === 'LineString', 'LineString parsed');
    const lineOut = wasmts.io.WKTWriter.write(line);
    console.log('PASS: LineString WKT:', lineOut);

    // Test Polygon WKT
    const polyWKT = 'POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))';
    const poly = wasmts.io.WKTReader.read(polyWKT);
    assert(poly.type === 'Polygon', 'Polygon parsed');
    const polyArea = poly.getArea();
    assert(polyArea === 100, 'Polygon area is 100');
    console.log('PASS: Polygon WKT area:', polyArea);
}

function testWKBIO() {
    // Create geometry
    const point = wasmts.geom.createPoint(5, 10);

    // Write to WKB
    const wkb = wasmts.io.WKBWriter.write(point);
    assert(wkb instanceof Uint8Array, 'writeWKB returns Uint8Array');
    assert(wkb.length > 0, 'WKB has data');
    console.log('PASS: writeWKB: length =', wkb.length, 'bytes');
    console.log('  Hex:', Array.from(wkb.slice(0, 16)).map(b => b.toString(16).padStart(2, '0')).join(' '));

    // Read from WKB
    const parsed = wasmts.io.WKBReader.read(wkb);
    assert(parsed.type === 'Point', 'readWKB parsed as Point');
    const coords = parsed.getCoordinates();
    assert(coords[0].x === 5 && coords[0].y === 10, 'WKB round-trip preserves coordinates');
    console.log('PASS: readWKB: coordinates =', coords[0]);

    // Test with polygon
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const polyWkb = wasmts.io.WKBWriter.write(poly);
    const polyParsed = wasmts.io.WKBReader.read(polyWkb);
    assert(polyParsed.getArea() === 100, 'Polygon WKB round-trip preserves area');
    console.log('PASS: Polygon WKB round-trip: area =', polyParsed.getArea());
}

function testSTRtree() {
    // Create STRtree
    const index = wasmts.index.strtree.STRtree.create();
    console.log('PASS: STRtree created');

    // Create geometries and insert into STRtree
    for (let i = 0; i < 10; i++) {
        const point = wasmts.geom.createPoint(i * 10, i * 10);
        const buffered = point.buffer(5);
        const envelope = buffered.getEnvelopeInternal();
        wasmts.index.strtree.STRtree.insert(index, envelope, { id: i, name: `Geometry ${i}` });
    }
    console.log('PASS: Inserted 10 geometries');

    // Query with search envelope
    const searchEnv = wasmts.geom.createEnvelope(20, 30, 20, 30);
    const results = wasmts.index.strtree.STRtree.query(index, searchEnv);
    assert(results.length > 0, 'Query returned results');
    console.log('PASS: Query found', results.length, 'results');

    // Check result structure
    const first = results[0];
    assert(first !== undefined, 'Result exists');
    console.log('PASS: First result:', first);
}

function test3DGeometry() {
    // Test 3D point (XYZ)
    const point3d = wasmts.geom.createPoint(5, 10, 15);
    assert(point3d.type === 'Point', '3D Point created');
    console.log('PASS: 3D Point created');

    const coords3d = point3d.getCoordinates();
    assert(coords3d.length === 1, '3D Point has 1 coordinate');
    assert(coords3d[0].x === 5, 'X coordinate correct');
    assert(coords3d[0].y === 10, 'Y coordinate correct');
    assert(coords3d[0].z === 15, 'Z coordinate correct');
    console.log('PASS: 3D coordinates:', { x: coords3d[0].x, y: coords3d[0].y, z: coords3d[0].z });

    // Test 3D LineString via WKT
    const line3d = wasmts.io.WKTReader.read('LINESTRING Z (0 0 0, 1 1 10, 2 0 20)');
    assert(line3d.type === 'LineString', '3D LineString created');
    const lineCoords3d = line3d.getCoordinates();
    assert(lineCoords3d.length === 3, '3D LineString has 3 coordinates');
    assert(lineCoords3d[1].z === 10, 'Middle point Z coordinate correct');
    console.log('PASS: 3D LineString: 3 points with Z values');

    // Test WKT output for 3D
    const wkt3d = wasmts.io.WKTWriter.write(point3d);
    console.log('PASS: 3D WKT:', wkt3d);

    // Test WKT round-trip
    const parsed3d = wasmts.io.WKTReader.read(wkt3d);
    assert(parsed3d.type === 'Point', '3D WKT parsed');
    console.log('PASS: 3D WKT round-trip successful');

    // Test WKB for 3D
    const wkb3d = wasmts.io.WKBWriter.write(point3d);
    assert(wkb3d instanceof Uint8Array, '3D WKB is Uint8Array');
    console.log('PASS: 3D WKB length:', wkb3d.length, 'bytes');

    const parsedWkb3d = wasmts.io.WKBReader.read(wkb3d);
    assert(parsedWkb3d.type === 'Point', '3D WKB parsed');
    const wkbCoords = parsedWkb3d.getCoordinates();
    assert(wkbCoords[0].z === 15, '3D WKB Z coordinate preserved');
    console.log('PASS: 3D WKB round-trip: Z =', wkbCoords[0].z);

    // Test distance calculation (2D projection)
    const point3d_a = wasmts.geom.createPoint(0, 0, 0);
    const point3d_b = wasmts.geom.createPoint(3, 4, 0);
    const distance = point3d_a.distance(point3d_b);
    assert(distance === 5, '3D point distance (2D projection) is 5');
    console.log('PASS: 3D distance (2D projection):', distance);
}

function test4DGeometry() {
    // Test 4D point (XYZM)
    const point4d = wasmts.geom.createPoint(5, 10, 15, 20);
    assert(point4d.type === 'Point', '4D Point created');
    console.log('PASS: 4D Point created');

    const coords4d = point4d.getCoordinates();
    assert(coords4d.length === 1, '4D Point has 1 coordinate');
    assert(coords4d[0].x === 5, 'X coordinate correct');
    assert(coords4d[0].y === 10, 'Y coordinate correct');
    assert(coords4d[0].z === 15, 'Z coordinate correct');
    assert(coords4d[0].m === 20, 'M coordinate correct');
    console.log('PASS: 4D coordinates:', { x: coords4d[0].x, y: coords4d[0].y, z: coords4d[0].z, m: coords4d[0].m });

    // Test 4D LineString via WKT
    const line4d = wasmts.io.WKTReader.read('LINESTRING ZM (0 0 0 100, 1 1 10 200, 2 0 20 300)');
    assert(line4d.type === 'LineString', '4D LineString created');
    const lineCoords4d = line4d.getCoordinates();
    assert(lineCoords4d.length === 3, '4D LineString has 3 coordinates');
    assert(lineCoords4d[1].z === 10, 'Middle point Z coordinate correct');
    assert(lineCoords4d[1].m === 200, 'Middle point M coordinate correct');
    console.log('PASS: 4D LineString: 3 points with Z and M values');

    // Test WKT output for 4D
    const wkt4d = wasmts.io.WKTWriter.write(point4d);
    console.log('PASS: 4D WKT:', wkt4d);

    // Test WKB for 4D
    const wkb4d = wasmts.io.WKBWriter.write(point4d);
    assert(wkb4d instanceof Uint8Array, '4D WKB is Uint8Array');
    console.log('PASS: 4D WKB length:', wkb4d.length, 'bytes');

    const parsedWkb4d = wasmts.io.WKBReader.read(wkb4d);
    assert(parsedWkb4d.type === 'Point', '4D WKB parsed');
    const wkbCoords = parsedWkb4d.getCoordinates();
    assert(wkbCoords[0].z === 15, '4D WKB Z coordinate preserved');
    assert(wkbCoords[0].m === 20, '4D WKB M coordinate preserved');
    console.log('PASS: 4D WKB round-trip: Z =', wkbCoords[0].z, ', M =', wkbCoords[0].m);
}

function testPreparedGeometry() {
    // Create a large polygon for containment tests
    const container = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');

    // Prepare the geometry for optimized predicates
    assert(typeof wasmts.geom.prep !== 'undefined', 'wasmts.geom.prep namespace exists');
    assert(typeof wasmts.geom.prep.PreparedGeometryFactory !== 'undefined', 'PreparedGeometryFactory class exists');

    const prepared = wasmts.geom.prep.PreparedGeometryFactory.prepare(container);
    assert(prepared !== null && prepared !== undefined, 'PreparedGeometry created');
    assert(prepared._jtsPreparedGeometry !== undefined, 'PreparedGeometry has internal reference');
    console.log('PASS: PreparedGeometry created');

    // Test containsProperly - point clearly inside
    const insidePoint = wasmts.geom.createPoint(50, 50);
    const containsResult = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, insidePoint);
    assert(containsResult === true, 'containsProperly returns true for inside point');
    console.log('PASS: containsProperly(inside point) = true');

    // Test containsProperly - point on boundary (should be false for containsProperly)
    const boundaryPoint = wasmts.geom.createPoint(0, 0);
    const boundaryResult = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, boundaryPoint);
    assert(boundaryResult === false, 'containsProperly returns false for boundary point');
    console.log('PASS: containsProperly(boundary point) = false');

    // Test containsProperly - point outside
    const outsidePoint = wasmts.geom.createPoint(150, 150);
    const outsideResult = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, outsidePoint);
    assert(outsideResult === false, 'containsProperly returns false for outside point');
    console.log('PASS: containsProperly(outside point) = false');

    // Test coveredBy - create a polygon that is covered by the container
    const innerPoly = wasmts.io.WKTReader.read('POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))');
    const coveredResult = wasmts.geom.prep.PreparedGeometry.coveredBy(prepared, innerPoly);
    assert(coveredResult === false, 'coveredBy returns false (container not covered by inner)');
    console.log('PASS: coveredBy test completed');

    // Test getGeometry - extract underlying geometry
    const extracted = wasmts.geom.prep.PreparedGeometry.getGeometry(prepared);
    assert(extracted !== null && extracted !== undefined, 'getGeometry returns geometry');
    assert(extracted.type === 'Polygon', 'extracted geometry is Polygon');
    assert(extracted.getArea() === 10000, 'extracted geometry has correct area');
    console.log('PASS: getGeometry returned original Polygon with area =', extracted.getArea());

    // Performance comparison - run containsProperly multiple times
    const testPoints = [];
    for (let i = 0; i < 10; i++) {
        testPoints.push(wasmts.geom.createPoint(Math.random() * 120 - 10, Math.random() * 120 - 10));
    }

    for (const pt of testPoints) {
        wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, pt);
    }
    console.log('PASS: Ran containsProperly on 10 test points');
}

function testRectangleAlgorithms() {
    // Create an irregular polygon
    const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 2, 12 10, 2 12, 0 0))');
    console.log('Created irregular polygon, area:', polygon.getArea().toFixed(2));

    // Test namespace exists
    assert(typeof wasmts.algorithm !== 'undefined', 'wasmts.algorithm namespace exists');
    assert(typeof wasmts.algorithm.MinimumDiameter !== 'undefined', 'MinimumDiameter class exists');
    assert(typeof wasmts.algorithm.MinimumBoundingCircle !== 'undefined', 'MinimumBoundingCircle class exists');

    // Test MinimumDiameter - finds minimum-width bounding rectangle
    const minDiamRect = wasmts.algorithm.MinimumDiameter.getMinimumRectangle(polygon);
    assert(minDiamRect !== null && minDiamRect !== undefined, 'minimumDiameter returned geometry');
    assert(minDiamRect.type === 'LineString' || minDiamRect.type === 'Polygon', 'minimumDiameter returns LineString or Polygon');
    console.log('PASS: MinimumDiameter rectangle:', minDiamRect.type);

    if (minDiamRect.type === 'Polygon') {
        const rectArea = minDiamRect.getArea();
        console.log('  Minimum-width rectangle area:', rectArea.toFixed(2));
        assert(rectArea > 0, 'Rectangle has positive area');
    }

    // Test MinimumAreaRectangle - finds smallest bounding rectangle
    const minAreaRect = wasmts.algorithm.MinimumBoundingCircle.getMinimumRectangle(polygon);
    assert(minAreaRect !== null && minAreaRect !== undefined, 'minimumAreaRectangle returned geometry');
    assert(minAreaRect.type === 'Polygon' || minAreaRect.type === 'LineString' || minAreaRect.type === 'Point', 'minimumAreaRectangle returns geometry');
    console.log('PASS: MinimumAreaRectangle:', minAreaRect.type);

    if (minAreaRect.type === 'Polygon') {
        const rectArea = minAreaRect.getArea();
        console.log('  Minimum-area rectangle area:', rectArea.toFixed(2));
        assert(rectArea > 0, 'Rectangle has positive area');

        // Minimum area rectangle should contain the original polygon
        assert(minAreaRect.contains(polygon), 'Minimum area rectangle contains original polygon');
        console.log('PASS: Minimum area rectangle contains original polygon');
    }

    // Test with a simple triangle
    const triangle = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 5 10, 0 0))');
    const triMinRect = wasmts.algorithm.MinimumBoundingCircle.getMinimumRectangle(triangle);
    console.log('PASS: Tested algorithms on triangle, result type:', triMinRect.type);
}

function testAdvancedBuffering() {
    // BufferParameters constants (from JTS)
    const CAP_ROUND = 1, CAP_FLAT = 2, CAP_SQUARE = 3;
    const JOIN_ROUND = 1, JOIN_MITRE = 2, JOIN_BEVEL = 3;

    // Create a simple line
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0)');
    console.log('Created test line');

    // Test 1: Round caps (default-like)
    const roundBuffer = line.buffer(2, CAP_ROUND, JOIN_ROUND, 5.0);
    assert(roundBuffer !== null && roundBuffer !== undefined, 'Round cap buffer created');
    assert(roundBuffer.type === 'Polygon', 'Round cap buffer is Polygon');
    const roundArea = roundBuffer.getArea();
    console.log('PASS: Round cap buffer area:', roundArea.toFixed(2));

    // Test 2: Flat caps (single-sided effect on lines)
    const flatBuffer = line.buffer(2, CAP_FLAT, JOIN_ROUND, 5.0);
    assert(flatBuffer !== null && flatBuffer !== undefined, 'Flat cap buffer created');
    const flatArea = flatBuffer.getArea();
    console.log('PASS: Flat cap buffer area:', flatArea.toFixed(2));
    assert(flatArea < roundArea, 'Flat cap buffer has smaller area than round');

    // Test 3: Square caps
    const squareBuffer = line.buffer(2, CAP_SQUARE, JOIN_ROUND, 5.0);
    assert(squareBuffer !== null && squareBuffer !== undefined, 'Square cap buffer created');
    const squareArea = squareBuffer.getArea();
    console.log('PASS: Square cap buffer area:', squareArea.toFixed(2));

    // Test 4: Mitre join on polygon with sharp corners
    const sharpPoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 1, 5 1, 5 10, 0 10, 0 0))');
    const mitreBuffer = sharpPoly.buffer(0.5, CAP_ROUND, JOIN_MITRE, 10.0);
    assert(mitreBuffer !== null && mitreBuffer !== undefined, 'Mitre join buffer created');
    console.log('PASS: Mitre join buffer created for sharp polygon');

    // Test 5: Bevel join
    const bevelBuffer = sharpPoly.buffer(0.5, CAP_ROUND, JOIN_BEVEL, 5.0);
    assert(bevelBuffer !== null && bevelBuffer !== undefined, 'Bevel join buffer created');
    console.log('PASS: Bevel join buffer created');

    // Test 6: Negative buffer (erosion/deflate)
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))');
    const eroded = poly.buffer(-2, CAP_ROUND, JOIN_ROUND, 5.0);
    assert(eroded !== null && eroded !== undefined, 'Negative buffer (erosion) created');
    if (eroded.type !== 'GeometryCollection' && !eroded.isEmpty()) {
        const erodedArea = eroded.getArea();
        const originalArea = poly.getArea();
        assert(erodedArea < originalArea, 'Eroded polygon has smaller area');
        console.log('PASS: Erosion reduced area from', originalArea, 'to', erodedArea.toFixed(2));
    }

    console.log('PASS: All advanced buffering tests completed');
}

function testOffsetCurves() {
    // Buffer parameter constants
    const CAP_ROUND = 1, CAP_FLAT = 2, CAP_SQUARE = 3;
    const JOIN_ROUND = 1, JOIN_MITRE = 2, JOIN_BEVEL = 3;

    // Test 1: Simple line offset
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0, 10 10)');
    console.log('Created test line');

    // Standard offset
    const offset1 = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 2);
    assert(offset1 !== null && offset1 !== undefined, 'Offset curve created');
    assert(offset1.type === 'LineString', 'Offset curve is LineString');
    const coords1 = offset1.getCoordinates();
    console.log('PASS: Standard offset curve created with', coords1.length, 'points');

    // Negative offset (other side)
    const offset2 = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, -2);
    assert(offset2 !== null && offset2 !== undefined, 'Negative offset curve created');
    const coords2 = offset2.getCoordinates();
    console.log('PASS: Negative offset curve created with', coords2.length, 'points');

    // Test 2: Offset with custom parameters
    const offset3 = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 2, CAP_FLAT, JOIN_MITRE, 10.0);
    assert(offset3 !== null && offset3 !== undefined, 'Offset with custom params created');
    console.log('PASS: Offset curve with custom parameters created');

    // Test 3: Polygon exterior ring offset
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const polyOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(poly, 2);
    assert(polyOffset !== null && polyOffset !== undefined, 'Polygon offset curve created');
    console.log('PASS: Polygon offset curve created');

    console.log('PASS: All offset curve tests completed');
}

function testLineMerger() {
    // Test line merging - combines connected linestrings

    // Create separate line segments that connect
    const line1 = wasmts.io.WKTReader.read('LINESTRING (0 0, 5 0)');
    const line2 = wasmts.io.WKTReader.read('LINESTRING (5 0, 10 0)');
    const line3 = wasmts.io.WKTReader.read('LINESTRING (10 0, 10 5)');

    // Separate disconnected line
    const line4 = wasmts.io.WKTReader.read('LINESTRING (20 20, 25 25)');

    console.log('Created test lines');

    // Create LineMerger
    const merger = wasmts.operation.linemerge.LineMerger.create();
    assert(merger !== null && merger !== undefined, 'LineMerger created');
    console.log('PASS: LineMerger created');

    // Add lines to merger
    wasmts.operation.linemerge.LineMerger.add(merger, line1);
    wasmts.operation.linemerge.LineMerger.add(merger, line2);
    wasmts.operation.linemerge.LineMerger.add(merger, line3);
    wasmts.operation.linemerge.LineMerger.add(merger, line4);
    console.log('PASS: Added 4 lines to merger');

    // Get merged result
    const merged = wasmts.operation.linemerge.LineMerger.getMergedLineStrings(merger);
    assert(merged !== null && merged !== undefined, 'Got merged result');
    assert(Array.isArray(merged), 'Result is array');
    console.log('PASS: Got merged linestrings, count:', merged.length);

    // Should have 2 merged lines: one from line1+line2+line3, one from line4
    assert(merged.length === 2, 'Expected 2 merged lines');
    console.log('PASS: Correct number of merged lines (2)');

    // Check first merged line (should connect 3 segments)
    const firstMerged = merged[0];
    assert(firstMerged.type === 'LineString', 'First result is LineString');
    const coords1 = firstMerged.getCoordinates();
    console.log('PASS: First merged line has', coords1.length, 'points');

    // Check second merged line (disconnected, unchanged)
    const secondMerged = merged[1];
    assert(secondMerged.type === 'LineString', 'Second result is LineString');
    const coords2 = secondMerged.getCoordinates();
    console.log('PASS: Second merged line has', coords2.length, 'points');

    console.log('PASS: All LineMerger tests completed');
}

function testCascadedPolygonUnion() {
    // Test efficient union of many polygons

    // Create multiple overlapping polygons
    const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const poly2 = wasmts.io.WKTReader.read('POLYGON ((5 5, 15 5, 15 15, 5 15, 5 5))');
    const poly3 = wasmts.io.WKTReader.read('POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))');
    const poly4 = wasmts.io.WKTReader.read('POLYGON ((8 0, 18 0, 18 8, 8 8, 8 0))');

    console.log('Created 4 overlapping polygons');

    // Create array of polygons
    const polygons = [poly1, poly2, poly3, poly4];

    // Perform cascaded union
    const union = wasmts.operation.union.CascadedPolygonUnion.union(polygons);
    assert(union !== null && union !== undefined, 'Cascaded union result created');
    assert(union.type === 'Polygon', 'Result is Polygon');
    console.log('PASS: Cascaded union created');

    // Union should have larger area than any individual polygon
    const unionArea = union.getArea();
    const poly1Area = poly1.getArea();
    assert(unionArea > poly1Area, 'Union area larger than individual polygon');
    console.log('PASS: Union area:', unionArea.toFixed(2), '(individual polygon: 100)');

    // Union should be smaller than sum of all areas (due to overlaps)
    const totalArea = poly1.getArea() + poly2.getArea() + poly3.getArea() + poly4.getArea();
    assert(unionArea < totalArea, 'Union area smaller than sum (overlaps removed)');
    console.log('PASS: Union removes overlaps (total: ' + totalArea + ', union: ' + unionArea.toFixed(2) + ')');

    console.log('PASS: All CascadedPolygonUnion tests completed');
}

function testNewGeometryMethods() {
    // Test data
    const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const poly3 = wasmts.io.WKTReader.read('POLYGON ((5 5, 15 5, 15 15, 5 15, 5 5))'); // overlapping
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 5 5, 10 0)');
    const complexLine = wasmts.io.WKTReader.read('LINESTRING (0 0, 1 1, 1 0, 0 1, 2 2)'); // self-intersecting

    // Test copy first (needed for equalsTopo test)
    const poly1Copy = poly1.copy();
    assert(poly1Copy !== null, 'copy returns geometry');
    assert(poly1Copy.type === 'Polygon', 'Copy is same type');
    assert(poly1Copy.getArea() === poly1.getArea(), 'Copy has same area');
    console.log('PASS: copy test (early check)');

    // Test equalsTopo
    assert(poly1Copy.equalsTopo(poly1) === true, 'equalsTopo returns true for copied geometry');
    assert(poly1.equalsTopo(poly3) === false, 'equalsTopo returns false for different geometries');
    console.log('PASS: equalsTopo tests');

    // Test covers and coveredBy
    const container = wasmts.io.WKTReader.read('POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))');
    const contained = wasmts.io.WKTReader.read('POLYGON ((5 5, 15 5, 15 15, 5 15, 5 5))');
    assert(container.covers(contained) === true, 'covers returns true when geometry covers another');
    assert(contained.coveredBy(container) === true, 'coveredBy returns true when geometry is covered');
    assert(contained.covers(container) === false, 'covers returns false when geometry does not cover');
    console.log('PASS: covers/coveredBy tests');

    // Test getEnvelope
    const envelope = poly1.getEnvelope();
    assert(envelope !== null, 'getEnvelope returns geometry');
    assert(envelope.type === 'Polygon', 'Envelope is a Polygon');
    const envArea = envelope.getArea();
    assert(envArea === 100, 'Envelope area matches bounding box');
    console.log('PASS: getEnvelope test, area:', envArea);

    // Test getInteriorPoint
    const interiorPt = poly1.getInteriorPoint();
    assert(interiorPt !== null, 'getInteriorPoint returns point');
    assert(interiorPt.type === 'Point', 'Interior point is a Point');
    assert(poly1.contains(interiorPt) === true, 'Interior point is inside geometry');
    console.log('PASS: getInteriorPoint test');

    // Test copy
    const polyCopy = poly1.copy();
    assert(polyCopy !== null, 'copy returns geometry');
    assert(polyCopy.equalsTopo(poly1) === true, 'Copy is topologically equal to original');
    assert(polyCopy.getArea() === poly1.getArea(), 'Copy has same area');
    console.log('PASS: copy test');

    // Test reverse
    const reversed = line.reverse();
    assert(reversed !== null, 'reverse returns geometry');
    const origCoords = line.getCoordinates();
    const revCoords = reversed.getCoordinates();
    assert(revCoords.length === origCoords.length, 'Reversed has same number of coordinates');
    assert(revCoords[0].x === origCoords[origCoords.length - 1].x, 'Reversed coordinates are in opposite order');
    console.log('PASS: reverse test');

    // Test normalize
    const normalized = poly1.normalize();
    assert(normalized !== null, 'normalize returns geometry');
    assert(normalized.type === 'Polygon', 'Normalized geometry is same type');
    assert(normalized.getArea() === poly1.getArea(), 'Normalized has same area');
    console.log('PASS: normalize test');

    // Test isSimple
    assert(line.isSimple() === true, 'isSimple returns true for simple line');
    assert(complexLine.isSimple() === false, 'isSimple returns false for self-intersecting line');
    console.log('PASS: isSimple test');

    // Test isRectangle
    assert(poly1.isRectangle() === true, 'isRectangle returns true for rectangle');
    const triangle = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 5 10, 0 0))');
    assert(triangle.isRectangle() === false, 'isRectangle returns false for triangle');
    console.log('PASS: isRectangle test');

    // Test getUserData / setUserData
    const testData = { id: 123, name: 'test polygon' };
    poly1.setUserData(testData);
    const retrievedData = poly1.getUserData();
    assert(retrievedData !== null, 'getUserData returns data');
    assert(retrievedData.id === 123, 'User data preserved correctly');
    assert(retrievedData.name === 'test polygon', 'User data object properties preserved');
    console.log('PASS: getUserData/setUserData test');

    console.log('PASS: All new geometry methods tested');
}

function assert(condition, message) {
    if (!condition) {
        console.error('✗ Assertion failed:', message);
        console.error('   Condition value:', condition);
        console.error('   Condition type:', typeof condition);
        throw new Error(`Assertion failed: ${message}`);
    }
}
