/*
 * Demo-only worker-router handler: runs a REPL snippet against the worker's
 * wasmts instance and returns serialized output. Lives in the wasmts demo (not
 * in backproj's shipping handler) because arbitrary `new Function` eval is a
 * demo concern. Hosted on its own single-worker clj-native pool (backproj
 * 0.0.5 dropped the extraHandlers hook that once co-hosted it on the tile
 * pool), so init loads wasmts itself from the URL passed in initArgs.
 *
 * Self-contained: no bare imports (loads in a browser module worker, which
 * ignores importmaps). wasmts.js is dynamic-imported by URL passed at init.
 *
 * The boundary: live GraalVM geometry handles cannot cross postMessage, so the
 * snippet runs ENTIRELY worker-side; only console output, a stringified result,
 * and any geometry passed to visualization() (serialized to GeoJSON via the
 * worker's GeoJsonWriter) come back. The page renders the map from the GeoJSON.
 */
let wts = null;

function fmt(x) {
  try {
    if (x === null || x === undefined) return String(x);
    if (typeof x === 'string') return x;
    if (typeof x === 'number' || typeof x === 'boolean') return String(x);
    if (typeof x === 'object') {
      // wasmts geometry proxies stringify usefully via toString (WKT-ish);
      // plain objects/arrays via JSON.
      if (typeof x.getGeometryType === 'function') return String(x);
      return JSON.stringify(x);
    }
    return String(x);
  } catch {
    return String(x);
  }
}

// wasmts surfaces JTS errors as opaque GraalVM proxies whose .message / keys
// are unsafe to touch (the own-keys trap throws). String(e) yields the Java
// class, e.g. "[Java Proxy: java.lang.ClassCastException]"; unwrap it to the
// class name plus a hint, since a bare ClassCastException usually means a
// missing or wrong-typed argument (e.g. calling a static op with no args).
function fmtErr(e) {
  let s;
  try { s = (e && e.message) ? String(e.message) : String(e); } catch { s = 'error'; }
  const m = s.match(/^\[Java Proxy:\s*([\w.$]+)\]$/);
  return m ? m[1] + ' (JTS error — often a missing or wrong-typed argument)' : s;
}

function evalSnippet(code) {
  const logs = [];
  const push = (...a) => logs.push(a.map(fmt).join(' '));
  const cons = { log: push, info: push, warn: push, error: push, debug: push };

  const groups = {};
  let writer = null;
  const visualization = (g) => {
    if (!writer) writer = wts.io.geojson.GeoJsonWriter.create0();
    for (const [label, geoms] of Object.entries(g || {})) {
      const arr = [];
      for (const geom of geoms) {
        try {
          const gj = JSON.parse(writer.write(geom));
          // LinearRing isn't a valid GeoJSON type; render it as a LineString.
          if (gj && gj.type === 'LinearRing') gj.type = 'LineString';
          arr.push(gj);
        } catch { /* skip non-geom */ }
      }
      groups[label] = arr;
    }
    return Promise.resolve();
  };

  const finalize = (result, error) => ({
    logs,
    result: result === undefined ? undefined : fmt(result),
    error: error === undefined ? undefined : error,
    groups: Object.keys(groups).length ? groups : undefined,
  });

  try {
    // Async IIFE wrapper mirrors the page's old main-thread eval: lets the
    // snippet use top-level await (e.g. `await visualization(...)`).
    const fn = new Function(
      'wasmts', 'console', 'visualization',
      `return (async () => {\n${code}\n})();`,
    );
    const out = fn(wts, cons, visualization);
    return Promise.resolve(out)
      .then((result) => finalize(result, undefined))
      .catch((e) => finalize(undefined, fmtErr(e)));
  } catch (e) {
    return finalize(undefined, fmtErr(e));
  }
}

export async function create(initArgs) {
  // Load wasmts from the init URL when this worker does not already carry
  // it. The loader mutates globalThis.wasmts as a side effect after module
  // evaluation, so poll for geom; __filename is how the loader resolves
  // its sibling .wasm in some paths.
  if (!globalThis.wasmts?.geom && initArgs?.wasmtsJsUrl) {
    globalThis.__filename = initArgs.wasmtsJsUrl;
    await import(/* @vite-ignore */ initArgs.wasmtsJsUrl);
  }
  for (let i = 0; i < 200 && !globalThis.wasmts?.geom; i++) {
    await new Promise((r) => setTimeout(r, 50));
  }
  if (!globalThis.wasmts?.geom) {
    throw new Error('wasmts.geom not available after handler init');
  }
  wts = globalThis.wasmts;
  return { evalSnippet };
}

export default create;
