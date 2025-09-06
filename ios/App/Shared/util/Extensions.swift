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
    
    // This will be called when any @Persisted List<> is decoded
    func decode<T: Decodable>(_ type: Persisted<List<T>>.Type, forKey key: Key) throws -> Persisted<List<T>> {
        // Use decode if present, falling back to an empty list
        try decodeIfPresent(type, forKey: key) ?? Persisted<List<T>>(wrappedValue: List<T>())
    }
}

extension CAPPluginCall {
    func getJson<T: Decodable>(_ key: String, type: T.Type) -> T? {
        guard let value = getObject(key) else { return nil }
        do {
            let json = try JSONSerialization.data(withJSONObject: value)
            return try JSONDecoder().decode(type, from: json)
        } catch {
            AbsLogger.error(message: "Failed to get json for \(key)", error: error)
            return nil
        }
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

// MARK: - RealmSwift helpers
// From https://github.com/realm/realm-swift/issues/5859#issuecomment-589026869

extension Results {
    func toArray<T: Object>() -> [T] {
        var array = [T]()
        
        for i in 0 ..< self.count {
            if let result = self[i] as? T {
                array.append(result.detached())
            }
        }

        return array
    }
}

extension List {
    func toArray<T: Object>() -> [T] {
        var array = [T]()
        
        for i in 0 ..< self.count {
            if let result = self[i] as? T {
                array.append(result.detached())
            }
        }

        return array
    }
}

protocol DetachableObject: AnyObject {
    func detached() -> Self
}

extension Object: DetachableObject {
    func detached() -> Self {
        let detached = type(of: self).init()
        for property in objectSchema.properties {
            guard let value = value(forKey: property.name) else { continue }
            if property.isArray == true {
                //Realm List property support
                let detachable = value as? DetachableObject
                detached.setValue(detachable?.detached(), forKey: property.name)
            } else if property.type == .object {
                //Realm Object property support
                let detachable = value as? DetachableObject
                detached.setValue(detachable?.detached(), forKey: property.name)
            } else {
                detached.setValue(value, forKey: property.name)
            }
        }
        return detached
    }
}

extension List: DetachableObject {
    func detached() -> List<Element> {
        let result = List<Element>()

        forEach {
            if let detachable = $0 as? DetachableObject {
                let detached = detachable.detached() as! Element
                result.append(detached)
            } else {
                result.append($0) //Primtives are pass by value; don't need to recreate
            }
        }

        return result
    }

    func toArray() -> [Element] {
        return Array(self.detached())
    }
}
