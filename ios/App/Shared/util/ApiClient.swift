//
//  ApiClient.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Alamofire

class ApiClient {
    private static let logger = AppLogger(category: "ApiClient")
    private static let secureStorage = SecureStorage()
    
    public static func getData(from url: URL, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url, completionHandler: {(data, response, error) in
            if let data = data {
                completion(UIImage(data:data))
            }
        }).resume()
    }
    
    public static func postResource<T: Decodable>(endpoint: String, parameters: [String: Any], decodable: T.Type = T.self, callback: ((_ param: T) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoding: JSONEncoding.default, headers: headers).responseDecodable(of: decodable) { response in
            switch response.result {
            case .success(let obj):
                callback?(obj)
            case .failure(let error):
                logger.error("api request to \(endpoint) failed")
                print(error)
            }
        }
    }
    
    public static func postResource<T: Encodable, U: Decodable>(endpoint: String, parameters: T, decodable: U.Type = U.self, callback: ((_ param: U) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers).responseDecodable(of: decodable) { response in
            switch response.result {
            case .success(let obj):
                callback?(obj)
            case .failure(let error):
                logger.error("api request to \(endpoint) failed")
                print(error)
            }
        }
    }
    
    public static func postResource<T:Encodable>(endpoint: String, parameters: T) async -> Bool {
        return await withCheckedContinuation { continuation in
            postResource(endpoint: endpoint, parameters: parameters) { success in
                continuation.resume(returning: success)
            }
        }
    }
    
    public static func postResource<T:Encodable>(endpoint: String, parameters: T, callback: ((_ success: Bool) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(false)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers).response { response in
            switch response.result {
            case .success(_):
                callback?(true)
            case .failure(let error):
                logger.error("api request to \(endpoint) failed")
                print(error)
                
                callback?(false)
            }
        }
    }
    
    public static func patchResource<T: Encodable>(endpoint: String, parameters: T, callback: ((_ success: Bool) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(false)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .patch, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers).response { response in
            switch response.result {
            case .success(_):
                callback?(true)
            case .failure(let error):
                logger.error("api request to \(endpoint) failed")
                print(error)
                callback?(false)
            }
        }
    }
    
    public static func getResource<T: Decodable>(endpoint: String, decodable: T.Type = T.self) async -> T? {
        return await withCheckedContinuation { continuation in
            getResource(endpoint: endpoint, decodable: decodable) { result in
                continuation.resume(returning: result)
            }
        }
    }
    
    public static func getResource<T: Decodable>(endpoint: String, decodable: T.Type = T.self, callback: ((_ param: T?) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(nil)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .get, encoding: JSONEncoding.default, headers: headers).responseDecodable(of: decodable) { response in
            switch response.result {
                case .success(let obj):
                    callback?(obj)
                case .failure(let error):
                    logger.error("api request to \(endpoint) failed")
                    print(error)
            }
        }
    }
    
    // MARK: - Token Refresh Handling
    
    /**
     * Handles token refresh when a 401 Unauthorized response is received
     * This function will:
     * 1. Get the refresh token from secure storage for the current server connection
     * 2. Make a request to /auth/refresh endpoint with the refresh token
     * 3. Update the connection config with the new accessToken and put the refreshToken in secure storage
     * 4. Retry the original request with the new access token
     * 5. If refresh fails, handle logout
     */
    private static func handleTokenRefresh<T: Decodable>(originalRequest: DataRequest, endpoint: String, method: HTTPMethod, parameters: Any?, decodable: T.Type, callback: ((_ param: T?) -> Void)?) {
        guard let serverConfig = Store.serverConfig else {
            logger.error("handleTokenRefresh: No server config available")
            callback?(nil)
            return
        }
        
        logger.log("handleTokenRefresh: Attempting to refresh auth tokens for server \(serverConfig.name)")
        
        // Get refresh token from secure storage
        guard let refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId: serverConfig.id) else {
            logger.error("handleTokenRefresh: No refresh token available for server \(serverConfig.name)")
            handleRefreshFailure()
            callback?(nil)
            return
        }
        
        logger.log("handleTokenRefresh: Retrieved refresh token, attempting to refresh access token")
        
        // Create refresh token request
        let refreshHeaders: HTTPHeaders = [
            "x-refresh-token": refreshToken,
            "Content-Type": "application/json"
        ]
        
        let refreshRequest = AF.request("\(serverConfig.address)/auth/refresh", method: .post, headers: refreshHeaders)
        
        refreshRequest.responseDecodable(of: RefreshResponse.self) { response in
            switch response.result {
            case .success(let refreshResponse):
                guard let user = refreshResponse.user,
                      !user.accessToken.isEmpty else {
                    logger.error("handleTokenRefresh: No access token in refresh response for server \(serverConfig.name)")
                    handleRefreshFailure()
                    callback?(nil)
                    return
                }
                
                logger.log("handleTokenRefresh: Successfully obtained new access token")
                
                // Update tokens in secure storage and store
                updateTokens(newAccessToken: user.accessToken, newRefreshToken: user.refreshToken ?? refreshToken, serverConnectionConfigId: serverConfig.id)
                
                // Retry the original request with the new access token
                logger.log("handleTokenRefresh: Retrying original request with new token")
                retryOriginalRequest(endpoint: endpoint, method: method, parameters: parameters, decodable: decodable, newAccessToken: user.accessToken, callback: callback)
                
            case .failure(let error):
                logger.error("handleTokenRefresh: Refresh request failed for server \(serverConfig.name): \(error)")
                handleRefreshFailure()
                callback?(nil)
            }
        }
    }
    
    /**
     * Updates the stored tokens with new access and refresh tokens
     */
    private static func updateTokens(newAccessToken: String, newRefreshToken: String, serverConnectionConfigId: String) {
        // Update the refresh token in secure storage if it's new
        if newRefreshToken != secureStorage.getRefreshToken(serverConnectionConfigId: serverConnectionConfigId) {
            let hasStored = secureStorage.storeRefreshToken(serverConnectionConfigId: serverConnectionConfigId, refreshToken: newRefreshToken)
            logger.log("updateTokens: Updated refresh token in secure storage. Stored=\(hasStored)")
        }
        
        // Update access token on server connection config
        Database.shared.updateServerConnectionConfigToken(newToken: newAccessToken)
        logger.log("updateTokens: Updated access token in server connection config")
        
        logger.log("updateTokens: Successfully refreshed auth tokens for server \(Store.serverConfig?.name ?? "unknown")")
        
        // Notify webview frontend about token refresh
        if let callback = AbsDatabase.tokenRefreshCallback {
            let tokenData: [String: Any] = ["accessToken": newAccessToken]
            callback("onTokenRefresh", tokenData)
        }
    }
    
    /**
     * Retries the original request with the new access token
     */
    private static func retryOriginalRequest<T: Decodable>(endpoint: String, method: HTTPMethod, parameters: Any?, decodable: T.Type, newAccessToken: String, callback: ((_ param: T?) -> Void)?) {
        guard let serverConfig = Store.serverConfig else {
            logger.error("retryOriginalRequest: No server config available")
            callback?(nil)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(newAccessToken)"
        ]
        
        let retryRequest: DataRequest
        
        switch method {
        case .get:
            retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .get, headers: headers)
        case .post:
            if let parameters = parameters as? [String: Any] {
                retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .post, parameters: parameters, encoding: JSONEncoding.default, headers: headers)
            } else if let encodableParams = parameters as? Encodable {
                retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .post, parameters: encodableParams, encoder: JSONParameterEncoder.default, headers: headers)
            } else {
                retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .post, headers: headers)
            }
        case .patch:
            if let encodableParams = parameters as? Encodable {
                retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .patch, parameters: encodableParams, encoder: JSONParameterEncoder.default, headers: headers)
            } else {
                retryRequest = AF.request("\(serverConfig.address)/\(endpoint)", method: .patch, headers: headers)
            }
        default:
            logger.error("retryOriginalRequest: Unsupported method \(method)")
            callback?(nil)
            return
        }
        
        // Handle the response
        retryRequest.response { response in
            if let statusCode = response.response?.statusCode, (200...299).contains(statusCode) {
                // Check if response has data
                if let data = response.data, !data.isEmpty {
                    // If it is a string return nil (e.g. express returns OK for 200 status codes)
                    if let responseString = String(data: data, encoding: .utf8) {
                        logger.log("retryOriginalRequest: Got string response '\(responseString)'")
                        callback?(nil)
                        return
                    }
                    
                    // If not a string, try JSON
                    do {
                        let decodedObject = try JSONDecoder().decode(decodable, from: data)
                        callback?(decodedObject)
                    } catch {
                        logger.error("retryOriginalRequest: JSON decode failed: \(error)")
                        callback?(nil)
                    }
                } else {
                    // Empty response
                    logger.log("retryOriginalRequest: Empty response with success status \(statusCode)")
                    callback?(nil)
                }
            } else {
                logger.error("retryOriginalRequest: Request failed with status \(response.response?.statusCode ?? 0)")
                callback?(nil)
            }
        }
    }
    
    /**
     * Handles the case when token refresh fails
     * This will clear the current server connection and notify webview
     */
    private static func handleRefreshFailure() {
        logger.log("handleRefreshFailure: Token refresh failed, clearing session")
        
        // Clear the current server connection
        Store.serverConfig = nil
        
        // Remove refresh token from secure storage
        if let serverConfig = Store.serverConfig {
            _ = secureStorage.removeRefreshToken(serverConnectionConfigId: serverConfig.id)
        }
        
        // Notify webview frontend about token refresh failure
        if let callback = AbsDatabase.tokenRefreshCallback {
            callback("onTokenRefreshFailure", ["error": "Token refresh failed"])
        }
    }
    
    // MARK: - Enhanced API Methods with Token Refresh
    
    public static func getResourceWithTokenRefresh<T: Decodable>(endpoint: String, decodable: T.Type = T.self, callback: ((_ param: T?) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(nil)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        let request = AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .get, headers: headers)
        
        request.responseDecodable(of: decodable) { response in
            if let statusCode = response.response?.statusCode, statusCode == 401 {
                logger.log("getResourceWithTokenRefresh: 401 Unauthorized for request to \(endpoint) - attempting token refresh")
                handleTokenRefresh(originalRequest: request, endpoint: endpoint, method: .get, parameters: nil, decodable: decodable, callback: callback)
            } else {
                switch response.result {
                case .success(let obj):
                    callback?(obj)
                case .failure(let error):
                    logger.error("api request to \(endpoint) failed")
                    print(error)
                    callback?(nil)
                }
            }
        }
    }
    
    public static func postResourceWithTokenRefresh<T: Encodable, U: Decodable>(endpoint: String, parameters: T, decodable: U.Type = U.self, callback: ((_ param: U?) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(nil)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        let request = AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers)
        
        request.responseDecodable(of: decodable) { response in
            if let statusCode = response.response?.statusCode, statusCode == 401 {
                logger.log("postResourceWithTokenRefresh: 401 Unauthorized for request to \(endpoint) - attempting token refresh")
                handleTokenRefresh(originalRequest: request, endpoint: endpoint, method: .post, parameters: parameters, decodable: decodable, callback: callback)
            } else {
                switch response.result {
                case .success(let obj):
                    callback?(obj)
                case .failure(let error):
                    logger.error("api request to \(endpoint) failed")
                    print(error)
                    callback?(nil)
                }
            }
        }
    }

    /**
     * POST request for endpoints that only return success/failure
     */
    public static func postResourceWithTokenRefresh<T: Encodable>(endpoint: String, parameters: T, callback: ((_ success: Bool) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(false)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        let request = AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers)
        
        request.response { response in
            if let statusCode = response.response?.statusCode, statusCode == 401 {
                logger.log("postResourceWithTokenRefresh: 401 Unauthorized for request to \(endpoint) - attempting token refresh")
                handleTokenRefresh(originalRequest: request, endpoint: endpoint, method: .post, parameters: parameters, decodable: EmptyResponse.self) { result in
                    callback?(result != nil)
                }
            } else {
                switch response.result {
                case .success(_):
                    callback?(true)
                case .failure(let error):
                    logger.error("api request to \(endpoint) failed")
                    print(error)
                    callback?(false)
                }
            }
        }
    }
    
    public static func patchResourceWithTokenRefresh<T: Encodable>(endpoint: String, parameters: T, callback: ((_ success: Bool) -> Void)?) {
        if (Store.serverConfig == nil) {
            logger.error("Server config not set")
            callback?(false)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        let request = AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .patch, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers)
        
        request.response { response in
            if let statusCode = response.response?.statusCode, statusCode == 401 {
                logger.log("patchResourceWithTokenRefresh: 401 Unauthorized for request to \(endpoint) - attempting token refresh")
                handleTokenRefresh(originalRequest: request, endpoint: endpoint, method: .patch, parameters: parameters, decodable: EmptyResponse.self) { result in
                    callback?(result != nil)
                }
            } else {
                switch response.result {
                case .success(_):
                    callback?(true)
                case .failure(let error):
                    logger.error("api request to \(endpoint) failed")
                    print(error)
                    callback?(false)
                }
            }
        }
    }
    
    // MARK: - API Functions
    
    public static func startPlaybackSession(libraryItemId: String, episodeId: String?, forceTranscode:Bool, callback: @escaping (_ param: PlaybackSession) -> Void) {
        var endpoint = "api/items/\(libraryItemId)/play"
        if episodeId != nil {
            endpoint += "/\(episodeId!)"
        }
        
        var systemInfo = utsname()
        uname(&systemInfo)
        let modelCode = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                ptr in String.init(validatingUTF8: ptr)
            }
        }
        
        // Create an Encodable struct for the parameters
        let parameters = PlaybackSessionRequest(
            forceDirectPlay: !forceTranscode ? "1" : "",
            forceTranscode: forceTranscode ? "1" : "",
            mediaPlayer: "AVPlayer",
            deviceInfo: DeviceInfo(
                deviceId: UIDevice.current.identifierForVendor?.uuidString,
                manufacturer: "Apple",
                model: modelCode,
                clientVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            )
        )
        
        // Use the new token refresh-enabled method
        postResourceWithTokenRefresh(endpoint: endpoint, parameters: parameters, decodable: PlaybackSession.self) { session in
            guard let session = session else {
                logger.error("startPlaybackSession: Failed to create playback session")
                callback(PlaybackSession()) // Return empty session on failure
                return
            }
            
            // Set server connection info on the session
            session.serverConnectionConfigId = Store.serverConfig!.id
            session.serverAddress = Store.serverConfig!.address
            
            callback(session)
        }
    }
    
    public static func reportPlaybackProgress(report: PlaybackReport, sessionId: String) async -> Bool {
        return await withCheckedContinuation { continuation in
            postResourceWithTokenRefresh(endpoint: "api/session/\(sessionId)/sync", parameters: report) { success in
                continuation.resume(returning: success)
            }
        }
    }
    
    public static func reportLocalPlaybackProgress(_ session: PlaybackSession) async -> Bool {
        return await withCheckedContinuation { continuation in
            postResourceWithTokenRefresh(endpoint: "api/session/local", parameters: session) { success in
                continuation.resume(returning: success)
            }
        }
    }
    
    public static func reportAllLocalPlaybackSessions(_ sessions: [PlaybackSession]) async -> Bool {
        return await withCheckedContinuation { continuation in
            let payload = LocalPlaybackSessionSyncAllPayload(sessions: sessions, deviceInfo: sessions.first?.deviceInfo)
            postResourceWithTokenRefresh(endpoint: "api/session/local-all", parameters: payload) { success in
                continuation.resume(returning: success)
            }
        }
    }
    
    public static func syncLocalSessionsWithServer(isFirstSync: Bool) async {
        do {
            // Sync server progress with local media progress
            let localMediaProgressList = Database.shared.getAllLocalMediaProgress().filter {
                $0.serverConnectionConfigId == Store.serverConfig?.id
            }.map { $0.freeze() }
            logger.log("syncLocalSessionsWithServer: Found \(localMediaProgressList.count) local media progress for server")
            
            if (localMediaProgressList.isEmpty) {
                logger.log("syncLocalSessionsWithServer: No local progress to sync")
            } else {
                let currentUser = await ApiClient.getCurrentUser()
                guard let currentUser = currentUser else {
                    logger.log("syncLocalSessionsWithServer: No User")
                    return
                }
                try currentUser.mediaProgress.forEach { mediaProgress in
                    let localMediaProgress = localMediaProgressList.first { lmp in
                        if (lmp.episodeId != nil) {
                            return lmp.episodeId == mediaProgress.episodeId
                        } else {
                            return lmp.libraryItemId == mediaProgress.libraryItemId
                        }
                    }
                    if (localMediaProgress != nil && mediaProgress.lastUpdate > localMediaProgress!.lastUpdate) {
                        logger.log("syncLocalSessionsWithServer: Updating local media progress \(localMediaProgress!.id) with server media progress")
                        if let localMediaProgress = localMediaProgress?.thaw() {
                            try localMediaProgress.updateFromServerMediaProgress(mediaProgress)
                        }
                    } else if (localMediaProgress != nil) {
                        logger.log("syncLocalSessionsWithServer: Local progress for \(localMediaProgress!.id) is more recent then server progress")
                    }
                }
            }
            
            // Send saved playback sessions to server and remove them from db
            let playbackSessions = Database.shared.getAllPlaybackSessions().filter {
                $0.serverConnectionConfigId == Store.serverConfig?.id
            }.map { $0.freeze() }
            logger.log("syncLocalSessionsWithServer: Found \(playbackSessions.count) playback sessions for server (first sync: \(isFirstSync))")
            if (!playbackSessions.isEmpty) {
                let success = await ApiClient.reportAllLocalPlaybackSessions(playbackSessions)
                if (success) {
                    // Remove sessions from db
                    try playbackSessions.forEach { session in
                        logger.log("syncLocalSessionsWithServer: Handling \(session.displayTitle ?? "") (\(session.id)) \(session.isActiveSession)")
                        // On first sync then remove all sessions
                        if (!session.isActiveSession || isFirstSync) {
                            if let session = session.thaw() {
                                try session.delete()
                            }
                        }
                    }
                }
            }
        } catch {
            debugPrint(error)
            return
        }
    }
    
    public static func updateMediaProgress<T:Encodable>(libraryItemId: String, episodeId: String?, payload: T, callback: @escaping () -> Void) {
        logger.log("updateMediaProgress \(libraryItemId) \(episodeId ?? "NIL") \(payload)")
        let endpoint = episodeId?.isEmpty ?? true ? "api/me/progress/\(libraryItemId)" : "api/me/progress/\(libraryItemId)/\(episodeId ?? "")"
        patchResourceWithTokenRefresh(endpoint: endpoint, parameters: payload) { _ in
            callback()
        }
    }
    
    public static func getMediaProgress(libraryItemId: String, episodeId: String?) async -> MediaProgress? {
        logger.log("getMediaProgress \(libraryItemId) \(episodeId ?? "NIL")")
        let endpoint = episodeId?.isEmpty ?? true ? "api/me/progress/\(libraryItemId)" : "api/me/progress/\(libraryItemId)/\(episodeId ?? "")"
        return await withCheckedContinuation { continuation in
            getResourceWithTokenRefresh(endpoint: endpoint, decodable: MediaProgress.self) { result in
                continuation.resume(returning: result)
            }
        }
    }
    
    public static func getCurrentUser() async -> User? {
        logger.log("getCurrentUser")
        return await withCheckedContinuation { continuation in
            getResourceWithTokenRefresh(endpoint: "api/me", decodable: User.self) { result in
                continuation.resume(returning: result)
            }
        }
    }
    
    public static func getLibraryItemWithProgress(libraryItemId: String, episodeId: String?, callback: @escaping (_ param: LibraryItem?) -> Void) {
        var endpoint = "api/items/\(libraryItemId)?expanded=1&include=progress"
        if episodeId != nil {
            endpoint += "&episodeId=\(episodeId!)"
        }

        getResourceWithTokenRefresh(endpoint: endpoint, decodable: LibraryItem.self) { obj in
            callback(obj)
        }
    }
    
    public static func pingServer() async -> Bool {
        var status = true
        AF.request("\(Store.serverConfig!.address)/ping", method: .get).responseDecodable(of: PingResponsePayload.self) { response in
            switch response.result {
                case .success:
                    status = true
                case .failure:
                    status = false
            }
        }
        return status
    }
}

