//
//  Database.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Database {
    // All DB releated actions must be executed on "realm-queue"
    public static let realmQueue = DispatchQueue(label: "realm-queue")
    private static var instance: Realm = {
        try! Realm(queue: realmQueue)
    }()
    
    public static func setServerConnectionConfig(config: ServerConnectionConfig) {
        var refrence: ThreadSafeReference<ServerConnectionConfig>?
        if config.realm != nil {
            refrence = ThreadSafeReference(to: config)
        }
        
        realmQueue.sync {
            let existing: ServerConnectionConfig? = instance.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
            
            if config.index == 0 {
                let lastConfig: ServerConnectionConfig? = instance.objects(ServerConnectionConfig.self).last
                
                if lastConfig != nil {
                    config.index = lastConfig!.index + 1
                } else {
                    config.index = 1
                }
            }
            
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
            
            setLastActiveConfigIndex(index: config.index)
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
    
    public static func setLastActiveConfigIndexToNil() {
        realmQueue.sync {
            setLastActiveConfigIndex(index: nil)
        }
    }
    public static func setLastActiveConfigIndex(index: Int?) {
        let existing = instance.objects(ServerConnectionConfigActiveIndex.self)
        let obj = ServerConnectionConfigActiveIndex()
        obj.index = index
     
        do {
            try instance.write {
                instance.delete(existing)
                instance.add(obj)
            }
        } catch(let exception) {
            NSLog("failed to save server config active index")
            debugPrint(exception)
        }
    }
    public static func getLastActiveConfigIndex() -> Int? {
        return realmQueue.sync {
            return instance.objects(ServerConnectionConfigActiveIndex.self).first?.index ?? nil
        }
    }
}
