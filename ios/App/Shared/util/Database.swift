//
//  Database.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Database {
    public static var shared = {
        return Database()
    }()
    
    private let logger = AppLogger(category: "Database")

    private init() {}
    
    public func setServerConnectionConfig(config: ServerConnectionConfig) {
        let config = config
        let realm = try! Realm()
        let existing: ServerConnectionConfig? = realm.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
        
        if let existing = existing {
            do {
                try existing.update {
                    existing.name = config.name
                    existing.address = config.address
                    existing.userId = config.userId
                    existing.username = config.username
                    existing.token = config.token
                }
            } catch {
                logger.error("failed to update server config")
                debugPrint(error)
            }
            
            setLastActiveConfigIndex(index: existing.index)
        } else {
            if config.index == 0 {
                let lastConfig: ServerConnectionConfig? = realm.objects(ServerConnectionConfig.self).last
                
                if lastConfig != nil {
                    config.index = lastConfig!.index + 1
                } else {
                    config.index = 1
                }
            }
            
            do {
                try realm.write {
                    realm.add(config)
                }
            } catch(let exception) {
                logger.error("failed to save server config")
                debugPrint(exception)
            }
            
            setLastActiveConfigIndex(index: config.index)
        }
    }
    
    public func deleteServerConnectionConfig(id: String) {
        let realm = try! Realm()
        let config = realm.object(ofType: ServerConnectionConfig.self, forPrimaryKey: id)
        
        do {
            try realm.write {
                if config != nil {
                    realm.delete(config!)
                }
            }
        } catch(let exception) {
            logger.error("failed to delete server config")
            debugPrint(exception)
        }
    }
    
    public func getServerConnectionConfigs() -> [ServerConnectionConfig] {
        let realm = try! Realm()
        return Array(realm.objects(ServerConnectionConfig.self))
    }
    
    public func setLastActiveConfigIndexToNil() {
        setLastActiveConfigIndex(index: nil)
    }
    
    private func setLastActiveConfigIndex(index: Int?) {
        let realm = try! Realm()
        do {
            try realm.write {
                let existing = realm.objects(ServerConnectionConfigActiveIndex.self).last
                
                if ( existing?.index != index ) {
                    if let existing = existing {
                        realm.delete(existing)
                    }
                    
                    let activeConfig = ServerConnectionConfigActiveIndex()
                    activeConfig.index = index
                    realm.add(activeConfig)
                }
            }
        } catch(let exception) {
            logger.error("failed to save server config active index")
            debugPrint(exception)
        }
    }
    
    public func getLastActiveConfigIndex() -> Int? {
        let realm = try! Realm()
        return realm.objects(ServerConnectionConfigActiveIndex.self).first?.index ?? nil
    }
    
    public func setDeviceSettings(deviceSettings: DeviceSettings) {
        let realm = try! Realm()
        let existing = realm.objects(DeviceSettings.self)

        do {
            try realm.write {
                realm.delete(existing)
                realm.add(deviceSettings)
            }
        } catch {
            logger.error("failed to save device settings")
        }
    }
    
    public func getLocalLibraryItems(mediaType: MediaType? = nil) -> [LocalLibraryItem] {
        do {
            let realm = try Realm()
            return Array(realm.objects(LocalLibraryItem.self))
        } catch {
            debugPrint(error)
            return []
        }
    }
    
    public func getLocalLibraryItem(byServerLibraryItemId: String) -> LocalLibraryItem? {
        do {
            let realm = try Realm()
            return realm.objects(LocalLibraryItem.self).first(where: { $0.libraryItemId == byServerLibraryItemId })
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func getLocalLibraryItem(localLibraryItemId: String) -> LocalLibraryItem? {
        do {
            let realm = try Realm()
            return realm.object(ofType: LocalLibraryItem.self, forPrimaryKey: localLibraryItemId)
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) throws {
        let realm = try Realm()
        try realm.write { realm.add(localLibraryItem, update: .modified) }
    }
    
    public func getLocalFile(localFileId: String) -> LocalFile? {
        do {
            let realm = try Realm()
            return realm.object(ofType: LocalFile.self, forPrimaryKey: localFileId)
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func getDownloadItem(downloadItemId: String) -> DownloadItem? {
        do {
            let realm = try Realm()
            return realm.object(ofType: DownloadItem.self, forPrimaryKey: downloadItemId)
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func getDownloadItem(libraryItemId: String) -> DownloadItem? {
        do {
            let realm = try Realm()
            return realm.objects(DownloadItem.self).filter("libraryItemId == %@", libraryItemId).first
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func getDownloadItem(downloadItemPartId: String) -> DownloadItem? {
        do {
            let realm = try Realm()
            return realm.objects(DownloadItem.self).filter("SUBQUERY(downloadItemParts, $part, $part.id == %@) .@count > 0", downloadItemPartId).first
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func saveDownloadItem(_ downloadItem: DownloadItem) throws {
        let realm = try Realm()
        return try realm.write { realm.add(downloadItem, update: .modified) }
    }
    
    public func getDeviceSettings() -> DeviceSettings {
        let realm = try! Realm()
        return realm.objects(DeviceSettings.self).first ?? getDefaultDeviceSettings()
    }
    
    public func getAllLocalMediaProgress() -> [LocalMediaProgress] {
        do {
            let realm = try Realm()
            return Array(realm.objects(LocalMediaProgress.self))
        } catch {
            debugPrint(error)
            return []
        }
    }
    
    // For books this will just be the localLibraryItemId for podcast episodes this will be "{localLibraryItemId}-{episodeId}"
    public func getLocalMediaProgress(localMediaProgressId: String) -> LocalMediaProgress? {
        do {
            let realm = try Realm()
            return realm.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
        } catch {
            debugPrint(error)
            return nil
        }
    }
    
    public func removeLocalMediaProgress(localMediaProgressId: String) throws {
        let realm = try Realm()
        try realm.write {
            let progress = realm.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
            realm.delete(progress!)
        }
    }
    
    public func getPlaybackSession(id: String) -> PlaybackSession? {
        do {
            let realm = try Realm()
            realm.refresh() // Refresh, because working with stale sessions leads to wrong times
            return realm.object(ofType: PlaybackSession.self, forPrimaryKey: id)
        } catch {
            debugPrint(error)
            return nil
        }
    }
}
