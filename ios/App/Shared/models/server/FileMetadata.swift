//
//  FileMetadata.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class FileMetadata: EmbeddedObject, Codable {
    @Persisted var filename: String = ""
    @Persisted var ext: String = ""
    @Persisted var path: String = ""
    @Persisted var relPath: String = ""
    @Persisted var size:Double = 0
    
    private enum CodingKeys : String, CodingKey {
        case filename, ext, path, relPath, size
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        filename = try values.decode(String.self, forKey: .filename)
        ext = try values.decode(String.self, forKey: .ext)
        path = try values.decode(String.self, forKey: .path)
        relPath = try values.decode(String.self, forKey: .relPath)
        size = try values.decode(Double.self, forKey: .size)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(filename, forKey: .filename)
        try container.encode(ext, forKey: .ext)
        try container.encode(path, forKey: .path)
        try container.encode(relPath, forKey: .relPath)
        try container.encode(size, forKey: .size)
    }
}
