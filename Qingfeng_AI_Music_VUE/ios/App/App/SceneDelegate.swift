import UIKit
import Capacitor

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
        window?.rootViewController = MusicBridgeViewController()
        window?.makeKeyAndVisible()

        for context in connectionOptions.urlContexts { _ = QFWechat.handle(context.url) }
        for activity in connectionOptions.userActivities { _ = QFWechat.continueActivity(activity) }
        SceneDelegateProxy.shared.scene(scene, willConnectTo: session, options: connectionOptions)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        let remaining = Set(URLContexts.filter { !QFWechat.handle($0.url) })
        if !remaining.isEmpty { SceneDelegateProxy.shared.scene(scene, openURLContexts: remaining) }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        if !QFWechat.continueActivity(userActivity) { SceneDelegateProxy.shared.scene(scene, continue: userActivity) }
    }
}
