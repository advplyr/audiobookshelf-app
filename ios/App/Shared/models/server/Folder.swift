//
//  Folder.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class Folder: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var fullPath: String = ""
    
    private enum CodingKeys : String, CodingKey {
        case id, fullPath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        fullPath = try values.decode(String.self, forKey: .fullPath)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(fullPath, forKey: .fullPath)
    }
}
