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

    console.log('\n--- GeoJSON I/O Tests ---\n');
    testGeoJSONIO();

    console.log('\n--- Polygon Accessors ---\n');
    testPolygonAccessors();

    console.log('\n--- Distance Operations ---\n');
    testDistanceOperations();

    console.log('\n--- Geometry Factory Methods ---\n');
    testGeometryFactory();

    console.log('\n--- Coordinate Sequence ---\n');
    testCoordinateSequence();

    console.log('\n--- Coordinate Sequence Filter ---\n');
    testCoordinateSequenceFilter();

    console.log('\n--- Geometry Base Class ---\n');
    testGeometryBaseClass();

    console.log('\n--- IntersectionMatrix ---\n');
    testIntersectionMatrix();

    testDimension();

    console.log('\n--- Point (getX, getY) ---\n');
    testPointMethods();

    console.log('\n--- LineString/LinearRing ---\n');
    testLineStringMethods();

    console.log('\n--- Envelope Methods ---\n');
    testEnvelopeMethods();

    console.log('\n--- Geometry Additional Methods ---\n');
    testGeometryAdditionalMethods();

    console.log('\n--- Densifier ---\n');
    testDensifier();

    console.log('\n--- GeometryFixer ---\n');
    testGeometryFixer();

    console.log('\n--- CoverageUnion ---\n');
    testCoverageUnion();

    console.log('\n--- PrecisionModel ---\n');
    testPrecisionModel();

    console.log('\n--- GeometryPrecisionReducer ---\n');
    testGeometryPrecisionReducer();

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

    // Test contains - PreparedGeometry.contains(prepared, geom)
    const containsInside = wasmts.geom.prep.PreparedGeometry.contains(prepared, insidePoint);
    assert(containsInside === true, 'contains returns true for inside point');
    const containsOutside = wasmts.geom.prep.PreparedGeometry.contains(prepared, outsidePoint);
    assert(containsOutside === false, 'contains returns false for outside point');
    console.log('PASS: PreparedGeometry.contains works');

    // Test covers - PreparedGeometry.covers(prepared, geom)
    const coversInner = wasmts.geom.prep.PreparedGeometry.covers(prepared, innerPoly);
    assert(coversInner === true, 'covers returns true for inner polygon');
    const coversOutside = wasmts.geom.prep.PreparedGeometry.covers(prepared, outsidePoint);
    assert(coversOutside === false, 'covers returns false for outside point');
    console.log('PASS: PreparedGeometry.covers works');

    // Test intersects - PreparedGeometry.intersects(prepared, geom)
    const overlappingPoly = wasmts.io.WKTReader.read('POLYGON ((50 50, 150 50, 150 150, 50 150, 50 50))');
    const intersectsOverlap = wasmts.geom.prep.PreparedGeometry.intersects(prepared, overlappingPoly);
    assert(intersectsOverlap === true, 'intersects returns true for overlapping polygon');
    const disjointPoly = wasmts.io.WKTReader.read('POLYGON ((200 200, 300 200, 300 300, 200 300, 200 200))');
    const intersectsDisjoint = wasmts.geom.prep.PreparedGeometry.intersects(prepared, disjointPoly);
    assert(intersectsDisjoint === false, 'intersects returns false for disjoint polygon');
    console.log('PASS: PreparedGeometry.intersects works');

    // Test disjoint - PreparedGeometry.disjoint(prepared, geom)
    const disjointResult = wasmts.geom.prep.PreparedGeometry.disjoint(prepared, disjointPoly);
    assert(disjointResult === true, 'disjoint returns true for disjoint polygon');
    const notDisjoint = wasmts.geom.prep.PreparedGeometry.disjoint(prepared, overlappingPoly);
    assert(notDisjoint === false, 'disjoint returns false for overlapping polygon');
    console.log('PASS: PreparedGeometry.disjoint works');

    // Test within - PreparedGeometry.within(prepared, geom)
    const largerPoly = wasmts.io.WKTReader.read('POLYGON ((-10 -10, 110 -10, 110 110, -10 110, -10 -10))');
    const withinLarger = wasmts.geom.prep.PreparedGeometry.within(prepared, largerPoly);
    assert(withinLarger === true, 'within returns true when prepared is inside larger');
    const withinSmaller = wasmts.geom.prep.PreparedGeometry.within(prepared, innerPoly);
    assert(withinSmaller === false, 'within returns false when prepared is not inside smaller');
    console.log('PASS: PreparedGeometry.within works');

    // Test overlaps - PreparedGeometry.overlaps(prepared, geom)
    const overlapsResult = wasmts.geom.prep.PreparedGeometry.overlaps(prepared, overlappingPoly);
    assert(overlapsResult === true, 'overlaps returns true for overlapping polygon');
    const overlapsContained = wasmts.geom.prep.PreparedGeometry.overlaps(prepared, innerPoly);
    assert(overlapsContained === false, 'overlaps returns false for contained polygon');
    console.log('PASS: PreparedGeometry.overlaps works');

    // Test touches - PreparedGeometry.touches(prepared, geom)
    const touchingPoly = wasmts.io.WKTReader.read('POLYGON ((100 0, 200 0, 200 100, 100 100, 100 0))');
    const touchesResult = wasmts.geom.prep.PreparedGeometry.touches(prepared, touchingPoly);
    assert(touchesResult === true, 'touches returns true for adjacent polygon');
    const notTouches = wasmts.geom.prep.PreparedGeometry.touches(prepared, disjointPoly);
    assert(notTouches === false, 'touches returns false for disjoint polygon');
    console.log('PASS: PreparedGeometry.touches works');

    // Test crosses - PreparedGeometry.crosses(prepared, geom)
    const crossingLine = wasmts.io.WKTReader.read('LINESTRING (-10 50, 110 50)');
    const crossesResult = wasmts.geom.prep.PreparedGeometry.crosses(prepared, crossingLine);
    assert(crossesResult === true, 'crosses returns true for line crossing polygon');
    const nonCrossingLine = wasmts.io.WKTReader.read('LINESTRING (10 10, 90 90)');
    const notCrosses = wasmts.geom.prep.PreparedGeometry.crosses(prepared, nonCrossingLine);
    assert(notCrosses === false, 'crosses returns false for line inside polygon');
    console.log('PASS: PreparedGeometry.crosses works');

    // Performance comparison - run containsProperly multiple times
    const testPoints = [];
    for (let i = 0; i < 10; i++) {
        testPoints.push(wasmts.geom.createPoint(Math.random() * 120 - 10, Math.random() * 120 - 10));
    }

    for (const pt of testPoints) {
        wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, pt);
    }
    console.log('PASS: Ran containsProperly on 10 test points');

    console.log('PASS: All PreparedGeometry predicate tests completed');
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
    const minAreaRect = wasmts.algorithm.MinimumAreaRectangle.getMinimumRectangle(polygon);
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

    // Test MinimumBoundingCircle
    const circle = wasmts.algorithm.MinimumBoundingCircle.getCircle(polygon);
    assert(circle !== null && circle !== undefined, 'MinimumBoundingCircle.getCircle returned geometry');
    assert(circle.type === 'Polygon', 'Bounding circle is a Polygon');
    console.log('PASS: MinimumBoundingCircle.getCircle:', circle.type, 'area:', circle.getArea().toFixed(2));

    const centre = wasmts.algorithm.MinimumBoundingCircle.getCentre(polygon);
    assert(typeof centre.x === 'number' && typeof centre.y === 'number', 'getCentre returns coordinate');
    console.log('PASS: MinimumBoundingCircle.getCentre:', centre);

    const radius = wasmts.algorithm.MinimumBoundingCircle.getRadius(polygon);
    assert(typeof radius === 'number' && radius > 0, 'getRadius returns positive number');
    console.log('PASS: MinimumBoundingCircle.getRadius:', radius.toFixed(2));

    // Test with a simple triangle
    const triangle = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 5 10, 0 0))');
    const triMinRect = wasmts.algorithm.MinimumAreaRectangle.getMinimumRectangle(triangle);
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

    // Test getGeometryType
    const testPoint = wasmts.geom.createPoint(1, 2);
    const pointType = wasmts.geom.getGeometryType(testPoint);
    assert(pointType === 'Point', 'Point type should be "Point"');
    console.log('PASS: getGeometryType for Point:', pointType);

    const lineWkt = 'LINESTRING(0 0, 1 1, 2 0)';
    const testLine = wasmts.io.WKTReader.read(lineWkt);
    const lineType = wasmts.geom.getGeometryType(testLine);
    assert(lineType === 'LineString', 'LineString type should be "LineString"');
    console.log('PASS: getGeometryType for LineString:', lineType);

    const polyWkt = 'POLYGON((0 0, 4 0, 4 4, 0 4, 0 0))';
    const testPoly = wasmts.io.WKTReader.read(polyWkt);
    const polyType = wasmts.geom.getGeometryType(testPoly);
    assert(polyType === 'Polygon', 'Polygon type should be "Polygon"');
    console.log('PASS: getGeometryType for Polygon:', polyType);

    console.log('PASS: All new geometry methods tested');
}

