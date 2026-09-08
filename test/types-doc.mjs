// Checks that the TSDoc in types/wasmts.d.ts reaches the TypeScript language
// service, which is what editors query for hover text.
//
// Asking the checker rather than grepping the file: a malformed block comment,
// a stray */, or a comment detached from its declaration all pass a grep.
//
// Run: node test/types-doc.mjs

import ts from 'typescript';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dts = join(root, 'types', 'wasmts.d.ts');

const program = ts.createProgram([dts], {
  noEmit: true,
  target: ts.ScriptTarget.ES2020,
  lib: ['lib.es2020.d.ts', 'lib.dom.d.ts'],
});
const checker = program.getTypeChecker();
const source = program.getSourceFile(dts);
if (!source) throw new Error(`could not load ${dts}`);

/** Documentation the checker reports for a member of an exported interface. */
function interfaceMemberDoc(interfaceName, memberName) {
  let found = null;
  ts.forEachChild(source, (node) => {
    if (ts.isInterfaceDeclaration(node) && node.name.text === interfaceName) {
      for (const m of node.members) {
        if (m.name && ts.isIdentifier(m.name) && m.name.text === memberName) {
          const sym = checker.getSymbolAtLocation(m.name);
          if (sym) found = ts.displayPartsToString(sym.getDocumentationComment(checker));
        }
      }
    }
  });
  return found;
}

// Each emitted TSDoc block, without the hand-written brand stubs. Read from
// the text and not the AST, because getJSDocCommentsAndTags walks up to the
// enclosing nodes and reports a block more than once. The emitter escapes */,
// so the non-greedy match is exact.
const docBlocks = (source.getFullText().match(/\/\*\*[\s\S]*?\*\//g) || []).filter(
  (b) => !/^\/\*\* Brand:/.test(b)
);

/** The markdown in a block, without the comment frame. */
function blockBody(block) {
  return block
    .split('\n')
    .map((line) => line.trim().replace(/^\/\*\*|^\*\/$|^\* ?/, ''))
    .join('\n')
    .trim();
}

const docBodies = docBlocks.map(blockBody);

const withoutFences = (s) => s.replace(/(?:^|\n)```[\s\S]*?\n```/g, '');
const withoutCode = (s) => withoutFences(s).replace(/`[^`]*`/g, '');
const count = (s, re) => (s.match(re) || []).length;

/** Fail with the first offending block, because a count alone cannot be fixed. */
function failBodies(pred, message) {
  const bad = docBodies.filter(pred);
  if (bad.length) {
    throw new Error(`${bad.length} of ${docBodies.length} blocks ${message}, e.g.\n${bad[0].slice(0, 400)}`);
  }
}

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check('Geometry.buffer carries its description', () => {
  const doc = interfaceMemberDoc('Geometry', 'buffer');
  if (!doc) throw new Error('no documentation returned');
  if (!/Computes a buffer area around this geometry/.test(doc)) {
    throw new Error(`unexpected text: ${doc.slice(0, 120)}`);
  }
});

check('Geometry.convexHull keeps its markdown table', () => {
  const doc = interfaceMemberDoc('Geometry', 'convexHull');
  if (!/\| --- \| --- \|/.test(doc)) {
    throw new Error('delimiter row missing, table will not render');
  }
});

check('Geometry.equalsTopo keeps its fenced code block', () => {
  const doc = interfaceMemberDoc('Geometry', 'equalsTopo');
  const fences = (doc.match(/```/g) || []).length;
  if (fences !== 2) throw new Error(`expected one fenced block, saw ${fences} fences`);
});

