//
//  Database.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Database {
    public static let realmQueue = DispatchQueue(label: "realm-queue")
    
    public static func setServerConnectionConfig(config: ServerConnectionConfig) {
        let realm = try! Realm(queue: realmQueue)
        let existing = realm.objects(ServerConnectionConfig.self)
        
        try! realm.write {
            realm.delete(existing)
            realm.add(config)
        }
    }
    public static func getServerConnectionConfig() -> ServerConnectionConfig {
        let realm = try! Realm(queue: realmQueue)
        guard let config = realm.objects(ServerConnectionConfig.self).first else {
            let fallback = ServerConnectionConfig()
            return fallback
        }
        
        return config
    }
}
