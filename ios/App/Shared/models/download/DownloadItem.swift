//
//  DownloadItem.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class DownloadItem: Object, Codable {
    @Persisted(primaryKey: true) var id:String?
    @Persisted(indexed: true) var libraryItemId: String?
    @Persisted var episodeId: String?
    @Persisted var userMediaProgress: MediaProgress?
    @Persisted var serverConnectionConfigId: String?
    @Persisted var serverAddress: String?
    @Persisted var serverUserId: String?
    @Persisted var mediaType: String?
    @Persisted var itemTitle: String?
    @Persisted var media: MediaType?
    @Persisted var downloadItemParts = List<DownloadItemPart>()
    
    private enum CodingKeys : String, CodingKey {
        case id, libraryItemId, episodeId, serverConnectionConfigId, serverAddress, serverUserId, mediaType, itemTitle, downloadItemParts
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try? values.decode(String.self, forKey: .id)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
        serverConnectionConfigId = try? values.decode(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try? values.decode(String.self, forKey: .serverAddress)
        serverUserId = try? values.decode(String.self, forKey: .serverUserId)
        mediaType = try? values.decode(String.self, forKey: .mediaType)
        itemTitle = try? values.decode(String.self, forKey: .itemTitle)
        if let parts = try? values.decode([DownloadItemPart].self, forKey: .downloadItemParts) {
            downloadItemParts.append(objectsIn: parts)
        }
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(itemTitle, forKey: .itemTitle)
        try container.encode(Array(downloadItemParts), forKey: .downloadItemParts)
    }
}

extension DownloadItem {
    convenience init(libraryItem: LibraryItem, episodeId: String?, server: ServerConnectionConfig) {
        self.init()
        
        self.id = libraryItem.id
        self.libraryItemId = libraryItem.id
        self.userMediaProgress = libraryItem.userMediaProgress
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        self.mediaType = libraryItem.mediaType
        self.itemTitle = libraryItem.media?.metadata?.title
        self.media = libraryItem.media
        
        if let episodeId = episodeId {
            self.id! += "-\(episodeId)"
            self.episodeId = episodeId
        }
    }
    
    func isDoneDownloading() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.completed })
    }
    
    func didDownloadSuccessfully() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.failed == false })
    }

    // Reconstruct a LibraryItem from the metadata captured when the download was queued. Used to
    // finalize a fully-downloaded item when the server can't be reached to refresh it (offline or
    // HTTP 429 rate-limiting), so the download doesn't wedge forever at "Processing...". Uses
    // detached copies so the result is safe to use off the caller's thread, like a decoded item.
    func asLibraryItem() -> LibraryItem {
        let item = LibraryItem()
        item.id = self.libraryItemId ?? self.id ?? ""
        item.mediaType = self.mediaType ?? ""
        item.media = MediaType.detachCopy(of: self.media)
        item.userMediaProgress = MediaProgress.detachCopy(of: self.userMediaProgress)
        return item
    }
    
    func delete() throws {
        try self.realm?.write {
            self.realm?.delete(self.downloadItemParts)
            self.realm?.delete(self)
        }
    }
}
