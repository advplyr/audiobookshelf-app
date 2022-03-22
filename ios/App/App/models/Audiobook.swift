//
//  AudioBook.swift
//  App
//
//  Created by Rasmus Krämer on 07.03.22.
//

import Foundation

struct Audiobook {
    var streamId: String
    var audiobookId: String
    var playlistUrl: String
    
    var startTime: Double = 0.0
    var duration: Double
    
    var title: String
    var series: String?
    var author: String?
    var artworkUrl: String?
    
    var token: String
}
