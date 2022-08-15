//
//  Extensions.swift
//  App
//
//  Created by Rasmus Krämer on 14.04.22.
//

import Foundation
import RealmSwift
import Capacitor
import CoreMedia

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

extension KeyedDecodingContainer {
    func doubleOrStringDecoder(key: KeyedDecodingContainer<K>.Key) throws -> Double {
        do {
            return try decode(Double.self, forKey: key)
        } catch {
            let stringValue = try decode(String.self, forKey: key)
            return Double(stringValue) ?? 0.0
        }
    }
    
    func intOrStringDecoder(key: KeyedDecodingContainer<K>.Key) throws -> Int {
        do {
            return try decode(Int.self, forKey: key)
        } catch {
            let stringValue = try decode(String.self, forKey: key)
            return Int(stringValue) ?? 0
        }
    }
}

extension CAPPluginCall {
    func getJson<T: Decodable>(_ key: String, type: T.Type) -> T? {
        guard let value = getObject(key) else { return nil }
        guard let json = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode(type, from: json)
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

// MARK: - DAO Methods
extension Object {
    func save() {
        let realm = try! Realm()
        try! realm.write {
            realm.add(self, update: .modified)
        }
    }
    
    func update(handler: () -> Void?) {
        try! self.realm?.write {
            handler()
        }
    }
}
