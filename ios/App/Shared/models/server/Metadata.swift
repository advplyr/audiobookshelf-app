//
//  Metadata.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class Metadata: EmbeddedObject, Codable {
    @Persisted var title: String = "Unknown"
    @Persisted var subtitle: String?
    @Persisted var authors = List<Author>()
    @Persisted var author: String? // Podcast author
    @Persisted var narrators = List<String>()
    @Persisted var genres = List<String>()
    @Persisted var publishedYear: String?
    @Persisted var publishedDate: String?
    @Persisted var publisher: String?
    @Persisted var desc: String?
    @Persisted var isbn: String?
    @Persisted var asin: String?
    @Persisted var language: String?
    @Persisted var explicit: Bool = false
    @Persisted var authorName: String?
    @Persisted var authorNameLF: String?
    @Persisted var narratorName: String?
    @Persisted var seriesName: String?
    @Persisted var feedUrl: String?
    
    var authorDisplayName: String { self.authorName ?? self.author ?? "Unknown" }
    
    private enum CodingKeys : String, CodingKey {
        case title,
             subtitle,
             authors,
             author, // Podcast author
             narrators,
             genres,
             publishedYear,
             publishedDate,
             publisher,
             desc = "description", // Fixes a collision with the base Swift object's field "description"
             isbn,
             asin,
             language,
             explicit,
             authorName,
             authorNameLF,
             narratorName,
             seriesName,
             feedUrl
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        let values = try decoder.container(keyedBy: CodingKeys.self)
        title = try values.decode(String.self, forKey: .title)
        subtitle = try? values.decode(String.self, forKey: .subtitle)
        author = try? values.decode(String.self, forKey: .author) // Podcast author
        if let authorList = try? values.decode([Author].self, forKey: .authors) {
            authors.append(objectsIn: authorList)
        }
        if let narratorList = try? values.decode([String].self, forKey: .narrators) {
            narrators.append(objectsIn: narratorList)
        }
        if let genreList = try? values.decode([String].self, forKey: .genres) {
            genres.append(objectsIn: genreList)
        }
        publishedYear = try? values.decode(String.self, forKey: .publishedYear)
        publishedDate = try? values.decode(String.self, forKey: .publishedDate)
        publisher = try? values.decode(String.self, forKey: .publisher)
        desc = try? values.decode(String.self, forKey: .desc)
        isbn = try? values.decode(String.self, forKey: .isbn)
        asin = try? values.decode(String.self, forKey: .asin)
        language = try? values.decode(String.self, forKey: .language)
        explicit = try values.decode(Bool.self, forKey: .explicit)
        authorName = try? values.decode(String.self, forKey: .authorName)
        authorNameLF = try? values.decode(String.self, forKey: .authorNameLF)
        narratorName = try? values.decode(String.self, forKey: .narratorName)
        seriesName = try? values.decode(String.self, forKey: .seriesName)
        feedUrl = try? values.decode(String.self, forKey: .feedUrl)
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(subtitle, forKey: .subtitle)
        try container.encode(author, forKey: .author) // Podcast author
        try container.encode(Array(authors), forKey: .authors)
        try container.encode(Array(narrators), forKey: .narrators)
        try container.encode(Array(genres), forKey: .genres)
        try container.encode(publishedYear, forKey: .publishedYear)
        try container.encode(publishedDate, forKey: .publishedDate)
        try container.encode(publisher, forKey: .publisher)
        try container.encode(desc, forKey: .desc)
        try container.encode(isbn, forKey: .isbn)
        try container.encode(asin, forKey: .asin)
        try container.encode(language, forKey: .language)
        try container.encode(explicit, forKey: .explicit)
        try container.encode(authorName, forKey: .authorName)
        try container.encode(authorNameLF, forKey: .authorNameLF)
        try container.encode(narratorName, forKey: .narratorName)
        try container.encode(seriesName, forKey: .seriesName)
        try container.encode(feedUrl, forKey: .feedUrl)
    }
}
