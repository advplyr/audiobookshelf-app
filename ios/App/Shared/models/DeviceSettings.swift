//
//  DeviceSettings.swift
//  App
//
//  Created by advplyr on 7/2/22.
//

import Foundation
import RealmSwift

class DeviceSettings: Object {
    @Persisted var disableAutoRewind: Bool
    @Persisted var enableAltView: Bool
    @Persisted var jumpBackwardsTime: Int
    @Persisted var jumpForwardTime: Int
}

func getDefaultDeviceSettings() -> DeviceSettings {
    let settings = DeviceSettings()
    settings.disableAutoRewind = false
    settings.enableAltView = false
    settings.jumpForwardTime = 10
    settings.jumpBackwardsTime = 10
    return settings
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
