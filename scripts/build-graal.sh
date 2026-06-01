#!/bin/bash
# Build GraalVM from submodule and create graal-home symlink
#
# Use this script when building GraalVM from source (e.g., from a fork or git HEAD)
# rather than using an official GraalVM release. This is needed when:
# - Testing patches not yet in a release
# - Building against latest development code
# - Reproducible builds with pinned commit
#
# Prerequisites:
# - Submodules initialized: git submodule update --init
#
# The JVMCI labsjdk this build needs is fetched automatically (see below); it is
# not a prerequisite you install yourself.

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
GRAAL_DIR="$PROJECT_ROOT/graal"
MX_DIR="$PROJECT_ROOT/mx"
# Version-independent alias for the fetched JDK, so the JAVA_HOME that pom.xml
# documents survives a graal submodule bump. pom.xml references this path.
JDK_ALIAS="labsjdk-ce-latest"

# BSD and GNU stat spell mtime differently, and GNU stat reads `-f` as
# --file-system: it prints a filesystem dump to STDOUT and exits non-zero, so a
# `stat -f ... || stat -c ...` fallback captures that dump AND the real answer.
# Nix coreutils shadows /usr/bin/stat on macOS, so probe once rather than guess.
if stat -f %m . >/dev/null 2>&1; then
    mtime() { stat -f %m "$1" 2>/dev/null || echo 0; }
else
    mtime() { stat -c %Y "$1" 2>/dev/null || echo 0; }
fi

echo "Building GraalVM from submodule..."
echo "Project root: $PROJECT_ROOT"

# Fetch the exact JVMCI labsjdk THIS graal revision requires, read from the
# checked-out graal/common.json, into a gitignored repo-local .jdks dir. This is
# self-updating: bumping the graal submodule pulls the matching JDK on the next
# build (the old SDKMAN `ls | head -1` pick silently grabbed a stale labsjdk and
# broke after a submodule bump — common.json currently pins
# ce-25.0.3+9-jvmci-25.1-b19).
JDK_DIR="$PROJECT_ROOT/.jdks"
mkdir -p "$JDK_DIR"
echo "Fetching required JVMCI labsjdk via mx fetch-jdk (per graal/common.json)..."
"$MX_DIR/mx" fetch-jdk --configuration "$PROJECT_ROOT/graal/common.json" \
    --to "$JDK_DIR" --alias "$JDK_ALIAS" labsjdk-ce-latest
# macOS nests the home under Contents/Home; Linux is flat.
if [ -d "$JDK_DIR/$JDK_ALIAS/Contents/Home" ]; then
    export JAVA_HOME="$JDK_DIR/$JDK_ALIAS/Contents/Home"
else
    export JAVA_HOME="$JDK_DIR/$JDK_ALIAS"
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "Error: mx fetch-jdk did not produce a usable JDK at $JAVA_HOME"
    exit 1
fi
echo "Set JAVA_HOME to: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# Check submodules exist
if [ ! -d "$GRAAL_DIR/sdk" ]; then
    echo "Error: graal submodule not found. Run: git submodule update --init"
    exit 1
fi

if [ ! -f "$MX_DIR/mx" ]; then
    echo "Error: mx submodule not found. Run: git submodule update --init"
    exit 1
fi

# Build GraalVM with native-image using local mx
# Need to build from vm directory with ni-ce environment
cd "$GRAAL_DIR/vm"
echo "Building native-image in $(pwd)..."
"$MX_DIR/mx" --env ni-ce build

# Build web-image (required for --tool:svm-wasm)
cd "$GRAAL_DIR/web-image"
echo "Building web-image in $(pwd)..."
"$MX_DIR/mx" build

