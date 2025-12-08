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
# - SDKMAN installed: curl -s "https://get.sdkman.io" | bash
# - Labs JDK installed: sdk install java labsjdk-ce-latest
# - Submodules initialized: git submodule update --init

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
GRAAL_DIR="$PROJECT_ROOT/graal"
MX_DIR="$PROJECT_ROOT/mx"

echo "Building GraalVM from submodule..."
echo "Project root: $PROJECT_ROOT"

# Initialize SDKMAN and switch to Labs JDK (required for building GraalVM)
export SDKMAN_DIR="$HOME/.sdkman"
if [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    # Use Labs JDK with JVMCI support
    LABSJDK=$(ls "$SDKMAN_DIR/candidates/java/" | grep -E "^labsjdk" | head -1)
    if [ -n "$LABSJDK" ]; then
        echo "Switching to Labs JDK: $LABSJDK"
        sdk use java "$LABSJDK"
        # On macOS, Labs JDK has Contents/Home structure
        LABSJDK_PATH="$SDKMAN_DIR/candidates/java/$LABSJDK"
        if [ -d "$LABSJDK_PATH/Contents/Home" ]; then
            export JAVA_HOME="$LABSJDK_PATH/Contents/Home"
            echo "Set JAVA_HOME to: $JAVA_HOME"
        fi
    else
        echo "Warning: Labs JDK not found. Install with: sdk install java labsjdk-ce-latest"
        echo "Continuing with current JDK (may fail if not JVMCI-enabled)..."
    fi
else
    echo "SDKMAN not found. Ensure JAVA_HOME points to a JVMCI-enabled JDK."
fi

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

# Find the built GraalVM directory with svm-wasm tool (created by web-image build)
# Look for GRAALVM_*_JAVA* directories that have lib/svm/tools/svm-wasm
GRAALVM_BUILD=""
for dir in $(find "$GRAAL_DIR/sdk/mxbuild" -maxdepth 3 -type d -name "graalvm-*-java*" 2>/dev/null | grep -v STAGE1); do
    if [ -d "$dir/Contents/Home/lib/svm/tools/svm-wasm" ]; then
        GRAALVM_BUILD="$dir"
        break
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

echo ""
echo "Done! Created symlink: graal-home -> $GRAALVM_HOME"
echo ""
echo "You can now run: mvn package"
