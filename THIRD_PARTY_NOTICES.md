# Third-Party Notices

The published npm package `@wcohen/wasmts` distributes a WebAssembly binary
(`dist/wasmts.js.wasm`), a JavaScript loader (`dist/wasmts.js`), and a
TypeScript declaration file (`dist/wasmts.d.ts`). GraalVM Native Image produces
the binary and the loader. The binary statically embeds code from the projects
listed below. The declaration file embeds JTS documentation text. Each license
is reproduced in the file named under "License text".

## JTS Topology Suite

- Components: `jts-core` 1.20.0, `jts-io-common` 1.20.0
- Copyright: Eclipse Foundation and the LocationTech JTS contributors
- License: Eclipse Public License 2.0 OR Eclipse Distribution License 1.0 (dual)
- License text: `LICENSE_EPLv2.txt`, `LICENSE_EDLv1.txt`
- Project: https://github.com/locationtech/jts

JTS provides the geometry model, the spatial operations, and the WKT, WKB, KML,
TWKB, and GeoJSON readers and writers exposed by this package.

`dist/wasmts.d.ts` also contains JTS documentation text. The generator
(`script/javadoc_index.clj`) reads the javadoc comments from the JTS sources jar
(`jts-core-1.20.0-sources.jar`) and writes them into the declaration file as
TSDoc comments. The text keeps its original meaning. Only its markup changes,
from HTML to markdown. The declaration file is therefore a derivative work of
JTS and is licensed under EPL-2.0 OR EDL-1.0, the same as JTS.

## json-simple

- Component: `json-simple` 1.1.1 (`com.googlecode.json-simple`)
- License: Apache License 2.0
- License text: `LICENSE_Apache-2.0.txt`
- Project: https://code.google.com/archive/p/json-simple/

Transitive dependency of `jts-io-common`, used by the GeoJSON reader and writer.

## GraalVM Community Edition runtime

- Components: SubstrateVM and a subset of the OpenJDK class library (`java.base`
  and related modules), AOT-compiled into the WebAssembly binary and the
  `wasmts.js` loader.
- Version: GraalVM Community Edition 25.1.0-dev, JDK 25.0.1
- Copyright: Oracle and/or its affiliates; Free Software Foundation, Inc.
- License: GNU General Public License version 2 with the Classpath Exception
- License text: `LICENSE_GraalVM-CE.txt`
- Project: https://github.com/oracle/graal

The Classpath Exception permits distributing the linked binary without
extending the GPL to code that merely uses it.

## GraalVM SDK / web-image API

- Component: `org.graalvm.webimage.api`
- Copyright: Oracle and/or its affiliates
- License: Universal Permissive License 1.0
- License text: `LICENSE_UPL-1.0.txt`
- Project: https://github.com/oracle/graal/tree/master/web-image

## Wrapper code

The bridge code in this repository (`java/src/main/java/net/willcohen/wasmts/`,
including generated sources) is licensed under EPL-2.0 OR EDL-1.0 to match JTS.
The generated TypeScript declarations (`types/wasmts.d.ts`) carry the same
license, because they contain JTS documentation text.

## Build-time only

`com.github.javaparser/javaparser-core` (Apache License 2.0 OR LGPL-3.0) parses
the JTS sources jar during code generation. It is a build dependency of this
repository. The npm package does not contain it, and the WebAssembly binary does
not embed it.

## Not included in the npm package

The browser demo on GitHub Pages loads
[coi-serviceworker](https://github.com/gzuidhof/coi-serviceworker) (MIT,
Copyright (c) 2021 Guido Zuidhof) for SharedArrayBuffer support on static
hosting. It is part of the demo site only; it is not bundled in the npm package
or the WebAssembly binary.
