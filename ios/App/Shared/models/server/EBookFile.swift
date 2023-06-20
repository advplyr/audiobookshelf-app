//
//  EBookFile.swift
//  Audiobookshelf
//
//  Created by Advplyr on 6/19/23.
//

import Foundation
import RealmSwift

class EBookFile: EmbeddedObject, Codable {
    @Persisted var ino: String = ""
    @Persisted var metadata: FileMetadata?
    @Persisted var ebookFormat: String
    @Persisted var _contentUrl: String?
    @Persisted var localFileId: String?
    
    var contentUrl: String? {
        if let path = _contentUrl {
            return AbsDownloader.itemDownloadFolder(path: path)!.absoluteString
        } else {
            return nil
        }
    }
    
    private enum CodingKeys : String, CodingKey {
        case ino, metadata, ebookFormat, contentUrl, localFileId
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        ino = try values.decode(String.self, forKey: .ino)
        metadata = try values.decode(FileMetadata.self, forKey: .metadata)
        ebookFormat = try values.decode(String.self, forKey: .ebookFormat)
        localFileId = try? values.decodeIfPresent(String.self, forKey: .localFileId)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ino, forKey: .ino)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(ebookFormat, forKey: .ebookFormat)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(localFileId, forKey: .localFileId)
    }
}

extension EBookFile {
    func setLocalInfo(localFile: LocalFile) -> Bool {
        self.localFileId = localFile.id
        self._contentUrl = localFile._contentUrl
        return false
    }
    
    func getLocalFile() -> LocalFile? {
        guard let localFileId = self.localFileId else { return nil }
        return Database.shared.getLocalFile(localFileId: localFileId)
    }
    
    func mimeType() -> String? {
        var mimeType = ""
        switch ebookFormat {
        case "epub":
            mimeType = "application/epub+zip"
        case "pdf":
            mimeType = "application/pdf"
        case "mobi":
            mimeType = "application/x-mobipocket-ebook"
        case "azw3":
            mimeType = "application/vnd.amazon.mobi8-ebook"
        case "cbr":
            mimeType = "application/vnd.comicbook-rar"
        case "cbz":
            mimeType = "application/vnd.comicbook+zip"
        default:
            mimeType = "application/epub+zip"
        }
        return mimeType
    }
}
