#!/usr/bin/env node
// Smoke-check the demo examples: load the demo page in headless Chromium,
// wait for the worker pool, then run every docs/examples.js entry through
// the page's eval-handler plumbing (window._workerCall). An example fails
// if the handler reports an error, if it times out, or if it calls
// visualization() and no geometry group comes back. Monaco and the map
// stay out of the loop; this checks the examples, not the UI.
//
//   bb demo-check              CDN mode: docs/ verbatim, published pins
//   bb demo-check --local      local wasmts dist, published pins for the rest (bb demo mode)
//   bb demo-check --url URL    check a server that already runs (no spawn)
import { spawn } from 'node:child_process';
import net from 'node:net';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);
const LOCAL = args.includes('--local');
const URL_ARG = args.includes('--url') ? args[args.indexOf('--url') + 1] : null;

const BOOT_TIMEOUT_MS = 240_000; // pool spawn + proj.db + 14MB wasmts per worker
const EXAMPLE_TIMEOUT_MS = 30_000;

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    throw new Error(
      'playwright not found; run `npm install`, then `npx playwright install chromium` if no browser is cached');
  }
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

async function startServer() {
  const port = await freePort();
  const serverArgs = [join(ROOT, 'scripts/demo-server.mjs')];
  if (!LOCAL) serverArgs.push('--cdn');
  serverArgs.push(String(port));
  const child = spawn('node', serverArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', (d) => { output += d; });
  child.stderr.on('data', (d) => { output += d; });
  const url = `http://127.0.0.1:${port}/`;
  for (let i = 0; i < 50; i++) {
    if (child.exitCode !== null) {
      throw new Error(`demo-server exited (${child.exitCode}):\n${output}`);
    }
    try {
      const res = await fetch(url, { method: 'HEAD' });
      if (res.ok) return { child, url };
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  child.kill();
  throw new Error(`demo-server did not become ready:\n${output}`);
}

async function main() {
  const { chromium } = await loadPlaywright();

  let server = null;
  let url = URL_ARG;
  if (!url) {
    server = await startServer();
    url = server.url;
  }
  console.log(`checking examples against ${url} (${URL_ARG ? 'external' : LOCAL ? 'local dist' : 'CDN pins'})`);

  const browser = await chromium.launch();
  const diagnostics = [];
  const failures = [];
  try {
    const page = await browser.newPage();
    page.on('console', (msg) => {
      if (msg.type() === 'error') diagnostics.push(`console.error: ${msg.text()}`);
    });
    page.on('pageerror', (err) => diagnostics.push(`pageerror: ${err.message}`));
    page.on('requestfailed', (req) =>
      diagnostics.push(`requestfailed: ${req.url()} (${req.failure()?.errorText})`));

    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 120_000 });
    await page.waitForFunction(
      () => window._replPool || window._visualizationInitDone,
      { timeout: BOOT_TIMEOUT_MS },
    );
    const booted = await page.evaluate(() => Boolean(window._replPool));
    if (!booted) {
      throw new Error('worker pool did not boot (init finished without _replPool)');
    }
    console.log('worker pool ready');

    const keys = await page.evaluate(() => Object.keys(window.examples));
    for (const key of keys) {
      const started = Date.now();
      // In-page race: a hung snippet resolves to an error object, so the
      // node side never waits on a dead evaluate. The node-side race is a
      // backstop for a wedged page; the pool has one worker, so a hang
      // would block every later example -- abort the run instead.
      const evalPromise = page.evaluate(async ({ key, timeoutMs }) => {
        const code = window.examples[key];
        const run = window._workerCall(
          window._replPool, window._replEvalKey, 'evalSnippet', [code]);
        const timeout = new Promise((resolve) =>
          setTimeout(() => resolve({ error: `timeout after ${timeoutMs}ms` }), timeoutMs));
        return Promise.race([run, timeout]);
      }, { key, timeoutMs: EXAMPLE_TIMEOUT_MS });
      const backstop = new Promise((resolve) =>
        setTimeout(() => resolve({ error: 'page hung (node-side backstop)', hung: true }),
          EXAMPLE_TIMEOUT_MS + 15_000));
      const out = await Promise.race([evalPromise, backstop]);

      const ms = Date.now() - started;
      const wantsViz = await page.evaluate(
        (k) => /\bvisualization\(/.test(window.examples[k]), key);
      const groupCount = out.groups ? Object.keys(out.groups).length : 0;
      const hasGeom = out.groups
        && Object.values(out.groups).some((arr) => Array.isArray(arr) && arr.length > 0);

      let problem = null;
      const junk = (out.logs || []).find((l) => l.includes('[Java Proxy'));
      if (out.error) problem = out.error;
      else if (wantsViz && !hasGeom) problem = 'visualization() returned no geometry';
      else if (junk) problem = 'log leaks an unconverted Java proxy: ' + junk;

      if (problem) {
        failures.push({ key, problem, logs: out.logs || [] });
        console.log(`FAIL ${key.padEnd(16)} ${String(ms).padStart(5)}ms  ${problem}`);
      } else {
        console.log(`ok   ${key.padEnd(16)} ${String(ms).padStart(5)}ms  logs=${(out.logs || []).length} groups=${groupCount}`);
      }
      if (out.hung) {
        console.error('aborting: the single pool worker is wedged; later examples cannot run');
        process.exitCode = 1;
        return;
      }
    }

    console.log(`\n${keys.length} examples, ${failures.length} failed`);
    for (const f of failures) {
      console.log(`\n--- ${f.key}: ${f.problem}`);
      for (const line of f.logs) console.log(`    ${line}`);
    }
    if (diagnostics.length) {
      console.log('\nbrowser diagnostics (informational):');
      for (const d of diagnostics) console.log(`  ${d}`);
    }
    if (failures.length) process.exitCode = 1;
  } finally {
    await browser.close();
    if (server) server.child.kill();
  }
}

main().catch((e) => {
  console.error(e.message || e);
  process.exit(1);
});
