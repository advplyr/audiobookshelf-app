//
//  Extensions.swift
//  App
//
//  Created by Rasmus Krämer on 14.04.22.
//

import Foundation
import SwiftUI
import RealmSwift

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
