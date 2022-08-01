//
//  ApiClient.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Alamofire

class ApiClient {
    public static func getData(from url: URL, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url, completionHandler: {(data, response, error) in
            if let data = data {
                completion(UIImage(data:data))
            }
        }).resume()
    }
    
    public static func postResource<T: Decodable>(endpoint: String, parameters: [String: Any], decodable: T.Type = T.self, callback: ((_ param: T) -> Void)?) {
        if (Store.serverConfig == nil) {
            NSLog("Server config not set")
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
    public static func getResource<T: Decodable>(endpoint: String, decodable: T.Type = T.self, callback: ((_ param: T?) -> Void)?) {
        if (Store.serverConfig == nil) {
            NSLog("Server config not set")
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
                    NSLog("api request to \(endpoint) failed")
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
        
        ApiClient.postResource(endpoint: endpoint, parameters: [
            "forceDirectPlay": !forceTranscode ? "1" : "",
            "forceTranscode": forceTranscode ? "1" : "",
            "mediaPlayer": "AVPlayer",
            "deviceInfo": [
                "manufacturer": "Apple",
                "model": modelCode,
                "clientVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            ]
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
    
    public static func getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, callback: @escaping (_ param: LibraryItem?) -> Void) {
        var endpoint = "api/items/\(libraryItemId)?expanded=1&include=progress"
        if episodeId != nil {
            endpoint += "&episodeId=\(episodeId!)"
        }
        
        ApiClient.getResource(endpoint: endpoint, decodable: LibraryItem.self) { obj in
                callback(obj)
        }
    }
}