check('every doc block links to the JTS javadoc', () => {
  if (docBlocks.length === 0) throw new Error('no doc blocks found at all');
  const unlinked = docBlocks.filter((b) => !/@see \[[^\]]+\]\(https:\/\/locationtech\.github\.io\/jts\/javadoc\//.test(b));
  if (unlinked.length) {
    throw new Error(`${unlinked.length} of ${docBlocks.length} blocks lack a JTS link, e.g.\n${unlinked[0].slice(0, 200)}`);
  }
});

// The four checks that follow guard html->markdown against the malformed
// markup in the JTS javadoc, which no release is obliged to keep the same.

check('no doc block leaks raw HTML', () => {
  // Lower-case only: entity decoding runs last, so a decoded `&lt;T&gt;` is
  // correctly `<T>` in the output, and JTS generic parameters are upper-case.
  failBodies((b) => /<\/?[a-z][a-z0-9]*(?:\s[^>]*)?>/.test(b), 'contain raw HTML tags');
});

check('no doc block leaks an HTML entity', () => {
  failBodies((b) => /&(?:#\d+|[a-zA-Z][a-zA-Z0-9]*);/.test(b), 'contain undecoded entities');
});

check('code fences and code spans are balanced', () => {
  // An odd fence count means dedent-prose read prose as a fenced block. An
  // odd backtick count means a span that runs to the end of the hover.
  failBodies((b) => count(b, /```/g) % 2 === 1, 'have an unterminated code fence');
  failBodies((b) => count(withoutFences(b), /`/g) % 2 === 1, 'have an unterminated code span');
});

check('bold markers are balanced', () => {
  // Outside fences and code spans: a DE-9IM pattern like `[FT*******]`
  // would otherwise count as a bold marker.
  failBodies((b) => count(withoutCode(b), /\*\*/g) % 2 === 1, 'have an unterminated bold run');
});

check('nested parameter types use the javadoc spelling in @see anchors', () => {
  // Reflection writes PrecisionModel$Type where the JDK 8 anchor writes
  // PrecisionModel.Type. A `$` silently lands on the top of the class page.
  const bad = docBlocks.filter((b) => /@see \[[^\]]+\]\([^)]*#[^)]*\$/.test(b));
  if (bad.length) {
    throw new Error(`${bad.length} @see anchors contain '$', e.g.\n${bad[0].slice(0, 300)}`);
  }
});

check('an overload does not inherit a sibling overload\'s prose', () => {
  // Only documented declarations reach the index, so the sole candidate for
  // one of two same-arity overloads can describe the other. The @param check
  // below cannot catch this: the positional fallback renames the wrong
  // overload's tags onto these identifiers.
  const src = source.getFullText();
  const sig = src.indexOf('create2(_x: number, _y: number)');
  if (sig < 0) throw new Error('HCoordinate create2 signature not found');
  const block = src.slice(src.lastIndexOf('/**', sig), sig);
  if (/intersection of the lines/.test(block)) {
    throw new Error(`create2 carries the Coordinate overload's description:\n${block}`);
  }
});

check('a nested-type parameter does not cost an entry its docs', () => {
  // While simple-type kept the `$`, this constructor matched no candidate
  // and lost its javadoc with no report.
  const src = source.getFullText();
  const sig = src.indexOf('fromType(modelType:');
  if (sig < 0) throw new Error('PrecisionModel(Type) constructor not found');
  const block = src.slice(src.lastIndexOf('/**', sig), sig);
  if (!/an explicit precision model type/.test(block)) {
    throw new Error(`PrecisionModel(Type) has no javadoc block:\n${block.slice(-300)}`);
  }
  // The anchor has to spell the nested type the way the site does.
  if (!/#PrecisionModel-org\.locationtech\.jts\.geom\.PrecisionModel\.Type-/.test(block)) {
    throw new Error(`PrecisionModel(Type) anchor is not the javadoc spelling:\n${block}`);
  }
});

check('inherited docs are attributed to the declaring class', () => {
  // CoordinateArraySequence.getX inherits its text from the
  // CoordinateSequence interface, and the link must name that class.
  const src = source.getFullText();
  if (!src.includes('CoordinateSequence.html#getX-int-')) {
    throw new Error('inherited doc not linked to its declaring class');
  }
  if (src.includes('CoordinateArraySequence.html#getX-int-')) {
    throw new Error('inherited doc misattributed to the inheriting class');
  }
});

check('overloads get their own doc, not a sibling\'s', () => {
  // createPoint takes either a Coordinate or a CoordinateSequence, and their
  // descriptions differ. The emitted signature takes Coordinate, so the doc
  // and the @see anchor must too.
  const src = source.getFullText();
  const sig = src.indexOf('createPoint(geometryFactory:');
  if (sig < 0) throw new Error('createPoint signature not found');
  const block = src.slice(src.lastIndexOf('/**', sig), sig);
  if (!/Creates a Point using the given Coordinate/.test(block)) {
    throw new Error('description does not match the Coordinate overload');
  }
  if (/createPoint-org\.locationtech\.jts\.geom\.CoordinateSequence-/.test(block)) {
    throw new Error('@see anchor points at the CoordinateSequence overload');
  }
});

check('@param names match the emitted signature', () => {
  // The functional surface prepends a receiver and the OO surface curries it
  // away, so the two number arguments differently. A @param naming an unused
  // identifier shows up as an orphaned tag in the editor.
  const src = source.getFullText();
  const bad = [];
  ts.forEachChild(source, (node) => {
    if (!ts.isInterfaceDeclaration(node)) return;
    for (const m of node.members) {
      if (!ts.isMethodSignature(m) || !m.name || !ts.isIdentifier(m.name)) continue;
      const jsdoc = ts.getJSDocCommentsAndTags(m).filter(ts.isJSDoc);
      const names = new Set(m.parameters.map((p) => p.name.getText(source)));
      for (const d of jsdoc) {
        for (const tag of d.tags || []) {
          if (ts.isJSDocParameterTag(tag)) {
            const n = tag.name.getText(source);
            if (!names.has(n)) bad.push(`${node.name.text}.${m.name.text}: @param ${n}`);
          }
        }
      }
    }
  });
  if (bad.length) {
    throw new Error(`${bad.length} orphaned @param tags, e.g.\n  ${bad.slice(0, 5).join('\n  ')}`);
  }
});

check('the declaration file type-checks clean', () => {
  // Catches two things. An unescaped */ ends its comment and leaves the rest
  // of the text as syntax. And a ts-type mapping with no oo-interfaces entry
  // references a name the file never declares (TS2304).
  const errors = ts
    .getPreEmitDiagnostics(program)
    .filter((d) => d.file === source && d.category === ts.DiagnosticCategory.Error);
  if (errors.length) {
    const { line } = source.getLineAndCharacterOfPosition(errors[0].start);
    const msg = ts.flattenDiagnosticMessageText(errors[0].messageText, ' ');
    throw new Error(`${errors.length} errors, first at line ${line + 1}: TS${errors[0].code} ${msg}`);
  }
});

let failed = 0;
for (const [name, fn] of checks) {
  try {
    fn();
    console.log(`  ok    ${name}`);
  } catch (e) {
    failed++;
    console.log(`  FAIL  ${name}\n        ${e.message}`);
  }
}
console.log(`\n${checks.length - failed}/${checks.length} passed`);
process.exit(failed ? 1 : 0);
