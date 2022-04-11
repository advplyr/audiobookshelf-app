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
    
    // All DB releated actions must be executed on "realm-queue"
    private static var instance: Realm = {
        // TODO: handle thread errors
        try! Realm(queue: realmQueue)
    }()
    
    public static func getActiveServerConfigIndex() -> Int {
        guard let config = instance.objects(ServerConnectionConfig.self).first else {
            return -1
        }
        return config.index
    }
    
    public static func setServerConnectionConfig(config: ServerConnectionConfig) {
        let existing: ServerConnectionConfig? = instance.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
        
        try! instance.write {
            if existing != nil {
                instance.delete(existing!)
            }
            instance.add(config)
        }
    }
    public static func getServerConnectionConfigs() -> [ServerConnectionConfig] {
        let configs = instance.objects(ServerConnectionConfig.self)
        
        if configs.count <= 0 {
            return []
        }
        return Array(configs)
    }
}
