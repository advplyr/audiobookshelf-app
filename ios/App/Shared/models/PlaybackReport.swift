//
//  PlaybackReport.swift
//  App
//
//  Created by Rasmus Krämer on 12.04.22.
//

import Foundation
import RealmSwift

class PlaybackReport: Object {
    @Persisted var token: String
}
