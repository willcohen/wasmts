# WasmTS Documentation and Demo

This directory contains the GitHub Pages site for WasmTS.

## Files

- `index.html` - Interactive browser demo with Monaco Editor REPL
- `.nojekyll` - Tells GitHub Pages not to process with Jekyll

## Local Development

To test the demo locally:

```bash
# From the project root, serve the files
python3 -m http.server 8000

# Or with Node.js
npx http-server -p 8000

# Then open: http://localhost:8000/docs/
```

## GitHub Pages Setup

The demo loads WASM files from CDN. Update `index.html` to use the published version:

```html
<script src="https://cdn.jsdelivr.net/npm/@wcohen/wasmts@0.1.0-alpha1/dist/wasmts.js"></script>
```

For local development, the demo can load from `../dist/` instead.

## Deployment

GitHub Pages automatically deploys from the `docs/` folder.

The site is available at: `https://willcohen.github.io/wasmts/`
