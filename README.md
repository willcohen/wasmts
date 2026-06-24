# WasmTS

[![npm version](https://badge.fury.io/js/@wcohen%2Fwasmts.svg)](https://www.npmjs.com/package/@wcohen/wasmts)
[![npm downloads](https://img.shields.io/npm/dm/@wcohen/wasmts.svg)](https://www.npmjs.com/package/@wcohen/wasmts)
[![License](https://img.shields.io/badge/License-EPL%202.0%20OR%20EDL%201.0-blue.svg)](LICENSE_EDLv1.txt)

Spatial operations and computational geometry for WebAssembly.

A WebAssembly port of [JTS (Java Topology Suite)](https://github.com/locationtech/jts) 1.20.0, compiled with [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/) and its [web-image backend](https://github.com/oracle/graal/tree/master/web-image). You get JTS's geometry model, spatial operations, predicates, and WKT/WKB/GeoJSON I/O in the browser, in Node, or in a Web Worker.

**[Try the interactive demo →](https://willcohen.github.io/wasmts/)**

This is alpha software. The API is code-generated from a reflection of the JTS classpath, so it covers a large surface, but coverage of edge behavior is still being verified.

## How the API is shaped

The whole API follows a few rules. Learn these and the rest is discoverable from the type definitions.

Namespaces mirror JTS. Every class keeps its JTS package path. `org.locationtech.jts.geom.Envelope` is `wasmts.geom.Envelope`; `org.locationtech.jts.operation.distance.DistanceOp` is `wasmts.operation.distance.DistanceOp`. The top-level groups are `geom`, `io`, `operation.*`, `algorithm`, `index.strtree`, `precision`, `densify`, `coverage`, and `math`.

Geometries are immutable handles. A geometry is a JavaScript object wrapping a pointer into WASM memory. Operations return new geometries and never mutate their inputs. Memory is reclaimed by the JavaScript garbage collector, so there is nothing to free by hand.

Operations come in two call styles that return the same result: fluent methods on a geometry, and free functions that take the geometry first.

```javascript
point.buffer(5);                  // fluent
wasmts.geom.buffer(point, 5);     // functional, identical result
```

Constructors and stateful helpers use `create<N>`. Because the surface comes from reflection, overloaded constructors are disambiguated by argument count: `create0()`, `create1(x)`, `create2(a, b)`, and so on, with named variants for the rest. So an envelope from four bounds is `wasmts.geom.Envelope.create4(minX, maxX, minY, maxY)`, a fixed precision model is `wasmts.geom.PrecisionModel.fromScale(1000)`, and a DE-9IM matrix from a pattern is `wasmts.geom.IntersectionMatrix.fromString('T*F**FFF*')`. Readers, writers, factories, indexes, and merger-style helpers are objects you build once with `create0()` (or `create1(...)`) and reuse.

Coordinates are plain objects. `{x, y}` for 2D, `{x, y, z}` for 3D, `{x, y, z, m}` for 4D. `getCoordinates()` returns an array of these, and the WKT, WKB, and GeoJSON readers and writers preserve Z and M.

Move geometry across boundaries as a serialized format. A handle is only valid inside the WASM instance that created it. To send geometry to a Web Worker or persist it, serialize to WKT, WKB, or GeoJSON and parse it on the other side.

The shipped `dist/wasmts.d.ts` is the authoritative list of what exists. It is generated alongside the WASM and drives editor autocomplete.

## Install

```bash
npm install @wcohen/wasmts
```

## Quick start

Once the `wasmts` global is loaded and initialized (see [Loading](#loading)):

```javascript
// Readers and writers are built once, then reused.
const wkt = wasmts.io.WKTReader.create0();
const gjWriter = wasmts.io.geojson.GeoJsonWriter.create0();

// Parse some geometry.
const poly = wkt.read('POLYGON ((0 0, 100 0, 100 100, 0 100, 0 0))');
const point = wkt.read('POINT (50 50)');

// Operations are fluent on the geometry.
const buffered = point.buffer(10);
const overlap = poly.intersection(buffered);

console.log('Buffer area:', buffered.getArea().toFixed(2));
console.log('Contains point:', poly.contains(point));   // true

// Write the result back out as GeoJSON.
console.log(gjWriter.write(overlap));
```

Reading and writing GeoJSON directly:

```javascript
const reader = wasmts.io.geojson.GeoJsonReader.create0();
const writer = wasmts.io.geojson.GeoJsonWriter.create0();

const geom = reader.read('{"type":"Point","coordinates":[5,10]}');
console.log(geom.getCoordinates()[0]);   // {x: 5, y: 10}
console.log(writer.write(geom));         // GeoJSON string
```

Building a point from numbers goes through a `GeometryFactory`:

```javascript
const gf = wasmts.geom.GeometryFactory.create0();
const p2d = wasmts.geom.GeometryFactory.createPoint(gf, {x: 5, y: 10});
const p3d = wasmts.geom.GeometryFactory.createPoint(gf, {x: 5, y: 10, z: 15});
```

WKB round-trips all dimensions:

```javascript
const wkbWriter = wasmts.io.WKBWriter.create0();
const wkbReader = wasmts.io.WKBReader.create0();

const bytes = wkbWriter.write(p3d);           // Uint8Array
const back = wkbReader.read(bytes);
console.log(back.getCoordinates()[0].z);      // 15
```

## Loading

WASM initialization is asynchronous, so wait for the `wasmts` global before calling into it.

### Browser

Load the loader with a `<script>` tag (not `import()`) so it can resolve the `.wasm` file next to it, then poll for readiness:

```html
<script src="wasmts.js"></script>
<script>
  (function whenReady() {
    if (window.wasmts && wasmts.geom) start();
    else setTimeout(whenReady, 50);
  })();

  function start() {
    const wkt = wasmts.io.WKTReader.create0();
    const p = wkt.read('POINT (5 10)');
    console.log('Buffer area:', p.buffer(5).getArea().toFixed(2));
  }
</script>
```

Keep `wasmts.js` and `wasmts.js.wasm` in the same directory. Works in any modern browser with WebAssembly support.

### Node

Node's `fetch` does not resolve the `.wasm` by file path on its own, so the loader needs the binary supplied to it. See [test/test-node.mjs](test/test-node.mjs) for a complete, working bootstrap: it reads `dist/wasmts.js.wasm`, hands the bytes to the loader through a small `fetch` shim, waits for initialization, and then uses the API exactly as above.

### Web Worker

`wasmts` loads in any Web Worker that supports WebAssembly. The library ships no built-in worker pool, so wrap it yourself when you want geometry work off the main thread. Geometry handles cannot cross the worker boundary, so pass WKT or WKB strings in the messages and parse them inside the worker:

```javascript
// worker.js
importScripts('wasmts.js');

self.addEventListener('message', async ({ data }) => {
  const wkt = wasmts.io.WKTReader.create0();
  const writer = wasmts.io.WKTWriter.create0();
  const a = wkt.read(data.a);
  const b = wkt.read(data.b);
  self.postMessage(writer.write(a.union(b)));
});
```

[Comlink](https://github.com/GoogleChromeLabs/comlink) wraps this in a `Proxy` so the calls read as local; pair it with `Uint8Array` WKB buffers and Comlink's `transfer` to avoid copying.

## Building from source

Prerequisites:

- GraalVM with `native-image` and the `svm-wasm` (web-image) tool
- Maven 3.6+
- [Babashka](https://babashka.org/) (for the code generator)
- Node.js (for the test suite)

```bash
npm run build      # mvn package, then copy artifacts into dist/
npm test           # run the Node test suite
```

The build emits:

- `dist/wasmts.js` — the WASM loader
- `dist/wasmts.js.wasm` — the WASM binary
- `dist/wasmts.d.ts` — the TypeScript declarations

The Java bridge, the `.d.ts`, and the test suite are generated from a reflection of the JTS classpath by the `bb gen:*` tasks (`gen:api`, `gen:dts`, `gen:tests`, or `gen:all`), with per-method overrides kept in `manual.edn`. To widen JTS coverage you adjust the registry and the overrides, then regenerate, rather than writing bridge code by hand.

To update the JTS version, change `<jts.version>` in `pom.xml`, regenerate, and rebuild.

## License

This package distributes a WebAssembly binary (`dist/wasmts.js.wasm`) and a JavaScript loader (`dist/wasmts.js`), both produced by GraalVM Native Image. The binary statically embeds code from several projects; [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists all of them.

The binary includes a subset of JTS (Java Topology Suite) 1.20.0: `jts-core` and `jts-io-common`, which provide the geometry model and the WKT, WKB, KML, TWKB, and GeoJSON I/O. JTS is dual-licensed under:

- [Eclipse Public License v2.0](LICENSE_EPLv2.txt)
- [Eclipse Distribution License v1.0](LICENSE_EDLv1.txt)

You may use JTS under either license. See the [JTS project](https://github.com/locationtech/jts) for more information.

GeoJSON parsing pulls in json-simple 1.1.1 (transitive dependency of `jts-io-common`), licensed under the [Apache License 2.0](LICENSE_Apache-2.0.txt).

GraalVM Native Image AOT-compiles GraalVM Community Edition's SubstrateVM and a subset of the OpenJDK class library into both `wasmts.js.wasm` and the `wasmts.js` loader. That runtime is licensed under the [GNU General Public License v2 with the Classpath Exception](LICENSE_GraalVM-CE.txt). The GraalVM SDK and web-image API it links against are licensed under the [Universal Permissive License v1.0](LICENSE_UPL-1.0.txt).

The wrapper code (`API.java` and generated sources) is licensed under EPL-2.0 OR EDL-1.0 to match JTS.

--

The browser demo (GitHub Pages, not part of the npm package) uses [coi-serviceworker](https://github.com/gzuidhof/coi-serviceworker)
for SharedArrayBuffer support on static hosting, which is distributed under the MIT license:

```
MIT License

Copyright (c) 2021 Guido Zuidhof

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
