//
//  LocalLibraryExtensions.swift
//  App
//
//  Created by Ron Heft on 7/16/22.
//

import Foundation

extension LocalLibraryItem {
    enum CodingKeys: CodingKey {
        case id
        case basePath
        case absolutePath
        case contentUrl
        case isInvalid
        case mediaType
        case media
        case localFiles
        case coverContentUrl
        case coverAbsolutePath
        case isLocal
        case serverConnectionConfigId
        case serverAddress
        case serverUserId
        case libraryItemId
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(basePath, forKey: .basePath)
        try container.encode(absolutePath, forKey: .absolutePath)
        try container.encode(contentUrl, forKey: .contentUrl)
        try container.encode(isInvalid, forKey: .isInvalid)
        try container.encode(mediaType, forKey: .mediaType)
        //try container.encode(media, forKey: .media)
        //try container.encode(localFiles, forKey: .localFiles)
    }
    
    convenience init(_ item: LibraryItem, localUrl: URL, server: ServerConnectionConfig, files: [LocalFile]) {
        self.init()
        self.contentUrl = localUrl.absoluteString
        self.mediaType = item.mediaType
        self.media = LocalMediaType(mediaType: item.media)
        self.localFiles.append(objectsIn: files)
        // TODO: self.coverContentURL
        // TODO: self.converAbsolutePath
        self.libraryItemId = item.id
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
    }
}
