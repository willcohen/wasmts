window.examples = {
    geometry: `// Geometry Methods: Operations, Predicates, Transformations
// All coordinates in feet from Boston City Hall (EPSG:2249)

const circle1 = wasmts.geom.createPoint(0, 0).buffer(800);
const circle2 = wasmts.geom.createPoint(1200, 0).buffer(800);

const union = circle1.union(circle2);
console.log('Union area:', union.getArea().toFixed(0), 'sq ft');

const intersection = circle1.intersection(circle2);
console.log('Intersection area:', intersection.getArea().toFixed(0), 'sq ft');

const difference = circle1.difference(circle2);
console.log('Difference area:', difference.getArea().toFixed(0), 'sq ft');

const symDiff = circle1.symDifference(circle2);
console.log('Symmetric difference area:', symDiff.getArea().toFixed(0), 'sq ft');

// Predicates
const container = wasmts.io.WKTReader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
const small = wasmts.io.WKTReader.read('POLYGON ((400 400, 1200 400, 1200 1200, 400 1200, 400 400))');
console.log('container.contains(small):', container.contains(small));
console.log('small.within(container):', small.within(container));
console.log('container.intersects(small):', container.intersects(small));

// Transformations
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Area:', poly.getArea(), 'sq ft');
console.log('Centroid:', wasmts.io.WKTWriter.write(poly.getCentroid()));
console.log('isValid:', poly.isValid());
console.log('isRectangle:', poly.isRectangle());

await visualization({"Input": [circle1, circle2], "Union": [union]})`,

    buffer: `// Buffering - Simple and Advanced
// Distances in feet from Boston City Hall

const point = wasmts.geom.createPoint(0, 0);
const buffered = point.buffer(800);
console.log('Buffer area:', buffered.getArea().toFixed(0), 'sq ft');

const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 1500 0)');
const lineBuffer = line.buffer(200);
console.log('Line buffer area:', lineBuffer.getArea().toFixed(0), 'sq ft');

// Negative buffer (erosion)
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
const eroded = poly.buffer(-200);
console.log('Original area:', poly.getArea());
console.log('Eroded area:', eroded.getArea().toFixed(0));

// Cap styles: 1=round, 2=flat, 3=square
const flatCap = line.buffer(200, 2, 1, 5.0);
console.log('Flat cap area:', flatCap.getArea().toFixed(0));

await visualization({"Point": [point], "Buffer (800 ft)": [buffered], "Line Buffer": [lineBuffer]})`,

    strtree: `// STRtree Spatial Indexing
// 100 random parcels near Boston City Hall
const startTime = performance.now();

const index = wasmts.index.strtree.STRtree.create();
const geometries = [];
for (let i = 0; i < 100; i++) {
    const x = (Math.random() - 0.5) * 4000;
    const y = (Math.random() - 0.5) * 4000;
    const poly = wasmts.io.WKTReader.read(
        \`POLYGON ((\${x} \${y}, \${x+200} \${y}, \${x+200} \${y+200}, \${x} \${y+200}, \${x} \${y}))\`
    );
    const envelope = poly.getEnvelopeInternal();
    wasmts.index.strtree.STRtree.insert(index, envelope, {id: i, geom: poly});
    geometries.push({id: i, poly, envelope});
}
console.log('Inserted 100 geometries');

// Query a search area
const searchEnv = wasmts.geom.createEnvelope(-500, 500, -500, 500);
const results = wasmts.index.strtree.STRtree.query(index, searchEnv);
const indexTime = performance.now() - startTime;
console.log(\`Found \${results.length} results in \${indexTime.toFixed(2)}ms\`);

const searchPoly = wasmts.io.WKTReader.read('POLYGON ((-500 -500, 500 -500, 500 500, -500 500, -500 -500))');
const allPolys = geometries.map(g => g.poly);
const hitPolys = results.map(r => r.geom);

await visualization({"All Parcels": allPolys, "Search Area": [searchPoly], "Hits": hitPolys})`,

    prepared: `// PreparedGeometry - Optimized for repeated predicates
const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0), (400 400, 800 400, 800 800, 400 800, 400 400))');
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
    const pt = wasmts.geom.createPoint(x, y);
    const result = wasmts.geom.prep.PreparedGeometry.containsProperly(prepared, pt);
    console.log(\`  (\${x}, \${y}) \${desc}: \${result}\`);
    if (result) insidePts.push(pt); else outsidePts.push(pt);
}

await visualization({"Polygon": [polygon], "Inside": insidePts, "Outside": outsidePts})`,

    rectangles: `// Minimum Bounding Rectangles & Circles
const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 1500 300, 1800 1500, 300 1800, 0 0))');
console.log('Polygon area:', polygon.getArea().toFixed(0));

const minDiamRect = wasmts.algorithm.MinimumDiameter.getMinimumRectangle(polygon);
console.log('Min-width rect area:', minDiamRect.getArea().toFixed(0));

const minAreaRect = wasmts.algorithm.MinimumAreaRectangle.getMinimumRectangle(polygon);
console.log('Min-area rect area:', minAreaRect.getArea().toFixed(0));

const circle = wasmts.algorithm.MinimumBoundingCircle.getCircle(polygon);
const radius = wasmts.algorithm.MinimumBoundingCircle.getRadius(polygon);
console.log('Bounding circle radius:', radius.toFixed(0), 'ft');

await visualization({"Polygon": [polygon], "Min-Width Rect": [minDiamRect], "Min-Area Rect": [minAreaRect], "Bounding Circle": [circle]})`,

    offsetcurve: `// Offset Curves - parallel lines for road shoulders, setbacks
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 1500 0, 1500 1500)');

const rightOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 200);
const leftOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, -200);
console.log('Right offset points:', rightOffset.getCoordinates().length);
console.log('Left offset points:', leftOffset.getCoordinates().length);

const CAP_FLAT = 2, JOIN_MITRE = 2;
const sharpOffset = wasmts.operation.buffer.OffsetCurveBuilder.getOffsetCurve(line, 200, CAP_FLAT, JOIN_MITRE, 10.0);
console.log('Sharp corner offset created');

await visualization({"Center Line": [line], "Right (+200 ft)": [rightOffset], "Left (-200 ft)": [leftOffset]})`,

    linemerger: `// LineMerger - Combine connected linestrings
const line1 = wasmts.io.WKTReader.read('LINESTRING (0 0, 500 0)');
const line2 = wasmts.io.WKTReader.read('LINESTRING (500 0, 1200 0)');
const line3 = wasmts.io.WKTReader.read('LINESTRING (1200 0, 1200 800)');
const line4 = wasmts.io.WKTReader.read('LINESTRING (2000 2000, 2500 2500)');

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

    cascadedunion: `// CascadedPolygonUnion - Efficiently union overlapping polygons
const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
const poly2 = wasmts.io.WKTReader.read('POLYGON ((500 500, 1500 500, 1500 1500, 500 1500, 500 500))');
const poly3 = wasmts.io.WKTReader.read('POLYGON ((1000 1000, 2000 1000, 2000 2000, 1000 2000, 1000 1000))');
const poly4 = wasmts.io.WKTReader.read('POLYGON ((800 0, 1800 0, 1800 800, 800 800, 800 0))');

console.log('Total area (with overlaps):', poly1.getArea() + poly2.getArea() + poly3.getArea() + poly4.getArea());

const union = wasmts.operation.union.CascadedPolygonUnion.union([poly1, poly2, poly3, poly4]);
console.log('Union area (no overlaps):', union.getArea().toFixed(0));

await visualization({"Input": [poly1, poly2, poly3, poly4], "Union": [union]})`,

    "3d": `// 3D Geometries with Z coordinates (elevation in feet)
// Note: MapLibre renders all geometry in 2D; Z values are shown as
// vertex labels and color gradients but not as true 3D elevation.
const point3d = wasmts.geom.createPoint(0, 0, 150);
const coords = point3d.getCoordinates();
console.log('3D Point - X:', coords[0].x, 'Y:', coords[0].y, 'Z:', coords[0].z);

const line3d = wasmts.io.WKTReader.read('LINESTRING Z (0 0 0, 500 500 100, 1000 500 200, 1500 0 150)');
const lineCoords = line3d.getCoordinates();
lineCoords.forEach((c, i) => {
    console.log(\`  Point \${i}: X=\${c.x}, Y=\${c.y}, Z=\${c.z}\`);
});
console.log('2D length:', line3d.getLength().toFixed(0), 'ft');

const wkb = wasmts.io.WKBWriter.write(line3d);
const fromWKB = wasmts.io.WKBReader.read(wkb);
console.log('WKB round-trip Z preserved:', fromWKB.getCoordinates()[2].z === 200);

await visualization({"3D Point": [point3d], "3D Line (XY projection)": [line3d]})`,

    "4d": `// 4D Geometries with XYZM coordinates
// Note: MapLibre renders all geometry in 2D; Z/M values are shown as
// vertex labels and color gradients but not as true 3D elevation.
const point4d = wasmts.geom.createPoint(0, 0, 150, 0);
const coords = point4d.getCoordinates();
console.log('X:', coords[0].x, 'Y:', coords[0].y, 'Z:', coords[0].z, 'M:', coords[0].m);

const line4d = wasmts.io.WKTReader.read('LINESTRING ZM (0 0 0 100, 500 500 100 200, 1000 0 200 300)');
const line4dCoords = line4d.getCoordinates();
console.log('Point 0:', line4dCoords[0]);
console.log('Point 1:', line4dCoords[1]);

const wkb = wasmts.io.WKBWriter.write(line4d);
const parsed = wasmts.io.WKBReader.read(wkb);
console.log('Round-trip M preserved:', parsed.getCoordinates()[1].m === 200);

await visualization({"4D Point": [point4d], "4D Line": [line4d]})`,

    io: `// WKT and WKB Input/Output
console.log('=== WKT I/O ===');
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Read polygon, area:', poly.getArea());
const wkt = wasmts.io.WKTWriter.write(poly);
console.log('WKT:', wkt);

console.log('\\n=== WKB I/O ===');
const wkb = wasmts.io.WKBWriter.write(poly);
console.log('WKB size:', wkb.length, 'bytes');
const fromWKB = wasmts.io.WKBReader.read(wkb);
console.log('Round-trip successful:', fromWKB.equals(poly));

console.log('\\n=== 3D/4D I/O ===');
const point3d = wasmts.geom.createPoint(500, 500, 100);
console.log('3D WKT:', wasmts.io.WKTWriter.write(point3d));

await visualization({"Polygon": [poly], "3D Point": [point3d]})`,

    geojson: `// GeoJSON Input/Output
const pointGeoJSON = '{"type":"Point","coordinates":[0,0]}';
const point = wasmts.io.GeoJSONReader.read(pointGeoJSON);
console.log('Point type:', point.type);

const polyGeoJSON = '{"type":"Polygon","coordinates":[[[0,0],[1500,0],[1500,1500],[0,1500],[0,0]]]}';
const poly = wasmts.io.GeoJSONReader.read(polyGeoJSON);
console.log('Polygon area:', poly.getArea());

const holeGeoJSON = '{"type":"Polygon","coordinates":[[[0,0],[2000,0],[2000,2000],[0,2000],[0,0]],[[400,400],[1200,400],[1200,1200],[400,1200],[400,400]]]}';
const polyWithHole = wasmts.io.GeoJSONReader.read(holeGeoJSON);
console.log('Polygon with hole area:', polyWithHole.getArea());

const circle = wasmts.geom.createPoint(500, 500).buffer(600);
const circleGeoJSON = wasmts.io.GeoJSONWriter.write(circle);
console.log('Buffer type:', JSON.parse(circleGeoJSON).type);

// Instance writer without CRS
const writer = wasmts.io.GeoJSONWriter.create();
writer.setEncodeCRS(false);
console.log('No-CRS output:', writer.write(point));

await visualization({"Polygon": [poly], "With Hole": [polyWithHole], "Circle": [circle]})`,

    polyaccessors: `// Polygon Accessors - Exterior Ring and Interior Holes
const simplePoly = wasmts.io.WKTReader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');
console.log('Simple polygon area:', simplePoly.getArea());
console.log('Exterior ring points:', simplePoly.getExteriorRing().getCoordinates().length);
console.log('Number of holes:', simplePoly.getNumInteriorRing());

const polyWithHole = wasmts.io.WKTReader.read(
    'POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0), (400 400, 1200 400, 1200 1200, 400 1200, 400 400))'
);
console.log('\\nWith hole area:', polyWithHole.getArea());
console.log('Number of holes:', polyWithHole.getNumInteriorRing());
const hole = polyWithHole.getInteriorRingN(0);
console.log('Hole ring type:', hole.type);

const multiHolePoly = wasmts.io.WKTReader.read(
    'POLYGON ((0 0, 3000 0, 3000 3000, 0 3000, 0 0), ' +
    '(200 200, 800 200, 800 800, 200 800, 200 200), ' +
    '(1200 1200, 1800 1200, 1800 1800, 1200 1800, 1200 1200), ' +
    '(2200 200, 2800 200, 2800 800, 2200 800, 2200 200))'
);
console.log('\\nMulti-hole area:', multiHolePoly.getArea());
console.log('Number of holes:', multiHolePoly.getNumInteriorRing());

await visualization({"Simple": [simplePoly], "One Hole": [polyWithHole], "Multi Hole": [multiHolePoly]})`,

    distance: `// Distance Operations - nearest points between geometries
const poly1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 800 0, 800 800, 0 800, 0 0))');
const poly2 = wasmts.io.WKTReader.read('POLYGON ((1500 0, 2500 0, 2500 800, 1500 800, 1500 0))');

const dist = poly1.distance(poly2);
console.log('Distance between polygons:', dist, 'ft');

const nearest = wasmts.geom.nearestPoints(poly1, poly2);
console.log('Nearest on poly1:', nearest[0]);
console.log('Nearest on poly2:', nearest[1]);

// Build a connector line between nearest points
const connectorWKT = \`LINESTRING (\${nearest[0].x} \${nearest[0].y}, \${nearest[1].x} \${nearest[1].y})\`;
const connector = wasmts.io.WKTReader.read(connectorWKT);
console.log('Connector length:', connector.getLength().toFixed(0), 'ft');

// Point to line distance
const point = wasmts.geom.createPoint(400, 1200);
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 1500 0)');
console.log('Point to line distance:', point.distance(line).toFixed(0), 'ft');

await visualization({"Poly 1": [poly1], "Poly 2": [poly2], "Connector": [connector], "Point": [point]})`,

    factory: `// Geometry Factory - create from coordinate arrays
const point2D = wasmts.geom.createPoint(0, 0);
console.log('2D Point:', point2D.getCoordinates()[0]);

const point3D = wasmts.geom.createPoint(500, 500, 150);
console.log('3D Point:', point3D.getCoordinates()[0]);

const lineCoords = [{ x: 0, y: 0 }, { x: 1000, y: 1000 }, { x: 2000, y: 0 }];
const line = wasmts.geom.createLineString(lineCoords);
console.log('LineString length:', line.getLength().toFixed(0), 'ft');

const shell = [
    { x: 0, y: 0 }, { x: 1500, y: 0 },
    { x: 1500, y: 1500 }, { x: 0, y: 1500 }, { x: 0, y: 0 }
];
const polygon = wasmts.geom.createPolygon(shell);
console.log('Polygon area:', polygon.getArea());

const hole = [
    { x: 300, y: 300 }, { x: 900, y: 300 },
    { x: 900, y: 900 }, { x: 300, y: 900 }, { x: 300, y: 300 }
];
const donut = wasmts.geom.createPolygon(shell, [hole]);
console.log('Donut area:', donut.getArea());

await visualization({"Point": [point2D], "Line": [line], "Polygon": [polygon], "Donut": [donut]})`,

    spatialrelate: `// Spatial Relationships - DE-9IM via relate()
const polygon = wasmts.io.WKTReader.read('POLYGON ((0 0, 1500 0, 1500 1500, 0 1500, 0 0))');
const insidePoint = wasmts.geom.createPoint(700, 700);
const outsidePoint = wasmts.geom.createPoint(2500, 2500);
const edgePoint = wasmts.geom.createPoint(0, 700);

const matrix = polygon.relate(insidePoint);
console.log('DE-9IM matrix:', matrix.toString());
console.log('Contains?', matrix.isContains());
console.log('Intersects?', matrix.isIntersects());
console.log('Disjoint?', matrix.isDisjoint());

console.log('\\nPattern T*****FF* (contains):', polygon.relate(insidePoint, 'T*****FF*'));
console.log('Outside matches?', polygon.relate(outsidePoint, 'T*****FF*'));

const custom = new wasmts.geom.IntersectionMatrix('T*F**FFF*');
console.log('\\nCustom matrix:', custom.toString());
console.log('Matches T********?', custom.matches('T********'));

await visualization({"Polygon": [polygon], "Inside": [insidePoint], "Edge": [edgePoint], "Outside": [outsidePoint]})`,

    envelope: `// Envelope - Bounding box operations
const env = wasmts.geom.createEnvelope(0, 1500, 0, 2000);
console.log('Width:', env.getWidth(), 'Height:', env.getHeight());
console.log('Area:', env.getArea());
console.log('Centre:', JSON.stringify(env.centre()));

const expanded = wasmts.geom.createEnvelope(0, 1500, 0, 2000);
expanded.expandBy(300);
console.log('After expandBy(300):', expanded.getMinX(), expanded.getMaxX());

const env1 = wasmts.geom.createEnvelope(0, 1000, 0, 1000);
const env2 = wasmts.geom.createEnvelope(500, 1500, 500, 1500);
const inter = env1.intersection(env2);
console.log('Intersection:', inter.getMinX(), inter.getMaxX(), inter.getMinY(), inter.getMaxY());

console.log('env covers (500,500)?', env.covers({ x: 500, y: 500 }));

// Convert envelopes to geometry for visualization
const env1Geom = wasmts.io.WKTReader.read(\`POLYGON ((\${env1.getMinX()} \${env1.getMinY()}, \${env1.getMaxX()} \${env1.getMinY()}, \${env1.getMaxX()} \${env1.getMaxY()}, \${env1.getMinX()} \${env1.getMaxY()}, \${env1.getMinX()} \${env1.getMinY()}))\`);
const env2Geom = wasmts.io.WKTReader.read(\`POLYGON ((\${env2.getMinX()} \${env2.getMinY()}, \${env2.getMaxX()} \${env2.getMinY()}, \${env2.getMaxX()} \${env2.getMaxY()}, \${env2.getMinX()} \${env2.getMaxY()}, \${env2.getMinX()} \${env2.getMinY()}))\`);
const interGeom = wasmts.io.WKTReader.read(\`POLYGON ((\${inter.getMinX()} \${inter.getMinY()}, \${inter.getMaxX()} \${inter.getMinY()}, \${inter.getMaxX()} \${inter.getMaxY()}, \${inter.getMinX()} \${inter.getMaxY()}, \${inter.getMinX()} \${inter.getMinY()}))\`);

await visualization({"Envelope 1": [env1Geom], "Envelope 2": [env2Geom], "Intersection": [interGeom]})`,

    linestring: `// LineString / LinearRing Methods
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 30 50, 80 20, 120 60)');
const ring = wasmts.io.WKTReader.read('LINEARRING (0 -30, 100 -30, 100 -130, 0 -130, 0 -30)');

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
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Original:', wasmts.io.WKTWriter.write(poly));

// Translate by (1500, 500)
const translated = poly.apply((seq, i) => {
    seq.setOrdinate(i, 0, seq.getX(i) + 1500);
    seq.setOrdinate(i, 1, seq.getY(i) + 500);
});
console.log('Translated:', wasmts.io.WKTWriter.write(translated));

// Scale by 0.5x
const scaled = poly.apply((seq, i) => {
    seq.setOrdinate(i, 0, seq.getX(i) * 0.5);
    seq.setOrdinate(i, 1, seq.getY(i) * 0.5);
});
console.log('Scaled 0.5x:', wasmts.io.WKTWriter.write(scaled));
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
console.log('Rotated 45deg:', wasmts.io.WKTWriter.write(rotated));

await visualization({"Original": [poly], "Translated": [translated], "Scaled": [scaled], "Rotated 45": [rotated]})`,

    densifier: `// Densifier - Add vertices to limit segment length
// Critical for reprojection: straight edges become curves
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 3000 0)');
console.log('Original line points:', line.getCoordinates().length);

const dense = wasmts.densify.Densifier.densify(line, 500);
console.log('Densified (max 500 ft):', dense.getCoordinates().length, 'points');

const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 2000 0, 2000 2000, 0 2000, 0 0))');
console.log('\\nOriginal polygon points:', poly.getCoordinates().length);

const d = wasmts.densify.Densifier.create(poly);
wasmts.densify.Densifier.setDistanceTolerance(d, 400);
wasmts.densify.Densifier.setValidate(d, true);
const result = wasmts.densify.Densifier.getResultGeometry(d);
console.log('Densified polygon points:', result.getCoordinates().length);
console.log('Valid:', result.isValid());

await visualization({"Original": [poly], "Densified": [result]})`,

    geometryfixer: `// GeometryFixer - topology repair
// Create an invalid polygon (bowtie/self-intersecting)
const bowtie = wasmts.io.WKTReader.read('POLYGON ((0 0, 1500 1500, 1500 0, 0 1500, 0 0))');
console.log('Input valid?', bowtie.isValid());

const fixed = wasmts.geom.util.GeometryFixer.fix(bowtie);
console.log('Fixed type:', fixed.type);
console.log('Fixed valid?', fixed.isValid());
console.log('Fixed WKT:', wasmts.io.WKTWriter.write(fixed));

const fixer = wasmts.geom.util.GeometryFixer.create(bowtie);
wasmts.geom.util.GeometryFixer.setKeepCollapsed(fixer, false);
wasmts.geom.util.GeometryFixer.setKeepMulti(fixer, true);
const result = wasmts.geom.util.GeometryFixer.getResult(fixer);
console.log('\\nInstance result type:', result.type);

await visualization({"Invalid (Bowtie)": [bowtie], "Fixed": [fixed]})`,

    coverageunion: `// CoverageUnion - Fast union for adjacent non-overlapping tiles
const tile1 = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
const tile2 = wasmts.io.WKTReader.read('POLYGON ((1000 0, 2000 0, 2000 1000, 1000 1000, 1000 0))');
const tile3 = wasmts.io.WKTReader.read('POLYGON ((0 1000, 1000 1000, 1000 2000, 0 2000, 0 1000))');
const tile4 = wasmts.io.WKTReader.read('POLYGON ((1000 1000, 2000 1000, 2000 2000, 1000 2000, 1000 1000))');

console.log('Total area:', tile1.getArea() + tile2.getArea() + tile3.getArea() + tile4.getArea());

const union = wasmts.coverage.CoverageUnion.union([tile1, tile2, tile3, tile4]);
console.log('Union type:', union.type);
console.log('Union area:', union.getArea());
console.log('Valid:', union.isValid());

await visualization({"Tiles": [tile1, tile2, tile3, tile4], "Union": [union]})`,

    precisionmodel: `// PrecisionModel - Control coordinate precision
const pmFloat = wasmts.geom.PrecisionModel.create();
console.log('Type:', pmFloat.getType());
console.log('Floating?', pmFloat.isFloating());

const pm1000 = wasmts.geom.PrecisionModel.createFixed(1000);
console.log('\\nType:', pm1000.getType());
console.log('Scale:', pm1000.getScale());
console.log('Grid size:', pm1000.gridSize());
console.log('Max sig digits:', pm1000.getMaximumSignificantDigits());

console.log('\\nmakePrecise(1.23456):', pm1000.makePrecise(1.23456));
console.log('makePrecise(9.99951):', pm1000.makePrecise(9.99951));

pm1000`,

    precisionreducer: `// GeometryPrecisionReducer - Snap coords to a grid
const pm = wasmts.geom.PrecisionModel.createFixed(1.0);

const poly = wasmts.io.WKTReader.read(
    'POLYGON ((0.1 0.2, 1500.7 0.3, 1500.8 1500.9, 0.4 1500.6, 0.1 0.2))'
);
console.log('Original:', poly.getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));

const reduced = wasmts.precision.GeometryPrecisionReducer.reduce(poly, pm);
console.log('Reduced:', reduced.getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));
console.log('Valid:', reduced.isValid());

const pm10 = wasmts.geom.PrecisionModel.createFixed(10);
console.log('\\n10x grid:', wasmts.precision.GeometryPrecisionReducer.reduce(
    wasmts.io.WKTReader.read('LINESTRING (0.123 0.456, 1500.789 1500.012)'), pm10
).getCoordinates().map(c => '(' + c.x + ',' + c.y + ')').join(' '));

await visualization({"Original": [poly], "Reduced (integer)": [reduced]})`,

    dimension: `// Dimension Constants - geometry dimensionality and DE-9IM values
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
console.log('SYM_FALSE:', wasmts.geom.Dimension.SYM_FALSE);
console.log('SYM_TRUE:', wasmts.geom.Dimension.SYM_TRUE);
console.log('SYM_DONTCARE:', wasmts.geom.Dimension.SYM_DONTCARE);

console.log('\\n=== Conversions ===');
console.log('toDimensionSymbol(0):', wasmts.geom.Dimension.toDimensionSymbol(0));
console.log('toDimensionSymbol(1):', wasmts.geom.Dimension.toDimensionSymbol(1));
console.log('toDimensionValue("F"):', wasmts.geom.Dimension.toDimensionValue('F'));

console.log('\\n=== Geometry getDimension() ===');
const point = wasmts.geom.createPoint(0, 0);
const line = wasmts.io.WKTReader.read('LINESTRING (0 0, 1000 1000)');
const poly = wasmts.io.WKTReader.read('POLYGON ((0 0, 1000 0, 1000 1000, 0 1000, 0 0))');
console.log('Point:', point.getDimension());
console.log('Line:', line.getDimension());
console.log('Polygon:', poly.getDimension());`
};
