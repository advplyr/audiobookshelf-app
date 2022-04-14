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
        return realmQueue.sync {
            guard let config = instance.objects(ServerConnectionConfig.self).first else {
                return -1
            }
            return config.index
        }
    }
    
    public static func setServerConnectionConfig(config: ServerConnectionConfig) {
        var refrence: ThreadSafeReference<ServerConnectionConfig>?
        if config.realm != nil {
            refrence = ThreadSafeReference(to: config)
        }
        
        realmQueue.sync {
            let existing: ServerConnectionConfig? = instance.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
            
            do {
                try instance.write {
                    if existing != nil {
                        instance.delete(existing!)
                    }
                    if refrence == nil {
                        instance.add(config)
                    } else {
                        guard let resolved = instance.resolve(refrence!) else {
                            throw "unable to resolve refrence"
                        }
                        
                        instance.add(resolved);
                    }
                }
            } catch(let exception) {
                NSLog("failed to save server config")
                debugPrint(exception)
            }
        }
    }
    public static func getServerConnectionConfigs() -> [ServerConnectionConfig] {
        var refrences: [ThreadSafeReference<ServerConnectionConfig>] = []
        
        realmQueue.sync {
            let configs = instance.objects(ServerConnectionConfig.self)
            refrences = configs.map { config in
                return ThreadSafeReference(to: config)
            }
        }
        
        do {
            let realm = try Realm()
            
            return refrences.map { refrence in
                return realm.resolve(refrence)!
            }
        } catch(let exception) {
            NSLog("error while readling configs")
            debugPrint(exception)
            return []
        }
    }
}
