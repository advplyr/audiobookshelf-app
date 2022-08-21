//
//  Library.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class Library: EmbeddedObject, Codable {
    @Persisted var id: String = ""
    @Persisted var name: String = "Unknown"
    @Persisted var folders = List<Folder>()
    @Persisted var icon: String = ""
    @Persisted var mediaType: String = ""
    
    private enum CodingKeys : String, CodingKey {
        case id, name, folders, icon, mediaType
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        if let folderList = try? values.decode([Folder].self, forKey: .folders) {
            folders.append(objectsIn: folderList)
        }
        icon = try values.decode(String.self, forKey: .icon)
        mediaType = try values.decode(String.self, forKey: .mediaType)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(folders, forKey: .folders)
        try container.encode(icon, forKey: .icon)
        try container.encode(mediaType, forKey: .mediaType)
    }
}
