//
//  DeviceSettings.swift
//  App
//
//  Created by advplyr on 7/2/22.
//

import Foundation
import RealmSwift
import Unrealm

struct DeviceSettings: Realmable {
    var disableAutoRewind: Bool = false
    var enableAltView: Bool = false
    var jumpBackwardsTime: Int = 10
    var jumpForwardTime: Int = 10
}

func getDefaultDeviceSettings() -> DeviceSettings {
    return DeviceSettings()
}

func deviceSettingsToJSON(settings: DeviceSettings) -> Dictionary<String, Any> {
    return Database.realmQueue.sync {
        return [
            "disableAutoRewind": settings.disableAutoRewind,
            "enableAltView": settings.enableAltView,
            "jumpBackwardsTime": settings.jumpBackwardsTime,
            "jumpForwardTime": settings.jumpForwardTime
        ]
    }
}
