#!/usr/bin/env node

import { copyFileSync, mkdirSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const srcDir = join(__dirname, 'src');
const distDir = join(__dirname, '..', 'dist');

// Ensure dist directory exists
mkdirSync(distDir, { recursive: true });

console.log('Building JavaScript bundle...\n');

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

// Note: WAT file (wasmts.js.wat) is ~127MB and only useful for debugging
// It's kept in js/src/ but not copied to dist/ for distribution

console.log('\n✅ Build complete!\n');
