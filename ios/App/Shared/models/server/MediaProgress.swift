//
//  MediaProgress.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class MediaProgress: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var userId: String = ""
    @Persisted var libraryItemId: String = ""
    @Persisted var episodeId: String?
    @Persisted var duration: Double = 0
    @Persisted var progress: Double = 0
    @Persisted var currentTime: Double = 0
    @Persisted var isFinished: Bool = false
    @Persisted var ebookLocation: String?
    @Persisted var ebookProgress: Double?
    @Persisted var lastUpdate: Double = 0
    @Persisted var startedAt: Double = 0
    @Persisted var finishedAt: Double?
    
    private enum CodingKeys : String, CodingKey {
        case id, userId, libraryItemId, episodeId, duration, progress, currentTime, isFinished, ebookLocation, ebookProgress, lastUpdate, startedAt, finishedAt
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        userId = try values.decode(String.self, forKey: .userId)
        libraryItemId = try values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
        duration = try values.doubleOrStringDecoder(key: .duration)
        progress = try values.doubleOrStringDecoder(key: .progress)
        currentTime = try values.doubleOrStringDecoder(key: .currentTime)
        isFinished = try values.decode(Bool.self, forKey: .isFinished)
        ebookLocation = try values.decodeIfPresent(String.self, forKey: .ebookLocation)
        ebookProgress = try? values.doubleOrStringDecoder(key: .ebookProgress)
        lastUpdate = try values.doubleOrStringDecoder(key: .lastUpdate)
        startedAt = try values.doubleOrStringDecoder(key: .startedAt)
        finishedAt = try? values.doubleOrStringDecoder(key: .finishedAt)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(userId, forKey: .userId)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
        try container.encode(duration, forKey: .duration)
        try container.encode(progress, forKey: .progress)
        try container.encode(currentTime, forKey: .currentTime)
        try container.encode(isFinished, forKey: .isFinished)
        try container.encode(ebookLocation, forKey: .ebookLocation)
        try container.encode(ebookProgress, forKey: .ebookProgress)
        try container.encode(lastUpdate, forKey: .lastUpdate)
        try container.encode(startedAt, forKey: .startedAt)
        try container.encode(finishedAt, forKey: .finishedAt)
    }
}
