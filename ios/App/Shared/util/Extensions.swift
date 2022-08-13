//
//  Extensions.swift
//  App
//
//  Created by Rasmus Krämer on 14.04.22.
//

import Foundation
import RealmSwift
import Capacitor

extension String: Error {}

typealias Dictionaryable = Encodable

extension Encodable {
    func asDictionary() throws -> [String: Any] {
        let data = try JSONEncoder().encode(self)
        guard let dictionary = try JSONSerialization.jsonObject(with: data, options: .allowFragments) as? [String: Any] else {
            throw NSError()
        }
        return dictionary
    }
}

extension Collection where Iterator.Element: Encodable {
    func asDictionaryArray() throws -> [[String: Any]] {
        return try self.enumerated().map() {
            i, element -> [String: Any] in try element.asDictionary()
        }
    }
}

extension CAPPluginCall {
    func getJson<T: Decodable>(_ key: String, type: T.Type) -> T? {
        guard let value = getString(key) else { return nil }
        guard let valueData = value.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(type, from: valueData)
    }
}

extension DispatchQueue {
    static func runOnMainQueue(callback: @escaping (() -> Void)) {
        if Thread.isMainThread {
            callback()
        } else {
            DispatchQueue.main.sync {
                callback()
            }
        }
    }
}

extension URL {
    var attributes: [FileAttributeKey : Any]? {
        do {
            return try FileManager.default.attributesOfItem(atPath: path)
        } catch let error as NSError {
            print("FileAttribute error: \(error)")
        }
        return nil
    }

    var fileSize: Int64 {
        return attributes?[.size] as? Int64 ?? Int64(0)
    }

    var fileSizeString: String {
        return ByteCountFormatter.string(fromByteCount: Int64(fileSize), countStyle: .file)
    }

    var creationDate: Date? {
        return attributes?[.creationDate] as? Date
    }
}
