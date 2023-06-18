//
//  PodcastEpisode.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class PodcastEpisode: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var index: Int?
    @Persisted var episode: String?
    @Persisted var episodeType: String?
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var desc: String?
    @Persisted var audioFile: AudioFile?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var chapters = List<Chapter>()
    @Persisted var duration: Double?
    @Persisted var size: Int?
    var serverEpisodeId: String { self.id }
    
    private enum CodingKeys : String, CodingKey {
        case id,
             index,
             episode,
             episodeType,
             title,
             subtitle,
             desc = "description", // Fixes a collision with the base Swift object's field "description"
             audioFile,
             audioTrack,
             chapters,
             duration,
             size,
             serverEpisodeId
    }
    
    override init() {}
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        index = try? values.decode(Int.self, forKey: .index)
        episode = try? values.decode(String.self, forKey: .episode)
        episodeType = try? values.decode(String.self, forKey: .episodeType)
        title = try values.decode(String.self, forKey: .title)
        subtitle = try? values.decode(String.self, forKey: .subtitle)
        desc = try? values.decode(String.self, forKey: .desc)
        audioFile = try? values.decode(AudioFile.self, forKey: .audioFile)
        audioTrack = try? values.decode(AudioTrack.self, forKey: .audioTrack)
        if let chapterList = try? values.decode([Chapter].self, forKey: .chapters) {
            chapters.append(objectsIn: chapterList)
        }
        duration = try? values.decode(Double.self, forKey: .duration)
        size = try? values.decode(Int.self, forKey: .size)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(index, forKey: .index)
        try container.encode(episode, forKey: .episode)
        try container.encode(episodeType, forKey: .episodeType)
        try container.encode(title, forKey: .title)
        try container.encode(subtitle, forKey: .subtitle)
        try container.encode(desc, forKey: .desc)
        try container.encode(audioFile, forKey: .audioFile)
        try container.encode(audioTrack, forKey: .audioTrack)
        try container.encode(Array(chapters), forKey: .chapters)
        try container.encode(duration, forKey: .duration)
        try container.encode(size, forKey: .size)
        try container.encode(serverEpisodeId, forKey: .serverEpisodeId)
    }
}