function testGeoJSONIO() {
    // Test GeoJSON namespace exists
    assert(typeof wasmts.io.GeoJSONReader !== 'undefined', 'GeoJSONReader exists');
    assert(typeof wasmts.io.GeoJSONWriter !== 'undefined', 'GeoJSONWriter exists');
    console.log('PASS: GeoJSON namespaces exist');

    // Test Point
    const pointGeoJSON = '{"type":"Point","coordinates":[5,10]}';
    const point = wasmts.io.GeoJSONReader.read(pointGeoJSON);
    assert(point !== null && point !== undefined, 'GeoJSON Point parsed');
    assert(point.type === 'Point', 'GeoJSON Point has correct type');
    const pointCoords = point.getCoordinates();
    assert(pointCoords[0].x === 5, 'Point X coordinate correct');
    assert(pointCoords[0].y === 10, 'Point Y coordinate correct');
    console.log('PASS: GeoJSON Point read:', pointCoords[0]);

    // Test Point write
    const pointOut = wasmts.io.GeoJSONWriter.write(point);
    assert(typeof pointOut === 'string', 'GeoJSONWriter returns string');
    const pointParsed = JSON.parse(pointOut);
    assert(pointParsed.type === 'Point', 'Written GeoJSON has correct type');
    assert(pointParsed.coordinates[0] === 5, 'Written coordinates correct');
    console.log('PASS: GeoJSON Point write:', pointOut);

    // Test LineString
    const lineGeoJSON = '{"type":"LineString","coordinates":[[0,0],[10,10],[20,0]]}';
    const line = wasmts.io.GeoJSONReader.read(lineGeoJSON);
    assert(line.type === 'LineString', 'GeoJSON LineString parsed');
    const lineCoords = line.getCoordinates();
    assert(lineCoords.length === 3, 'LineString has 3 coordinates');
    console.log('PASS: GeoJSON LineString read');

    // Test LineString round-trip
    const lineOut = wasmts.io.GeoJSONWriter.write(line);
    const lineParsed = JSON.parse(lineOut);
    assert(lineParsed.type === 'LineString', 'LineString round-trip type');
    assert(lineParsed.coordinates.length === 3, 'LineString round-trip coords');
    console.log('PASS: GeoJSON LineString round-trip');

    // Test Polygon
    const polyGeoJSON = '{"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,10],[0,0]]]}';
    const poly = wasmts.io.GeoJSONReader.read(polyGeoJSON);
    assert(poly.type === 'Polygon', 'GeoJSON Polygon parsed');
    const polyArea = poly.getArea();
    assert(polyArea === 100, 'Polygon area is 100');
    console.log('PASS: GeoJSON Polygon read, area:', polyArea);

    // Test Polygon round-trip
    const polyOut = wasmts.io.GeoJSONWriter.write(poly);
    const polyParsed = JSON.parse(polyOut);
    assert(polyParsed.type === 'Polygon', 'Polygon round-trip type');
    assert(polyParsed.coordinates[0].length === 5, 'Polygon ring has 5 coords');
    console.log('PASS: GeoJSON Polygon round-trip');

    // Test Polygon with hole
    const polyWithHoleGeoJSON = '{"type":"Polygon","coordinates":[[[0,0],[20,0],[20,20],[0,20],[0,0]],[[5,5],[15,5],[15,15],[5,15],[5,5]]]}';
    const polyWithHole = wasmts.io.GeoJSONReader.read(polyWithHoleGeoJSON);
    assert(polyWithHole.type === 'Polygon', 'Polygon with hole parsed');
    const holeArea = polyWithHole.getArea();
    assert(holeArea === 300, 'Polygon with hole area is 300 (400 - 100)');
    console.log('PASS: GeoJSON Polygon with hole, area:', holeArea);

    // Test MultiPoint
    const multiPointGeoJSON = '{"type":"MultiPoint","coordinates":[[0,0],[10,10],[20,20]]}';
    const multiPoint = wasmts.io.GeoJSONReader.read(multiPointGeoJSON);
    assert(multiPoint.type === 'MultiPoint', 'GeoJSON MultiPoint parsed');
    console.log('PASS: GeoJSON MultiPoint read');

    // Test MultiLineString
    const multiLineGeoJSON = '{"type":"MultiLineString","coordinates":[[[0,0],[10,10]],[[20,20],[30,30]]]}';
    const multiLine = wasmts.io.GeoJSONReader.read(multiLineGeoJSON);
    assert(multiLine.type === 'MultiLineString', 'GeoJSON MultiLineString parsed');
    console.log('PASS: GeoJSON MultiLineString read');

    // Test MultiPolygon
    const multiPolyGeoJSON = '{"type":"MultiPolygon","coordinates":[[[[0,0],[10,0],[10,10],[0,10],[0,0]]],[[[20,20],[30,20],[30,30],[20,30],[20,20]]]]}';
    const multiPoly = wasmts.io.GeoJSONReader.read(multiPolyGeoJSON);
    assert(multiPoly.type === 'MultiPolygon', 'GeoJSON MultiPolygon parsed');
    const multiPolyArea = multiPoly.getArea();
    assert(multiPolyArea === 200, 'MultiPolygon area is 200');
    console.log('PASS: GeoJSON MultiPolygon read, area:', multiPolyArea);

    // Test GeometryCollection
    const gcGeoJSON = '{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[0,0]},{"type":"LineString","coordinates":[[0,0],[10,10]]}]}';
    const gc = wasmts.io.GeoJSONReader.read(gcGeoJSON);
    assert(gc.type === 'GeometryCollection', 'GeoJSON GeometryCollection parsed');
    console.log('PASS: GeoJSON GeometryCollection read');

    // Test 3D coordinates
    const point3dGeoJSON = '{"type":"Point","coordinates":[5,10,15]}';
    const point3d = wasmts.io.GeoJSONReader.read(point3dGeoJSON);
    const coords3d = point3d.getCoordinates();
    assert(coords3d[0].z === 15, '3D Point Z coordinate preserved');
    console.log('PASS: GeoJSON 3D Point read, z:', coords3d[0].z);

    // Test 3D round-trip
    const point3dOut = wasmts.io.GeoJSONWriter.write(point3d);
    const point3dParsed = JSON.parse(point3dOut);
    assert(point3dParsed.coordinates.length === 3, '3D Point has 3 coordinates');
    assert(point3dParsed.coordinates[2] === 15, '3D Z coordinate in output');
    console.log('PASS: GeoJSON 3D round-trip');

    // Test buffer result to GeoJSON
    const buffered = wasmts.geom.createPoint(0, 0).buffer(10);
    const bufferedGeoJSON = wasmts.io.GeoJSONWriter.write(buffered);
    const bufferedParsed = JSON.parse(bufferedGeoJSON);
    assert(bufferedParsed.type === 'Polygon', 'Buffered point is Polygon in GeoJSON');
    assert(bufferedParsed.coordinates[0].length > 10, 'Buffer has many vertices');
    console.log('PASS: Buffer to GeoJSON, vertices:', bufferedParsed.coordinates[0].length);

    // Test GeoJSONWriter instance API (1:1 coverage)
    console.log('\n--- GeoJSON Instance API Tests ---\n');

    // Test GeoJSONWriter.create()
    const writer = wasmts.io.GeoJSONWriter.create();
    assert(writer !== null && writer !== undefined, 'GeoJSONWriter.create() works');
    assert(typeof writer.write === 'function', 'Writer has write method');
    assert(typeof writer.setEncodeCRS === 'function', 'Writer has setEncodeCRS method');
    assert(typeof writer.setForceCCW === 'function', 'Writer has setForceCCW method');
    console.log('PASS: GeoJSONWriter.create() returns writer with instance methods');

    // Test writer.write()
    const testPoint = wasmts.geom.createPoint(1, 2);
    const writerOutput = writer.write(testPoint);
    assert(typeof writerOutput === 'string', 'writer.write() returns string');
    const writerParsed = JSON.parse(writerOutput);
    assert(writerParsed.type === 'Point', 'writer.write() outputs correct type');
    console.log('PASS: writer.write() works');

    // Test setEncodeCRS(false) - disable CRS output
    writer.setEncodeCRS(false);
    const noCrsOutput = writer.write(testPoint);
    const noCrsParsed = JSON.parse(noCrsOutput);
    assert(noCrsParsed.crs === undefined, 'setEncodeCRS(false) removes CRS from output');
    console.log('PASS: writer.setEncodeCRS(false) works');

    // Test setEncodeCRS(true) - enable CRS output
    writer.setEncodeCRS(true);
    const withCrsOutput = writer.write(testPoint);
    const withCrsParsed = JSON.parse(withCrsOutput);
    assert(withCrsParsed.crs !== undefined, 'setEncodeCRS(true) includes CRS in output');
    console.log('PASS: writer.setEncodeCRS(true) works');

    // Test GeoJSONWriter.createWithDecimals()
    const writerDecimals = wasmts.io.GeoJSONWriter.createWithDecimals(2);
    assert(writerDecimals !== null, 'GeoJSONWriter.createWithDecimals() works');
    const precisePoint = wasmts.geom.createPoint(1.123456789, 2.987654321);
    writerDecimals.setEncodeCRS(false);
    const decimalsOutput = writerDecimals.write(precisePoint);
    const decimalsParsed = JSON.parse(decimalsOutput);
    // With 2 decimals, coordinates should be rounded
    assert(decimalsParsed.coordinates[0] === 1.12 || decimalsParsed.coordinates[0] === 1.1,
           'Decimals parameter limits precision');
    console.log('PASS: GeoJSONWriter.createWithDecimals() limits precision');

    // Test setForceCCW()
    const ccwWriter = wasmts.io.GeoJSONWriter.create();
    ccwWriter.setEncodeCRS(false);
    ccwWriter.setForceCCW(true);
    // Create a clockwise polygon
    const cwPoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 0 10, 10 10, 10 0, 0 0))');
    const ccwOutput = ccwWriter.write(cwPoly);
    const ccwParsed = JSON.parse(ccwOutput);
    assert(ccwParsed.type === 'Polygon', 'setForceCCW outputs polygon');
    console.log('PASS: writer.setForceCCW() works');

    // Test GeoJSONReader.create()
    const reader = wasmts.io.GeoJSONReader.create();
    assert(reader !== null && reader !== undefined, 'GeoJSONReader.create() works');
    assert(typeof reader.read === 'function', 'Reader has read method');
    console.log('PASS: GeoJSONReader.create() returns reader with instance methods');

    // Test reader.read()
    const readerInput = '{"type":"Point","coordinates":[3,4]}';
    const readerGeom = reader.read(readerInput);
    assert(readerGeom.type === 'Point', 'reader.read() parses geometry');
    const readerCoords = readerGeom.getCoordinates();
    assert(readerCoords[0].x === 3, 'reader.read() coordinates correct');
    console.log('PASS: reader.read() works');

    console.log('PASS: All GeoJSON instance API tests completed');

    console.log('PASS: All GeoJSON I/O tests completed');
}

function testPolygonAccessors() {
    // Test polygon without holes
    const simplePoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');

    // Test getExteriorRing
    const exteriorRing = simplePoly.getExteriorRing();
    assert(exteriorRing !== null && exteriorRing !== undefined, 'getExteriorRing returns geometry');
    assert(exteriorRing.type === 'LinearRing', 'Exterior ring is LinearRing');
    const ringCoords = exteriorRing.getCoordinates();
    assert(ringCoords.length === 5, 'Exterior ring has 5 coordinates (closed)');
    console.log('PASS: getExteriorRing returns LinearRing with', ringCoords.length, 'coordinates');

    // Test getNumInteriorRing on polygon without holes
    const numInterior = simplePoly.getNumInteriorRing();
    assert(numInterior === 0, 'Simple polygon has 0 interior rings');
    console.log('PASS: getNumInteriorRing returns 0 for simple polygon');

    // Test polygon with hole
    const polyWithHole = wasmts.io.WKTReader.read('POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0), (5 5, 15 5, 15 15, 5 15, 5 5))');
    const numHoles = polyWithHole.getNumInteriorRing();
    assert(numHoles === 1, 'Polygon with hole has 1 interior ring');
    console.log('PASS: getNumInteriorRing returns 1 for polygon with hole');

    // Test getInteriorRingN
    const hole = polyWithHole.getInteriorRingN(0);
    assert(hole !== null && hole !== undefined, 'getInteriorRingN returns geometry');
    assert(hole.type === 'LinearRing', 'Interior ring is LinearRing');
    const holeCoords = hole.getCoordinates();
    assert(holeCoords.length === 5, 'Interior ring has 5 coordinates (closed)');
    console.log('PASS: getInteriorRingN returns interior ring with', holeCoords.length, 'coordinates');

    // Verify hole coordinates
    assert(holeCoords[0].x === 5, 'First hole coordinate X is 5');
    assert(holeCoords[0].y === 5, 'First hole coordinate Y is 5');
    console.log('PASS: Interior ring coordinates are correct');

    // Test polygon with multiple holes
    const multiHolePoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0), (2 2, 8 2, 8 8, 2 8, 2 2), (12 12, 18 12, 18 18, 12 18, 12 12))');
    const numMultiHoles = multiHolePoly.getNumInteriorRing();
    assert(numMultiHoles === 2, 'Polygon has 2 interior rings');
    console.log('PASS: getNumInteriorRing returns 2 for polygon with 2 holes');

    // Test getting both interior rings
    const hole1 = multiHolePoly.getInteriorRingN(0);
    const hole2 = multiHolePoly.getInteriorRingN(1);
    assert(hole1 !== null && hole2 !== null, 'Both interior rings accessible');
    console.log('PASS: Both interior rings accessible via getInteriorRingN');

    // Test functional API (wasmts.geom.*)
    const exteriorFunctional = wasmts.geom.getExteriorRing(simplePoly);
    assert(exteriorFunctional.type === 'LinearRing', 'Functional API getExteriorRing works');
    console.log('PASS: Functional API wasmts.geom.getExteriorRing works');

    const numRingsFunctional = wasmts.geom.getNumInteriorRing(polyWithHole);
    assert(numRingsFunctional === 1, 'Functional API getNumInteriorRing works');
    console.log('PASS: Functional API wasmts.geom.getNumInteriorRing works');

    const holeFunctional = wasmts.geom.getInteriorRingN(polyWithHole, 0);
    assert(holeFunctional.type === 'LinearRing', 'Functional API getInteriorRingN works');
    console.log('PASS: Functional API wasmts.geom.getInteriorRingN works');

    // Test error handling for non-polygon
    let errorThrown = false;
    try {
        const point = wasmts.geom.createPoint(5, 10);
        wasmts.geom.getExteriorRing(point);
    } catch (e) {
        errorThrown = true;
    }
    assert(errorThrown, 'getExteriorRing throws error for non-polygon');
    console.log('PASS: getExteriorRing throws error for non-polygon geometry');

    console.log('PASS: All polygon accessor tests completed');
}

