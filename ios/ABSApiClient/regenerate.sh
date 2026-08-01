#!/usr/bin/env bash
#
# Regenerate the committed OpenAPI client sources
# (Sources/ABSApiClient/GeneratedSources/{Types,Client}.swift) from openapi.yaml.
#
# The swift-openapi-generator toolchain (Yams, OpenAPIKit, swift-argument-parser, …)
# is deliberately NOT a dependency of this package's Package.swift — keeping it out of
# the app build graph is the whole point (see Package.swift header). So we run the
# generator out-of-band from a throwaway SwiftPM package created in a temp directory,
# then copy its output back in. Nothing here touches the app's build graph.
#
# Usage:  ./regenerate.sh
# Requires: a Swift toolchain (swift --version) and network access to fetch the generator.
set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PKG_DIR/Sources/ABSApiClient"
OUT_DIR="$SRC_DIR/GeneratedSources"
GEN_VERSION="1.13.0"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/Sources/Gen"
cp "$SRC_DIR/openapi.yaml" "$TMP/Sources/Gen/openapi.yaml"
cp "$SRC_DIR/openapi-generator-config.yaml" "$TMP/Sources/Gen/openapi-generator-config.yaml"

# A minimal package whose only job is to run the generator command plugin.
# One real .swift file is required or SwiftPM skips the target (silent no-op).
cat > "$TMP/Sources/Gen/Placeholder.swift" <<'EOF'
// Placeholder so SwiftPM does not skip the target for lack of Swift sources.
EOF

cat > "$TMP/Package.swift" <<EOF
// swift-tools-version:6.0
import PackageDescription
let package = Package(
  name: "Gen",
  platforms: [.macOS(.v13)],
  dependencies: [
    .package(url: "https://github.com/apple/swift-openapi-generator", from: "$GEN_VERSION"),
    .package(url: "https://github.com/apple/swift-openapi-runtime", from: "1.12.0"),
    .package(url: "https://github.com/apple/swift-openapi-urlsession", from: "1.3.0"),
  ],
  targets: [
    .target(
      name: "Gen",
      dependencies: [
        .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
        .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
      ],
      plugins: [
        .plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator"),
      ]
    )
  ]
)
EOF

echo "==> Generating client from $SRC_DIR/openapi.yaml (generator $GEN_VERSION, out-of-band)"
( cd "$TMP" && swift build --target Gen >/dev/null )

GEN_OUT="$(find "$TMP/.build/plugins/outputs" -type d -name GeneratedSources | head -n1)"
if [[ -z "$GEN_OUT" ]]; then
  echo "error: generator produced no output — check openapi.yaml / config" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
cp "$GEN_OUT/Types.swift" "$OUT_DIR/Types.swift"
cp "$GEN_OUT/Client.swift" "$OUT_DIR/Client.swift"

echo "==> Wrote:"
echo "    $OUT_DIR/Types.swift"
echo "    $OUT_DIR/Client.swift"
echo "==> Done. Review the diff and commit."
