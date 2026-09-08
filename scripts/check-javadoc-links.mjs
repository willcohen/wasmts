// Makes sure that each @see anchor in types/wasmts.d.ts resolves on the
// published JTS javadoc.
//
// The build derives anchors from the registry without reading the site, so
// nothing in the build can show they are still correct. JDK 8 javadoc
// generated the site and writes `buffer-double-`; a newer one writes
// `buffer(double)`, which would send every deep link to the top of its page.
//
// Needs network access, so it is not part of `bb gen:all` or CI. Run it after
// a JTS upgrade or a change to `javadoc-anchor` in script/codegen_common.clj:
//
//   bb check:javadoc-links          (or: node scripts/check-javadoc-links.mjs)

import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dts = join(root, 'types', 'wasmts.d.ts');

const CONCURRENCY = 8;

/**
 * Each different javadoc URL that the declaration file links to. The URL may
 * hold one balanced pair of parentheses, as a `name(Type,Type)` anchor does.
 * CommonMark accepts that in a link destination without escapes.
 */
function linkedUrls() {
  const text = readFileSync(dts, 'utf8');
  const re =
    /@see \[[^\]]*\]\((https:\/\/locationtech\.github\.io\/jts\/javadoc\/(?:[^()\s]|\([^()\s]*\))+)\)/g;
  return [...new Set([...text.matchAll(re)].map((m) => m[1]))];
}

/**
 * The anchor names that a javadoc page defines, from both `name=` and `id=`
 * attributes, so the check does not depend on which one the page uses.
 */
function anchorsIn(html) {
  const found = new Set();
  for (const m of html.matchAll(/\b(?:name|id)="([^"]+)"/g)) found.add(m[1]);
  return found;
}

/**
 * Get a page, retrying on failure. GitHub Pages returns an occasional 503
 * under concurrency, which must not report as a dead link.
 */
async function fetchPage(url, attempts = 3) {
  let last;
  for (let i = 0; i < attempts; i++) {
    if (i) await new Promise((r) => setTimeout(r, 500 * 2 ** i));
    try {
      const res = await fetch(url);
      if (res.ok) return await res.text();
      last = `HTTP ${res.status}`;
    } catch (e) {
      last = e.message;
    }
  }
  throw new Error(last);
}

async function main() {
  const urls = linkedUrls();
  const byPage = new Map();
  for (const url of urls) {
    const [page, fragment] = url.split('#');
    if (!byPage.has(page)) byPage.set(page, new Set());
    byPage.get(page).add(fragment);
  }

  const pages = [...byPage.keys()].sort();
  console.log(`${urls.length} links across ${pages.length} class pages`);

  const missing = [];
  const unreachable = [];
  let checked = 0;
  let queue = 0;

  async function worker() {
    while (queue < pages.length) {
      const page = pages[queue++];
      let html;
      try {
        html = await fetchPage(page);
      } catch (e) {
        unreachable.push(`${page} -> ${e.message}`);
        continue;
      }
      const anchors = anchorsIn(html);
      for (const fragment of byPage.get(page)) {
        checked++;
        if (!anchors.has(fragment)) missing.push(`${page}#${fragment}`);
      }
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  console.log(`checked ${checked} anchors`);
  if (unreachable.length) {
    console.log(`\n${unreachable.length} pages did not load:`);
    for (const u of unreachable.slice(0, 20)) console.log(`  ${u}`);
  }
  if (missing.length) {
    console.log(`\n${missing.length} anchors did not resolve:`);
    for (const m of missing.slice(0, 40)) console.log(`  ${m}`);
    // A few missing anchors are wrong links. Most anchors missing means the
    // site uses a different anchor format. Field anchors are bare names in the
    // current format, so they can still resolve after such a change, and the
    // test cannot require every anchor to be missing.
    if (missing.length > checked / 2) {
      console.log(
        '\nMost anchors do not resolve, so the site probably uses a different anchor\n' +
          'format, such as the one a newer javadoc writes. Compare an anchor on one\n' +
          'of the pages above, then change javadoc-anchor in\n' +
          'script/codegen_common.clj to match, for example the name(Type,Type) form.'
      );
    }
  }
  if (!missing.length && !unreachable.length) console.log('\nall anchors resolve');
  process.exit(missing.length || unreachable.length ? 1 : 0);
}

main();
