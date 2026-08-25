//
//  ServerConnectionConfig.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift
import SystemConfiguration.CaptiveNetwork

class ServerConnectionConfig: Object {
    @Persisted(primaryKey: true) var id: String = UUID().uuidString
    @Persisted(indexed: true) var index: Int = 1
    @Persisted var name: String = ""
    @Persisted var address: String = ""
    @Persisted var version: String = ""
    @Persisted var userId: String = ""
    @Persisted var username: String = ""
    @Persisted var token: String = ""
    @Persisted var localAddress: String?
    @Persisted var localSsidWhitelist = List<String>()

    var resolvedAddress: String {
        guard let localAddress = localAddress, !localAddress.isEmpty, !localSsidWhitelist.isEmpty else {
            return address
        }
        guard let currentSsid = Self.currentWifiSsid(), localSsidWhitelist.contains(currentSsid) else {
            return address
        }
        return localAddress
    }

    static func currentWifiSsid() -> String? {
        guard let interfaces = CNCopySupportedInterfaces() as? [String] else {
            return nil
        }
        for interface in interfaces {
            guard let info = CNCopyCurrentNetworkInfo(interface as CFString) as? [String: AnyObject],
                  let ssid = info[kCNNetworkInfoKeySSID as String] as? String else {
                continue
            }
            return ssid
        }
        return nil
    }
}

class ServerConnectionConfigActiveIndex: Object {
    // This could overflow, but you really would have to try
    @Persisted(primaryKey: true) var index: Int?
}

func convertServerConnectionConfigToJSON(config: ServerConnectionConfig) -> Dictionary<String, Any> {
    return [
        "id": config.id,
        "name": config.name,
        "index": config.index,
        "address": config.address,
        "version": config.version,
        "userId": config.userId,
        "username": config.username,
        "token": config.token,
        "localAddress": config.localAddress ?? NSNull(),
        "localSsidWhitelist": Array(config.localSsidWhitelist),
    ]
}
