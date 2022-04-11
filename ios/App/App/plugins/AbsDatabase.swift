//
//  AbsDatabase.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import Capacitor
import RealmSwift

extension String {

    func fromBase64() -> String? {
        guard let data = Data(base64Encoded: self) else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    func toBase64() -> String {
        return Data(self.utf8).base64EncodedString()
    }

}

@objc(AbsDatabase)
public class AbsDatabase: CAPPlugin {
    @objc func setCurrentServerConnectionConfig(_ call: CAPPluginCall) {
        Database.realmQueue.sync {
            var id = call.getString("id")
            let address = call.getString("address", "")
            let userId = call.getString("userId", "")
            let username = call.getString("username", "")
            let token = call.getString("token", "")
            
            let name = "\(address) (\(username))"
            let config = ServerConnectionConfig()
            
            if id == nil {
                id = "\(address)@\(username)".toBase64()
            }
            
            config.id = id!
            config.name = name
            config.address = address
            config.userId = userId
            config.username = username
            config.token = token
            
            Store.serverConfig = config
            call.resolve(convertServerConnectionConfigToJSON(config: config))
        }
    }
    @objc func getDeviceData(_ call: CAPPluginCall) {
        Database.realmQueue.sync {
            let configs = Database.getServerConnectionConfigs()
            let index = Database.getActiveServerConfigIndex()
            
            call.resolve([
                "serverConnectionConfigs": configs.map { config in
                    return convertServerConnectionConfigToJSON(config: config)
                },
                "lastServerConnectionConfigId": index < 0 ? -1 : configs[index].id,
                "currentLocalPlaybackSession": nil, // Luckily this isn't implemented yet
            ])
        }
    }
    
    
}
