//
//  AbsNetwork.swift
//  App
//

import Foundation
import Capacitor
import NetworkExtension

@objc(AbsNetwork)
public class AbsNetwork: CAPPlugin, CAPBridgedPlugin {
    public var identifier = "AbsNetworkPlugin"
    public var jsName = "AbsNetwork"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getCurrentWifiSsid", returnType: CAPPluginReturnPromise)
    ]

    @objc func getCurrentWifiSsid(_ call: CAPPluginCall) {
        if #available(iOS 14.0, *) {
            NEHotspotNetwork.fetchCurrent { network in
                call.resolve([
                    "ssid": network?.ssid ?? NSNull()
                ])
            }
        } else {
            call.resolve([
                "ssid": NSNull()
            ])
        }
    }
}
