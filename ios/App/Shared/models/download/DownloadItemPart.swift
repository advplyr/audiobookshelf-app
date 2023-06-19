//
//  DownloadItemPart.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class DownloadItemPart: Object, Codable {
    @Persisted(primaryKey: true) var id = ""
    @Persisted var downloadItemId: String?
    @Persisted var filename: String?
    @Persisted var fileSize: Double = 0
    @Persisted var itemTitle: String?
    @Persisted var serverPath: String?
    @Persisted var audioTrack: AudioTrack?
    @Persisted var episode: PodcastEpisode?
    @Persisted var ebookFile: EBookFile?
    @Persisted var completed: Bool = false
    @Persisted var moved: Bool = false
    @Persisted var failed: Bool = false
    @Persisted var uri: String?
    @Persisted var destinationUri: String?
    @Persisted var progress: Double = 0
    @Persisted var bytesDownloaded: Double = 0
    
    private enum CodingKeys : String, CodingKey {
        case id, downloadItemId, filename, fileSize, itemTitle, completed, moved, failed, progress, bytesDownloaded
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        downloadItemId = try? values.decode(String.self, forKey: .downloadItemId)
        filename = try? values.decode(String.self, forKey: .filename)
        fileSize = try values.decode(Double.self, forKey: .fileSize)
        itemTitle = try? values.decode(String.self, forKey: .itemTitle)
        completed = try values.decode(Bool.self, forKey: .completed)
        moved = try values.decode(Bool.self, forKey: .moved)
        failed = try values.decode(Bool.self, forKey: .failed)
        progress = try values.decode(Double.self, forKey: .progress)
        bytesDownloaded = try values.decode(Double.self, forKey: .bytesDownloaded)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(downloadItemId, forKey: .downloadItemId)
        try container.encode(filename, forKey: .filename)
        try container.encode(fileSize, forKey: .fileSize)
        try container.encode(itemTitle, forKey: .itemTitle)
        try container.encode(completed, forKey: .completed)
        try container.encode(moved, forKey: .moved)
        try container.encode(failed, forKey: .failed)
        try container.encode(progress, forKey: .progress)
        try container.encode(bytesDownloaded, forKey: .bytesDownloaded)
    }
}

extension DownloadItemPart {
    convenience init(downloadItemId: String, filename: String, destination: String, itemTitle: String, serverPath: String, audioTrack: AudioTrack?, episode: PodcastEpisode?, ebookFile: EBookFile?, size: Double) {
        self.init()
        
        self.id = destination.toBase64()
        self.downloadItemId = downloadItemId
        self.filename = filename
        self.fileSize = size
        self.itemTitle = itemTitle
        self.serverPath = serverPath
        self.audioTrack = AudioTrack.detachCopy(of: audioTrack)
        self.episode = PodcastEpisode.detachCopy(of: episode)
        self.ebookFile = EBookFile.detachCopy(of: ebookFile)
        
        let config = Store.serverConfig!
        var downloadUrl = "\(config.address)\(serverPath)?token=\(config.token)"
        if (serverPath.hasSuffix("/cover")) {
            downloadUrl += "&format=jpeg" // For cover images force to jpeg
        }
        self.uri = downloadUrl
        self.destinationUri = destination
    }
    
    var downloadURL: URL? {
        if let uri = self.uri {
            return URL(string: uri)
        } else {
            return nil
        }
    }
    
    var destinationURL: URL? {
        if let destinationUri = self.destinationUri {
            return AbsDownloader.itemDownloadFolder(path: destinationUri)
        } else {
            return nil
        }
    }
    
    func mimeType() -> String? {
        audioTrack?.mimeType ?? episode?.audioTrack?.mimeType ?? ebookFile?.mimeType()
    }
}
