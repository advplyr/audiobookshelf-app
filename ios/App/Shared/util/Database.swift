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
    public static let realmQueue: DispatchQueue = DispatchQueue(label: "realm-queue")
    public static var shared = {
        realmQueue.sync {
            return Database()
        }
    }()

    private var instance: Realm
    private init() {
        self.instance = try! Realm(queue: Database.realmQueue)
    }
    
    public func setServerConnectionConfig(config: ServerConnectionConfig) {
        var refrence: ThreadSafeReference<ServerConnectionConfig>?
        if config.realm != nil {
            refrence = ThreadSafeReference(to: config)
        }
        
        Database.realmQueue.sync {
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
    
    public func deleteServerConnectionConfig(id: String) {
        Database.realmQueue.sync {
            let config = instance.object(ofType: ServerConnectionConfig.self, forPrimaryKey: id)
            
            do {
                try instance.write {
                    if config != nil {
                        instance.delete(config!)
                    }
                }
            } catch(let exception) {
                NSLog("failed to delete server config")
                debugPrint(exception)
            }
        }
    }
    
    public func getServerConnectionConfigs() -> [ServerConnectionConfig] {
        var refrences: [ThreadSafeReference<ServerConnectionConfig>] = []
        
        Database.realmQueue.sync {
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
    
    public func setLastActiveConfigIndexToNil() {
        Database.realmQueue.sync {
            setLastActiveConfigIndex(index: nil)
        }
    }
    
    public func setLastActiveConfigIndex(index: Int?) {
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
    
    public func getLastActiveConfigIndex() -> Int? {
        return Database.realmQueue.sync {
            return instance.objects(ServerConnectionConfigActiveIndex.self).first?.index ?? nil
        }
    }
    
    public func setDeviceSettings(deviceSettings: DeviceSettings) {
        Database.realmQueue.sync {
            let existing = instance.objects(DeviceSettings.self)

            do {
                try instance.write {
                    instance.delete(existing)
                    instance.add(deviceSettings)
                }
            } catch(let exception) {
                NSLog("failed to save device settings")
            }
        }
    }
    
    public func getLocalLibraryItems(mediaType: MediaType? = nil) -> [LocalLibraryItem] {
        var localLibraryItems: [ThreadSafeReference<LocalLibraryItem>] = []
        
        Database.realmQueue.sync {
            let items = instance.objects(LocalLibraryItem.self)
            localLibraryItems = items.map { item in
                return ThreadSafeReference(to: item)
            }
        }
        
        do {
            let realm = try Realm()
            
            return localLibraryItems.map { item in
                return realm.resolve(item)!
            }
        } catch(let exception) {
            NSLog("error while readling local library items")
            debugPrint(exception)
            return []
        }
    }
    
    public func getLocalLibraryItemByLLId(libraryItem: String) -> LocalLibraryItem? {
        let items = getLocalLibraryItems()
        for item in items {
            if (item.libraryItemId == libraryItem) {
                return item
            }
        }
        NSLog("Local library item with id \(libraryItem) not found")
        return nil
    }
    
    public func getLocalLibraryItem(localLibraryItem: String) -> LocalLibraryItem? {
        let items = getLocalLibraryItems()
        for item in items {
            if (item.id == localLibraryItem) {
                return item
            }
        }
        NSLog("Local library item with id \(localLibraryItem) not found")
        return nil
    }
    
    public func saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
        Database.realmQueue.sync {
            do {
                try instance.write {
                    instance.add(localLibraryItem);
                }
            } catch(let exception) {
                NSLog("Unable to save local library item")
                debugPrint(exception)
            }
        }
    }
    
    public func getDeviceSettings() -> DeviceSettings {
        return Database.realmQueue.sync {
            return instance.objects(DeviceSettings.self).first ?? getDefaultDeviceSettings()
        }
    }
    
    public func removeLocalLibraryItem(localLibraryItemId: String) {
        let item = getLocalLibraryItemByLLId(libraryItem: localLibraryItemId)
        Database.realmQueue.sync {
            do {
                try instance.write {
                    if item != nil {
                        instance.delete(item!)
                    } else {
                        NSLog("Unable to find local library item to delete")
                    }
                }
            } catch (let exception) {
                NSLog("Unable to delete local library item")
                debugPrint(exception)
            }
        }
    }
}
