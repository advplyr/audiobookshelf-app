//
//  LocalPodcastEpisode.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class LocalPodcastEpisode: Object, Codable {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted var index: Int = 0
    @Persisted var episode: String?
    @Persisted var episodeType: String?
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var desc: String?
    @Persisted var audioFile: AudioFile?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var chapters = List<Chapter>()
    @Persisted var duration: Double = 0
    @Persisted var size: Int = 0
    @Persisted(indexed: true) var serverEpisodeId: String?
    
    private enum CodingKeys : String, CodingKey {
        case id
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
    }
}


