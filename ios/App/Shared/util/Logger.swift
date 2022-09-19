//
//  AppLogger.swift
//  AppLogger
//
//  Created by Fernando Fernandes on 29.06.20.
//

import Foundation
import os

/// Logs app info by using the newest Swift unified logging APIs.
///
/// Reference:
///  - [Explore logging in Swift (WWDC20)](https://developer.apple.com/wwdc20/10168)
///  - [Unified Logging](https://developer.apple.com/documentation/os/logging)
///  - [OSLog](https://developer.apple.com/documentation/os/oslog)
///  - [Logger](https://developer.apple.com/documentation/os/logger)
public struct AppLogger {

    // MARK: - Properties

    /// Default values used by the `AppLogger`.
    public struct Defaults {
        public static let subsystem = Bundle.main.bundleIdentifier ?? "ABS"
        public static let category = "default"
        public static let isPrivate = false
    }

    // MARK: - Private Properties

    private let logger: Logger

    // MARK: - Lifecycle

    /// Creates an `AppLogger` instance.
    ///
    /// - Parameters:
    ///   - subsystem: `String`. Organizes large topic areas within the app or apps. For example, you might define
    ///   a subsystem for each process that you create. The default is `Bundle.main.bundleIdentifier ?? "AppLogger"`.
    ///   - category: `String`. Within a `subsystem`, you define categories to further distinguish parts of that
    ///   subsystem. For example, if you used a single subsystem for your app, you might create separate categories for
    ///   model code and user-interface code. In a game, you might use categories to distinguish between physics, AI,
    ///   world simulation, and rendering. The default is `default`.
    public init(subsystem: String = Defaults.subsystem, category: String = Defaults.category) {
        self.logger = Logger(subsystem: subsystem, category: category)
    }
}

// MARK: - Interface

public extension AppLogger {
    
    func log(_ information: String, isPrivate: Bool = Defaults.isPrivate) {
        if isPrivate {
            logger.log("\(information, privacy: .private)")
        } else {
            logger.log("\(information, privacy: .public)")
        }
    }
    
    func error(_ information: String, isPrivate: Bool = Defaults.isPrivate) {
        if isPrivate {
            logger.error("\(information, privacy: .private)")
        } else {
            logger.error("\(information, privacy: .public)")
        }
    }
    
    func error(_ error: Error) {
        logger.error("\(String(describing: error))")
    }
}
