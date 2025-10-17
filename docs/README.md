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

This site is configured to load from `../dist/` for local development.

When deployed to GitHub Pages and the package is published to npm, update `index.html` to load from CDN:

```html
<!-- Replace this: -->
<script src="../dist/wasmts.js"></script>

<!-- With this: -->
<script src="https://cdn.jsdelivr.net/npm/wasmts@latest/wasmts.js"></script>
```

## Deployment

GitHub Pages will automatically deploy from the `docs/` folder when:
1. Repository settings > Pages > Source is set to "Deploy from branch"
2. Branch is set to `main` and folder is `/docs`

The site will be available at: `https://<username>.github.io/<repo-name>/`
