//
//  PlaybackReport.swift
//  App
//
//  Created by Rasmus Krämer on 15.04.22.
//

import Foundation

struct PlaybackReport: Decodable, Encodable {
    var currentTime: Double
    var duration: Double
    var timeListened: Double
}
