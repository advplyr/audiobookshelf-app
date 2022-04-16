//
//  ServerConnectionConfig.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class ServerConnectionConfig: Object {
    @Persisted(primaryKey: true) var id: String
    @Persisted(indexed: true) var index: Int
    @Persisted var name: String
    @Persisted var address: String
    @Persisted var userId: String
    @Persisted var username: String
    @Persisted var token: String
}
class ServerConnectionConfigActiveIndex: Object {
    // This could overflow, but you really would have to try
    @Persisted var index: Int?

}

func convertServerConnectionConfigToJSON(config: ServerConnectionConfig) -> Dictionary<String, Any> {
    return Database.realmQueue.sync {
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
}
