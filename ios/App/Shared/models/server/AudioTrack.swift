//
//  AudioTrack.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class AudioTrack: EmbeddedObject, Codable {
    @Persisted var index: Int?
    @Persisted var startOffset: Double?
    @Persisted var duration: Double = 0
    @Persisted var title: String?
    @Persisted var contentUrl: String?
    @Persisted var mimeType: String = ""
    @Persisted var metadata: FileMetadata?
    @Persisted var localFileId: String?
    @Persisted var serverIndex: Int?
    
    private enum CodingKeys : String, CodingKey {
        case index, startOffset, duration, title, contentUrl, mimeType, metadata, localFileId, serverIndex
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        index = try? values.decode(Int.self, forKey: .index)
        startOffset = try? values.decode(Double.self, forKey: .startOffset)
        duration = try values.decode(Double.self, forKey: .duration)
        title = try? values.decode(String.self, forKey: .title)
        contentUrl = try? values.decode(String.self, forKey: .contentUrl)
        mimeType = try values.decode(String.self, forKey: .mimeType)
        metadata = try? values.decode(FileMetadata.self, forKey: .metadata)
        localFileId = try! values.decodeIfPresent(String.self, forKey: .localFileId)
        serverIndex = try? values.decode(Int.self, forKey: .serverIndex)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(index, forKey: .index)
        try container.encode(startOffset, forKey: .startOffset)
        try container.encode(duration, forKey: .duration)
        try container.encode(title, forKey: .title)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(mimeType, forKey: .mimeType)
        try container.encode(metadata, forKey: .metadata)
        try container.encode(localFileId, forKey: .localFileId)
        try container.encode(serverIndex, forKey: .serverIndex)
    }
}

extension AudioTrack {
    func setLocalInfo(filenameIdMap: [String: String], serverIndex: Int) -> Bool {
        if let localFileId = filenameIdMap[self.metadata?.filename ?? ""] {
            self.localFileId = localFileId
            self.serverIndex = serverIndex
            return true
        }
        return false
    }
    
    func getLocalFile() -> LocalFile? {
        guard let localFileId = self.localFileId else { return nil }
        return Database.shared.getLocalFile(localFileId: localFileId)
    }
}