struct LocalMediaProgressSyncPayload: Codable {
    var localMediaProgress: [LocalMediaProgress]
}

struct PingResponsePayload: Codable {
    var success: Bool
}

struct MediaProgressSyncResponsePayload: Decodable {
    var numServerProgressUpdates: Int?
    var localProgressUpdates: [LocalMediaProgress]?
    
    private enum CodingKeys : String, CodingKey {
        case numServerProgressUpdates, localProgressUpdates
    }
    
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        numServerProgressUpdates = try? values.intOrStringDecoder(key: .numServerProgressUpdates)
        localProgressUpdates = try? values.decode([LocalMediaProgress].self, forKey: .localProgressUpdates)
    }
}

struct LocalMediaProgressSyncResultsPayload: Codable {
    var numLocalMediaProgressForServer: Int?
    var numServerProgressUpdates: Int?
    var numLocalProgressUpdates: Int?
}

struct LocalPlaybackSessionSyncAllPayload: Codable {
    var sessions: [PlaybackSession]
    var deviceInfo: [String: String?]?
}

struct Connectivity {
  static private let sharedInstance = NetworkReachabilityManager()!
  static var isConnectedToInternet:Bool {
      return self.sharedInstance.isReachable
    }
}

// MARK: - Response Models

struct RefreshResponse: Decodable {
    let user: RefreshUser?
}

struct RefreshUser: Decodable {
    let accessToken: String
    let refreshToken: String?
}

struct EmptyResponse: Decodable {}

struct PlaybackSessionRequest: Encodable {
    let forceDirectPlay: String
    let forceTranscode: String
    let mediaPlayer: String
    let deviceInfo: DeviceInfo
}

struct DeviceInfo: Encodable {
    let deviceId: String?
    let manufacturer: String
    let model: String?
    let clientVersion: String?
}
