//
//  ServerConnectionConfig.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift
import Unrealm

struct ServerConnectionConfig: Realmable {
    var id: String = UUID().uuidString
    var index: Int = 1
    var name: String = ""
    var address: String = ""
    var userId: String = ""
    var username: String = ""
    var token: String = ""
    
    static func primaryKey() -> String? {
        return "id"
    }
    
    static func indexedProperties() -> [String] {
        return ["index"]
    }
}

struct ServerConnectionConfigActiveIndex: Realmable {
    // This could overflow, but you really would have to try
    var index: Int?
    
    static func primaryKey() -> String? {
        return "index"
    }
}

func convertServerConnectionConfigToJSON(config: ServerConnectionConfig) -> Dictionary<String, Any> {
    return [
        "id": config.id,
        "name": config.name,
        "index": config.index,
        "address": config.address,
        "userId": config.userId,
        "username": config.username,
        "token": config.token,
    ]
}
