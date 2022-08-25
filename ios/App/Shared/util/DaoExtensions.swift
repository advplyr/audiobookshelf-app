//
//  DaoExtensions.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

extension Object {
    func save() throws {
        let realm = try Realm()
        try realm.write {
            realm.add(self, update: .modified)
        }
    }
    
    func update(handler: () -> Void) throws {
        try self.realm?.write {
            handler()
        }
    }
}

extension EmbeddedObject {
    // Required to disassociate from Realm when copying into local objects
    static func detachCopy<T:Codable>(of object: T?) -> T? {
        guard let object = object else { return nil }
        let json = try! JSONEncoder().encode(object)
        return try! JSONDecoder().decode(T.self, from: json)
    }
}

protocol Deletable {
    func delete() throws
}

extension Deletable where Self: Object {
    func delete() throws {
        try self.realm?.write {
            self.realm?.delete(self)
        }
    }
}
