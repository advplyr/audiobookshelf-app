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

    private init() {
      do {
        try cleanExpiredLogs()
      } catch {
          debugPrint(error)
      }
    }
    
    public func setServerConnectionConfig(config: ServerConnectionConfig) {
        let config = config
        let realm = try! Realm()
        let existing: ServerConnectionConfig? = realm.object(ofType: ServerConnectionConfig.self, forPrimaryKey: config.id)
        
        if let existing = existing {
            do {
                try existing.update {
                    existing.name = config.name
                    existing.address = config.address
                    existing.version = config.version
                    existing.userId = config.userId
                    existing.username = config.username
                    existing.token = config.token
                }
            } catch {
                AbsLogger.error("setServerConn", message: "failed to update server config")
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
                AbsLogger.error(message: "failed to save server config")
                debugPrint(exception)
            }
            
            setLastActiveConfigIndex(index: config.index)
        }
    }

    public func updateServerConnectionConfigToken(newToken: String) {
        do {
            let realm = try Realm()
            if let config = realm.objects(ServerConnectionConfig.self).first(where: { $0.index == getLastActiveConfigIndex() }) {
                try realm.write {
                    config.token = newToken
                }
            }
        } catch {
            debugPrint("Failed to update server connection config token: \(error)")
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
            AbsLogger.error(message: "failed to delete server config")
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
            AbsLogger.error(message: "failed to save server config active index")
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
            AbsLogger.error(message: "failed to save device settings")
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
    
    public func getAllPlaybackSessions() -> [PlaybackSession] {
        do {
            let realm = try Realm()
            return Array(realm.objects(PlaybackSession.self))
        } catch {
            debugPrint(error)
            return []
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
    
    public func saveLog(_ log: LogEntry) throws {
        let realm = try Realm()
        return try realm.write { realm.add(log) }
    }
    
    // Bounded read for the log viewer. The log table can hold a large number of very frequent entries;
    // loading, JSON-encoding and rendering all of them hangs the viewer (and blocks the plugin queue so
    // even Clear Logs can't run). Return only the most recent entries, in chronological order.
    static let maxLogsReturned = 500

    public func getAllLogs() -> [LogEntry] {
        do {
            let realm = try Realm()
            // Take the most recent N and detach them from Realm (thread-independent copies), matching
            // Results.toArray(). Live Realm objects can't be JSON-encoded off their realm/thread, which
            // is what asDictionaryArray() does. Reverse to chronological (oldest -> newest) for the viewer.
            let recent: [LogEntry] = realm.objects(LogEntry.self)
                .sorted(byKeyPath: "timestamp", ascending: false)
                .prefix(Database.maxLogsReturned)
                .map { $0.detached() }
            return Array(recent.reversed())
        } catch {
            debugPrint(error)
            return []
        }
    }
    
    public func clearLogs() throws {
        do {
            let realm = try! Realm()
            try realm.write {
                realm.objects(LogEntry.self).forEach { log in
                    realm.delete(log)
                }
            }
        } catch {
            AbsLogger.error(message: "\(error)", error: error)
            throw error
        }
    }
    
    private func cleanExpiredLogs() throws {
        let realm = try Realm()
        let numberOfHoursToKeep = 48
        let maxLogsToKeep = 1000
        let keepLogCutoff = Int(Date().addingTimeInterval(TimeInterval(-1 * numberOfHoursToKeep * 3600)).timeIntervalSince1970)

        var logsRemoved = 0
        // Query the full table directly (getAllLogs is intentionally bounded and can't be used here).
        try? realm.write {
            // Remove logs older than the retention window (batch delete via query, not one-by-one).
            let expired = realm.objects(LogEntry.self).filter("timestamp < %@", NSNumber(value: keepLogCutoff))
            logsRemoved += expired.count
            realm.delete(expired)

            // Also cap the total stored logs by count. Within the retention window, frequent logging can
            // still accumulate far more entries than the viewer can load, so trim the oldest overflow.
            let remaining = realm.objects(LogEntry.self).sorted(byKeyPath: "timestamp", ascending: false)
            if remaining.count > maxLogsToKeep {
                let overflow = Array(remaining[maxLogsToKeep...])
                logsRemoved += overflow.count
                realm.delete(overflow)
            }
        }

        // Note: use debugPrint here, not AbsLogger.info — this runs inside Database.init, and logging
        // would re-enter Database.shared before it finishes initializing.
        if logsRemoved > 0 {
            debugPrint("cleanLogs: Removed \(logsRemoved) expired/overflow logs")
        }
    }
}
