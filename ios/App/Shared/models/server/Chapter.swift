//
//  Chapter.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class Chapter: EmbeddedObject, Codable {
    @Persisted var id: Int = 0
    @Persisted var start: Double = 0
    @Persisted var end: Double = 0
    @Persisted var title: String?
    
    private enum CodingKeys : String, CodingKey {
        case id, start, end, title
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(Int.self, forKey: .id)
        start = try values.decode(Double.self, forKey: .start)
        end = try values.decode(Double.self, forKey: .end)
        title = try? values.decode(String.self, forKey: .title)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(start, forKey: .start)
        try container.encode(end, forKey: .end)
        try container.encode(title, forKey: .title)
    }
}
