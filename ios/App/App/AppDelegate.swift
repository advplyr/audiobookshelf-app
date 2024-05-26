import UIKit
import Capacitor
import RealmSwift

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    private let logger = AppLogger(category: "AppDelegate")

    lazy var window: UIWindow? = UIWindow(frame: UIScreen.main.bounds)
    var backgroundCompletionHandler: (() -> Void)?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Override point for customization after application launch.

        let configuration = Realm.Configuration(
            schemaVersion: 18,
            migrationBlock: { [weak self] migration, oldSchemaVersion in
                if (oldSchemaVersion < 1) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)")
                    migration.enumerateObjects(ofType: DeviceSettings.className()) { oldObject, newObject in
                        newObject?["enableAltView"] = false
                    }
                }
                if (oldSchemaVersion < 4) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Reindexing server configs")
                    var indexCounter = 1
                    migration.enumerateObjects(ofType: ServerConnectionConfig.className()) { oldObject, newObject in
                        newObject?["index"] = indexCounter
                        indexCounter += 1
                    }
                }
                if (oldSchemaVersion < 5) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Adding lockOrientation setting")
                    migration.enumerateObjects(ofType: DeviceSettings.className()) { oldObject, newObject in
                        newObject?["lockOrientation"] = "NONE"
                    }
                }
                if (oldSchemaVersion < 6) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Adding hapticFeedback setting")
                    migration.enumerateObjects(ofType: DeviceSettings.className()) { oldObject, newObject in
                        newObject?["hapticFeedback"] = "LIGHT"
                    }
                }
                if (oldSchemaVersion < 15) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Adding languageCode setting")
                    migration.enumerateObjects(ofType: DeviceSettings.className()) { oldObject, newObject in
                        newObject?["languageCode"] = "en-us"
                    }
                }
                if (oldSchemaVersion < 16) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Adding chapterTrack setting")
                    migration.enumerateObjects(ofType: PlayerSettings.className()) { oldObject, newObject in
                        newObject?["chapterTrack"] = false
                    }
                }
                if (oldSchemaVersion < 17) {
                    self?.logger.log("Realm schema version was \(oldSchemaVersion)... Adding downloadUsingCellular and streamingUsingCellular settings")
                    migration.enumerateObjects(ofType: PlayerSettings.className()) { oldObject, newObject in
                        newObject?["downloadUsingCellular"] = "ALWAYS"
                        newObject?["streamingUsingCellular"] = "ALWAYS"
                    }
                }

            }
        )
        Realm.Configuration.defaultConfiguration = configuration

        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state. This can occur for certain types of temporary interruptions (such as an incoming phone call or SMS message) or when the user quits the application and it begins the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate graphics rendering callbacks. Games should use this method to pause the game.
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Use this method to release shared resources, save user data, invalidate timers, and store enough application state information to restore your application to its current state in case it is terminated later.
        // If your application supports background execution, this method is called instead of applicationWillTerminate: when the user quits.
        logger.log("Audiobookself is now in the background")
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state; here you can undo many of the changes made on entering the background.
        logger.log("Audiobookself is now in the foreground")
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive. If the application was previously in the background, optionally refresh the user interface.
        logger.log("Audiobookself is now active")
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate. See also applicationDidEnterBackground:.
        logger.log("Audiobookself is terminating")
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Called when the app was launched with a url. Feel free to add additional processing here,
        // but if you want the App API to support tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        // Feel free to add additional processing here, but if you want the App API to support
        // tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        // Stores the completion handler for background downloads
        // The identifier of this method can be ignored at this time as we only have one background url session
        backgroundCompletionHandler = completionHandler
    }


}

