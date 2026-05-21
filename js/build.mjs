#!/usr/bin/env node

import { copyFileSync, mkdirSync, existsSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { spawnSync } from 'child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const srcDir = join(__dirname, 'src');
const distDir = join(__dirname, '..', 'dist');
const typesDir = join(__dirname, '..', 'types');
const repoRoot = join(__dirname, '..');

mkdirSync(distDir, { recursive: true });

console.log('Building JavaScript bundle...\n');

// types/wasmts.d.ts is gitignored — regenerate before copying into dist/.
console.log('🧬 Running `bb gen:dts`...');
const gen = spawnSync('bb', ['gen:dts'], { cwd: repoRoot, stdio: 'inherit' });
if (gen.status !== 0) {
  console.error(`bb gen:dts exited ${gen.status}`);
  process.exit(gen.status ?? 1);
}

// Copy WASM file (binary, can't be bundled)
console.log('📦 Copying WASM binary...');
copyFileSync(
  join(srcDir, 'wasmts.js.wasm'),
  join(distDir, 'wasmts.js.wasm')
);
console.log('   ✓ wasmts.js.wasm -> dist/wasmts.js.wasm');

// Copy JS loader (minification breaks GraalVM's meta-property usage)
console.log('\n📄 Copying JavaScript loader...');
copyFileSync(
  join(srcDir, 'wasmts.js'),
  join(distDir, 'wasmts.js')
);
console.log('   ✓ wasmts.js -> dist/wasmts.js');

const typesFile = join(typesDir, 'wasmts.d.ts');
if (existsSync(typesFile)) {
  console.log('\n📑 Copying TypeScript declarations...');
  copyFileSync(typesFile, join(distDir, 'wasmts.d.ts'));
  console.log('   ✓ types/wasmts.d.ts -> dist/wasmts.d.ts');
} else {
  console.warn('\n⚠️  types/wasmts.d.ts missing — `bb gen:dts` should have produced it.');
}

// Note: WAT file (wasmts.js.wat) is ~127MB and only useful for debugging
// It's kept in js/src/ but not copied to dist/ for distribution

console.log('\n✅ Build complete!\n');
