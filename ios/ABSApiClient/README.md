# ABSApiClient

A local Swift package that provides a **typed Audiobookshelf API client**, generated from an
OpenAPI document with Apple's
[swift-openapi-generator](https://github.com/apple/swift-openapi-generator).

The generated sources are **pre-generated and committed** (`Sources/ABSApiClient/GeneratedSources/`).
The generator is **not** run as a build-tool plugin. Running it at build time dragged the whole
generator toolchain (swift-openapi-generator, Yams, OpenAPIKit, swift-argument-parser, …) into
every app build — slowing builds and, because those packages are checked out into DerivedData,
breaking `xcodebuild clean` in CI (`Could not delete .../checkouts/Yams/build …`). Shipping
pre-generated code leaves only three lightweight runtime packages in the app's build graph.

It lives inside this repo (not a separate repo) so it evolves with the app and needs no
cross-repo coordination. If the client is ever useful to other apps or the community, this
directory can be lifted into its own repository unchanged.

## What's here

| File | Purpose |
|------|---------|
| `Package.swift` | Package manifest; runtime + URLSession transport only (no generator plugin). |
| `Sources/ABSApiClient/openapi.yaml` | The bundled OpenAPI spec the client is generated from. |
| `Sources/ABSApiClient/openapi-generator-config.yaml` | Generator config (`types` + `client`, public access). |
| `Sources/ABSApiClient/GeneratedSources/{Types,Client}.swift` | **Committed** generated models + client. Regenerate with `./regenerate.sh`. |
| `Sources/ABSApiClient/ABSApiClient.swift` | Hand-written convenience layer: `makeClient(serverURL:accessToken:)` + a bearer-auth middleware. |
| `regenerate.sh` | Regenerates `GeneratedSources/` from `openapi.yaml`, out-of-band (keeps the generator toolchain out of the app build graph). |

`Client.swift` and `Types.swift` **are** checked in. After editing `openapi.yaml` (or the
config), regenerate and commit the result:

```bash
cd ios/ABSApiClient
./regenerate.sh
```

## The OpenAPI spec

`openapi.yaml` is a self-contained bundle of the Audiobookshelf server's OpenAPI docs plus
the playback/auth/progress endpoints (`/auth/refresh`, `/api/me`, `/api/me/progress/*`,
`/api/items/{id}/play*`, `/api/session/*`). To refresh it from the server repo's `docs/`:

```bash
# from a checkout of advplyr/audiobookshelf
npx @redocly/cli bundle docs/root.yaml -o <path>/ios/ABSApiClient/Sources/ABSApiClient/openapi.yaml
```

## Verify it builds standalone

```bash
cd ios/ABSApiClient
swift build
```

This compiles the committed generated client against the runtime packages — no Xcode, and no
generator toolchain, required.

## Using it

```swift
import ABSApiClient

let client = ABSApiClient.makeClient(
    serverURL: URL(string: serverConfig.address)!,
    accessToken: serverConfig.token
)

// Start a playback session (generated, typed):
let response = try await client.playLibraryItem(
    path: .init(id: libraryItemId),
    body: .json(.init(
        forceDirectPlay: "1",
        deviceInfo: .init(deviceId: deviceId, clientName: "Abs iOS", clientVersion: appVersion)
    ))
)
let session = try response.ok.body.json   // Components.Schemas.playbackSession

// Sync progress:
_ = try await client.syncPlaybackSession(
    path: .init(id: session.id!),
    body: .json(.init(currentTime: 1234, duration: 5678, timeListened: 30))
)
```

Every documented operation is available as a typed method (`getCurrentUser`,
`getMediaProgress`, `updateMediaProgress`, `playPodcastEpisode`, `syncLocalPlaybackSession`,
`syncAllLocalPlaybackSessions`, `refreshToken`, …), and every schema as a `Components.Schemas.*`
struct.

## Wiring into the `Audiobookshelf` app target (alongside CocoaPods)

The iOS app uses CocoaPods; SwiftPM and CocoaPods coexist fine in one Xcode project.

1. Open `ios/App/App.xcworkspace` in Xcode.
2. **File ▸ Add Package Dependencies… ▸ Add Local…** and select `ios/ABSApiClient`.
3. Add the `ABSApiClient` library product to the **Audiobookshelf** target
   (target ▸ General ▸ Frameworks, Libraries, and Embedded Content).
4. `import ABSApiClient` where needed.

Because the client is pre-generated, there is **no** build-tool plugin and therefore no
"trust & enable" plugin prompt — in Xcode or in CI. Headless `xcodebuild` builds it with no
special flags.

> Note: the package pulls its dependencies via SwiftPM (swift-openapi-runtime,
> swift-openapi-urlsession, and their transitive deps). These are independent of the
> Podfile — CocoaPods manages Alamofire/Realm/Capacitor as before.

## How this relates to the existing `ApiClient.swift`

This client is **additive**. It is intended first for *new* native surfaces that run outside
the WebView — CarPlay, Home/Lock-screen widgets, Siri Shortcuts — which need typed API access
that the JavaScript layer can't provide.

It is **not** a drop-in replacement for `ios/App/Shared/util/ApiClient.swift`: the generated
models (`Components.Schemas.playbackSession`, etc.) are plain `Codable` DTOs, whereas the
existing app models (`class PlaybackSession: Object`) are Realm-backed. Migrating the existing
player/download paths would require mapping generated DTOs ↔ Realm models and can be done
incrementally, later — it is out of scope for the initial adoption.
