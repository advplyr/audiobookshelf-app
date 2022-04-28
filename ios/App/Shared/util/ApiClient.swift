//
//  ApiClient.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Alamofire

class ApiClient {
    public static func postResource<T: Decodable>(endpoint: String, parameters: [String: String], decodable: T.Type = T.self, callback: ((_ param: T) -> Void)?) {
        if (Store.serverConfig == nil) {
            NSLog("Server config not set")
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
                NSLog("api request to \(endpoint) failed")
                print(error)
            }
        }
    }
    public static func postResource(endpoint: String, parameters: [String: String], callback: ((_ success: Bool) -> Void)?) {
        if (Store.serverConfig == nil) {
            NSLog("Server config not set")
            callback?(false)
            return
        }
        
        let headers: HTTPHeaders = [
            "Authorization": "Bearer \(Store.serverConfig!.token)"
        ]
        
        AF.request("\(Store.serverConfig!.address)/\(endpoint)", method: .post, parameters: parameters, encoder: JSONParameterEncoder.default, headers: headers).response { response in
            switch response.result {
            case .success(let _):
                callback?(true)
            case .failure(let error):
                NSLog("api request to \(endpoint) failed")
                print(error)
                
                callback?(false)
            }
        }
    }
    
    public static func startPlaybackSession(libraryItemId: String, episodeId: String?, callback: @escaping (_ param: PlaybackSession) -> Void) {
        
        var endpoint = "api/items/\(libraryItemId)/play"
        if episodeId != nil {
            endpoint += "/\(episodeId!)"
        }
        
        ApiClient.postResource(endpoint: endpoint, parameters: [
            "forceTranscode": "true", // TODO: direct play
            "mediaPlayer": "AVPlayer",
        ], decodable: PlaybackSession.self) { obj in
            var session = obj
            
            session.serverConnectionConfigId = Store.serverConfig!.id
            session.serverAddress = Store.serverConfig!.address
            
            callback(session)
        }
    }
    public static func reportPlaybackProgress(report: PlaybackReport, sessionId: String) {
        try? postResource(endpoint: "api/session/\(sessionId)/sync", parameters: report.asDictionary().mapValues({ value in "\(value)" }), callback: nil)
    }
}
