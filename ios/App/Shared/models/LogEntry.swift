//
//  LogEntry.swift
//  Audiobookshelf
//
//  Created by Christopher Jensen-Reimann on 9/3/25.
//

import Foundation
import RealmSwift

class LogEntry: Object, Codable {
  @Persisted(primaryKey: true) var id: String = UUID().uuidString
  @Persisted var tag: String = ""
  @Persisted var level: String = ""
  @Persisted var message: String = ""
  @Persisted var timestamp: Int = 0
}