function testDistanceOperations() {
    // Test nearestPoints between two disjoint geometries
    const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const poly2 = wasmts.io.WKTReader.read('POLYGON ((20 0, 30 0, 30 10, 20 10, 20 0))');

    const nearest = wasmts.geom.nearestPoints(poly1, poly2);
    assert(Array.isArray(nearest), 'nearestPoints returns array');
    assert(nearest.length === 2, 'nearestPoints returns 2 points');
    console.log('PASS: nearestPoints returns array of 2 points');

    // Verify both points have x/y coordinates
    assert(typeof nearest[0].x === 'number', 'First point has x coordinate');
    assert(typeof nearest[0].y === 'number', 'First point has y coordinate');
    assert(typeof nearest[1].x === 'number', 'Second point has x coordinate');
    assert(typeof nearest[1].y === 'number', 'Second point has y coordinate');
    console.log('PASS: nearestPoints coordinates:', nearest[0], '->', nearest[1]);

    // Verify distance matches calculated distance from nearest points
    const dist = poly1.distance(poly2);
    const calculatedDist = Math.sqrt(
        Math.pow(nearest[1].x - nearest[0].x, 2) +
        Math.pow(nearest[1].y - nearest[0].y, 2)
    );
    assert(Math.abs(dist - calculatedDist) < 0.001, 'Distance matches calculated from nearest points');
    console.log('PASS: Distance between geometries:', dist);

    // Test with point and line
    const point = wasmts.geom.createPoint(5, 5);
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0)');
    const nearestPtLine = wasmts.geom.nearestPoints(point, line);
    assert(nearestPtLine.length === 2, 'Point-line returns 2 points');
    // First point should be the original point, second should be on the line
    assert(nearestPtLine[0].y === 5 || nearestPtLine[1].y === 5, 'One point at y=5 (original point)');
    assert(nearestPtLine[0].y === 0 || nearestPtLine[1].y === 0, 'One point at y=0 (on line)');
    console.log('PASS: Point to line nearest points:', nearestPtLine[0], '->', nearestPtLine[1]);

    console.log('PASS: All distance operation tests completed');
}

function testGeometryFactory() {
    // Test createLineString
    const lineCoords = [
        { x: 0, y: 0 },
        { x: 10, y: 10 },
        { x: 20, y: 0 }
    ];
    const line = wasmts.geom.createLineString(lineCoords);
    assert(line !== null, 'createLineString returns geometry');
    assert(line.type === 'LineString', 'createLineString creates LineString');
    assert(line.getNumPoints() === 3, 'LineString has 3 points');
    const retrievedCoords = line.getCoordinates();
    assert(retrievedCoords[0].x === 0 && retrievedCoords[0].y === 0, 'First coordinate correct');
    assert(retrievedCoords[1].x === 10 && retrievedCoords[1].y === 10, 'Second coordinate correct');
    assert(retrievedCoords[2].x === 20 && retrievedCoords[2].y === 0, 'Third coordinate correct');
    console.log('PASS: createLineString creates valid LineString');

    // Test createLineString with 3D coordinates
    const line3DCoords = [
        { x: 0, y: 0, z: 0 },
        { x: 10, y: 10, z: 5 },
        { x: 20, y: 0, z: 10 }
    ];
    const line3D = wasmts.geom.createLineString(line3DCoords);
    assert(line3D.type === 'LineString', 'createLineString creates 3D LineString');
    const coords3D = line3D.getCoordinates();
    assert(coords3D[1].z === 5, '3D LineString preserves Z coordinate');
    console.log('PASS: createLineString supports 3D coordinates');

    // Test createPolygon (simple polygon without holes)
    const shellCoords = [
        { x: 0, y: 0 },
        { x: 100, y: 0 },
        { x: 100, y: 100 },
        { x: 0, y: 100 },
        { x: 0, y: 0 }  // Closed ring
    ];
    const polygon = wasmts.geom.createPolygon(shellCoords);
    assert(polygon !== null, 'createPolygon returns geometry');
    assert(polygon.type === 'Polygon', 'createPolygon creates Polygon');
    const area = polygon.getArea();
    assert(area === 10000, 'Polygon has correct area');
    assert(polygon.getNumInteriorRing() === 0, 'Simple polygon has no holes');
    console.log('PASS: createPolygon creates valid Polygon, area:', area);

    // Test createPolygon with holes
    const holeCoords = [
        { x: 25, y: 25 },
        { x: 75, y: 25 },
        { x: 75, y: 75 },
        { x: 25, y: 75 },
        { x: 25, y: 25 }  // Closed ring
    ];
    const polygonWithHole = wasmts.geom.createPolygon(shellCoords, [holeCoords]);
    assert(polygonWithHole !== null, 'createPolygon with hole returns geometry');
    assert(polygonWithHole.type === 'Polygon', 'createPolygon creates Polygon with hole');
    assert(polygonWithHole.getNumInteriorRing() === 1, 'Polygon has 1 hole');
    const areaWithHole = polygonWithHole.getArea();
    assert(areaWithHole === 7500, 'Polygon area minus hole area is correct');  // 10000 - 2500 = 7500
    console.log('PASS: createPolygon with hole, area:', areaWithHole);

    // Test createPolygon with multiple holes
    const hole1 = [
        { x: 10, y: 10 },
        { x: 30, y: 10 },
        { x: 30, y: 30 },
        { x: 10, y: 30 },
        { x: 10, y: 10 }
    ];
    const hole2 = [
        { x: 60, y: 60 },
        { x: 90, y: 60 },
        { x: 90, y: 90 },
        { x: 60, y: 90 },
        { x: 60, y: 60 }
    ];
    const polygonTwoHoles = wasmts.geom.createPolygon(shellCoords, [hole1, hole2]);
    assert(polygonTwoHoles.getNumInteriorRing() === 2, 'Polygon has 2 holes');
    const areaTwoHoles = polygonTwoHoles.getArea();
    // hole1: 20*20 = 400, hole2: 30*30 = 900, total = 10000 - 400 - 900 = 8700
    assert(areaTwoHoles === 8700, 'Polygon area with 2 holes correct');
    console.log('PASS: createPolygon with 2 holes, area:', areaTwoHoles);

    // Test createPolygon with 3D coordinates
    const shell3D = [
        { x: 0, y: 0, z: 0 },
        { x: 10, y: 0, z: 0 },
        { x: 10, y: 10, z: 0 },
        { x: 0, y: 10, z: 0 },
        { x: 0, y: 0, z: 0 }
    ];
    const polygon3D = wasmts.geom.createPolygon(shell3D);
    assert(polygon3D.type === 'Polygon', 'createPolygon creates 3D Polygon');
    const poly3DCoords = polygon3D.getCoordinates();
    assert(poly3DCoords[0].z === 0, '3D Polygon preserves Z coordinate');
    console.log('PASS: createPolygon supports 3D coordinates');

    // Test that created geometries can be used in operations
    const buffered = line.buffer(5);
    assert(buffered.type === 'Polygon', 'Created LineString can be buffered');
    console.log('PASS: Created geometries work with operations');

    // Test validation - created polygon is valid
    assert(polygon.isValid() === true, 'Created polygon is valid');
    assert(polygonWithHole.isValid() === true, 'Created polygon with hole is valid');
    console.log('PASS: Created polygons are valid');

    console.log('Testing createLinearRing(coords)...');
    const ringCoords = [
        { x: 0, y: 0 },
        { x: 10, y: 0 },
        { x: 10, y: 10 },
        { x: 0, y: 10 },
        { x: 0, y: 0 }
    ];
    const ring = wasmts.geom.createLinearRing(ringCoords);
    assert(ring !== null, 'createLinearRing returns geometry');
    assert(ring.type === 'LinearRing', 'createLinearRing creates LinearRing');
    assert(ring.isClosed() === true, 'LinearRing is closed');
    assert(ring.getNumPoints() === 5, 'LinearRing has 5 points');
    console.log('PASS: createLinearRing(coords)');

    console.log('Testing createMultiPoint(points)...');
    const p1 = wasmts.geom.createPoint(0, 0);
    const p2 = wasmts.geom.createPoint(10, 10);
    const p3 = wasmts.geom.createPoint(20, 0);
    const multiPoint = wasmts.geom.createMultiPoint([p1, p2, p3]);
    assert(multiPoint !== null, 'createMultiPoint returns geometry');
    assert(multiPoint.type === 'MultiPoint', 'createMultiPoint creates MultiPoint');
    assert(multiPoint.getNumGeometries() === 3, 'MultiPoint has 3 points');
    const mp0 = multiPoint.getGeometryN(0);
    assert(mp0.getX() === 0 && mp0.getY() === 0, 'First point is (0,0)');
    console.log('PASS: createMultiPoint(points)');

    console.log('Testing createMultiLineString(lines)...');
    const ls1 = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10)');
    const ls2 = wasmts.io.WKTReader.read('LINESTRING (20 20, 30 30)');
    const multiLine = wasmts.geom.createMultiLineString([ls1, ls2]);
    assert(multiLine !== null, 'createMultiLineString returns geometry');
    assert(multiLine.type === 'MultiLineString', 'createMultiLineString creates MultiLineString');
    assert(multiLine.getNumGeometries() === 2, 'MultiLineString has 2 linestrings');
    console.log('PASS: createMultiLineString(lines)');

    console.log('Testing createMultiPolygon(polygons)...');
    const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const poly2 = wasmts.io.WKTReader.read('POLYGON ((20 20, 30 20, 30 30, 20 30, 20 20))');
    const multiPoly = wasmts.geom.createMultiPolygon([poly1, poly2]);
    assert(multiPoly !== null, 'createMultiPolygon returns geometry');
    assert(multiPoly.type === 'MultiPolygon', 'createMultiPolygon creates MultiPolygon');
    assert(multiPoly.getNumGeometries() === 2, 'MultiPolygon has 2 polygons');
    assert(multiPoly.getArea() === 200, 'MultiPolygon area is sum of both (100 + 100)');
    console.log('PASS: createMultiPolygon(polygons)');

    console.log('Testing createGeometryCollection(geoms)...');
    const gcPoint = wasmts.geom.createPoint(5, 5);
    const gcLine = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10)');
    const gcPoly = wasmts.io.WKTReader.read('POLYGON ((20 20, 30 20, 30 30, 20 30, 20 20))');
    const geomCollection = wasmts.geom.createGeometryCollection([gcPoint, gcLine, gcPoly]);
    assert(geomCollection !== null, 'createGeometryCollection returns geometry');
    assert(geomCollection.type === 'GeometryCollection', 'createGeometryCollection creates GeometryCollection');
    assert(geomCollection.getNumGeometries() === 3, 'GeometryCollection has 3 geometries');
    assert(geomCollection.getGeometryN(0).type === 'Point', 'First geometry is Point');
    assert(geomCollection.getGeometryN(1).type === 'LineString', 'Second geometry is LineString');
    assert(geomCollection.getGeometryN(2).type === 'Polygon', 'Third geometry is Polygon');
    console.log('PASS: createGeometryCollection(geoms)');

    console.log('Testing createEmpty(dimension)...');
    const emptyPoint = wasmts.geom.createEmpty(0);
    assert(emptyPoint !== null, 'createEmpty(0) returns geometry');
    assert(emptyPoint.type === 'Point', 'createEmpty(0) creates Point');
    assert(emptyPoint.isEmpty() === true, 'createEmpty(0) creates empty Point');
    const emptyLine = wasmts.geom.createEmpty(1);
    assert(emptyLine.type === 'LineString', 'createEmpty(1) creates LineString');
    assert(emptyLine.isEmpty() === true, 'createEmpty(1) creates empty LineString');
    const emptyPoly = wasmts.geom.createEmpty(2);
    assert(emptyPoly.type === 'Polygon', 'createEmpty(2) creates Polygon');
    assert(emptyPoly.isEmpty() === true, 'createEmpty(2) creates empty Polygon');
    console.log('PASS: createEmpty(dimension)');

    console.log('Testing toGeometry(envelope)...');
    const env = wasmts.geom.createEnvelope(0, 10, 0, 20);
    const envGeom = wasmts.geom.toGeometry(env);
    assert(envGeom !== null, 'toGeometry returns geometry');
    assert(envGeom.type === 'Polygon', 'toGeometry creates Polygon from envelope');
    assert(envGeom.getArea() === 200, 'toGeometry Polygon has correct area (10 * 20 = 200)');
    const envCoords = envGeom.getCoordinates();
    assert(envCoords.length === 5, 'Envelope polygon has 5 coordinates (closed)');
    console.log('PASS: toGeometry(envelope)');

    console.log('PASS: All geometry factory tests completed');
}

