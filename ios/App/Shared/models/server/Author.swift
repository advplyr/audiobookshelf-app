//
//  Author.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class Author: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var name: String = "Unknown"
    @Persisted var coverPath: String?
    
    private enum CodingKeys : String, CodingKey {
        case id, name, coverPath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        coverPath = try? values.decode(String.self, forKey: .coverPath)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(coverPath, forKey: .coverPath)
    }
}
