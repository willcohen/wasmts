#!/usr/bin/env node
// Serve docs/ for the browser demo, two modes:
//
//   bb demo       (node scripts/demo-server.mjs [port])
//     LOCAL: serve the in-tree wasmts build under /vendor/wasmts. Every
//     other package keeps the published pin from index.html's importmap;
//     only the @wcohen/wasmts entry is rewritten, in memory (no file
//     mutation).
//
//   bb demo-cdn   (node scripts/demo-server.mjs --cdn [port])
//     CDN: serve docs/ verbatim -- exactly what GitHub Pages serves,
//     published pins and all. No rewrite, no /vendor, no local dist
//     required.
//
// Comlink resolution in the worker realm is handled by worker-router
// itself (it passes import.meta.resolve("comlink") as comlinkUrl on the
// bootstrap message), so no bootstrap rewrite is needed. No isolation
// headers either: the alpha9 stack is single-threaded and the page
// carries no COI service worker.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, extname, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DOCS = join(ROOT, 'docs');
const DIST = join(ROOT, 'dist');
const args = process.argv.slice(2);
const CDN = args.includes('--cdn');
const PORT = Number(args.find((a) => /^\d+$/.test(a)) || process.env.PORT || 8000);

// Top-level /vendor/<seg> -> local directory (served co-located, so the build's
// relative new URL('./wasmts.js.wasm', import.meta.url) asset probe resolves).
const VENDOR_DIRS = {
  wasmts: DIST,
};

// Importmap entries LOCAL mode overrides. Everything else comes from the
// published pins in index.html.
const LOCAL_OVERRIDES = {
  '@wcohen/wasmts': '/vendor/wasmts/wasmts.js',
};

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.wasm': 'application/wasm', '.css': 'text/css', '.json': 'application/json',
  '.map': 'application/json', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.png': 'image/png', '.wat': 'text/plain',
};

if (!CDN && !existsSync(join(DIST, 'wasmts.js'))) {
  console.error('No dist/wasmts.js -- run `npm run build` (or build:wasm + build:js) first.');
  process.exit(1);
}

function mimeFor(p, file) {
  if (p.endsWith('.d.ts')) return 'text/plain';
  return MIME[extname(file)] || 'application/octet-stream';
}

// Rewrite index.html: keep its importmap and apply LOCAL_OVERRIDES on top.
function rewriteIndex(html) {
  return html.replace(
    /<script type="importmap">([\s\S]*?)<\/script>/,
    (_, json) => {
      const map = JSON.parse(json);
      map.imports = { ...map.imports, ...LOCAL_OVERRIDES };
      return '<script type="importmap">\n' + JSON.stringify(map, null, 2) + '\n    </script>';
    },
  );
}

async function serveVendor(p, res) {
  const m = p.match(/^\/vendor\/([^/]+)\/(.+)$/);
  if (!m) return false;
  const dir = VENDOR_DIRS[m[1]];
  if (!dir) { res.statusCode = 404; res.end('no vendor: ' + m[1]); return true; }
  const file = normalize(join(dir, m[2]));
  if (!file.startsWith(dir)) { res.statusCode = 403; res.end('forbidden'); return true; }
  if (!existsSync(file)) { res.statusCode = 404; res.end('not found: ' + p); return true; }
  const data = await readFile(file);
  res.setHeader('Content-Type', mimeFor(p, file));
  res.end(data);
  return true;
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  try {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';

    if (p === '/index.html' && !CDN) {
      res.setHeader('Content-Type', 'text/html');
      res.end(rewriteIndex(await readFile(join(DOCS, 'index.html'), 'utf8')));
      return;
    }

    if (p.startsWith('/vendor/') && !CDN) {
      if (await serveVendor(p, res)) return;
    }

    const base = p.startsWith('/dist/') ? DIST : DOCS;
    const rel = p.startsWith('/dist/') ? p.slice('/dist/'.length) : p.slice(1);
    const file = normalize(join(base, rel));
    if (!file.startsWith(DOCS) && !file.startsWith(DIST)) { res.statusCode = 403; res.end('forbidden'); return; }
    if (!existsSync(file)) { res.statusCode = 404; res.end('not found: ' + p); return; }
    res.setHeader('Content-Type', mimeFor(p, file));
    res.end(await readFile(file));
  } catch (e) {
    res.statusCode = 500;
    res.end('server error: ' + e.message);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  if (CDN) {
    console.log('wasmts demo (CDN pins, docs/ verbatim) -> http://localhost:' + PORT + '/');
  } else {
    console.log('wasmts demo (local dist + vendored siblings) -> http://localhost:' + PORT + '/');
    console.log('serving docs/ with /vendor/* aliased to in-tree sibling builds; Ctrl+C to stop.');
  }
});
