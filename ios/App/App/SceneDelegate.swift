import UIKit
import Capacitor

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        // The window and root view controller are instantiated from the Main storyboard.
        guard (scene as? UIWindowScene) != nil else { return }

        // Cold-start URL opens arrive in the connection options now that scenes are used.
        for urlContext in connectionOptions.urlContexts {
            _ = ApplicationDelegateProxy.shared.application(
                UIApplication.shared,
                open: urlContext.url,
                options: Self.openURLOptions(from: urlContext.options)
            )
        }

        // Cold-start universal links / user activities.
        if let userActivity = connectionOptions.userActivities.first ?? session.stateRestorationActivity {
            _ = ApplicationDelegateProxy.shared.application(
                UIApplication.shared,
                continue: userActivity,
                restorationHandler: { _ in }
            )
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let urlContext = URLContexts.first else { return }
        _ = ApplicationDelegateProxy.shared.application(
            UIApplication.shared,
            open: urlContext.url,
            options: Self.openURLOptions(from: urlContext.options)
        )
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        _ = ApplicationDelegateProxy.shared.application(
            UIApplication.shared,
            continue: userActivity,
            restorationHandler: { _ in }
        )
    }

    private static func openURLOptions(from options: UIScene.OpenURLOptions) -> [UIApplication.OpenURLOptionsKey: Any] {
        var dict: [UIApplication.OpenURLOptionsKey: Any] = [:]
        if let source = options.sourceApplication {
            dict[.sourceApplication] = source
        }
        if let annotation = options.annotation {
            dict[.annotation] = annotation
        }
        dict[.openInPlace] = options.openInPlace
        return dict
    }
}
