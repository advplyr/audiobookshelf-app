//
//  MediaType.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class MediaType: EmbeddedObject, Codable {
    @Persisted var libraryItemId: String?
    @Persisted var metadata: Metadata?
    @Persisted var coverPath: String?
    @Persisted var tags = List<String>()
    @Persisted var audioFiles = List<AudioFile>()
    @Persisted var chapters = List<Chapter>()
    @Persisted var tracks = List<AudioTrack>()
    @Persisted var size: Int?
    @Persisted var duration: Double?
    @Persisted var episodes = List<PodcastEpisode>()
    @Persisted var autoDownloadEpisodes: Bool?
    
    private enum CodingKeys : String, CodingKey {
        case libraryItemId, metadata, coverPath, tags, audioFiles, chapters, tracks, size, duration, episodes, autoDownloadEpisodes
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
        metadata = try? values.decode(Metadata.self, forKey: .metadata)
        coverPath = try? values.decode(String.self, forKey: .coverPath)
        if let tagList = try? values.decode([String].self, forKey: .tags) {
            tags.append(objectsIn: tagList)
        }
        if let fileList = try? values.decode([AudioFile].self, forKey: .audioFiles) {
            audioFiles.append(objectsIn: fileList)
        }
        if let chapterList = try? values.decode([Chapter].self, forKey: .chapters) {
            chapters.append(objectsIn: chapterList)
        }
        if let trackList = try? values.decode([AudioTrack].self, forKey: .tracks) {
            tracks.append(objectsIn: trackList)
        }
        size = try? values.decode(Int.self, forKey: .size)
        duration = try? values.decode(Double.self, forKey: .duration)
        if let episodeList = try? values.decode([PodcastEpisode].self, forKey: .episodes) {
            episodes.append(objectsIn: episodeList)
        }
        autoDownloadEpisodes = try? values.decode(Bool.self, forKey: .autoDownloadEpisodes)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(coverPath, forKey: .coverPath)
        try container.encode(Array(tags), forKey: .tags)
        try container.encode(Array(audioFiles), forKey: .audioFiles)
        try container.encode(Array(chapters), forKey: .chapters)
        try container.encode(Array(tracks), forKey: .tracks)
        try container.encode(size, forKey: .size)
        try container.encode(duration, forKey: .duration)
        try container.encode(Array(episodes), forKey: .episodes)
        try container.encode(autoDownloadEpisodes, forKey: .autoDownloadEpisodes)
    }
}
