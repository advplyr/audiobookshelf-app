//
//  LibraryItem.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class LibraryItem: Object, Codable {
    @Persisted var id: String = ""
    @Persisted var ino: String = ""
    @Persisted var libraryId: String = ""
    @Persisted var folderId: String = ""
    @Persisted var path: String = ""
    @Persisted var relPath: String = ""
    @Persisted var isFile: Bool = true
    @Persisted var mtimeMs: Int = 0
    @Persisted var ctimeMs: Int = 0
    @Persisted var birthtimeMs: Int = 0
    @Persisted var addedAt: Int = 0
    @Persisted var updatedAt: Int = 0
    @Persisted var lastScan: Int?
    @Persisted var scanVersion: String?
    @Persisted var isMissing: Bool = false
    @Persisted var isInvalid: Bool = false
    @Persisted var mediaType: String = ""
    @Persisted var media: MediaType?
    @Persisted var libraryFiles = List<LibraryFile>()
    @Persisted var userMediaProgress: MediaProgress?
    
    private enum CodingKeys : String, CodingKey {
        case id, ino, libraryId, folderId, path, relPath, isFile, mtimeMs, ctimeMs, birthtimeMs, addedAt, updatedAt, lastScan, scanVersion, isMissing, isInvalid, mediaType, media, libraryFiles, userMediaProgress
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        ino = try values.decode(String.self, forKey: .ino)
        libraryId = try values.decode(String.self, forKey: .libraryId)
        folderId = try values.decode(String.self, forKey: .folderId)
        path = try values.decode(String.self, forKey: .path)
        relPath = try values.decode(String.self, forKey: .relPath)
        isFile = try values.decode(Bool.self, forKey: .isFile)
        mtimeMs = try values.decode(Int.self, forKey: .mtimeMs)
        ctimeMs = try values.decode(Int.self, forKey: .ctimeMs)
        birthtimeMs = try values.decode(Int.self, forKey: .birthtimeMs)
        addedAt = try values.decode(Int.self, forKey: .addedAt)
        updatedAt = try values.decode(Int.self, forKey: .updatedAt)
        lastScan = try? values.decode(Int.self, forKey: .lastScan)
        scanVersion = try? values.decode(String.self, forKey: .scanVersion)
        isMissing = try values.decode(Bool.self, forKey: .isMissing)
        isInvalid = try values.decode(Bool.self, forKey: .isInvalid)
        mediaType = try values.decode(String.self, forKey: .mediaType)
        media = try? values.decode(MediaType.self, forKey: .media)
        if let files = try? values.decode([LibraryFile].self, forKey: .libraryFiles) {
            libraryFiles.append(objectsIn: files)
        }
        userMediaProgress = try? values.decode(MediaProgress.self, forKey: .userMediaProgress)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(ino, forKey: .ino)
        try container.encode(libraryId, forKey: .libraryId)
        try container.encode(folderId, forKey: .folderId)
        try container.encode(path, forKey: .path)
        try container.encode(relPath, forKey: .relPath)
        try container.encode(isFile, forKey: .isFile)
        try container.encode(mtimeMs, forKey: .mtimeMs)
        try container.encode(ctimeMs, forKey: .ctimeMs)
        try container.encode(birthtimeMs, forKey: .birthtimeMs)
        try container.encode(addedAt, forKey: .addedAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(lastScan, forKey: .lastScan)
        try container.encode(scanVersion, forKey: .scanVersion)
        try container.encode(isMissing, forKey: .isMissing)
        try container.encode(isInvalid, forKey: .isInvalid)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(media, forKey: .media)
        try container.encode(Array(libraryFiles), forKey: .libraryFiles)
        try container.encode(userMediaProgress, forKey: .userMediaProgress)
    }
}
