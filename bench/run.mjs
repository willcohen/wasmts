#!/usr/bin/env node
// Compares the flat-buffer surface against the alternative a consumer would
// otherwise reach for. Read the speedup column; the two absolute us/call columns
// are context, not a baseline to diff against, since absolute timings on a
// developer machine drift with whatever else the box is doing. Both paths are
// measured in the same process seconds apart, so the ratio eats that drift on
// both sides.
//
// Paths with no consumer-facing alternative (WKB, the Coordinate[] extractor,
// applyCoordinates) are absent: there is nothing to ratio them against.
//
//   node --experimental-wasm-exnref bench/run.mjs
//   bb bench

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');
const wasmJsFile = join(root, 'dist', 'wasmts.js');
const wasmBinary = readFileSync(join(root, 'dist', 'wasmts.js.wasm'));

globalThis.__filename = wasmJsFile;
const originalFetch = globalThis.fetch;
globalThis.fetch = (url, ...rest) =>
  (url && (String(url).endsWith('.wasm') || String(url).includes('wasmts.js.wasm')))
    ? Promise.resolve({ ok: true, arrayBuffer: () =>
        Promise.resolve(wasmBinary.buffer.slice(wasmBinary.byteOffset, wasmBinary.byteOffset + wasmBinary.byteLength)) })
    : originalFetch(url, ...rest);

await import(wasmJsFile);

async function ready(timeoutMs = 30000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (globalThis.wasmts?.geom?.fromFlat && globalThis.wasmts?.geom?.GeometryFactory?.create0) return;
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error('wasmts namespace did not populate');
}
await ready();

const W = globalThis.wasmts;
const gjReader = W.io.geojson.GeoJsonReader.create0();
const gjWriter = W.io.geojson.GeoJsonWriter.create0();
gjWriter.setEncodeCRS(false);

// Coordinates per ring: a small road, a building, a large polygon.
const SIZES = [16, 128, 1024];

// Best-of-3 after warmup. Best rather than mean because this measures a floor:
// the fastest observed run is the one least polluted by the rest of the machine.
function perCallMs(fn, iters) {
  for (let i = 0; i < Math.max(3, Math.min(iters, 200)); i++) fn();
  let best = Infinity;
  for (let r = 0; r < 3; r++) {
    const t = process.hrtime.bigint();
    for (let i = 0; i < iters; i++) fn();
    best = Math.min(best, Number(process.hrtime.bigint() - t) / 1e6);
  }
  return best / iters;
}
const iters = (K, budget) => Math.max(20, Math.round(budget / K));

// A closed ring of K distinct points plus the closing point.
function ringGeoJSON(K) {
  const ring = [];
  for (let i = 0; i < K; i++) {
    const t = 2 * Math.PI * i / K;
    ring.push([10 + Math.cos(t), 20 + Math.sin(t)]);
  }
  ring.push(ring[0]);
  return { type: 'Polygon', coordinates: [ring] };
}

// The shape a consumer writes: walk nested coordinate arrays into typed arrays.
function flattenPolygon(gj) {
  const rings = gj.coordinates;
  let n = 0;
  for (const r of rings) n += r.length;
  const coords = new Float64Array(n * 2);
  const ringOffsets = new Int32Array(rings.length + 1);
  let c = 0;
  for (let r = 0; r < rings.length; r++) {
    ringOffsets[r] = c;
    for (const p of rings[r]) {
      coords[c * 2] = p[0];
      coords[c * 2 + 1] = p[1];
      c++;
    }
  }
  ringOffsets[rings.length] = c;
  return { coords, ringOffsets };
}

// Both sides whole-path, JS prep included, because that is what a consumer pays:
// stringify for the GeoJSON route, the flatten walk for this one.
function benchIngest(K) {
  const gj = ringGeoJSON(K);
  const alt = perCallMs(() => gjReader.read(JSON.stringify(gj)), iters(K, 40000));
  const flat = perCallMs(() => {
    const f = flattenPolygon(gj);
    return W.geom.fromFlat('Polygon', f.coords, 2, f.ringOffsets, null);
  }, iters(K, 40000));
  return { alt, flat };
}

function benchExtract(K) {
  const geom = gjReader.read(JSON.stringify(ringGeoJSON(K)));
  const alt = perCallMs(() => JSON.parse(gjWriter.write(geom)), iters(K, 40000));
  const flat = perCallMs(() => W.geom.toFlat(geom, 2), iters(K, 40000));
  return { alt, flat };
}

// Consumers read x/y off every coordinate, so the boxed path's real cost
// includes the walk, not just the call.
function benchCoords(K) {
  const geom = gjReader.read(JSON.stringify(ringGeoJSON(K)));
  const alt = perCallMs(() => {
    const cs = W.geom.getCoordinates(geom);
    let s = 0;
    for (let i = 0; i < cs.length; i++) s += cs[i].x + cs[i].y;
    return s;
  }, iters(K, 40000));
  const flat = perCallMs(() => {
    const a = W.geom.getCoordinatesFlat(geom, 2);
    let s = 0;
    for (let i = 0; i < a.length; i++) s += a[i];
    return s;
  }, iters(K, 40000));
  return { alt, flat };
}

const sections = [
  ['fromFlat vs GeoJsonReader.read', 'GeoJsonReader', benchIngest],
  ['toFlat vs GeoJsonWriter.write + JSON.parse', 'GeoJsonWriter', benchExtract],
  ['getCoordinatesFlat vs getCoordinates', 'getCoordinates', benchCoords],
];

console.log(`\nnode ${process.version}, best-of-3, us/call\n`);
for (const [title, altLabel, fn] of sections) {
  console.log(title);
  console.log('     K' + altLabel.padStart(15) + 'flat'.padStart(12) + 'speedup'.padStart(10));
  for (const K of SIZES) {
    const { alt, flat } = fn(K);
    console.log(String(K).padStart(6)
      + (alt * 1000).toFixed(1).padStart(15)
      + (flat * 1000).toFixed(1).padStart(12)
      + ((alt / flat).toFixed(2) + 'x').padStart(10));
  }
  console.log('');
}
