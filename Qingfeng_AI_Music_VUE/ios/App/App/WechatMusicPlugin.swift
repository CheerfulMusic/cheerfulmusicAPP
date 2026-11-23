import Capacitor
import Foundation

class MusicBridgeViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(WechatMusicPlugin())
    }
}

@objc(WechatMusicPlugin)
public class WechatMusicPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "WechatMusicPlugin"
    public let jsName = "WechatMusic"
    public let pluginMethods = [
        CAPPluginMethod(name: "availability", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "consumeOpenedSong", returnType: CAPPluginReturnPromise)
    ]

    @objc func availability(_ call: CAPPluginCall) {
        DispatchQueue.main.async { call.resolve(QFWechat.availability()) }
    }

    @objc func share(_ call: CAPPluginCall) {
        let payload = call.options as? [String: Any] ?? [:]
        DispatchQueue.main.async {
            QFWechat.share(payload) { accepted in
                if accepted { call.resolve() }
                else { call.reject("微信未接收此次音乐分享，请检查移动应用配置、微信版本及歌曲资料。") }
            }
        }
    }

    @objc func consumeOpenedSong(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let song = QFWechat.consumeOpenedSong() { call.resolve(["songId": song]) }
            else { call.resolve() }
        }
    }
}
