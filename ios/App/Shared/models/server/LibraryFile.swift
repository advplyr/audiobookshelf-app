//
//  LibraryFile.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class LibraryFile: EmbeddedObject, Codable {
    @Persisted var ino: String = ""
    @Persisted var metadata: FileMetadata?
    
    private enum CodingKeys : String, CodingKey {
        case ino, metadata
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        ino = try values.decode(String.self, forKey: .ino)
        metadata = try values.decode(FileMetadata.self, forKey: .metadata)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ino, forKey: .ino)
        try container.encode(metadata, forKey: .metadata)
    }
}
