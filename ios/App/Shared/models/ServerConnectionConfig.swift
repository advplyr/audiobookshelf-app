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
