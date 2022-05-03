//
//  Extensions.swift
//  App
//
//  Created by Rasmus Krämer on 14.04.22.
//

import Foundation

extension String: Error {}

extension Encodable {
  func asDictionary() throws -> [String: Any] {
    let data = try JSONEncoder().encode(self)
    guard let dictionary = try JSONSerialization.jsonObject(with: data, options: .allowFragments) as? [String: Any] else {
      throw NSError()
    }
    return dictionary
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
