window.examples = {
    geometry: `// Geometry Methods: Operations, Predicates, Transformations
// Coordinates relative to origin in the active CRS (default: EPSG:2249, feet)
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const writer = wasmts.io.WKTWriter.create0();

const circle1 = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0}).buffer(800);
const circle2 = wasmts.geom.GeometryFactory.createPoint(f, {x: 1200, y: 0}).buffer(800);

const union = circle1.union(circle2);
console.log('Union area:', union.getArea().toFixed(0), 'sq ft');

const intersection = circle1.intersection(circle2);
console.log('Intersection area:', intersection.getArea().toFixed(0), 'sq ft');

const difference = circle1.difference(circle2);
console.log('Difference area:', difference.getArea().toFixed(0), 'sq ft');

const symDiff = circle1.symDifference(circle2);
console.log('Symmetric difference area:', symDiff.getArea().toFixed(0), 'sq ft');

// Predicates
const container = reader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
const small = reader.read('POLYGON ((400 400, 1200 400, 1200 1200, 400 1200, 400 400))');
console.log('container.contains(small):', container.contains(small));
console.log('small.within(container):', small.within(container));
console.log('container.intersects(small):', container.intersects(small));

// Transformations
const poly = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Area:', poly.getArea(), 'sq ft');
console.log('Centroid:', writer.write(poly.getCentroid()));
console.log('isValid:', poly.isValid());
console.log('isRectangle:', poly.isRectangle());

await visualization({"Input": [circle1, circle2], "Union": [union]})`,

    buffer: `// Buffering - Simple and Advanced
// Distances in active CRS units from origin (default: feet)
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();

const point = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0});
const buffered = point.buffer(800);
console.log('Buffer area:', buffered.getArea().toFixed(0), 'sq ft');

const line = reader.read('LINESTRING (0 0, 1500 0)');
const lineBuffer = line.buffer(200);
console.log('Line buffer area:', lineBuffer.getArea().toFixed(0), 'sq ft');

// Negative buffer (erosion)
const poly = reader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
const eroded = poly.buffer(-200);
console.log('Original area:', poly.getArea());
console.log('Eroded area:', eroded.getArea().toFixed(0));

// Advanced cap/join control lives on BufferParameters + BufferOp;
// see the API Reference panel for wasmts.operation.buffer.*

await visualization({"Point": [point], "Buffer (800 ft)": [buffered], "Line Buffer": [lineBuffer]})`,

    strtree: `// STRtree Spatial Indexing
// 100 random parcels near origin
const reader = wasmts.io.WKTReader.create0();
const startTime = performance.now();

const index = wasmts.index.strtree.STRtree.create();
const geometries = [];
for (let i = 0; i < 100; i++) {
    const x = (Math.random() - 0.5) * 4000;
    const y = (Math.random() - 0.5) * 4000;
    const poly = reader.read(
        \`POLYGON ((\${x} \${y}, \${x+200} \${y}, \${x+200} \${y+200}, \${x} \${y+200}, \${x} \${y}))\`
    );
    const envelope = poly.getEnvelopeInternal();
    wasmts.index.strtree.STRtree.insert(index, envelope, {id: i, geom: poly});
    geometries.push({id: i, poly, envelope});
}
console.log('Inserted 100 geometries');

// Query a search area
const searchEnv = wasmts.geom.Envelope.create4(-500, 500, -500, 500);
const results = wasmts.index.strtree.STRtree.query(index, searchEnv);
const indexTime = performance.now() - startTime;
console.log(\`Found \${results.length} results in \${indexTime.toFixed(2)}ms\`);

const searchPoly = reader.read('POLYGON ((-500 -500, 500 -500, 500 500, -500 500, -500 -500))');
const allPolys = geometries.map(g => g.poly);
const hitPolys = results.map(r => r.geom);

await visualization({"All Parcels": allPolys, "Search Area": [searchPoly], "Hits": hitPolys})`,

    quadtree: `// Quadtree - dynamic spatial index (grows with inserts, supports remove)
const reader = wasmts.io.WKTReader.create0();
const Q = wasmts.index.quadtree.Quadtree;
const q = Q.create0();
const cells = [];
for (let i = 0; i < 60; i++) {
    const x = (Math.random() - 0.5) * 4000;
    const y = (Math.random() - 0.5) * 4000;
    const poly = reader.read(
        \`POLYGON ((\${x} \${y}, \${x+150} \${y}, \${x+150} \${y+150}, \${x} \${y+150}, \${x} \${y}))\`
    );
    Q.insert(q, poly.getEnvelopeInternal(), poly);
    cells.push(poly);
}
console.log('Size:', Q.size(q), ' Depth:', Q.depth(q));

// query() returns candidates and can include near misses, so verify with
// a real envelope test.
const searchEnv = wasmts.geom.Envelope.create4(-600, 600, -600, 600);
const candidates = Q.query(q, searchEnv);
const hits = candidates.filter(c => searchEnv.intersects(c.getEnvelopeInternal()));
console.log('Candidates:', candidates.length, ' Verified hits:', hits.length);

// queryVisit() fires a callback per candidate instead of building an array.
// (remove() is not usable from JS: item identity does not match across the
// JS/WASM boundary, so it always returns false.)
let visited = 0;
Q.queryVisit(q, searchEnv, () => visited++);
console.log('Visited via callback:', visited);

const searchPoly = reader.read('POLYGON ((-600 -600, 600 -600, 600 600, -600 600, -600 -600))');
await visualization({"Cells": cells, "Search Area": [searchPoly], "Hits": hits})`,

    nearest: `// STRtree nearest-neighbor queries (JTS spells it nearestNeighbour)
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const S = wasmts.index.strtree.STRtree;
const tree = S.create();
const pts = [];
for (let i = 0; i < 40; i++) {
    const pt = wasmts.geom.GeometryFactory.createPoint(f, {
        x: (Math.random() - 0.5) * 4000,
        y: (Math.random() - 0.5) * 4000
    });
    S.insert(tree, pt.getEnvelopeInternal(), pt);
    pts.push(pt);
}

// Closest pair across the whole index. The items here are geometries,
// which is what GeometryItemDistance measures between.
const itemDist = wasmts.index.strtree.GeometryItemDistance.create0();
const [a, b] = S.nearestNeighbour(tree, itemDist);
console.log('Closest pair:', a.distance(b).toFixed(0), 'ft apart');

// Nearest indexed item to a probe geometry.
const probe = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0});
const nearest = S.nearestNeighbourItem(tree, probe.getEnvelopeInternal(), probe, itemDist);
console.log('Nearest to origin:', nearest.distance(probe).toFixed(0), 'ft away');

// queryVisit fires a callback per hit instead of building a result array.
let inWindow = 0;
S.queryVisit(tree, wasmts.geom.Envelope.create4(-1000, 1000, -1000, 1000), () => inWindow++);
console.log('Points in the +/-1000 ft window:', inWindow);

const pair = reader.read(\`LINESTRING (\${a.getX()} \${a.getY()}, \${b.getX()} \${b.getY()})\`);
const toNearest = reader.read(\`LINESTRING (0 0, \${nearest.getX()} \${nearest.getY()})\`);
await visualization({"Points": pts, "Closest Pair": [pair], "Probe to Nearest": [probe, toNearest]})`,

    prepared: `// PreparedGeometry - Optimized for repeated predicates
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const polygon = reader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0), (400 400, 800 400, 800 800, 400 800, 400 400))');
console.log('Polygon with hole, area:', polygon.getArea());

const prepared = wasmts.geom.prep.PreparedGeometryFactory.prepare(polygon);

const testPoints = [
    { x: 1000, y: 1000, desc: 'center' },
    { x: 600, y: 600, desc: 'inside hole' },
    { x: 0, y: 0, desc: 'on boundary' },
    { x: 3000, y: 3000, desc: 'outside' },
    { x: 200, y: 200, desc: 'inside (near edge)' }
];

const insidePts = [];
const outsidePts = [];
for (const {x, y, desc} of testPoints) {
    const pt = wasmts.geom.GeometryFactory.createPoint(f, {x, y});
    const result = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, pt);
    console.log(\`  (\${x}, \${y}) \${desc}: \${result}\`);
    if (result) insidePts.push(pt); else outsidePts.push(pt);
}

await visualization({"Polygon": [polygon], "Inside": insidePts, "Outside": outsidePts})`,

    rectangles: `// Minimum Bounding Rectangles & Circles
const reader = wasmts.io.WKTReader.create0();
const polygon = reader.read('POLYGON ((0 0, 1500 300, 1800 1500, 300 1800, 0 0))');
console.log('Polygon area:', polygon.getArea().toFixed(0));

// MinimumDiameter is instance-based: build one, then query it.
const md = wasmts.algorithm.MinimumDiameter.create1(polygon);
const minDiamRect = wasmts.algorithm.MinimumDiameter.getMinimumRectangle(md);
console.log('Min-width rect area:', minDiamRect.getArea().toFixed(0));

// MinimumAreaRectangle exposes a static convenience.
const minAreaRect = wasmts.algorithm.MinimumAreaRectangle.getMinimumRectangle(polygon);
console.log('Min-area rect area:', minAreaRect.getArea().toFixed(0));

const mbc = wasmts.algorithm.MinimumBoundingCircle.create1(polygon);
const circle = wasmts.algorithm.MinimumBoundingCircle.getCircle(mbc);
const radius = wasmts.algorithm.MinimumBoundingCircle.getRadius(mbc);
console.log('Bounding circle radius:', radius.toFixed(0), 'ft');

await visualization({"Polygon": [polygon], "Min-Width Rect": [minDiamRect], "Min-Area Rect": [minAreaRect], "Bounding Circle": [circle]})`,

    offsetcurve: `// Offset Curves - parallel lines for road shoulders, setbacks
const reader = wasmts.io.WKTReader.create0();
const line = reader.read('LINESTRING (0 0, 1500 0, 1500 1500)');

const rightOffset = wasmts.operation.buffer.OffsetCurve.getCurve(line, 200);
const leftOffset = wasmts.operation.buffer.OffsetCurve.getCurve(line, -200);
console.log('Right offset points:', rightOffset.getCoordinates().length);
console.log('Left offset points:', leftOffset.getCoordinates().length);

// Custom quadrant segments + join style + mitre limit
const CAP_FLAT = 2, JOIN_MITRE = 2;
const sharpOffset = wasmts.operation.buffer.OffsetCurve.getCurveParametric(line, 200, 8, JOIN_MITRE, 10.0);
console.log('Sharp corner offset created');

await visualization({"Center Line": [line], "Right (+200 ft)": [rightOffset], "Left (-200 ft)": [leftOffset]})`,

    linemerger: `// LineMerger - Combine connected linestrings
const reader = wasmts.io.WKTReader.create0();
const line1 = reader.read('LINESTRING (0 0, 500 0)');
const line2 = reader.read('LINESTRING (500 0, 1200 0)');
const line3 = reader.read('LINESTRING (1200 0, 1200 800)');
const line4 = reader.read('LINESTRING (2000 2000, 2500 2500)');

const merger = wasmts.operation.linemerge.LineMerger.create();
wasmts.operation.linemerge.LineMerger.add(merger, line1);
wasmts.operation.linemerge.LineMerger.add(merger, line2);
wasmts.operation.linemerge.LineMerger.add(merger, line3);
wasmts.operation.linemerge.LineMerger.add(merger, line4);

const merged = wasmts.operation.linemerge.LineMerger.getMergedLineStrings(merger);
console.log('Merged result count:', merged.length);
merged.forEach((line, i) => {
    const coords = line.getCoordinates();
    console.log(\`Line \${i + 1}: \${coords.length} points, start=(\${coords[0].x},\${coords[0].y})\`);
});

await visualization({"Input Lines": [line1, line2, line3, line4], "Merged": merged})`,

    polygonizer: `// Polygonizer - assemble polygons from fully noded linework
const reader = wasmts.io.WKTReader.create0();
const P = wasmts.operation.polygonize.Polygonizer;
const pz = P.create0();

// Two squares sharing an edge, plus a dangling road stub.
const lines = [
    'LINESTRING (0 0, 1000 0)',
    'LINESTRING (1000 0, 1000 1000)',
    'LINESTRING (1000 1000, 0 1000)',
    'LINESTRING (0 1000, 0 0)',
    'LINESTRING (1000 0, 2000 0)',
    'LINESTRING (2000 0, 2000 1000)',
    'LINESTRING (2000 1000, 1000 1000)',
    'LINESTRING (2000 1000, 2600 1600)'
].map(wkt => reader.read(wkt));
lines.forEach(line => P.add(pz, line));

const polys = P.getPolygons(pz);
console.log('Polygons formed:', polys.length);
polys.forEach((p, i) => console.log(\`  Polygon \${i + 1} area:\`, p.getArea()));

// Diagnostics: linework that did NOT become part of a polygon.
const dangles = P.getDangles(pz);
console.log('Dangles (dead ends):', dangles.length);
console.log('Cut edges:', P.getCutEdges(pz).length);
console.log('Invalid ring lines:', P.getInvalidRingLines(pz).length);

await visualization({"Input Lines": lines, "Polygons": polys, "Dangles": dangles})`,

    cascadedunion: `// CascadedPolygonUnion - Efficiently union overlapping polygons
// Note: the array argument marshals in the browser; Node's GraalVM
// web-image build does not pass JS arrays across the boundary.
const reader = wasmts.io.WKTReader.create0();
const poly1 = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
const poly2 = reader.read('POLYGON ((500 500, 1500 500, 1500 1500, 500 1500, 500 500))');
const poly3 = reader.read('POLYGON ((1000 1000, 2000 1000, 2000 2000, 1000 2000, 1000 1000))');
const poly4 = reader.read('POLYGON ((800 0, 1800 0, 1800 800, 800 800, 800 0))');

console.log('Total area (with overlaps):', poly1.getArea() + poly2.getArea() + poly3.getArea() + poly4.getArea());

const union = wasmts.operation.union.CascadedPolygonUnion.union([poly1, poly2, poly3, poly4]);
console.log('Union area (no overlaps):', union.getArea().toFixed(0));

await visualization({"Input": [poly1, poly2, poly3, poly4], "Union": [union]})`,

    "3d": `// 3D Geometries with Z coordinates (elevation in feet)
// Note: MapLibre renders all geometry in 2D; Z values are shown as
// vertex labels and color gradients but not as true 3D elevation.
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const wkbWriter = wasmts.io.WKBWriter.create1(3);  // 3 output dimensions
const wkbReader = wasmts.io.WKBReader.create0();

const point3d = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0, z: 150});
const coords = point3d.getCoordinates();
console.log('3D Point - X:', coords[0].x, 'Y:', coords[0].y, 'Z:', coords[0].z);

const line3d = reader.read('LINESTRING Z (0 0 0, 500 500 100, 1000 500 200, 1500 0 150)');
const lineCoords = line3d.getCoordinates();
lineCoords.forEach((c, i) => {
    console.log(\`  Point \${i}: X=\${c.x}, Y=\${c.y}, Z=\${c.z}\`);
});
console.log('2D length:', line3d.getLength().toFixed(0), 'ft');

const wkb = wasmts.io.WKBWriter.write(wkbWriter, line3d);
const fromWKB = wkbReader.read(wkb);
console.log('WKB round-trip Z preserved:', fromWKB.getCoordinates()[2].z === 200);

await visualization({"3D Point": [point3d], "3D Line (XY projection)": [line3d]})`,

    "4d": `// 4D Geometries with XYZM coordinates
// Note: MapLibre renders all geometry in 2D; Z/M values are shown as
// vertex labels and color gradients but not as true 3D elevation.
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const wkbWriter = wasmts.io.WKBWriter.create1(4);  // 4 output dimensions (XYZM)
const wkbReader = wasmts.io.WKBReader.create0();

const point4d = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0, z: 150, m: 0});
const coords = point4d.getCoordinates();
console.log('X:', coords[0].x, 'Y:', coords[0].y, 'Z:', coords[0].z, 'M:', coords[0].m);

const line4d = reader.read('LINESTRING ZM (0 0 0 100, 500 500 100 200, 1000 0 200 300)');
const line4dCoords = line4d.getCoordinates();
console.log('Point 0:', line4dCoords[0]);
console.log('Point 1:', line4dCoords[1]);

const wkb = wasmts.io.WKBWriter.write(wkbWriter, line4d);
const parsed = wkbReader.read(wkb);
console.log('Round-trip M preserved:', parsed.getCoordinates()[1].m === 200);

await visualization({"4D Point": [point4d], "4D Line": [line4d]})`,

    io: `// WKT and WKB Input/Output
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const writer = wasmts.io.WKTWriter.create0();
const wkbWriter = wasmts.io.WKBWriter.create0();
const wkbReader = wasmts.io.WKBReader.create0();

console.log('=== WKT I/O ===');
const poly = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Read polygon, area:', poly.getArea());
const wkt = writer.write(poly);
console.log('WKT:', wkt);

console.log('\\n=== WKB I/O ===');
const wkb = wasmts.io.WKBWriter.write(wkbWriter, poly);
console.log('WKB size:', wkb.length, 'bytes');
const fromWKB = wkbReader.read(wkb);
console.log('Round-trip successful:', fromWKB.equals(poly));

console.log('\\n=== 3D I/O ===');
const point3d = wasmts.geom.GeometryFactory.createPoint(f, {x: 500, y: 500, z: 100});
console.log('3D WKT:', writer.write(point3d));

await visualization({"Polygon": [poly], "3D Point": [point3d]})`,

    geojson: `// GeoJSON Input/Output
const reader = wasmts.io.geojson.GeoJsonReader.create0();
const writer = wasmts.io.geojson.GeoJsonWriter.create0();

const point = reader.read('{"type":"Point","coordinates":[0,0]}');
console.log('Point type:', point.type);

const poly = reader.read('{"type":"Polygon","coordinates":[[[0,0],[1500,0],[1500,1500],[0,1500],[0,0]]]}');
console.log('Polygon area:', poly.getArea());

const polyWithHole = reader.read('{"type":"Polygon","coordinates":[[[0,0],[2000,0],[2000,2000],[0,2000],[0,0]],[[400,400],[1200,400],[1200,1200],[400,1200],[400,400]]]}');
console.log('Polygon with hole area:', polyWithHole.getArea());

const wktReader = wasmts.io.WKTReader.create0();
const circle = wktReader.read('POINT (500 500)').buffer(600);
const circleGeoJSON = writer.write(circle);
console.log('Buffer type:', JSON.parse(circleGeoJSON).type);

console.log('GeoJSON out:', writer.write(point));

await visualization({"Polygon": [poly], "With Hole": [polyWithHole], "Circle": [circle]})`,

    flatbuffers: `// Flat-buffer geometry: typed arrays in and out, no serialization
// ringOffsets lists each ring's start index (in coordinates), then the end.
const shellAndHole = new Float64Array([
    0, 0,  2000, 0,  2000, 2000,  0, 2000,  0, 0,
    400, 400,  1200, 400,  1200, 1200,  400, 1200,  400, 400
]);
const ringOffsets = new Int32Array([0, 5, 10]);
const poly = wasmts.geom.fromFlat('Polygon', shellAndHole, 2, ringOffsets, null);
console.log('Built:', poly.getGeometryType(), ' area:', poly.getArea());

// toFlat extracts the same layout straight into typed arrays.
const flat = wasmts.geom.toFlat(poly, 2);
console.log('toFlat type:', flat.type);
console.log('coords length:', flat.coords.length, ' ringOffsets:', Array.from(flat.ringOffsets).join(','));

// Rebuild from the extracted buffers: a lossless round trip.
const back = wasmts.geom.fromFlat(flat.type, flat.coords, flat.dim, flat.ringOffsets, flat.partOffsets);
console.log('Round trip equalsExact:', back.equalsExact(poly, 0));

// getCoordinatesFlat: one Float64Array off any geometry.
const reader = wasmts.io.WKTReader.create0();
const line = reader.read('LINESTRING (0 0, 1000 1000, 2000 0)');
console.log('Line XY buffer:', Array.from(wasmts.geom.getCoordinatesFlat(line, 2)).join(','));

await visualization({"From Flat Buffers": [poly], "Line": [line]})`,

    formats: `// KML and TWKB I/O
const reader = wasmts.io.WKTReader.create0();
const poly = reader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');

// KML: the XML fragment format used by Google Earth and friends.
const kmlWriter = wasmts.io.kml.KMLWriter.create0();
const kml = wasmts.io.kml.KMLWriter.write(kmlWriter, poly);
console.log('KML:', kml);
const fromKml = wasmts.io.kml.KMLReader.read(wasmts.io.kml.KMLReader.create0(), kml);
console.log('KML round trip equal:', fromKml.equals(poly));

// TWKB: a compact binary encoding; precision trades bytes for digits.
const twkbWriter = wasmts.io.twkb.TWKBWriter.create0();
wasmts.io.twkb.TWKBWriter.setXYPrecision(twkbWriter, 0);
const twkb = wasmts.io.twkb.TWKBWriter.write(twkbWriter, poly);
const wkb = wasmts.io.WKBWriter.write(wasmts.io.WKBWriter.create0(), poly);
console.log('WKB size:', wkb.length, 'bytes; TWKB size:', twkb.length, 'bytes');
const fromTwkb = wasmts.io.twkb.TWKBReader.read(wasmts.io.twkb.TWKBReader.create0(), twkb);
console.log('TWKB round trip equal:', fromTwkb.equals(poly));

await visualization({"Polygon": [fromKml]})`,

    polyaccessors: `// Polygon Accessors - Exterior Ring and Interior Holes
const reader = wasmts.io.WKTReader.create0();
const simplePoly = reader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');
console.log('Simple polygon area:', simplePoly.getArea());
console.log('Exterior ring points:', simplePoly.getExteriorRing().getCoordinates().length);
console.log('Number of holes:', simplePoly.getNumInteriorRing());

const polyWithHole = reader.read(
    'POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0), (400 400, 1200 400, 1200 1200, 400 1200, 400 400))'
);
console.log('\\nWith hole area:', polyWithHole.getArea());
console.log('Number of holes:', polyWithHole.getNumInteriorRing());
const hole = polyWithHole.getInteriorRingN(0);
console.log('Hole ring type:', hole.type);

const multiHolePoly = reader.read(
    'POLYGON ((0 0, 3000 0, 3000 3000, 0 3000, 0 0), ' +
    '(200 200, 800 200, 800 800, 200 800, 200 200), ' +
    '(1200 1200, 1800 1200, 1800 1800, 1200 1800, 1200 1200), ' +
    '(2200 200, 2800 200, 2800 800, 2200 800, 2200 200))'
);
console.log('\\nMulti-hole area:', multiHolePoly.getArea());
console.log('Number of holes:', multiHolePoly.getNumInteriorRing());

await visualization({"Simple": [simplePoly], "One Hole": [polyWithHole], "Multi Hole": [multiHolePoly]})`,

    distance: `// Distance Operations - nearest points between geometries
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const poly1 = reader.read('POLYGON ((0 0, 800 0, 800 800, 0 800, 0 0))');
const poly2 = reader.read('POLYGON ((1500 0, 2500 0, 2500 800, 1500 800, 1500 0))');

const dist = poly1.distance(poly2);
console.log('Distance between polygons:', dist, 'ft');

const nearest = wasmts.operation.distance.DistanceOp.nearestPoints(poly1, poly2);
console.log('Nearest on poly1:', nearest[0]);
console.log('Nearest on poly2:', nearest[1]);

// Build a connector line between nearest points
const connector = reader.read(\`LINESTRING (\${nearest[0].x} \${nearest[0].y}, \${nearest[1].x} \${nearest[1].y})\`);
console.log('Connector length:', connector.getLength().toFixed(0), 'ft');

// Point to line distance
const point = wasmts.geom.GeometryFactory.createPoint(f, {x: 400, y: 1200});
const line = reader.read('LINESTRING (0 0, 1500 0)');
console.log('Point to line distance:', point.distance(line).toFixed(0), 'ft');

await visualization({"Poly 1": [poly1], "Poly 2": [poly2], "Connector": [connector], "Point": [point]})`,

    factory: `// GeometryFactory - build geometries from coordinates
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();

const point2D = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0});
console.log('2D Point:', point2D.getCoordinates()[0]);

const point3D = wasmts.geom.GeometryFactory.createPoint(f, {x: 500, y: 500, z: 150});
console.log('3D Point:', point3D.getCoordinates()[0]);

// Lines and polygons read most cleanly from WKT in the REPL.
const line = reader.read('LINESTRING (0 0, 1000 1000, 2000 0)');
console.log('LineString length:', line.getLength().toFixed(0), 'ft');

const polygon = reader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');
console.log('Polygon area:', polygon.getArea());

const donut = reader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0), (300 300, 900 300, 900 900, 300 900, 300 300))');
console.log('Donut area:', donut.getArea());

// buildGeometry wraps a JS array of geometries in one collection.
const collection = wasmts.geom.GeometryFactory.buildGeometry(f, [point2D, line, polygon]);
console.log('buildGeometry:', collection.getGeometryType(), 'with', collection.getNumGeometries(), 'members');

await visualization({"Point": [point2D], "Line": [line], "Polygon": [polygon], "Donut": [donut]})`,

    spatialrelate: `// Spatial Relationships - DE-9IM via relate()
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();
const polygon = reader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');
const insidePoint = wasmts.geom.GeometryFactory.createPoint(f, {x: 700, y: 700});
const outsidePoint = wasmts.geom.GeometryFactory.createPoint(f, {x: 2500, y: 2500});
const edgePoint = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 700});

const matrix = polygon.relate(insidePoint);
console.log('DE-9IM matrix:', matrix.toString());
console.log('Contains?', matrix.isContains());
console.log('Intersects?', matrix.isIntersects());
console.log('Disjoint?', matrix.isDisjoint());

console.log('\\nPattern T*****FF* (contains):', polygon.relatePattern(insidePoint, 'T*****FF*'));
console.log('Outside matches?', polygon.relatePattern(outsidePoint, 'T*****FF*'));

const custom = wasmts.geom.IntersectionMatrix.fromString('T*F**FFF*');
console.log('\\nCustom matrix:', custom.toString());
console.log('Matches T********?', custom.matches('T********'));

await visualization({"Polygon": [polygon], "Inside": [insidePoint], "Edge": [edgePoint], "Outside": [outsidePoint]})`,

    envelope: `// Envelope - Bounding box operations
// Envelope.create4(minX, maxX, minY, maxY)
const reader = wasmts.io.WKTReader.create0();
const env = wasmts.geom.Envelope.create4(0, 1500, 0, 2000);
console.log('Width:', env.getWidth(), 'Height:', env.getHeight());
console.log('Area:', env.getArea());
console.log('Centre:', JSON.stringify(env.centre()));

const expanded = wasmts.geom.Envelope.create4(0, 1500, 0, 2000);
expanded.expandBy(300);
console.log('After expandBy(300):', expanded.getMinX(), expanded.getMaxX());

const env1 = wasmts.geom.Envelope.create4(0, 1000, 0, 1000);
const env2 = wasmts.geom.Envelope.create4(500, 1500, 500, 1500);
const inter = env1.intersection(env2);
console.log('Intersection:', inter.getMinX(), inter.getMaxX(), inter.getMinY(), inter.getMaxY());

console.log('env covers (500,500)?', env.covers({ x: 500, y: 500 }));

// Convert envelopes to geometry for visualization
const env1Geom = reader.read(\`POLYGON ((\${env1.getMinX()} \${env1.getMinY()}, \${env1.getMaxX()} \${env1.getMinY()}, \${env1.getMaxX()} \${env1.getMaxY()}, \${env1.getMinX()} \${env1.getMaxY()}, \${env1.getMinX()} \${env1.getMinY()}))\`);
const env2Geom = reader.read(\`POLYGON ((\${env2.getMinX()} \${env2.getMinY()}, \${env2.getMaxX()} \${env2.getMinY()}, \${env2.getMaxX()} \${env2.getMaxY()}, \${env2.getMinX()} \${env2.getMaxY()}, \${env2.getMinX()} \${env2.getMinY()}))\`);
const interGeom = reader.read(\`POLYGON ((\${inter.getMinX()} \${inter.getMinY()}, \${inter.getMaxX()} \${inter.getMinY()}, \${inter.getMaxX()} \${inter.getMaxY()}, \${inter.getMinX()} \${inter.getMaxY()}, \${inter.getMinX()} \${inter.getMinY()}))\`);

await visualization({"Envelope 1": [env1Geom], "Envelope 2": [env2Geom], "Intersection": [interGeom]})`,

    linestring: `// LineString / LinearRing Methods
const reader = wasmts.io.WKTReader.create0();
const line = reader.read('LINESTRING (0 0, 30 50, 80 20, 120 60)');
const ring = reader.read('LINEARRING (0 -30, 100 -30, 100 -130, 0 -130, 0 -30)');

console.log('Start:', line.getStartPoint().getX(), line.getStartPoint().getY());
console.log('End:', line.getEndPoint().getX(), line.getEndPoint().getY());
console.log('Point at idx 2:', line.getPointN(2).getX(), line.getPointN(2).getY());

console.log('\\nLine closed?', line.isClosed());
console.log('Line is ring?', line.isRing());
console.log('Ring closed?', ring.isClosed());
console.log('Ring is ring?', ring.isRing());

const seq = line.getCoordinateSequence();
console.log('\\nCoordinateSequence size:', seq.size());
for (let i = 0; i < seq.size(); i++) {
    console.log('  [' + i + '] x=' + seq.getX(i) + ' y=' + seq.getY(i));
}

await visualization({"LineString": [line], "LinearRing": [ring]})`,

    coordsequence: `// CoordinateSequence & Filters - transform geometry via apply()
const reader = wasmts.io.WKTReader.create0();
const writer = wasmts.io.WKTWriter.create0();
const poly = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Original:', writer.write(poly));

// Translate by (1500, 500)
const translated = poly.apply((seq, i) => {
    seq.setOrdinate(i, 0, seq.getX(i) + 1500);
    seq.setOrdinate(i, 1, seq.getY(i) + 500);
});
console.log('Translated:', writer.write(translated));

// Scale by 0.5x
const scaled = poly.apply((seq, i) => {
    seq.setOrdinate(i, 0, seq.getX(i) * 0.5);
    seq.setOrdinate(i, 1, seq.getY(i) * 0.5);
});
console.log('Scaled 0.5x:', writer.write(scaled));
console.log('Area ratio:', poly.getArea() / scaled.getArea(), '(expect 4)');

// 45-degree rotation around origin
const rotated = poly.apply((seq, i) => {
    const x = seq.getX(i);
    const y = seq.getY(i);
    const cos45 = Math.cos(Math.PI/4);
    const sin45 = Math.sin(Math.PI/4);
    seq.setOrdinate(i, 0, x * cos45 - y * sin45);
    seq.setOrdinate(i, 1, x * sin45 + y * cos45);
});
console.log('Rotated 45deg:', writer.write(rotated));

await visualization({"Original": [poly], "Translated": [translated], "Scaled": [scaled], "Rotated 45": [rotated]})`,

    densifier: `// Densifier - Add vertices to limit segment length
// Critical for reprojection: straight edges become curves
const reader = wasmts.io.WKTReader.create0();
const line = reader.read('LINESTRING (0 0, 3000 0)');
console.log('Original line points:', line.getCoordinates().length);

const dense = wasmts.densify.Densifier.densify(line, 500);
console.log('Densified (max 500 ft):', dense.getCoordinates().length, 'points');

const poly = reader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
console.log('\\nOriginal polygon points:', poly.getCoordinates().length);

const d = wasmts.densify.Densifier.create(poly);
wasmts.densify.Densifier.setDistanceTolerance(d, 400);
wasmts.densify.Densifier.setValidate(d, true);
const result = wasmts.densify.Densifier.getResultGeometry(d);
console.log('Densified polygon points:', result.getCoordinates().length);
console.log('Valid:', result.isValid());

await visualization({"Original": [poly], "Densified": [result]})`,

    geometryfixer: `// GeometryFixer - topology repair
const reader = wasmts.io.WKTReader.create0();
const writer = wasmts.io.WKTWriter.create0();
// An invalid polygon (bowtie/self-intersecting)
const bowtie = reader.read('POLYGON ((0 0, 1500 1500, 1500 0, 0 1500, 0 0))');
console.log('Input valid?', bowtie.isValid());

const fixed = wasmts.geom.util.GeometryFixer.fix(bowtie);
console.log('Fixed type:', fixed.type);
console.log('Fixed valid?', fixed.isValid());
console.log('Fixed WKT:', writer.write(fixed));

const fixer = wasmts.geom.util.GeometryFixer.create(bowtie);
wasmts.geom.util.GeometryFixer.setKeepCollapsed(fixer, false);
wasmts.geom.util.GeometryFixer.setKeepMulti(fixer, true);
const result = wasmts.geom.util.GeometryFixer.getResult(fixer);
console.log('\\nInstance result type:', result.type);

await visualization({"Invalid (Bowtie)": [bowtie], "Fixed": [fixed]})`,

    coverageunion: `// CoverageUnion - Fast union for adjacent non-overlapping tiles
// Note: the array argument marshals in the browser; Node's GraalVM
// web-image build does not pass JS arrays across the boundary.
const reader = wasmts.io.WKTReader.create0();
const tile1 = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
const tile2 = reader.read('POLYGON ((1000 0, 2000 0, 2000 1000, 1000 1000, 1000 0))');
const tile3 = reader.read('POLYGON ((0 1000, 1000 1000, 1000 2000, 0 2000, 0 1000))');
const tile4 = reader.read('POLYGON ((1000 1000, 2000 1000, 2000 2000, 1000 2000, 1000 1000))');

console.log('Total area:', tile1.getArea() + tile2.getArea() + tile3.getArea() + tile4.getArea());

const union = wasmts.coverage.CoverageUnion.union([tile1, tile2, tile3, tile4]);
console.log('Union type:', union.type);
console.log('Union area:', union.getArea());
console.log('Valid:', union.isValid());

await visualization({"Tiles": [tile1, tile2, tile3, tile4], "Union": [union]})`,

    precisionmodel: `// PrecisionModel - Control coordinate precision
const pmFloat = wasmts.geom.PrecisionModel.create0();
console.log('Type:', pmFloat.getType());
console.log('Floating?', pmFloat.isFloating());

const pm1000 = wasmts.geom.PrecisionModel.fromScale(1000);
console.log('\\nType:', pm1000.getType());
console.log('Scale:', pm1000.getScale());
console.log('Grid size:', pm1000.gridSize());
console.log('Max sig digits:', pm1000.getMaximumSignificantDigits());

console.log('\\nmakePrecise(1.23456):', pm1000.makePrecise(1.23456));
console.log('makePrecise(9.99951):', pm1000.makePrecise(9.99951));

pm1000`,

    precisionreducer: `// GeometryPrecisionReducer - Snap coords to a grid
const reader = wasmts.io.WKTReader.create0();
const pm = wasmts.geom.PrecisionModel.fromScale(1.0);

const poly = reader.read('POLYGON ((0.1 0.2, 1500.7 0.3, 1500.8 1500.9, 0.4 1500.6, 0.1 0.2))');
console.log('Original:', poly.getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));

const reduced = wasmts.precision.GeometryPrecisionReducer.reduce(poly, pm);
console.log('Reduced:', reduced.getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));
console.log('Valid:', reduced.isValid());

const pm10 = wasmts.geom.PrecisionModel.fromScale(10);
const line = reader.read('LINESTRING (0.123 0.456, 1500.789 1500.012)');
console.log('\\n10x grid:', wasmts.precision.GeometryPrecisionReducer.reduce(line, pm10)
    .getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));

await visualization({"Original": [poly], "Reduced (integer)": [reduced]})`,

    dimension: `// Dimension Constants - geometry dimensionality and DE-9IM values
const f = wasmts.geom.GeometryFactory.create0();
const reader = wasmts.io.WKTReader.create0();

console.log('=== Geometry Dimension Values ===');
console.log('Point (P):', wasmts.geom.Dimension.P);
console.log('Line (L):', wasmts.geom.Dimension.L);
console.log('Area (A):', wasmts.geom.Dimension.A);
console.log('FALSE:', wasmts.geom.Dimension.FALSE);
console.log('TRUE:', wasmts.geom.Dimension.TRUE);
console.log('DONTCARE:', wasmts.geom.Dimension.DONTCARE);

console.log('\\n=== Dimension Symbols ===');
console.log('SYM_P:', wasmts.geom.Dimension.SYM_P);
console.log('SYM_L:', wasmts.geom.Dimension.SYM_L);
console.log('SYM_A:', wasmts.geom.Dimension.SYM_A);

console.log('\\n=== Conversions ===');
console.log('toDimensionSymbol(0):', wasmts.geom.Dimension.toDimensionSymbol(0));
console.log('toDimensionSymbol(1):', wasmts.geom.Dimension.toDimensionSymbol(1));
console.log('toDimensionValue("F"):', wasmts.geom.Dimension.toDimensionValue('F'));

console.log('\\n=== Geometry getDimension() ===');
const point = wasmts.geom.GeometryFactory.createPoint(f, {x: 0, y: 0});
const line = reader.read('LINESTRING (0 0, 1000 1000)');
const poly = reader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Point:', point.getDimension());
console.log('Line:', line.getDimension());
console.log('Polygon:', poly.getDimension());`,

    errors: `// Java exceptions cross into JavaScript as real Errors
// This build flattens .message before converting the Java string, so read
// the text off the retained throwable instead: $as('string') converts a
// java.lang.String proxy to a JS string.
const reader = wasmts.io.WKTReader.create0();
const javaMessage = (e) => {
    try { return e.javaError.getMessage().$as('string'); }
    catch { return e.message; }
};

try {
    reader.read('POLYGON ((0 0, 1000 0,');
} catch (e) {
    console.log('instanceof Error:', e instanceof Error);
    console.log('Parse error:', javaMessage(e));
}

try {
    reader.read('LINEARRING (0 0, 1000 0, 1000 1000, 5 5)');
} catch (e) {
    console.log('Constraint error:', javaMessage(e));
}

'caught both errors'`
};
