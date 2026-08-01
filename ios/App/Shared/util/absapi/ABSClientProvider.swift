//
//  ABSClientProvider.swift
//  Audiobookshelf
//
//  Single owner of ABSApiClient configuration. The generated `Client` is constructed inside the
//  ABSApiClient package (so the app never links OpenAPIRuntime); this provider vends the config it
//  needs — server base URL, a lazy access-token reader, and the shared token refresher — sourced
//  from the active server connection.
//

import Foundation
import ABSApiClient

enum ABSClientProvider {
    /// Config for the active server, or nil if none is configured / the address is invalid.
    /// The access token is read lazily at request time so a token refreshed by either client is
    /// always picked up.
    static var config: ABSClientConfig? {
        guard let address = Store.serverConfig?.address, let serverURL = URL(string: address) else {
            return nil
        }
        return ABSClientConfig(
            serverURL: serverURL,
            accessToken: { Store.serverConfig?.token },
            refresher: ABSTokenRefreshCoordinator.shared,
            // Surface the thrown decode/network errors the operation wrappers otherwise swallow —
            // important because there is no legacy fallback.
            diagnostics: { AbsLogger.error(message: "ABSApiClient: \($0)") }
        )
    }
}