function testCoordinateSequence() {
    // Test CoordinateSequence wrapper methods via geometry.apply()
    // The seq parameter in apply callback is a full CoordinateSequence wrapper

    // Test 2D CoordinateSequence
    const poly2d = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    let seq2d = null;
    poly2d.apply((seq, i) => {
        if (i === 0) seq2d = seq;
    });

    assert(seq2d !== null, 'CoordinateSequence captured from apply');
    assert(typeof seq2d.size === 'function', 'seq.size is a function');
    assert(seq2d.size() === 5, 'size() returns 5 for closed polygon');
    console.log('PASS: CoordinateSequence.size() = ' + seq2d.size());

    assert(typeof seq2d.getDimension === 'function', 'seq.getDimension is a function');
    assert(seq2d.getDimension() >= 2, 'getDimension() >= 2 for 2D');
    console.log('PASS: CoordinateSequence.getDimension() = ' + seq2d.getDimension());

    assert(typeof seq2d.getMeasures === 'function', 'seq.getMeasures is a function');
    assert(seq2d.getMeasures() === 0, 'getMeasures() = 0 for 2D geometry');
    console.log('PASS: CoordinateSequence.getMeasures() = ' + seq2d.getMeasures());

    assert(typeof seq2d.hasZ === 'function', 'seq.hasZ is a function');
    assert(typeof seq2d.hasM === 'function', 'seq.hasM is a function');
    console.log('PASS: CoordinateSequence.hasZ() = ' + seq2d.hasZ() + ', hasM() = ' + seq2d.hasM());

    // Test getX, getY, getZ, getM
    assert(seq2d.getX(0) === 0, 'getX(0) = 0');
    assert(seq2d.getY(0) === 0, 'getY(0) = 0');
    assert(seq2d.getX(1) === 10, 'getX(1) = 10');
    assert(seq2d.getY(1) === 0, 'getY(1) = 0');
    console.log('PASS: CoordinateSequence.getX/getY work correctly');

    // Test getOrdinate
    assert(seq2d.getOrdinate(0, 0) === 0, 'getOrdinate(0, 0) = X = 0');
    assert(seq2d.getOrdinate(0, 1) === 0, 'getOrdinate(0, 1) = Y = 0');
    assert(seq2d.getOrdinate(2, 0) === 10, 'getOrdinate(2, 0) = X = 10');
    assert(seq2d.getOrdinate(2, 1) === 10, 'getOrdinate(2, 1) = Y = 10');
    console.log('PASS: CoordinateSequence.getOrdinate works correctly');

    // Test getCoordinate
    const coord0 = seq2d.getCoordinate(0);
    assert(coord0.x === 0 && coord0.y === 0, 'getCoordinate(0) returns {x:0, y:0}');
    const coord2 = seq2d.getCoordinate(2);
    assert(coord2.x === 10 && coord2.y === 10, 'getCoordinate(2) returns {x:10, y:10}');
    console.log('PASS: CoordinateSequence.getCoordinate returns coordinate objects');

    // Test toCoordinateArray
    const allCoords = seq2d.toCoordinateArray();
    assert(Array.isArray(allCoords), 'toCoordinateArray returns array');
    assert(allCoords.length === 5, 'toCoordinateArray has 5 elements');
    assert(allCoords[0].x === 0 && allCoords[0].y === 0, 'First coord correct');
    assert(allCoords[2].x === 10 && allCoords[2].y === 10, 'Third coord correct');
    console.log('PASS: CoordinateSequence.toCoordinateArray works');

    // Test copy
    const seqCopy = seq2d.copy();
    assert(seqCopy !== null, 'copy() returns a new sequence');
    assert(seqCopy.size() === seq2d.size(), 'copy has same size');
    assert(seqCopy.getX(1) === seq2d.getX(1), 'copy has same coordinates');
    console.log('PASS: CoordinateSequence.copy works');

    // Test 3D CoordinateSequence
    const point3d = wasmts.geom.createPoint(5, 10, 15);
    let seq3d = null;
    point3d.apply((seq, i) => {
        if (i === 0) seq3d = seq;
    });

    assert(seq3d.getDimension() >= 3, '3D sequence dimension >= 3');
    assert(seq3d.hasZ() === true, '3D sequence hasZ() = true');
    assert(seq3d.getZ(0) === 15, 'getZ(0) = 15');
    assert(seq3d.getOrdinate(0, 2) === 15, 'getOrdinate(0, 2) = Z = 15');
    console.log('PASS: 3D CoordinateSequence works, Z = ' + seq3d.getZ(0));

    // Test 4D CoordinateSequence (XYZM)
    const point4d = wasmts.geom.createPoint(1, 2, 3, 4);
    let seq4d = null;
    point4d.apply((seq, i) => {
        if (i === 0) seq4d = seq;
    });

    assert(seq4d.getDimension() >= 4, '4D sequence dimension >= 4');
    assert(seq4d.hasZ() === true, '4D sequence hasZ() = true');
    assert(seq4d.hasM() === true, '4D sequence hasM() = true');
    assert(seq4d.getMeasures() === 1, '4D sequence getMeasures() = 1');
    assert(seq4d.getZ(0) === 3, '4D getZ(0) = 3');
    assert(seq4d.getM(0) === 4, '4D getM(0) = 4');
    console.log('PASS: 4D CoordinateSequence works, Z = ' + seq4d.getZ(0) + ', M = ' + seq4d.getM(0));

    // Test coordinate from 4D
    const coord4d = seq4d.getCoordinate(0);
    assert(coord4d.x === 1, '4D coord x = 1');
    assert(coord4d.y === 2, '4D coord y = 2');
    assert(coord4d.z === 3, '4D coord z = 3');
    assert(coord4d.m === 4, '4D coord m = 4');
    console.log('PASS: 4D getCoordinate returns {x, y, z, m}:', coord4d);

    console.log('PASS: All CoordinateSequence tests completed');
}

function testCoordinateSequenceFilter() {
    // Test translation using JTS-style API: filter(seq, i) with seq.setOrdinate()
    // Matches JTS: geometry.apply(CoordinateSequenceFilter)
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');

    // Apply filter - translate by (10, 5) using JTS CoordinateSequenceFilter pattern
    const translated = poly.apply((seq, i) => {
        seq.setOrdinate(i, 0, seq.getX(i) + 10);  // X ordinate
        seq.setOrdinate(i, 1, seq.getY(i) + 5);   // Y ordinate
    });

    assert(translated !== null, 'apply returns geometry');
    assert(translated.type === 'Polygon', 'Result is same geometry type');

    const translatedCoords = translated.getCoordinates();
    assert(translatedCoords[0].x === 10, 'First coord X shifted by 10');
    assert(translatedCoords[0].y === 5, 'First coord Y shifted by 5');
    assert(translatedCoords[1].x === 20, 'Second coord X shifted by 10');
    assert(translatedCoords[1].y === 5, 'Second coord Y shifted by 5');
    console.log('PASS: Translation filter works, first coord:', translatedCoords[0]);

    // Verify original is unchanged (immutability)
    const origCoordsAfter = poly.getCoordinates();
    assert(origCoordsAfter[0].x === 0, 'Original geometry unchanged');
    console.log('PASS: Original geometry is unchanged (immutable)');

    // Verify area is preserved
    assert(translated.getArea() === poly.getArea(), 'Area preserved after translation');
    console.log('PASS: Area preserved after translation:', translated.getArea());

    // Test scaling - double all coordinates
    const scaled = poly.apply((seq, i) => {
        seq.setOrdinate(i, 0, seq.getX(i) * 2);
        seq.setOrdinate(i, 1, seq.getY(i) * 2);
    });
    const scaledCoords = scaled.getCoordinates();
    assert(scaledCoords[1].x === 20, 'Scaled X doubled');
    assert(scaledCoords[1].y === 0, 'Scaled Y correct');
    assert(scaled.getArea() === poly.getArea() * 4, 'Area quadrupled after 2x scale');
    console.log('PASS: Scale filter works, area:', scaled.getArea());

    // Test on Point
    const point = wasmts.geom.createPoint(5, 10);
    const movedPoint = point.apply((seq, i) => {
        seq.setOrdinate(i, 0, seq.getX(i) + 100);
        seq.setOrdinate(i, 1, seq.getY(i) + 100);
    });
    const movedCoords = movedPoint.getCoordinates();
    assert(movedCoords[0].x === 105, 'Point X moved');
    assert(movedCoords[0].y === 110, 'Point Y moved');
    console.log('PASS: Filter works on Point:', movedCoords[0]);

    // Test on LineString
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10, 20 0)');
    const rotatedLine = line.apply((seq, i) => {
        // Simple 90 degree rotation around origin
        const x = seq.getX(i);
        const y = seq.getY(i);
        seq.setOrdinate(i, 0, -y);
        seq.setOrdinate(i, 1, x);
    });
    const rotatedCoords = rotatedLine.getCoordinates();
    assert(rotatedCoords[1].x === -10, 'Rotated point X correct');
    assert(rotatedCoords[1].y === 10, 'Rotated point Y correct');
    console.log('PASS: Filter works on LineString (rotation)');

    // Test 3D coordinate filter
    const point3d = wasmts.geom.createPoint(5, 10, 15);
    const lifted = point3d.apply((seq, i) => {
        if (seq.hasZ()) {
            seq.setOrdinate(i, 2, seq.getZ(i) + 100);
        }
    });
    const liftedCoords = lifted.getCoordinates();
    assert(liftedCoords[0].z === 115, '3D Z coordinate modified');
    console.log('PASS: Filter works on 3D coordinates, z:', liftedCoords[0].z);

    // Test functional API
    const funcTranslated = wasmts.geom.apply(poly, (seq, i) => {
        seq.setOrdinate(i, 0, seq.getX(i) + 50);
        seq.setOrdinate(i, 1, seq.getY(i) + 50);
    });
    const funcCoords = funcTranslated.getCoordinates();
    assert(funcCoords[0].x === 50, 'Functional API works');
    console.log('PASS: Functional API wasmts.geom.apply works');

    // Test index parameter
    let indexSum = 0;
    poly.apply((seq, i) => {
        indexSum += i;
    });
    assert(indexSum === 0 + 1 + 2 + 3 + 4, 'Index parameter works (sum: 0+1+2+3+4=10)');
    console.log('PASS: Index parameter correctly passed, sum:', indexSum);

    // Test CoordinateSequence helper methods
    const testPoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    let seqSize = 0;
    let seqDim = 0;
    testPoly.apply((seq, i) => {
        if (i === 0) {
            seqSize = seq.size();
            seqDim = seq.getDimension();
        }
    });
    assert(seqSize === 5, 'seq.size() returns correct count');
    assert(seqDim >= 2, 'seq.getDimension() returns >= 2');
    console.log('PASS: CoordinateSequence helper methods work, size:', seqSize, 'dim:', seqDim);

    // Test getCoordinate
    let firstCoord = null;
    testPoly.apply((seq, i) => {
        if (i === 0) {
            firstCoord = seq.getCoordinate(0);
        }
    });
    assert(firstCoord !== null, 'getCoordinate returns coordinate');
    assert(firstCoord.x === 0 && firstCoord.y === 0, 'getCoordinate returns correct values');
    console.log('PASS: seq.getCoordinate() works:', firstCoord);

    console.log('PASS: All coordinate sequence filter tests completed');
}

