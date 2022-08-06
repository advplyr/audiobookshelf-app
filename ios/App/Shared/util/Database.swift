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
        var config = config
        Database.realmQueue.sync {
            var existing: ServerConnectionConfig? = instance.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
            
            if config.index == 0 {
                var lastConfig: ServerConnectionConfig? = instance.objects(ServerConnectionConfig.self).last
                
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
                    instance.add(config)
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
        Database.realmQueue.sync {
            return Array(instance.objects(ServerConnectionConfig.self))
        }
    }
    
    public func setLastActiveConfigIndexToNil() {
        Database.realmQueue.sync {
            setLastActiveConfigIndex(index: nil)
        }
    }
    
    private func setLastActiveConfigIndex(index: Int?) {
        do {
            try instance.write {
                var existing = instance.objects(ServerConnectionConfigActiveIndex.self).last ?? ServerConnectionConfigActiveIndex(index: index)
                existing.index = index
                instance.add(existing, update: .modified)
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
        Database.realmQueue.sync {
            Array(instance.objects(LocalLibraryItem.self))
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
        Database.realmQueue.sync {
            instance.object(ofType: LocalLibraryItem.self, forPrimaryKey: localLibraryItem)
        }
    }
    
    public func saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
        Database.realmQueue.sync {
            try! instance.write { instance.add(localLibraryItem) }
        }
    }
    
    public func getDownloadItem(downloadItemId: String) -> DownloadItem? {
        Database.realmQueue.sync {
            instance.object(ofType: DownloadItem.self, forPrimaryKey: downloadItemId)
        }
    }
    
    public func getDownloadItem(libraryItemId: String) -> DownloadItem? {
        Database.realmQueue.sync {
            instance.objects(DownloadItem.self).filter("libraryItemId == %@", libraryItemId).first
        }
    }
    
    public func getDownloadItem(downloadItemPartId: String) -> DownloadItem? {
        Database.realmQueue.sync {
            instance.objects(DownloadItem.self).filter("SUBQUERY(downloadItemParts, $part, $part.id == %@) .@count > 0", downloadItemPartId).first
        }
    }
    
    public func saveDownloadItem(_ downloadItem: DownloadItem) {
        Database.realmQueue.sync {
            try! instance.write { instance.add(downloadItem) }
        }
    }
    
    public func getDeviceSettings() -> DeviceSettings {
        return Database.realmQueue.sync {
            return instance.objects(DeviceSettings.self).first ?? getDefaultDeviceSettings()
        }
    }
    
    public func removeLocalLibraryItem(localLibraryItemId: String) {
        Database.realmQueue.sync {
            try! instance.write {
                let item = getLocalLibraryItemByLLId(libraryItem: localLibraryItemId)
                instance.delete(item!)
            }
        }
    }
    
    public func saveLocalMediaProgress(_ mediaProgress: LocalMediaProgress) {
        Database.realmQueue.sync {
            try! instance.write { instance.add(mediaProgress) }
        }
    }
    
    // For books this will just be the localLibraryItemId for podcast episodes this will be "{localLibraryItemId}-{episodeId}"
    public func getLocalMediaProgress(localMediaProgressId: String) -> LocalMediaProgress? {
        Database.realmQueue.sync {
            instance.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
        }
    }
    
    public func removeLocalMediaProgress(localMediaProgressId: String) {
        Database.realmQueue.sync {
            try! instance.write {
                let progress = instance.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
                instance.delete(progress!)
            }
        }
    }
}
