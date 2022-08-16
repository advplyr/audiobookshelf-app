//
//  LocalFile.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class LocalFile: Object, Codable {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted var filename: String?
    @Persisted var _contentUrl: String = ""
    @Persisted var mimeType: String?
    @Persisted var size: Int = 0

    var contentUrl: String { AbsDownloader.itemDownloadFolder(path: _contentUrl)!.absoluteString }
    var contentPath: URL { AbsDownloader.itemDownloadFolder(path: _contentUrl)! }
    var basePath: String? { self.filename }
    
    private enum CodingKeys : String, CodingKey {
        case id, filename, contentUrl, mimeType, size, basePath
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        filename = try? values.decode(String.self, forKey: .filename)
        mimeType = try? values.decode(String.self, forKey: .mimeType)
        size = try values.decode(Int.self, forKey: .size)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(filename, forKey: .filename)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(mimeType, forKey: .mimeType)
        try container.encode(size, forKey: .size)
        try container.encode(basePath, forKey: .basePath)
    }
}

extension LocalFile {
    convenience init(_ libraryItemId: String, _ filename: String, _ mimeType: String, _ localUrl: String, fileSize: Int) {
        self.init()
        
        self.id = "\(libraryItemId)_\(filename.toBase64())"
        self.filename = filename
        self.mimeType = mimeType
        self._contentUrl = localUrl
        self.size = fileSize
    }
    
    var absolutePath: String {
        return AbsDownloader.itemDownloadFolder(path: self._contentUrl)?.absoluteString ?? ""
    }
    
    func isAudioFile() -> Bool {
        switch self.mimeType {
            case "application/octet-stream",
                "video/mp4":
                return true
            default:
                return self.mimeType?.starts(with: "audio") ?? false
        }
    }
}
