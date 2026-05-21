#!/usr/bin/env node
/*
 * Differential test runner. Long-running Node process driven over
 * stdin/stdout via line-delimited JSON requests. The JVM-side harness
 * spawns this once per test session and dispatches every wasmts call
 * through it; test.check shrinking re-drives cases through the same
 * process.
 *
 * Request format:
 *   {"id": <int>, "op": "<dotted.path | _builtin>", "args": [...]}
 *
 * Response format (success):
 *   {"id": <int>, "ok": <jsonable-result>}
 *
 * Response format (error):
 *   {"id": <int>, "err": "<message>"}
 *
 * Geometry references cross the boundary as opaque handles:
 *   {"__handle": "h0"}
 *
 * Built-in ops the runner understands directly:
 *   _readWKT     args: [wkt-string]            -> {__handle}
 *   _writeWKT    args: [{__handle}]            -> wkt-string
 *   _writeWKB    args: [{__handle}]            -> {__b64: base64-bytes}
 *   _readWKB     args: [{__b64: base64-bytes}] -> {__handle}
 *   _release     args: [{__handle}]            -> true
 *   _ping        args: []                      -> "pong"
 *
 * Everything else routes to a dotted path under globalThis.wasmts.
 * Geometry-shaped args are dereffed before the call; geometry results
 * are wrapped in a fresh handle.
 *
 * Run: node --experimental-wasm-exnref test/node_runner.mjs
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import * as readline from 'node:readline';

const __filename = fileURLToPath(import.meta.url);
const __dirname  = dirname(__filename);

// ----- WASM module load -----------------------------------------------------

const wasmJsFile = join(__dirname, '..', 'dist', 'wasmts.js');
const wasmPath   = join(__dirname, '..', 'dist', 'wasmts.js.wasm');
const wasmBinary = readFileSync(wasmPath);

globalThis.__filename = wasmJsFile;
const originalFetch = globalThis.fetch;
globalThis.fetch = function (url, ...rest) {
  if (url && (String(url).endsWith('.wasm') || String(url).includes('wasmts.js.wasm'))) {
    return Promise.resolve({
      ok: true,
      arrayBuffer: () => Promise.resolve(
        wasmBinary.buffer.slice(
          wasmBinary.byteOffset,
          wasmBinary.byteOffset + wasmBinary.byteLength,
        ),
      ),
    });
  }
  return originalFetch(url, ...rest);
};

await import(wasmJsFile);

// Web Image emits wasmts.* asynchronously after the module instantiates.
// Wait for the namespace to populate before announcing readiness.
async function awaitWasmts(timeoutMs = 30_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (globalThis.wasmts?.io?.WKTReader?.read) {
      return;
    }
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error('wasmts.io.WKTReader.read did not appear within ' + timeoutMs + 'ms');
}

await awaitWasmts();

// ----- shared reader/writer instances --------------------------------------
//
// Bundle I migrated WKTReader / WKBReader / WKTWriter from single-arg statics
// to instance methods. Bundle X migrated WKBWriter the same way (the dim-free
// JS contract retired; callers now choose dim via create0() / create1(dim)).
// Cache one instance of each at startup so the builtins can dispatch via the
// 2-arg `(receiver, payload)` shape without per-call construction.

const wkbReader = globalThis.wasmts.io.WKBReader.create0();
const wktReader = globalThis.wasmts.io.WKTReader.create0();
const wktWriter = globalThis.wasmts.io.WKTWriter.create0();
const wkbWriter = globalThis.wasmts.io.WKBWriter.create0();

// ----- handle table ---------------------------------------------------------

const handles = new Map();
let nextHandleId = 0;

function mkHandle(geom) {
  const id = 'h' + (nextHandleId++);
  handles.set(id, geom);
  return { __handle: id };
}

function deref(value) {
  if (Array.isArray(value)) {
    return value.map(deref);
  }
  if (value && typeof value === 'object' && typeof value.__handle === 'string') {
    const g = handles.get(value.__handle);
    if (!g) throw new Error('Unknown handle: ' + value.__handle);
    return g;
  }
  return value;
}

function wrapResult(value) {
  // Anything that looks like a wasmts geometry (carries _jtsGeom or
  // exposes wasmts-style methods) gets wrapped as a handle. Primitives
  // pass through. NaN / +-Infinity ship as tagged objects because
  // JSON.stringify lossy-coerces them to null.
  if (value == null) return null;
  if (typeof value === 'number') {
    if (Number.isNaN(value))       return { __num: 'NaN' };
    if (value === Infinity)        return { __num: 'Infinity' };
    if (value === -Infinity)       return { __num: '-Infinity' };
    return value;
  }
  if (typeof value === 'boolean' || typeof value === 'string') {
    return value;
  }
  return mkHandle(value);
}

// ----- op dispatch ----------------------------------------------------------

function resolveDottedPath(path) {
  return path.split('.').reduce((acc, k) => (acc == null ? acc : acc[k]), globalThis);
}

const builtins = {
  _ping:    () => 'pong',
  _release: ({ __handle }) => { handles.delete(__handle); return true; },
  // Bundle HH: Coordinate[]-returning methods now produce a real JS array
  // of plain {x, y, z?, m?} objects (with a non-enumerable _jtsCoordArray
  // stash for round-trip back into JTS). This builtin derefs the handle
  // and returns the xys pairs as a plain JSON array — used by the
  // differential test fixture's wasmts-coord-array-xys helper.
  _coordArrayXys: ({ __handle }) => {
    const arr = handles.get(__handle);
    if (!Array.isArray(arr)) throw new Error('Expected coord array handle, got: ' + typeof arr);
    return arr.map(c => [c.x, c.y]);
  },
  _readWKT: (wkt) => mkHandle(globalThis.wasmts.io.WKTReader.read(wktReader, wkt)),
  _writeWKT: (h) => globalThis.wasmts.io.WKTWriter.write(wktWriter, deref(h)),
  _writeWKB: (h) => {
    const bytes = globalThis.wasmts.io.WKBWriter.write(wkbWriter, deref(h));
    // bytes is a JS Uint8Array
    return { __b64: Buffer.from(bytes).toString('base64') };
  },
  _readWKB: ({ __b64 }) => {
    const bytes = Uint8Array.from(Buffer.from(__b64, 'base64'));
    return mkHandle(globalThis.wasmts.io.WKBReader.read(wkbReader, bytes));
  },
};

function dispatch(op, args) {
  if (Object.prototype.hasOwnProperty.call(builtins, op)) {
    return builtins[op](...args);
  }
  const fn = resolveDottedPath(op);
  if (typeof fn !== 'function') {
    throw new Error('Unknown op: ' + op);
  }
  const derefed = args.map(deref);
  return wrapResult(fn(...derefed));
}

// ----- line loop ------------------------------------------------------------

const rl = readline.createInterface({ input: process.stdin, terminal: false });

function send(payload) {
  process.stdout.write(JSON.stringify(payload) + '\n');
}

send({ ready: true, handles: 0 });

rl.on('line', (line) => {
  const text = line.trim();
  if (!text) return;
  let req;
  try {
    req = JSON.parse(text);
  } catch (e) {
    send({ err: 'parse: ' + e.message });
    return;
  }
  const { id, op, args = [] } = req;
  try {
    const ok = dispatch(op, args);
    send({ id, ok });
  } catch (e) {
    send({ id, err: (e && e.stack) || String(e) });
  }
});

rl.on('close', () => process.exit(0));
