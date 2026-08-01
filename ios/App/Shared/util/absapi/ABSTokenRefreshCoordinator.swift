//
//  ABSTokenRefreshCoordinator.swift
//  Audiobookshelf
//
//  The single, shared owner of token refresh. Concurrent 401s — from the generated client's
//  middleware and/or the legacy Alamofire ApiClient — coalesce onto ONE /auth/refresh round-trip
//  via SingleFlight, so a burst of unauthorized requests can't trigger a refresh-token rotation
//  race (multiple refreshes each consuming the same rotating refresh token, all but one failing).
//
//  Replicates ApiClient.handleTokenRefresh exactly: on refresh it persists the new tokens
//  (SecureStorage + server connection config) and notifies the Capacitor WebView via
//  AbsDatabase.tokenRefreshCallback("onTokenRefresh"); on failure it clears the session and fires
//  "onTokenRefreshFailure".
//

import Foundation
import ABSApiClient

actor ABSTokenRefreshCoordinator: ABSTokenRefreshing {
    static let shared = ABSTokenRefreshCoordinator()

    private let singleFlight = SingleFlight<String?>()

    /// Return a fresh access token, or nil on failure. Concurrent callers share one refresh.
    func refreshAccessToken() async -> String? {
        await singleFlight.run { await Self.performRefresh() }
    }

    private static func performRefresh() async -> String? {
        let secureStorage = SecureStorage()

        guard let serverConfig = Store.serverConfig else {
            AbsLogger.error(message: "ABSTokenRefreshCoordinator: No server config available")
            return nil
        }
        // Copy values before any await — never hold a Realm object across a suspension.
        let configId = serverConfig.id
        let configName = serverConfig.name
        guard let serverURL = URL(string: serverConfig.address) else {
            AbsLogger.error(message: "ABSTokenRefreshCoordinator: Invalid server address for \(configName)")
            return nil
        }

        guard let refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId: configId) else {
            AbsLogger.error(message: "ABSTokenRefreshCoordinator: No refresh token available for server \(configName)")
            handleRefreshFailure(serverConfigId: configId)
            return nil
        }

        AbsLogger.info(message: "ABSTokenRefreshCoordinator: Refreshing access token for server \(configName)")

        guard let tokens = await ABSApiClient.performTokenRefresh(serverURL: serverURL, refreshToken: refreshToken) else {
            AbsLogger.error(message: "ABSTokenRefreshCoordinator: Refresh request failed for server \(configName)")
            handleRefreshFailure(serverConfigId: configId)
            return nil
        }

        AbsLogger.info(message: "ABSTokenRefreshCoordinator: Successfully obtained new access token")
        updateTokens(
            newAccessToken: tokens.accessToken,
            newRefreshToken: tokens.refreshToken ?? refreshToken,
            serverConnectionConfigId: configId
        )
        return tokens.accessToken
    }

    /// Persist the new tokens and notify the WebView. Mirrors ApiClient.updateTokens.
    private static func updateTokens(newAccessToken: String, newRefreshToken: String, serverConnectionConfigId: String) {
        let secureStorage = SecureStorage()

        if newRefreshToken != secureStorage.getRefreshToken(serverConnectionConfigId: serverConnectionConfigId) {
            let stored = secureStorage.storeRefreshToken(serverConnectionConfigId: serverConnectionConfigId, refreshToken: newRefreshToken)
            AbsLogger.info(message: "ABSTokenRefreshCoordinator: Updated refresh token in secure storage. Stored=\(stored)")
        }

        Database.shared.updateServerConnectionConfigToken(newToken: newAccessToken)

        // Notify the WebView so the web layer stays authenticated — the critical parity detail.
        if let callback = AbsDatabase.tokenRefreshCallback {
            callback("onTokenRefresh", ["accessToken": newAccessToken])
        }
    }

    /// Clear the session and notify the WebView. Mirrors ApiClient.handleRefreshFailure (and fixes
    /// its latent bug where the refresh-token removal never ran because Store.serverConfig had
    /// already been nil'd).
    private static func handleRefreshFailure(serverConfigId: String) {
        AbsLogger.info(message: "ABSTokenRefreshCoordinator: Token refresh failed, clearing session")

        Store.serverConfig = nil
        _ = SecureStorage().removeRefreshToken(serverConnectionConfigId: serverConfigId)

        if let callback = AbsDatabase.tokenRefreshCallback {
            callback("onTokenRefreshFailure", ["error": "Token refresh failed"])
        }
    }
}
