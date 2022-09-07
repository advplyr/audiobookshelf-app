//
//  Store.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Store {
    public static var serverConfig: ServerConnectionConfig? {
        get {
            do {
                // Fetch each time, as holding onto a live or frozen realm object is bad
                let index = Database.shared.getLastActiveConfigIndex()
                let realm = try Realm()
                return realm.objects(ServerConnectionConfig.self).first(where: { $0.index == index })
            } catch {
                debugPrint(error)
                return nil
            }
        }
        set(updated) {
            if updated != nil {
                Database.shared.setServerConnectionConfig(config: updated!)
            } else {
                Database.shared.setLastActiveConfigIndexToNil()
            }
        }
    }
}
