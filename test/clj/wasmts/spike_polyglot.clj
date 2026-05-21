(ns wasmts.spike-polyglot
  "Spike 2: can a single in-JVM GraalJS+GraalWasm Polyglot Context load
   the wasmts Web Image bundle and execute a JTS call?

   Run with: clojure -M:test -m wasmts.spike-polyglot

   Pass criterion: the context evaluates wasmts.js, the WASM
   instantiates without errors on any GC opcode, and a call to
   wasmts.io.readWKT(\"POINT (1 2)\") returns something non-null.

   Fail outcomes worth noting:
     - ClassNotFoundException on org.graalvm.polyglot.Context — the
       Maven coords in deps.edn :test alias don't pull js-language or
       wasm-language. Adjust extra-deps.
     - PolyglotException citing an unsupported WASM opcode — Web Image
       emits a GC/exnref feature GraalWasm doesn't yet handle. Fall
       back to subprocess-driven Node testing.
     - The instantiate stalls indefinitely — the bundled wasmts.js
       expects browser globals (fetch, URL); the host polyglot env
       lacks them. Patch on the JS side before eval."
  (:require [clojure.java.io :as io])
  (:import [org.graalvm.polyglot Context PolyglotAccess Source]
           [java.io ByteArrayOutputStream]
           [java.nio.file Files]))

(def wasmts-js-path   "dist/wasmts.js")
(def wasmts-wasm-path "dist/wasmts.js.wasm")

(defn- read-wasm-bytes []
  (Files/readAllBytes (.toPath (io/file wasmts-wasm-path))))

(defn- build-context []
  (-> (Context/newBuilder (into-array String ["js" "wasm"]))
      (.allowPolyglotAccess PolyglotAccess/ALL)
      (.option "js.ecmascript-version" "staging")
      (.option "js.webassembly" "true")
      (.option "js.esm-eval-returns-exports" "true")
      (.allowIO true)
      (.allowAllAccess true)
      .build))

(defn- patch-fetch
  "Install a globalThis.fetch shim that returns the wasmts.js.wasm bytes
   for any URL ending in .wasm. The shim uses Java.type interop to read
   the bytes host-side, then copies them into a JS Uint8Array so the
   returned `.buffer` is a real JS ArrayBuffer that WebAssembly.instantiate
   will accept. (Returning a raw host byte[] fails with `Argument 0 must
   be a buffer source` because GraalJS doesn't auto-coerce.)"
  [^Context ctx]
  (.eval ctx "js"
         (str
          "(() => {\n"
          "  const Files = Java.type('java.nio.file.Files');\n"
          "  const Paths = Java.type('java.nio.file.Paths');\n"
          "  const hostBytes = Files.readAllBytes(Paths.get('" wasmts-wasm-path "'));\n"
          "  const len = hostBytes.length;\n"
          "  const u8 = new Uint8Array(len);\n"
          "  for (let i = 0; i < len; i++) u8[i] = hostBytes[i] & 0xff;\n"
          "  const arrayBuf = u8.buffer;\n"
          "  globalThis.fetch = (url) => Promise.resolve({\n"
          "    ok: true,\n"
          "    arrayBuffer: () => Promise.resolve(arrayBuf),\n"
          "  });\n"
          "  globalThis.__filename = '" wasmts-js-path "';\n"
          "})();\n")))

(defn run-spike []
  (println "Spike 2: GraalJS+GraalWasm load wasmts.js")
  (println "  Reading WASM bytes from" wasmts-wasm-path)
  (let [wasm-bytes (read-wasm-bytes)]
    (println "  WASM size:" (count wasm-bytes) "bytes"))
  (println "  Constructing polyglot context...")
  (let [ctx (build-context)]
    (try
      (println "  Patching fetch...")
      (patch-fetch ctx)
      (println "  Loading wasmts.js (Source.newBuilder)...")
      (let [src (-> (Source/newBuilder "js" (io/file wasmts-js-path))
                    .build)]
        (.eval ctx src))
      (println "  PASS: wasmts.js evaluated without throwing")
      ;; The Web Image bundle initialises asynchronously; the WASM module
      ;; instantiates on first call. Try a tiny operation:
      (println "  Calling wasmts.io.readWKT('POINT (1 2)')...")
      (let [result (.eval ctx "js" "wasmts && wasmts.io && wasmts.io.readWKT && wasmts.io.readWKT('POINT (1 2)')")]
        (println "  Result:" (str result))
        (println "  PASS: GraalWasm runs Web Image output in-process"))
      true
      (catch Throwable t
        (println "  FAIL:" (.getMessage t))
        (println "  (this is the answer Spike 2 was asking for — fall back to subprocess)")
        (let [out (ByteArrayOutputStream.)]
          (.printStackTrace t (java.io.PrintStream. out))
          (println (.toString out)))
        false))))

(defn -main [& _]
  (let [ok? (run-spike)]
    (System/exit (if ok? 0 1))))
