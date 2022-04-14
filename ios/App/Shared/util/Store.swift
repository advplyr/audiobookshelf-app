//
//  Store.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Store {
    @ThreadSafe private static var _serverConfig: ServerConnectionConfig?
    // ONLY USE REALM IN Database.realmQueue OR ELSE THE APP WILL CRASH
    public static var serverConfig: ServerConnectionConfig {
        get {
            if _serverConfig == nil {
                let index = Database.getActiveServerConfigIndex()
                // TODO: change this when multiple configs are possible
                _serverConfig = Database.getServerConnectionConfigs().first { config in
                    return config.index == index
                }
            }
            
            return _serverConfig ?? ServerConnectionConfig()
        }
        set(updated) {
            Database.setServerConnectionConfig(config: updated)
            _serverConfig = nil
        }
    }
}
