(ns wasmts.differential.smoke-test
  "End-to-end smoke test: validates the JVM↔Node RPC plumbing by running
   four concrete cases that have known answers from JTS itself.

   Skips at test-discovery time if `dist/wasmts.js.wasm` is missing, so
   developers without a built WASM can still run the rest of the suite."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wasmts.differential.core :as rpc]))

(def wasm-present?
  (.exists (io/file "dist/wasmts.js.wasm")))

(defn- with-runner [f]
  (if-not wasm-present?
    (println "  skipping smoke test: dist/wasmts.js.wasm not present")
    (try
      (rpc/start!)
      (f)
      (finally
        (rpc/stop!)))))

(use-fixtures :once with-runner)

(deftest ^:smoke ping
  (when wasm-present?
    (is (= "pong" (rpc/ping)))))

(deftest ^:smoke contains-and-area
  (when wasm-present?
    (let [poly        (rpc/read-jts "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))")
          inside-pt   (rpc/read-jts "POINT (5 5)")
          outside-pt  (rpc/read-jts "POINT (15 15)")
          h-poly      (rpc/jts->wasmts poly)
          h-inside    (rpc/jts->wasmts inside-pt)
          h-outside   (rpc/jts->wasmts outside-pt)]
      (try
        (testing "JTS contains agrees with wasmts contains (inside)"
          (is (true?  (.contains poly inside-pt)))
          (is (true?  (boolean (rpc/call! "wasmts.geom.contains" h-poly h-inside)))))
        (testing "JTS contains agrees with wasmts contains (outside)"
          (is (false? (.contains poly outside-pt)))
          (is (false? (boolean (rpc/call! "wasmts.geom.contains" h-poly h-outside)))))
        (testing "getArea is exactly 100.0 on both sides"
          (is (= 100.0 (.getArea poly)))
          (is (= 100.0 (double (rpc/call! "wasmts.geom.getArea" h-poly)))))
        (testing "WKB round-trip preserves geometry"
          (let [back (rpc/wasmts->jts h-poly)]
            (is (.equalsExact poly back 1.0e-9))))
        (finally
          (rpc/release! h-poly)
          (rpc/release! h-inside)
          (rpc/release! h-outside))))))