function testGeometryBaseClass() {
    console.log('Testing getDimension()...');
    const point = wasmts.geom.createPoint(0, 0);
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10)');
    const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');

    assert(point.getDimension() === 0, 'Point.getDimension() returns 0');
    assert(line.getDimension() === 1, 'LineString.getDimension() returns 1');
    assert(polygon.getDimension() === 2, 'Polygon.getDimension() returns 2');
    console.log('PASS: getDimension()');

    console.log('Testing getBoundaryDimension()...');
    assert(point.getBoundaryDimension() === -1, 'Point.getBoundaryDimension() returns -1');
    assert(line.getBoundaryDimension() === 0, 'LineString.getBoundaryDimension() returns 0');
    assert(polygon.getBoundaryDimension() === 1, 'Polygon.getBoundaryDimension() returns 1');
    console.log('PASS: getBoundaryDimension()');

    console.log('Testing relate(g, pattern)...');
    const interiorPoint = wasmts.geom.createPoint(5, 5);
    // T*****FF* is the DE-9IM pattern for "contains"
    assert(polygon.relate(interiorPoint, 'T*****FF*') === true, 'Polygon.relate(interiorPoint, pattern) returns true');
    console.log('PASS: relate(g, pattern)');

    console.log('Testing relate(g) returns IntersectionMatrix...');
    const matrix = polygon.relate(interiorPoint);
    assert(matrix !== null && matrix !== undefined, 'relate(g) returns matrix');
    assert(typeof matrix.toString === 'function', 'IntersectionMatrix has toString()');
    const matrixStr = matrix.toString();
    assert(matrixStr.length === 9, 'IntersectionMatrix.toString() returns 9-char string');
    console.log('PASS: relate(g) returns IntersectionMatrix:', matrixStr);

    console.log('Testing equalsExact(g, tolerance)...');
    const p1 = wasmts.geom.createPoint(0, 0);
    const p2 = wasmts.geom.createPoint(0.0001, 0);
    assert(p1.equalsExact(p2, 0.001) === true, 'Points within tolerance are equalsExact');
    assert(p1.equalsExact(p2, 0.00001) === false, 'Points outside tolerance are not equalsExact');
    console.log('PASS: equalsExact(g, tolerance)');

    console.log('Testing equalsNorm(g)...');
    const line1 = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10)');
    const line2 = wasmts.io.WKTReader.read('LINESTRING (10 10, 0 0)');
    assert(line1.equalsNorm(line2) === true, 'Reversed LineString equalsNorm original');
    console.log('PASS: equalsNorm(g)');

    console.log('Testing isWithinDistance(g, distance)...');
    const pA = wasmts.geom.createPoint(0, 0);
    const pB = wasmts.geom.createPoint(5, 0);
    assert(pA.isWithinDistance(pB, 6) === true, 'Points 5 apart are within distance 6');
    assert(pA.isWithinDistance(pB, 4) === false, 'Points 5 apart are not within distance 4');
    console.log('PASS: isWithinDistance(g, distance)');

    console.log('Testing getSRID() / setSRID()...');
    const geom = wasmts.geom.createPoint(0, 0);
    assert(geom.getSRID() === 0, 'Default SRID is 0');
    geom.setSRID(4326);
    assert(geom.getSRID() === 4326, 'SRID set to 4326');
    console.log('PASS: getSRID() / setSRID()');

    console.log('Testing union() no-arg...');
    const multi = wasmts.io.WKTReader.read('MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0)), ((5 5, 15 5, 15 15, 5 15, 5 5)))');
    const unioned = multi.union();
    assert(unioned.type === 'Polygon' || unioned.type === 'MultiPolygon', 'union() returns polygon geometry');
    console.log('PASS: union() no-arg');

    console.log('PASS: All Geometry base class tests completed');
}

function testIntersectionMatrix() {
    console.log('Testing IntersectionMatrix constructor and basic methods...');

    // Get a matrix from relate() first
    const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const point = wasmts.geom.createPoint(5, 5);
    const matrix = polygon.relate(point);

    console.log('Testing IntersectionMatrix.toString()...');
    const str = matrix.toString();
    assert(str.length === 9, 'toString() returns 9-char string');
    console.log('PASS: toString() =', str);

    console.log('Testing IntersectionMatrix.get(row, col)...');
    const val = matrix.get(0, 0);
    assert(typeof val === 'number', 'get() returns number');
    console.log('PASS: get(0,0) =', val);

    console.log('Testing IntersectionMatrix.matches(pattern)...');
    assert(typeof matrix.matches === 'function', 'matches() method exists');
    // Interior-Interior should match 'T********' or '0********'
    const matchesPattern = matrix.matches('T********');
    assert(typeof matchesPattern === 'boolean', 'matches() returns boolean');
    console.log('PASS: matches(pattern) =', matchesPattern);

    console.log('Testing IntersectionMatrix predicates...');
    assert(typeof matrix.isContains === 'function', 'isContains() exists');
    assert(typeof matrix.isWithin === 'function', 'isWithin() exists');
    assert(typeof matrix.isIntersects === 'function', 'isIntersects() exists');
    assert(typeof matrix.isDisjoint === 'function', 'isDisjoint() exists');
    assert(typeof matrix.isCovers === 'function', 'isCovers() exists');
    assert(typeof matrix.isCoveredBy === 'function', 'isCoveredBy() exists');
    console.log('PASS: Predicate methods exist');

    console.log('Testing IntersectionMatrix.isContains()...');
    assert(matrix.isContains() === true, 'Polygon contains point');
    console.log('PASS: isContains() =', matrix.isContains());

    console.log('Testing IntersectionMatrix.isIntersects()...');
    assert(matrix.isIntersects() === true, 'Polygon intersects point');
    console.log('PASS: isIntersects() =', matrix.isIntersects());

    console.log('Testing IntersectionMatrix.isDisjoint()...');
    assert(matrix.isDisjoint() === false, 'Polygon not disjoint from interior point');
    console.log('PASS: isDisjoint() =', matrix.isDisjoint());

    console.log('Testing IntersectionMatrix.transpose()...');
    const transposed = matrix.transpose();
    assert(transposed !== null, 'transpose() returns matrix');
    assert(transposed.toString().length === 9, 'Transposed matrix has 9-char string');
    console.log('PASS: transpose() =', transposed.toString());

    console.log('Testing IntersectionMatrix.set(row, col, value)...');
    matrix.set(0, 0, 2);
    assert(matrix.get(0, 0) === 2, 'set() updates value');
    console.log('PASS: set(0, 0, 2) works');

    console.log('Testing IntersectionMatrix.setAll(value)...');
    matrix.setAll(-1);
    assert(matrix.get(0, 0) === -1, 'setAll() sets all to value');
    assert(matrix.get(2, 2) === -1, 'setAll() sets all positions');
    console.log('PASS: setAll(-1) works');

    console.log('Testing static IntersectionMatrix.isTrue(dimValue)...');
    assert(wasmts.geom.IntersectionMatrix.isTrue(0) === true, 'isTrue(0) is true');
    assert(wasmts.geom.IntersectionMatrix.isTrue(1) === true, 'isTrue(1) is true');
    assert(wasmts.geom.IntersectionMatrix.isTrue(2) === true, 'isTrue(2) is true');
    assert(wasmts.geom.IntersectionMatrix.isTrue(-1) === false, 'isTrue(-1) is false');
    console.log('PASS: static isTrue()');

    console.log('Testing static IntersectionMatrix.matches(dimValue, symbol)...');
    assert(wasmts.geom.IntersectionMatrix.matches(1, 'T') === true, 'matches(1, T) is true');
    assert(wasmts.geom.IntersectionMatrix.matches(-1, 'F') === true, 'matches(-1, F) is true');
    assert(wasmts.geom.IntersectionMatrix.matches(0, '*') === true, 'matches(0, *) is true');
    console.log('PASS: static matches(dimValue, symbol)');

    console.log('Testing IntersectionMatrix constructors...');
    const defaultMatrix = new wasmts.geom.IntersectionMatrix();
    assert(defaultMatrix.toString() === 'FFFFFFFFF', 'Default constructor creates all F matrix');
    console.log('PASS: Default constructor');

    const fromString = new wasmts.geom.IntersectionMatrix('T*F**FFF*');
    assert(fromString.toString() === 'T*F**FFF*', 'String constructor preserves pattern');
    console.log('PASS: String constructor');

    console.log('Testing IntersectionMatrix.isTouches(dimA, dimB)...');
    const touchMatrix = polygon.relate(wasmts.io.WKTReader.read('POINT (0 0)'));
    assert(typeof touchMatrix.isTouches === 'function', 'isTouches() exists');
    console.log('PASS: isTouches() method exists');

    console.log('Testing IntersectionMatrix.isCrosses(dimA, dimB)...');
    assert(typeof matrix.isCrosses === 'function', 'isCrosses() exists');
    console.log('PASS: isCrosses() method exists');

    console.log('Testing IntersectionMatrix.isEquals(dimA, dimB)...');
    assert(typeof matrix.isEquals === 'function', 'isEquals() exists');
    console.log('PASS: isEquals() method exists');

    console.log('Testing IntersectionMatrix.isOverlaps(dimA, dimB)...');
    assert(typeof matrix.isOverlaps === 'function', 'isOverlaps() exists');
    console.log('PASS: isOverlaps() method exists');

    console.log('Testing IntersectionMatrix.isWithin()...');
    // Point is within polygon - get matrix from point's perspective
    const withinMatrix = point.relate(polygon);
    assert(typeof withinMatrix.isWithin === 'function', 'isWithin() exists');
    console.log('PASS: isWithin() method exists');

    console.log('Testing IntersectionMatrix.setAtLeast(row, col, min)...');
    const testMatrix = new wasmts.geom.IntersectionMatrix();
    testMatrix.setAtLeast(0, 0, 1);
    assert(testMatrix.get(0, 0) === 1, 'setAtLeast updates when current < min');
    testMatrix.setAtLeast(0, 0, 0);
    assert(testMatrix.get(0, 0) === 1, 'setAtLeast does not reduce');
    console.log('PASS: setAtLeast()');

    console.log('Testing IntersectionMatrix.add(other)...');
    const m1 = new wasmts.geom.IntersectionMatrix('012012012');
    const m2 = new wasmts.geom.IntersectionMatrix('210210210');
    m1.add(m2);
    assert(m1.get(0, 0) === 2, 'add() takes maximum');
    console.log('PASS: add()');

    console.log('PASS: All IntersectionMatrix tests completed');
}

