//
//  User.swift
//  Audiobookshelf
//
//  Created by advplyr on 11/12/23.
//

import Foundation
import RealmSwift

class User: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var username: String = ""
    @Persisted var mediaProgress = List<MediaProgress>()
    
    private enum CodingKeys : String, CodingKey {
        case id, username, mediaProgress
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        username = try values.decode(String.self, forKey: .username)
        if let progresses = try? values.decode([MediaProgress].self, forKey: .mediaProgress) {
            mediaProgress.append(objectsIn: progresses)
        }
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(username, forKey: .username)
        try container.encode(Array(mediaProgress), forKey: .mediaProgress)
    }
}
