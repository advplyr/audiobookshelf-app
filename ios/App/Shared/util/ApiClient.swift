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
        
        let parameters: [String: Any] = [
            "forceDirectPlay": !forceTranscode ? "1" : "",
            "forceTranscode": forceTranscode ? "1" : "",
            "mediaPlayer": "AVPlayer",
            "deviceInfo": [
                "deviceId": UIDevice.current.identifierForVendor?.uuidString,
                "manufacturer": "Apple",
                "model": modelCode,
                "clientVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            ]
        ]
        ApiClient.postResource(endpoint: endpoint, parameters: parameters, decodable: PlaybackSession.self) { obj in
            let session = obj
            
            session.serverConnectionConfigId = Store.serverConfig!.id
            session.serverAddress = Store.serverConfig!.address
            
            callback(session)
        }
    }
    
    public static func reportPlaybackProgress(report: PlaybackReport, sessionId: String) async -> Bool {
        return await postResource(endpoint: "api/session/\(sessionId)/sync", parameters: report)
    }
    
    public static func reportLocalPlaybackProgress(_ session: PlaybackSession) async -> Bool {
        return await postResource(endpoint: "api/session/local", parameters: session)
    }
    
    public static func reportAllLocalPlaybackSessions(_ sessions: [PlaybackSession]) async -> Bool {
        return await postResource(endpoint: "api/session/local-all", parameters: LocalPlaybackSessionSyncAllPayload(sessions: sessions))
    }
    
    public static func syncLocalSessionsWithServer() async {
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
                        try localMediaProgress?.updateFromServerMediaProgress(mediaProgress)
                    } else if (localMediaProgress != nil) {
                        logger.log("syncLocalSessionsWithServer: Local progress for \(localMediaProgress!.id) is more recent then server progress")
                    }
                }
            }
            
            // Send saved playback sessions to server and remove them from db
            let playbackSessions = Database.shared.getAllPlaybackSessions().filter {
                $0.serverConnectionConfigId == Store.serverConfig?.id
            }.map { $0.freeze() }
            logger.log("syncLocalSessionsWithServer: Found \(playbackSessions.count) playback sessions for server")
            if (!playbackSessions.isEmpty) {
                let success = await ApiClient.reportAllLocalPlaybackSessions(playbackSessions)
                if (success) {
                    // Remove sessions from db
                    try playbackSessions.forEach { session in
                        if (!session.isActiveSession) {
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
        patchResource(endpoint: endpoint, parameters: payload) { success in
            callback()
        }
    }
    
    public static func getMediaProgress(libraryItemId: String, episodeId: String?) async -> MediaProgress? {
        logger.log("getMediaProgress \(libraryItemId) \(episodeId ?? "NIL")")
        let endpoint = episodeId?.isEmpty ?? true ? "api/me/progress/\(libraryItemId)" : "api/me/progress/\(libraryItemId)/\(episodeId ?? "")"
        return await getResource(endpoint: endpoint, decodable: MediaProgress.self)
    }
    
    public static func getCurrentUser() async -> User? {
        logger.log("getCurrentUser")
        return await getResource(endpoint: "api/me", decodable: User.self)
    }
    
    public static func getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, callback: @escaping (_ param: LibraryItem?) -> Void) {
        var endpoint = "api/items/\(libraryItemId)?expanded=1&include=progress"
        if episodeId != nil {
            endpoint += "&episodeId=\(episodeId!)"
        }
        
        ApiClient.getResource(endpoint: endpoint, decodable: LibraryItem.self) { obj in
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
}

struct Connectivity {
  static private let sharedInstance = NetworkReachabilityManager()!
  static var isConnectedToInternet:Bool {
      return self.sharedInstance.isReachable
    }
}
