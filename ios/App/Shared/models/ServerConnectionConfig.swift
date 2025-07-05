//
//  ServerConnectionConfig.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class ServerConnectionConfig: Object {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted(indexed: true) var index: Int = 1
    @Persisted var name: String = ""
    @Persisted var address: String = ""
    @Persisted var version: String = ""
    @Persisted var userId: String = ""
    @Persisted var username: String = ""
    @Persisted var token: String = ""
}

class ServerConnectionConfigActiveIndex: Object {
    // This could overflow, but you really would have to try
    @Persisted(primaryKey: true) var index: Int?
}

func convertServerConnectionConfigToJSON(config: ServerConnectionConfig) -> Dictionary<String, Any> {
    return [
        "id": config.id,
        "name": config.name,
        "index": config.index,
        "address": config.address,
        "version": config.version,
        "userId": config.userId,
        "username": config.username,
        "token": config.token,
    ]
}
