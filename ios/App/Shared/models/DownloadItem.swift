//
//  DownloadItem.swift
//  App
//
//  Created by Ron Heft on 8/5/22.
//

import Foundation
import RealmSwift

class DownloadItem: Object, Codable {
    @Persisted(primaryKey: true) var id: String?
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

class DownloadItemPart: Object, Codable {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted var filename: String?
    @Persisted var itemTitle: String?
    @Persisted var serverPath: String?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var episode: PodcastEpisode?
    @Persisted var completed: Bool = false
    @Persisted var moved: Bool = false
    @Persisted var failed: Bool = false
    @Persisted var uri: String?
    @Persisted var destinationUri: String?
    @Persisted var progress: Double = 0
    var task: URLSessionDownloadTask!
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, itemTitle, completed, moved, failed, progress
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        filename = try? values.decode(String.self, forKey: .filename)
        itemTitle = try? values.decode(String.self, forKey: .itemTitle)
        completed = try values.decode(Bool.self, forKey: .completed)
        moved = try values.decode(Bool.self, forKey: .moved)
        failed = try values.decode(Bool.self, forKey: .failed)
        progress = try values.decode(Double.self, forKey: .progress)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(filename, forKey: .filename)
        try container.encode(itemTitle, forKey: .itemTitle)
        try container.encode(completed, forKey: .completed)
        try container.encode(moved, forKey: .moved)
        try container.encode(failed, forKey: .failed)
        try container.encode(progress, forKey: .progress)
    }
}
