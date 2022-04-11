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
    public static func getActiveServerConfigIndex() -> Int {
        let realm = try! Realm(queue: realmQueue)
        guard let config = realm.objects(ServerConnectionConfig.self).first else {
            return -1
        }
        return config.index
    }
    
    public static func setServerConnectionConfig(config: ServerConnectionConfig) {
        // TODO: handle thread errors
        let realm = try! Realm(queue: realmQueue)
        var existing: ServerConnectionConfig?
        
        if config.id != nil {
            existing = realm.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
        }
        try! realm.write {
            if existing != nil {
                realm.delete(existing!)
            }
            realm.add(config)
        }
    }
    public static func getServerConnectionConfigs() -> [ServerConnectionConfig] {
        let realm = try! Realm(queue: realmQueue)
        let configs = realm.objects(ServerConnectionConfig.self)
        
        if configs.count <= 0 {
            return []
        }
        return Array(configs)
    }
}