async function testDimension() {
    console.log('');
    console.log('--- Section: Dimension Class ---');
    console.log('');

    console.log('Testing Dimension constants...');
    assert(wasmts.geom.Dimension.P === 0, 'Dimension.P = 0');
    assert(wasmts.geom.Dimension.L === 1, 'Dimension.L = 1');
    assert(wasmts.geom.Dimension.A === 2, 'Dimension.A = 2');
    assert(wasmts.geom.Dimension.FALSE === -1, 'Dimension.FALSE = -1');
    assert(wasmts.geom.Dimension.TRUE === -2, 'Dimension.TRUE = -2');
    assert(wasmts.geom.Dimension.DONTCARE === -3, 'Dimension.DONTCARE = -3');
    console.log('PASS: Dimension value constants');

    assert(wasmts.geom.Dimension.SYM_FALSE === 'F', 'Dimension.SYM_FALSE = F');
    assert(wasmts.geom.Dimension.SYM_TRUE === 'T', 'Dimension.SYM_TRUE = T');
    assert(wasmts.geom.Dimension.SYM_DONTCARE === '*', 'Dimension.SYM_DONTCARE = *');
    assert(wasmts.geom.Dimension.SYM_P === '0', 'Dimension.SYM_P = 0');
    assert(wasmts.geom.Dimension.SYM_L === '1', 'Dimension.SYM_L = 1');
    assert(wasmts.geom.Dimension.SYM_A === '2', 'Dimension.SYM_A = 2');
    console.log('PASS: Dimension symbol constants');

    console.log('Testing Dimension.toDimensionSymbol()...');
    assert(wasmts.geom.Dimension.toDimensionSymbol(0) === '0', 'toDimensionSymbol(0) = "0"');
    assert(wasmts.geom.Dimension.toDimensionSymbol(1) === '1', 'toDimensionSymbol(1) = "1"');
    assert(wasmts.geom.Dimension.toDimensionSymbol(2) === '2', 'toDimensionSymbol(2) = "2"');
    assert(wasmts.geom.Dimension.toDimensionSymbol(-1) === 'F', 'toDimensionSymbol(-1) = "F"');
    assert(wasmts.geom.Dimension.toDimensionSymbol(-2) === 'T', 'toDimensionSymbol(-2) = "T"');
    assert(wasmts.geom.Dimension.toDimensionSymbol(-3) === '*', 'toDimensionSymbol(-3) = "*"');
    console.log('PASS: Dimension.toDimensionSymbol()');

    console.log('Testing Dimension.toDimensionValue()...');
    assert(wasmts.geom.Dimension.toDimensionValue('0') === 0, 'toDimensionValue("0") = 0');
    assert(wasmts.geom.Dimension.toDimensionValue('1') === 1, 'toDimensionValue("1") = 1');
    assert(wasmts.geom.Dimension.toDimensionValue('2') === 2, 'toDimensionValue("2") = 2');
    assert(wasmts.geom.Dimension.toDimensionValue('F') === -1, 'toDimensionValue("F") = -1');
    assert(wasmts.geom.Dimension.toDimensionValue('T') === -2, 'toDimensionValue("T") = -2');
    assert(wasmts.geom.Dimension.toDimensionValue('*') === -3, 'toDimensionValue("*") = -3');
    console.log('PASS: Dimension.toDimensionValue()');

    console.log('PASS: All Dimension tests completed');
}

function testPointMethods() {
    const point = wasmts.geom.createPoint(3, 4);

    console.log('Testing Point.getX()...');
    assert(point.getX() === 3, 'Point(3, 4).getX() returns 3');
    console.log('PASS: Point.getX()');

    console.log('Testing Point.getY()...');
    assert(point.getY() === 4, 'Point(3, 4).getY() returns 4');
    console.log('PASS: Point.getY()');

    // Test with 3D point
    const point3d = wasmts.geom.createPoint(1, 2, 3);
    assert(point3d.getX() === 1, '3D Point getX works');
    assert(point3d.getY() === 2, '3D Point getY works');
    console.log('PASS: 3D Point getX/getY');

    console.log('PASS: All Point method tests completed');
}

function testLineStringMethods() {
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 5 5, 10 0)');
    const ring = wasmts.io.WKTReader.read('LINEARRING (0 0, 10 0, 10 10, 0 10, 0 0)');

    console.log('Testing LineString.getPointN(n)...');
    const middlePoint = line.getPointN(1);
    assert(middlePoint !== null && middlePoint !== undefined, 'getPointN(1) returns a point');
    assert(middlePoint.type === 'Point', 'getPointN(1) returns Point type');
    assert(middlePoint.getX() === 5, 'getPointN(1) returns point at x=5');
    assert(middlePoint.getY() === 5, 'getPointN(1) returns point at y=5');
    const firstPoint = line.getPointN(0);
    assert(firstPoint.getX() === 0 && firstPoint.getY() === 0, 'getPointN(0) returns first point');
    const lastPoint = line.getPointN(2);
    assert(lastPoint.getX() === 10 && lastPoint.getY() === 0, 'getPointN(2) returns last point');
    console.log('PASS: LineString.getPointN(n)');

    console.log('Testing LineString.getStartPoint()...');
    const startPoint = line.getStartPoint();
    assert(startPoint !== null && startPoint !== undefined, 'getStartPoint() returns a point');
    assert(startPoint.type === 'Point', 'getStartPoint() returns Point type');
    assert(startPoint.getX() === 0, 'getStartPoint() returns point at x=0');
    assert(startPoint.getY() === 0, 'getStartPoint() returns point at y=0');
    console.log('PASS: LineString.getStartPoint()');

    console.log('Testing LineString.getEndPoint()...');
    const endPoint = line.getEndPoint();
    assert(endPoint !== null && endPoint !== undefined, 'getEndPoint() returns a point');
    assert(endPoint.type === 'Point', 'getEndPoint() returns Point type');
    assert(endPoint.getX() === 10, 'getEndPoint() returns point at x=10');
    assert(endPoint.getY() === 0, 'getEndPoint() returns point at y=0');
    console.log('PASS: LineString.getEndPoint()');

    console.log('Testing LineString.isClosed()...');
    assert(line.isClosed() === false, 'Open LineString.isClosed() returns false');
    assert(ring.isClosed() === true, 'LinearRing.isClosed() returns true');
    // Test closed linestring (but not a LinearRing)
    const closedLine = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0, 10 10, 0 0)');
    assert(closedLine.isClosed() === true, 'Closed LineString.isClosed() returns true');
    console.log('PASS: LineString.isClosed()');

    console.log('Testing LineString.isRing()...');
    assert(line.isRing() === false, 'Open LineString.isRing() returns false');
    assert(ring.isRing() === true, 'LinearRing.isRing() returns true');
    // A figure-8 is closed but NOT simple (self-intersecting), so not a ring
    const figureEight = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10, 10 0, 0 10, 0 0)');
    assert(figureEight.isClosed() === true, 'Figure-8 is closed');
    assert(figureEight.isRing() === false, 'Figure-8 is not a ring (self-intersecting)');
    console.log('PASS: LineString.isRing()');

    console.log('Testing LineString.getCoordinateSequence()...');
    const coordSeq = line.getCoordinateSequence();
    assert(coordSeq !== null && coordSeq !== undefined, 'getCoordinateSequence() returns sequence');
    assert(typeof coordSeq.size === 'function', 'CoordinateSequence has size() method');
    assert(coordSeq.size() === 3, 'CoordinateSequence size matches numPoints (3)');
    // Also verify it returns valid Coordinate data
    assert(typeof coordSeq.getX === 'function', 'CoordinateSequence has getX() method');
    assert(typeof coordSeq.getY === 'function', 'CoordinateSequence has getY() method');
    assert(coordSeq.getX(1) === 5, 'getX(1) returns 5');
    assert(coordSeq.getY(1) === 5, 'getY(1) returns 5');
    console.log('PASS: LineString.getCoordinateSequence()');

    console.log('Testing LinearRing methods...');
    const ringStartPoint = ring.getStartPoint();
    const ringEndPoint = ring.getEndPoint();
    assert(ringStartPoint.getX() === ringEndPoint.getX(), 'LinearRing start and end X match');
    assert(ringStartPoint.getY() === ringEndPoint.getY(), 'LinearRing start and end Y match');
    console.log('PASS: LinearRing methods');

    console.log('PASS: All LineString/LinearRing tests completed');
}

