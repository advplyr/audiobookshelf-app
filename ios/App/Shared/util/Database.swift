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

    private init() {}
    
    public func setServerConnectionConfig(config: ServerConnectionConfig) {
        var config = config
        let realm = try! Realm()
        let existing: ServerConnectionConfig? = realm.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
        
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
                if existing != nil {
                    realm.delete(existing!)
                }
                realm.add(config)
            }
        } catch(let exception) {
            NSLog("failed to save server config")
            debugPrint(exception)
        }
        
        setLastActiveConfigIndex(index: config.index)
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
            NSLog("failed to delete server config")
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
                var existing = realm.objects(ServerConnectionConfigActiveIndex.self).last ?? ServerConnectionConfigActiveIndex(index: index)
                existing.index = index
                realm.add(existing, update: .modified)
            }
        } catch(let exception) {
            NSLog("failed to save server config active index")
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
            NSLog("failed to save device settings")
        }
    }
    
    public func getLocalLibraryItems(mediaType: MediaType? = nil) -> [LocalLibraryItem] {
        let realm = try! Realm()
        return Array(realm.objects(LocalLibraryItem.self))
    }
    
    public func getLocalLibraryItem(byServerLibraryItemId: String) -> LocalLibraryItem? {
        let realm = try! Realm()
        return realm.objects(LocalLibraryItem.self).first(where: { $0.libraryItemId == byServerLibraryItemId })
    }
    
    public func getLocalLibraryItem(localLibraryItemId: String) -> LocalLibraryItem? {
        let realm = try! Realm()
        return realm.object(ofType: LocalLibraryItem.self, forPrimaryKey: localLibraryItemId)
    }
    
    public func saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
        let realm = try! Realm()
        try! realm.write { realm.add(localLibraryItem, update: .modified) }
    }
    
    public func removeLocalLibraryItem(localLibraryItemId: String) {
        let realm = try! Realm()
        try! realm.write {
            let item = getLocalLibraryItem(localLibraryItemId: localLibraryItemId)
            realm.delete(item!)
        }
    }
    
    public func getLocalFile(localFileId: String) -> LocalFile? {
        let realm = try! Realm()
        return realm.object(ofType: LocalFile.self, forPrimaryKey: localFileId)
    }
    
    public func getDownloadItem(downloadItemId: String) -> DownloadItem? {
        let realm = try! Realm()
        return realm.object(ofType: DownloadItem.self, forPrimaryKey: downloadItemId)
    }
    
    public func getDownloadItem(libraryItemId: String) -> DownloadItem? {
        let realm = try! Realm()
        return realm.objects(DownloadItem.self).filter("libraryItemId == %@", libraryItemId).first
    }
    
    public func getDownloadItem(downloadItemPartId: String) -> DownloadItem? {
        let realm = try! Realm()
        return realm.objects(DownloadItem.self).filter("SUBQUERY(downloadItemParts, $part, $part.id == %@) .@count > 0", downloadItemPartId).first
    }
    
    public func saveDownloadItem(_ downloadItem: DownloadItem) {
        let realm = try! Realm()
        return try! realm.write { realm.add(downloadItem, update: .modified) }
    }
    
    public func updateDownloadItemPart(_ part: DownloadItemPart) {
        let realm = try! Realm()
        return try! realm.write { realm.add(part, update: .modified) }
    }
    
    public func removeDownloadItem(_ downloadItem: DownloadItem) {
        let realm = try! Realm()
        return try! realm.write { realm.delete(downloadItem) }
    }
    
    public func getDeviceSettings() -> DeviceSettings {
        let realm = try! Realm()
        return realm.objects(DeviceSettings.self).first ?? getDefaultDeviceSettings()
    }
    
    public func getAllLocalMediaProgress() -> [LocalMediaProgress] {
        let realm = try! Realm()
        return Array(realm.objects(LocalMediaProgress.self))
    }
    
    public func saveLocalMediaProgress(_ mediaProgress: LocalMediaProgress) {
        let realm = try! Realm()
        try! realm.write { realm.add(mediaProgress, update: .modified) }
    }
    
    // For books this will just be the localLibraryItemId for podcast episodes this will be "{localLibraryItemId}-{episodeId}"
    public func getLocalMediaProgress(localMediaProgressId: String) -> LocalMediaProgress? {
        let realm = try! Realm()
        return realm.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
    }
    
    public func removeLocalMediaProgress(localMediaProgressId: String) {
        let realm = try! Realm()
        try! realm.write {
            let progress = realm.object(ofType: LocalMediaProgress.self, forPrimaryKey: localMediaProgressId)
            realm.delete(progress!)
        }
    }
}
