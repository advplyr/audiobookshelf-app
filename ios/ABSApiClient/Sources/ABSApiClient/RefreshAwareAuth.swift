//
//  RefreshAwareAuth.swift
//  ABSApiClient
//
//  Refresh-aware authentication for the generated client. Phase 1 of the migration off the
//  hand-rolled ApiClient. This file is deliberately app-agnostic (no Realm / app singletons):
//  the actual token persistence + WebView notification is supplied by the app through the
//  `ABSTokenRefreshing` protocol, so this stays inside the package where OpenAPIRuntime /
//  HTTPTypes are available.
//

import Foundation
import HTTPTypes
import OpenAPIRuntime
import OpenAPIURLSession

/// Refreshes the session's access token when the server rejects a request with 401.
///
/// Implemented by the app, which owns token storage (SecureStorage), the server connection
/// config (Realm), and — critically — notifying the Capacitor WebView so the web layer stays
/// authenticated. Kept as a protocol here so the package has no app dependencies.
public protocol ABSTokenRefreshing: Sendable {
    /// Attempt to obtain a new access token. Returns the new token on success, or `nil` if the
    /// refresh failed (in which case the caller surfaces the original 401 unchanged).
    func refreshAccessToken() async -> String?
}

/// Injects the current bearer access token into every request and, on a 401, asks the injected
/// refresher for a new token and retries the original request exactly once.
///
/// Mirrors the hand-rolled `ApiClient` token-refresh path: 401 → refresh → retry with the new
/// token; if the refresh fails, the original 401 response is returned unchanged.
struct RefreshAwareAuthMiddleware: ClientMiddleware {
    /// Reads the *current* access token at request time (so a token refreshed elsewhere — e.g.
    /// by the legacy ApiClient during the migration — is picked up automatically).
    let accessTokenProvider: @Sendable () -> String?
    let refresher: any ABSTokenRefreshing

    func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        // Buffer the request body so it can be replayed on the retry (HTTPBody is single-pass).
        let bufferedBody: [UInt8]?
        if let body {
            bufferedBody = try await [UInt8](collecting: body, upTo: .max)
        } else {
            bufferedBody = nil
        }
        func freshBody() -> HTTPBody? { bufferedBody.map { HTTPBody($0) } }

        func authorized(with token: String?) -> HTTPRequest {
            var req = request
            if let token { req.headerFields[.authorization] = "Bearer \(token)" }
            return req
        }

        let (response, responseBody) = try await next(authorized(with: accessTokenProvider()), freshBody(), baseURL)
        guard response.status.code == 401 else {
            return (response, responseBody)
        }

        // 401 → attempt a token refresh, then retry once with the new token.
        guard let newToken = await refresher.refreshAccessToken() else {
            return (response, responseBody)
        }
        return try await next(authorized(with: newToken), freshBody(), baseURL)
    }
}

/// Injects the `x-refresh-token` header for the `/auth/refresh` call, which authenticates with
/// the refresh token rather than the (expired) bearer access token.
struct RefreshTokenHeaderMiddleware: ClientMiddleware {
    let refreshToken: String

    func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        if let name = HTTPField.Name("x-refresh-token") {
            request.headerFields[name] = refreshToken
        }
        return try await next(request, body, baseURL)
    }
}

/// The inputs needed to build a refresh-aware client: the server base URL, a lazy reader for the
/// current access token, and the token refresher. Holds no generated (`Client`/`Components`) types,
/// so the app can construct and pass it without linking OpenAPIRuntime.
public struct ABSClientConfig: Sendable {
    public let serverURL: URL
    public let accessToken: @Sendable () -> String?
    public let refresher: any ABSTokenRefreshing
    /// Optional sink for failure detail (thrown decode/network errors). Since there is no legacy
    /// fallback, this surfaces the error the operation wrappers otherwise swallow into nil/false.
    public let diagnostics: (@Sendable (String) -> Void)?

    public init(
        serverURL: URL,
        accessToken: @escaping @Sendable () -> String?,
        refresher: any ABSTokenRefreshing,
        diagnostics: (@Sendable (String) -> Void)? = nil
    ) {
        self.serverURL = serverURL
        self.accessToken = accessToken
        self.refresher = refresher
        self.diagnostics = diagnostics
    }
}

extension ABSApiClient {
    /// Build a client that transparently refreshes the access token on 401 and retries once.
    public static func makeRefreshAwareClient(config: ABSClientConfig) -> Client {
        Client(
            serverURL: config.serverURL,
            transport: URLSessionTransport(),
            middlewares: [RefreshAwareAuthMiddleware(accessTokenProvider: config.accessToken, refresher: config.refresher)]
        )
    }

    /// Perform a single `POST /auth/refresh` round-trip with the given refresh token.
    ///
    /// App-agnostic: performs no persistence and fires no notifications — the caller (the app's
    /// `ABSTokenRefreshing` implementation) is responsible for storing the returned tokens and
    /// notifying the WebView. Returns the new tokens, or `nil` if the refresh failed.
    public static func performTokenRefresh(
        serverURL: URL,
        refreshToken: String
    ) async -> (accessToken: String, refreshToken: String?)? {
        let client = Client(
            serverURL: serverURL,
            transport: URLSessionTransport(),
            middlewares: [RefreshTokenHeaderMiddleware(refreshToken: refreshToken)]
        )
        do {
            let output = try await client.refreshToken(.init())
            guard case let .ok(ok) = output else { return nil }
            let json = try ok.body.json
            guard let accessToken = json.user?.value2.accessToken, !accessToken.isEmpty else { return nil }
            return (accessToken, json.user?.value2.refreshToken)
        } catch {
            return nil
        }
    }
}