function testEnvelopeMethods() {
    const env = wasmts.geom.createEnvelope(0, 10, 0, 20);

    console.log('Testing Envelope.getMinX()...');
    assert(env.getMinX() === 0, 'getMinX() returns 0');
    console.log('PASS: Envelope.getMinX()');

    console.log('Testing Envelope.getMaxX()...');
    assert(env.getMaxX() === 10, 'getMaxX() returns 10');
    console.log('PASS: Envelope.getMaxX()');

    console.log('Testing Envelope.getMinY()...');
    assert(env.getMinY() === 0, 'getMinY() returns 0');
    console.log('PASS: Envelope.getMinY()');

    console.log('Testing Envelope.getMaxY()...');
    assert(env.getMaxY() === 20, 'getMaxY() returns 20');
    console.log('PASS: Envelope.getMaxY()');

    console.log('Testing Envelope.getWidth()...');
    assert(env.getWidth() === 10, 'getWidth() returns 10');
    console.log('PASS: Envelope.getWidth()');

    console.log('Testing Envelope.getHeight()...');
    assert(env.getHeight() === 20, 'getHeight() returns 20');
    console.log('PASS: Envelope.getHeight()');

    console.log('Testing Envelope.getArea()...');
    assert(env.getArea() === 200, 'getArea() returns 200');
    console.log('PASS: Envelope.getArea()');

    console.log('Testing Envelope.centre()...');
    const center = env.centre();
    assert(center !== null, 'centre() returns a coordinate');
    assert(center.x === 5, 'centre().x returns 5');
    assert(center.y === 10, 'centre().y returns 10');
    console.log('PASS: Envelope.centre()');

    console.log('Testing Envelope.expandBy(distance)...');
    const envExpand1 = wasmts.geom.createEnvelope(0, 10, 0, 20);
    envExpand1.expandBy(5);
    assert(envExpand1.getMinX() === -5, 'expandBy(5) minX becomes -5');
    assert(envExpand1.getMaxX() === 15, 'expandBy(5) maxX becomes 15');
    assert(envExpand1.getMinY() === -5, 'expandBy(5) minY becomes -5');
    assert(envExpand1.getMaxY() === 25, 'expandBy(5) maxY becomes 25');
    console.log('PASS: Envelope.expandBy(distance)');

    console.log('Testing Envelope.expandBy(deltaX, deltaY)...');
    const envExpand2 = wasmts.geom.createEnvelope(0, 10, 0, 20);
    envExpand2.expandBy(2, 3);
    assert(envExpand2.getMinX() === -2, 'expandBy(2,3) minX becomes -2');
    assert(envExpand2.getMaxX() === 12, 'expandBy(2,3) maxX becomes 12');
    assert(envExpand2.getMinY() === -3, 'expandBy(2,3) minY becomes -3');
    assert(envExpand2.getMaxY() === 23, 'expandBy(2,3) maxY becomes 23');
    console.log('PASS: Envelope.expandBy(deltaX, deltaY)');

    console.log('Testing Envelope.expandToInclude(coord)...');
    const envInclude1 = wasmts.geom.createEnvelope(0, 10, 0, 20);
    envInclude1.expandToInclude({ x: 15, y: 25 });
    assert(envInclude1.getMaxX() === 15, 'expandToInclude(coord) maxX becomes 15');
    assert(envInclude1.getMaxY() === 25, 'expandToInclude(coord) maxY becomes 25');
    console.log('PASS: Envelope.expandToInclude(coord)');

    console.log('Testing Envelope.expandToInclude(envelope)...');
    const envInclude2 = wasmts.geom.createEnvelope(0, 10, 0, 20);
    const envOther = wasmts.geom.createEnvelope(5, 20, 10, 30);
    envInclude2.expandToIncludeEnvelope(envOther);
    assert(envInclude2.getMinX() === 0, 'expandToInclude(env) minX stays 0');
    assert(envInclude2.getMaxX() === 20, 'expandToInclude(env) maxX becomes 20');
    assert(envInclude2.getMinY() === 0, 'expandToInclude(env) minY stays 0');
    assert(envInclude2.getMaxY() === 30, 'expandToInclude(env) maxY becomes 30');
    console.log('PASS: Envelope.expandToInclude(envelope)');

    console.log('Testing Envelope.intersection(envelope)...');
    const envInt1 = wasmts.geom.createEnvelope(0, 10, 0, 20);
    const envInt2 = wasmts.geom.createEnvelope(5, 15, 10, 30);
    const intersectionEnv = envInt1.intersection(envInt2);
    assert(intersectionEnv !== null, 'intersection() returns an envelope');
    assert(intersectionEnv.getMinX() === 5, 'intersection minX is 5');
    assert(intersectionEnv.getMaxX() === 10, 'intersection maxX is 10');
    assert(intersectionEnv.getMinY() === 10, 'intersection minY is 10');
    assert(intersectionEnv.getMaxY() === 20, 'intersection maxY is 20');
    console.log('PASS: Envelope.intersection(envelope)');

    console.log('Testing Envelope.covers(coord)...');
    assert(env.covers({ x: 5, y: 10 }) === true, 'covers(coord) inside returns true');
    assert(env.covers({ x: 0, y: 0 }) === true, 'covers(coord) on boundary returns true');
    assert(env.covers({ x: 100, y: 100 }) === false, 'covers(coord) outside returns false');
    console.log('PASS: Envelope.covers(coord)');

    console.log('Testing Envelope.covers(x, y)...');
    assert(env.coversXY(5, 10) === true, 'covers(x,y) inside returns true');
    assert(env.coversXY(0, 0) === true, 'covers(x,y) on boundary returns true');
    assert(env.coversXY(100, 100) === false, 'covers(x,y) outside returns false');
    console.log('PASS: Envelope.covers(x, y)');

    console.log('Testing Envelope.disjoint(envelope)...');
    const envDisj1 = wasmts.geom.createEnvelope(0, 10, 0, 10);
    const envDisj2 = wasmts.geom.createEnvelope(20, 30, 20, 30);
    const envDisj3 = wasmts.geom.createEnvelope(5, 15, 5, 15);
    assert(envDisj1.disjoint(envDisj2) === true, 'disjoint envelopes return true');
    assert(envDisj1.disjoint(envDisj3) === false, 'overlapping envelopes return false');
    console.log('PASS: Envelope.disjoint(envelope)');

    console.log('Testing Envelope.distance(envelope)...');
    const envDist1 = wasmts.geom.createEnvelope(0, 10, 0, 10);
    const envDist2 = wasmts.geom.createEnvelope(20, 30, 0, 10);
    const dist = envDist1.distance(envDist2);
    assert(dist === 10, 'distance between separated envelopes is 10');
    const envDist3 = wasmts.geom.createEnvelope(5, 15, 5, 15);
    assert(envDist1.distance(envDist3) === 0, 'distance between overlapping envelopes is 0');
    console.log('PASS: Envelope.distance(envelope)');

    console.log('Testing Envelope.isNull()...');
    assert(env.isNull() === false, 'valid envelope isNull() returns false');
    console.log('PASS: Envelope.isNull()');

    console.log('Testing Envelope.setToNull()...');
    const envNull = wasmts.geom.createEnvelope(0, 10, 0, 20);
    assert(envNull.isNull() === false, 'before setToNull, isNull() is false');
    envNull.setToNull();
    assert(envNull.isNull() === true, 'after setToNull, isNull() is true');
    console.log('PASS: Envelope.setToNull()');

    console.log('Testing Envelope.copy()...');
    const envCopy = env.copy();
    assert(envCopy !== null, 'copy() returns an envelope');
    assert(envCopy !== env, 'copy is not same reference');
    assert(envCopy.getMinX() === env.getMinX(), 'copy has same minX');
    assert(envCopy.getMaxX() === env.getMaxX(), 'copy has same maxX');
    assert(envCopy.getMinY() === env.getMinY(), 'copy has same minY');
    assert(envCopy.getMaxY() === env.getMaxY(), 'copy has same maxY');
    console.log('PASS: Envelope.copy()');

    console.log('Testing Envelope.translate(deltaX, deltaY)...');
    const envTrans = wasmts.geom.createEnvelope(0, 10, 0, 20);
    envTrans.translate(5, 10);
    assert(envTrans.getMinX() === 5, 'translate(5,10) minX becomes 5');
    assert(envTrans.getMaxX() === 15, 'translate(5,10) maxX becomes 15');
    assert(envTrans.getMinY() === 10, 'translate(5,10) minY becomes 10');
    assert(envTrans.getMaxY() === 30, 'translate(5,10) maxY becomes 30');
    console.log('PASS: Envelope.translate(deltaX, deltaY)');

    console.log('PASS: All Envelope tests completed');
}

function testGeometryAdditionalMethods() {
    const point = wasmts.geom.createPoint(5, 10);
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 10, 20 0)');
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const emptyPoint = wasmts.geom.createEmpty(0);

    console.log('Testing Geometry.getCoordinate()...');
    const pointCoord = point.getCoordinate();
    assert(pointCoord !== null, 'Point.getCoordinate() returns a coordinate');
    assert(pointCoord.x === 5, 'Point.getCoordinate().x === 5');
    assert(pointCoord.y === 10, 'Point.getCoordinate().y === 10');

    const lineCoord = line.getCoordinate();
    assert(lineCoord !== null, 'LineString.getCoordinate() returns a coordinate');
    assert(lineCoord.x === 0, 'LineString.getCoordinate().x === 0 (first point)');
    assert(lineCoord.y === 0, 'LineString.getCoordinate().y === 0 (first point)');

    const emptyCoord = emptyPoint.getCoordinate();
    assert(emptyCoord === null, 'Empty geometry.getCoordinate() returns null');
    console.log('PASS: Geometry.getCoordinate()');

    console.log('Testing Geometry.getFactory()...');
    const factory = point.getFactory();
    assert(factory !== null && factory !== undefined, 'getFactory() returns a factory');
    // Test that the factory can create geometries
    const newPoint = factory.createPoint(100, 200);
    assert(newPoint !== null, 'Factory can create new Point');
    assert(newPoint.getX() === 100, 'Factory-created point has correct X');
    assert(newPoint.getY() === 200, 'Factory-created point has correct Y');
    console.log('PASS: Geometry.getFactory()');

    console.log('Testing Geometry.getPrecisionModel()...');
    const pm = point.getPrecisionModel();
    assert(pm !== null && pm !== undefined, 'getPrecisionModel() returns a PrecisionModel');
    // PrecisionModel should have getType() method
    assert(typeof pm.getType === 'function', 'PrecisionModel has getType() method');
    const pmType = pm.getType();
    assert(pmType !== null, 'PrecisionModel.getType() returns a value');
    console.log('PASS: Geometry.getPrecisionModel(), type:', pmType);

    console.log('Testing Geometry.norm()...');
    // Create a polygon with coordinates in non-canonical order
    const unnormalizedPoly = wasmts.io.WKTReader.read('POLYGON ((10 10, 10 0, 0 0, 0 10, 10 10))');
    const normalizedPoly = unnormalizedPoly.norm();
    assert(normalizedPoly !== null, 'norm() returns a geometry');
    assert(normalizedPoly !== unnormalizedPoly, 'norm() returns a different object (copy)');
    // The normalized polygon should be valid
    assert(normalizedPoly.isValid() === true, 'norm() result is valid');
    // Original should be unchanged (still valid, just not normalized)
    assert(unnormalizedPoly.isValid() === true, 'Original remains valid after norm()');
    console.log('PASS: Geometry.norm()');

    console.log('Testing Geometry.compareTo()...');
    // Point < LineString < Polygon in JTS ordering
    const cmpPointLine = point.compareTo(line);
    const cmpLinePoly = line.compareTo(poly);
    const cmpPolyPoint = poly.compareTo(point);

    assert(cmpPointLine < 0, 'Point.compareTo(LineString) < 0');
    assert(cmpLinePoly < 0, 'LineString.compareTo(Polygon) < 0');
    assert(cmpPolyPoint > 0, 'Polygon.compareTo(Point) > 0');

    // Same type comparisons
    const point2 = wasmts.geom.createPoint(5, 10);
    const cmpSame = point.compareTo(point2);
    assert(cmpSame === 0, 'Equal points compareTo returns 0');

    const point3 = wasmts.geom.createPoint(100, 100);
    const cmpDiff = point.compareTo(point3);
    assert(typeof cmpDiff === 'number', 'compareTo returns a number');
    console.log('PASS: Geometry.compareTo()');

    console.log('PASS: All Geometry Additional Methods tests completed');
}