# Find the built GraalVM directory with svm-wasm tool (created by web-image build).
# Pick the most recently built one, not the first match: mx keys each build by a
# config hash and never GCs superseded ones, so a rebuild leaves dirs like
# GRAALVM_D7E28C1C43_JAVA25 and GRAALVM_75C38E2614_JAVA25 side by side at the same
# version, both carrying lib/svm/tools/svm-wasm. find's order is arbitrary, so
# first-match-then-break grabbed a stale toolchain. Track the newest mtime instead.
GRAALVM_BUILD=""
NEWEST_MT=0
for dir in $(find "$GRAAL_DIR/sdk/mxbuild" -maxdepth 3 -type d -name "graalvm-*-java*" 2>/dev/null | grep -v STAGE1); do
    if [ -d "$dir/Contents/Home/lib/svm/tools/svm-wasm" ]; then
        MT="$(mtime "$dir")"
        if [ "${MT:-0}" -gt "$NEWEST_MT" ]; then
            NEWEST_MT="$MT"
            GRAALVM_BUILD="$dir"
        fi
    fi
done

if [ -z "$GRAALVM_BUILD" ]; then
    echo "Error: Could not find built GraalVM with svm-wasm tool"
    echo "Looking in: $GRAAL_DIR/sdk/mxbuild"
    echo "Available builds:"
    find "$GRAAL_DIR/sdk/mxbuild" -maxdepth 4 -type d -name "graalvm-*" 2>/dev/null
    exit 1
fi

# On macOS, the actual home is inside Contents/Home
if [ -d "$GRAALVM_BUILD/Contents/Home" ]; then
    GRAALVM_HOME="$GRAALVM_BUILD/Contents/Home"
else
    GRAALVM_HOME="$GRAALVM_BUILD"
fi

echo "Found GraalVM at: $GRAALVM_HOME"

# Create/update symlink
cd "$PROJECT_ROOT"
rm -f graal-home
ln -s "$GRAALVM_HOME" graal-home

# Reclaim stale mx build artifacts from PRIOR build sessions. mx keys every
# GraalVM/GraalJDK image dir AND its dist tarball by a config hash and never
# GCs superseded ones, so each graal rebuild / submodule bump leaves ~2-3GB
# behind (a ~1GB image + its ~1.1GB tarball, both kept) that accumulates across
# days. Keep this session's artifacts (anything within GC window of the live
# image, so a multi-stage build's ni-ce / stage1 / web-image outputs all
# survive) and prune older sessions'. Never touches the live image graal-home
# points at. Opt out with WASMTS_KEEP_STALE_GRAAL=1.
if [ -z "${WASMTS_KEEP_STALE_GRAAL:-}" ]; then
    LIVE_HASH_DIR="$(dirname "$GRAALVM_BUILD")"            # .../GRAALVM_<hash>_JAVA25
    MXBUILD_ARCH="$(dirname "$LIVE_HASH_DIR")"             # .../mxbuild/<os-arch>
    GC_KEEP_WINDOW="${WASMTS_GRAAL_GC_WINDOW:-7200}"       # seconds; 2h = one build session
    LIVE_MT="$(mtime "$LIVE_HASH_DIR")"
    if [ "${LIVE_MT:-0}" -gt 0 ]; then
        CUTOFF=$((LIVE_MT - GC_KEEP_WINDOW))
        pruned=0
        for path in "$MXBUILD_ARCH"/GRAALVM_* "$MXBUILD_ARCH"/GRAALJDK_* \
                    "$MXBUILD_ARCH"/dists/graalvm-*.tar "$MXBUILD_ARCH"/dists/graaljdk-*.tar; do
            [ -e "$path" ] || continue
            [ "$path" = "$LIVE_HASH_DIR" ] && continue      # never the live image
            MT="$(mtime "$path")"
            # An unreadable mtime must not read as "ancient" and trigger a delete.
            [ "$MT" -eq 0 ] && MT="$LIVE_MT"
            if [ "$MT" -lt "$CUTOFF" ]; then
                echo "  GC prune (stale prior build): $(basename "$path")"
                rm -rf "$path"
                pruned=1
            fi
        done
        [ "$pruned" = 1 ] && echo "Pruned stale graal build artifacts (WASMTS_KEEP_STALE_GRAAL=1 to disable)."
    fi
fi

echo ""
echo "Done! Created symlink: graal-home -> $GRAALVM_HOME"
echo ""
echo "You can now run: mvn package"
