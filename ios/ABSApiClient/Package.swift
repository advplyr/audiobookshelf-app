// swift-tools-version:6.0
import PackageDescription

// Local Swift package exposing a typed Audiobookshelf API client generated from the
// bundled OpenAPI document (Sources/ABSApiClient/openapi.yaml).
//
// The generated sources (Sources/ABSApiClient/GeneratedSources/{Types,Client}.swift)
// are committed and are the source of truth for the build. We intentionally do NOT
// run swift-openapi-generator as a build-tool plugin: doing so dragged the whole
// generator toolchain (swift-openapi-generator, Yams, OpenAPIKit, swift-argument-parser,
// swift-collections, …) into every app build, which slowed builds and — because those
// packages are checked out into DerivedData — made `xcodebuild clean` fail in CI
// ("Could not delete .../checkouts/Yams/build …"). Shipping pre-generated code leaves
// only the three lightweight runtime packages below in the app's build graph.
//
// To regenerate after editing openapi.yaml, run ./regenerate.sh (it uses the generator
// out-of-band so the toolchain never enters the app build graph). See README.md.
//
// This lives inside the app repo intentionally (see README.md). The app target
// consumes it as a local package dependency alongside the existing CocoaPods setup.
let package = Package(
  name: "ABSApiClient",
  platforms: [
    .iOS(.v14),
    .macOS(.v13)
  ],
  products: [
    .library(name: "ABSApiClient", targets: ["ABSApiClient"])
  ],
  dependencies: [
    .package(url: "https://github.com/apple/swift-openapi-runtime", from: "1.12.0"),
    .package(url: "https://github.com/apple/swift-openapi-urlsession", from: "1.3.0"),
    // HTTPTypes is imported directly by RefreshAwareAuth.swift (the auth middlewares construct
    // HTTPField.Name / read HTTPResponse.status). It also arrives transitively via the OpenAPI
    // packages, but declaring it explicitly is correct hygiene AND makes it a first-level product
    // in the link closure — required so an app-hosted XCTest target that links ABSApiClient can
    // resolve HTTPTypes symbols (Xcode's implicit link does not pull second-level transitive products).
    .package(url: "https://github.com/apple/swift-http-types", from: "1.0.0")
  ],
  targets: [
    .target(
      name: "ABSApiClient",
      dependencies: [
        .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
        .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
        .product(name: "HTTPTypes", package: "swift-http-types")
      ],
      // Inputs to regenerate.sh only — not compiled. Excluded so SwiftPM doesn't
      // treat them as unhandled resources now that the build-tool plugin is gone.
      exclude: [
        "openapi.yaml",
        "openapi-generator-config.yaml"
      ]
    )
  ]
)