function testDensifier() {
    // Static convenience method
    const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 10 0)');
    const densified = wasmts.densify.Densifier.densify(line, 2.0);
    assert(densified !== null && densified !== undefined, 'Densified geometry created');
    assert(densified.type === 'LineString', 'Result is LineString');
    const coords = densified.getCoordinates();
    assert(coords.length > 2, 'Densified line has more points than original');
    console.log('PASS: Static densify - original 2 points, densified', coords.length, 'points');

    // Instance API
    const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');
    const d = wasmts.densify.Densifier.create(poly);
    assert(d !== null && d !== undefined, 'Densifier instance created');

    wasmts.densify.Densifier.setDistanceTolerance(d, 25.0);
    console.log('PASS: Set distance tolerance');

    wasmts.densify.Densifier.setValidate(d, true);
    console.log('PASS: Set validate');

    const result = wasmts.densify.Densifier.getResultGeometry(d);
    assert(result !== null && result !== undefined, 'Got result geometry');
    assert(result.type === 'Polygon', 'Result is Polygon');
    const resultCoords = result.getCoordinates();
    const origCoords = poly.getCoordinates();
    assert(resultCoords.length > origCoords.length, 'Densified polygon has more vertices');
    console.log('PASS: Instance API - original', origCoords.length, 'points, densified', resultCoords.length, 'points');

    // Densify a polygon for reprojection use case
    const tile = wasmts.io.WKTReader.read('POLYGON ((0 0, 45 0, 45 45, 0 45, 0 0))');
    const denseTile = wasmts.densify.Densifier.densify(tile, 5.0);
    assert(denseTile.getCoordinates().length > tile.getCoordinates().length, 'Tile densified for reprojection');
    assert(denseTile.isValid(), 'Densified tile is valid');
    console.log('PASS: Densified tile has', denseTile.getCoordinates().length, 'points (tolerance 5.0)');

    console.log('PASS: All Densifier tests completed');
}

function testGeometryFixer() {
    // Static convenience method - fix invalid polygon (bowtie/self-intersecting)
    const bowtie = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 10, 10 0, 0 10, 0 0))');
    assert(!bowtie.isValid(), 'Bowtie polygon is invalid');
    console.log('PASS: Bowtie polygon is invalid as expected');

    const fixed = wasmts.geom.util.GeometryFixer.fix(bowtie);
    assert(fixed !== null && fixed !== undefined, 'Fixed geometry created');
    assert(fixed.isValid(), 'Fixed geometry is valid');
    console.log('PASS: Static fix - result type:', fixed.type, ', valid:', fixed.isValid());

    // Static with keepMulti parameter
    const fixedKeepMulti = wasmts.geom.util.GeometryFixer.fix(bowtie, true);
    assert(fixedKeepMulti.isValid(), 'Fixed with keepMulti is valid');
    console.log('PASS: Static fix with keepMulti=true, type:', fixedKeepMulti.type);

    const fixedNoMulti = wasmts.geom.util.GeometryFixer.fix(bowtie, false);
    assert(fixedNoMulti.isValid(), 'Fixed without keepMulti is valid');
    console.log('PASS: Static fix with keepMulti=false, type:', fixedNoMulti.type);

    // Instance API
    const fixer = wasmts.geom.util.GeometryFixer.create(bowtie);
    assert(fixer !== null && fixer !== undefined, 'GeometryFixer instance created');
    console.log('PASS: GeometryFixer instance created');

    wasmts.geom.util.GeometryFixer.setKeepCollapsed(fixer, false);
    console.log('PASS: Set keepCollapsed');

    wasmts.geom.util.GeometryFixer.setKeepMulti(fixer, true);
    console.log('PASS: Set keepMulti');

    const instanceResult = wasmts.geom.util.GeometryFixer.getResult(fixer);
    assert(instanceResult.isValid(), 'Instance result is valid');
    console.log('PASS: Instance API result type:', instanceResult.type);

    // Fix already valid geometry (should return equivalent)
    const validPoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const fixedValid = wasmts.geom.util.GeometryFixer.fix(validPoly);
    assert(fixedValid.isValid(), 'Already valid polygon stays valid');
    assert(Math.abs(fixedValid.getArea() - validPoly.getArea()) < 0.001, 'Area preserved');
    console.log('PASS: Valid polygon unchanged after fix');

    console.log('PASS: All GeometryFixer tests completed');
}

function testCoverageUnion() {
    // Non-overlapping polygons sharing edges (coverage)
    const tile1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))');
    const tile2 = wasmts.io.WKTReader.read('POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))');
    const tile3 = wasmts.io.WKTReader.read('POLYGON ((0 10, 10 10, 10 20, 0 20, 0 10))');
    const tile4 = wasmts.io.WKTReader.read('POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))');

    console.log('Created 4 adjacent tiles (2x2 grid)');

    const union = wasmts.coverage.CoverageUnion.union([tile1, tile2, tile3, tile4]);
    assert(union !== null && union !== undefined, 'Coverage union result created');
    assert(union.isValid(), 'Coverage union is valid');

    const expectedArea = 400;
    assert(Math.abs(union.getArea() - expectedArea) < 0.001, 'Area equals sum (no overlaps)');
    console.log('PASS: Coverage union area:', union.getArea(), '(expected', expectedArea, ')');
    console.log('PASS: Result type:', union.type);

    // Single polygon
    const single = wasmts.coverage.CoverageUnion.union([tile1]);
    assert(single.isValid(), 'Single polygon union is valid');
    assert(Math.abs(single.getArea() - 100) < 0.001, 'Single polygon area preserved');
    console.log('PASS: Single polygon coverage union works');

    // Two adjacent polygons
    const pair = wasmts.coverage.CoverageUnion.union([tile1, tile2]);
    assert(pair.isValid(), 'Pair union is valid');
    assert(Math.abs(pair.getArea() - 200) < 0.001, 'Pair area correct');
    console.log('PASS: Two adjacent tiles merged, area:', pair.getArea());

    console.log('PASS: All CoverageUnion tests completed');
}

function testPrecisionModel() {
    // Create FLOATING (default)
    const pmDefault = wasmts.geom.PrecisionModel.create();
    assert(pmDefault !== null && pmDefault !== undefined, 'Default PrecisionModel created');
    assert(pmDefault.getType() === 'Floating', 'Default type is Floating');
    assert(pmDefault.isFloating() === true, 'Default is floating');
    console.log('PASS: Default PrecisionModel - type:', pmDefault.getType());

    // Create by type name
    const pmFloat = wasmts.geom.PrecisionModel.create('Floating');
    assert(pmFloat.getType() === 'Floating', 'Floating type');
    console.log('PASS: Floating PrecisionModel');

    const pmFloatSingle = wasmts.geom.PrecisionModel.create('Floating-Single');
    assert(pmFloatSingle.getType() === 'Floating-Single', 'Floating-Single type');
    console.log('PASS: Floating-Single PrecisionModel');

    // Create FIXED with scale
    const pmFixed = wasmts.geom.PrecisionModel.createFixed(1000.0);
    assert(pmFixed.getType() === 'Fixed', 'Fixed type');
    assert(pmFixed.getScale() === 1000.0, 'Scale is 1000');
    assert(pmFixed.isFloating() === false, 'Fixed is not floating');
    console.log('PASS: FIXED PrecisionModel - scale:', pmFixed.getScale());

    // makePrecise
    const precise = pmFixed.makePrecise(1.23456789);
    assert(precise === 1.235, 'makePrecise rounds to scale');
    console.log('PASS: makePrecise(1.23456789) =', precise);

    // getMaximumSignificantDigits
    const digits = pmFixed.getMaximumSignificantDigits();
    assert(typeof digits === 'number', 'getMaximumSignificantDigits returns number');
    console.log('PASS: getMaximumSignificantDigits:', digits);

    // gridSize
    const grid = pmFixed.gridSize();
    assert(grid === 0.001, 'Grid size is 1/scale');
    console.log('PASS: gridSize:', grid);

    // Create with scale via create()
    const pmScale = wasmts.geom.PrecisionModel.create(100.0);
    assert(pmScale.getType() === 'Fixed', 'Numeric arg creates Fixed');
    assert(pmScale.getScale() === 100.0, 'Scale is 100');
    console.log('PASS: create(100.0) - type:', pmScale.getType(), ', scale:', pmScale.getScale());

    console.log('PASS: All PrecisionModel tests completed');
}

function testGeometryPrecisionReducer() {
    // Static reduce
    const pm = wasmts.geom.PrecisionModel.createFixed(1.0);
    const poly = wasmts.io.WKTReader.read('POLYGON ((0.1 0.2, 10.7 0.3, 10.8 10.9, 0.4 10.6, 0.1 0.2))');

    const reduced = wasmts.precision.GeometryPrecisionReducer.reduce(poly, pm);
    assert(reduced !== null && reduced !== undefined, 'Reduced geometry created');
    assert(reduced.isValid(), 'Reduced geometry is valid');
    const coords = reduced.getCoordinates();
    coords.forEach(c => {
        assert(c.x === Math.round(c.x), 'X is integer: ' + c.x);
        assert(c.y === Math.round(c.y), 'Y is integer: ' + c.y);
    });
    console.log('PASS: Static reduce snapped to integer grid');

    // Static reducePointwise
    const reducedPw = wasmts.precision.GeometryPrecisionReducer.reducePointwise(poly, pm);
    assert(reducedPw !== null && reducedPw !== undefined, 'Pointwise reduced geometry created');
    console.log('PASS: Static reducePointwise');

    // Static reduceKeepCollapsed
    const reducedKc = wasmts.precision.GeometryPrecisionReducer.reduceKeepCollapsed(poly, pm);
    assert(reducedKc !== null && reducedKc !== undefined, 'KeepCollapsed reduced geometry created');
    console.log('PASS: Static reduceKeepCollapsed');

    // Instance API
    const pm2 = wasmts.geom.PrecisionModel.createFixed(10.0);
    const reducer = wasmts.precision.GeometryPrecisionReducer.create(pm2);
    assert(reducer !== null && reducer !== undefined, 'Reducer instance created');

    wasmts.precision.GeometryPrecisionReducer.setChangePrecisionModel(reducer, true);
    console.log('PASS: setChangePrecisionModel');

    wasmts.precision.GeometryPrecisionReducer.setPointwise(reducer, false);
    console.log('PASS: setPointwise');

    wasmts.precision.GeometryPrecisionReducer.setRemoveCollapsedComponents(reducer, true);
    console.log('PASS: setRemoveCollapsedComponents');

    const instanceResult = wasmts.precision.GeometryPrecisionReducer.reduceInstance(reducer, poly);
    assert(instanceResult !== null && instanceResult !== undefined, 'Instance reduce result created');
    assert(instanceResult.isValid(), 'Instance reduce result is valid');
    console.log('PASS: Instance reduce result type:', instanceResult.type);

    // MVT use case: 4096 grid
    const pm4096 = wasmts.geom.PrecisionModel.createFixed(4096.0);
    const mvtPoly = wasmts.io.WKTReader.read('POLYGON ((0.00024 0.00049, 0.00268 0.00049, 0.00268 0.00268, 0.00024 0.00268, 0.00024 0.00049))');
    const snapped = wasmts.precision.GeometryPrecisionReducer.reduce(mvtPoly, pm4096);
    assert(snapped.isValid(), 'MVT snapped polygon is valid');
    console.log('PASS: MVT 4096-grid snapping works');

    console.log('PASS: All GeometryPrecisionReducer tests completed');
}

function assert(condition, message) {
    if (!condition) {
        console.error('Assertion failed:', message);
        console.error('   Condition value:', condition);
        console.error('   Condition type:', typeof condition);
        throw new Error(`Assertion failed: ${message}`);
    }
}
