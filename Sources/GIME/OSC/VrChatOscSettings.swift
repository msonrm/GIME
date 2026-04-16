import Foundation
import Observation

/// VRChat OSC 連携の設定。UserDefaults バックエンド。
///
/// デフォルトは全て OFF。ユーザーが明示的に有効化するまで
/// ソケット open も送信もしない方針（プライバシー透明性のため）。
@Observable
public final class VrChatOscSettings {
    public static let defaultEnabled: Bool = false
    public static let defaultHost: String = "127.0.0.1"
    public static let defaultPort: UInt16 = 9000
    public static let defaultReceiverEnabled: Bool = false
    public static let defaultReceiverPort: UInt16 = 9001

    @ObservationIgnored
    private let defaults: UserDefaults

    public var enabled: Bool {
        didSet { defaults.set(enabled, forKey: Self.keyEnabled) }
    }

    public var host: String {
        didSet { defaults.set(host, forKey: Self.keyHost) }
    }

    public var port: UInt16 {
        didSet { defaults.set(Int(port), forKey: Self.keyPort) }
    }

    /// デバッグ受信を有効化するか。
    public var receiverEnabled: Bool {
        didSet { defaults.set(receiverEnabled, forKey: Self.keyReceiverEnabled) }
    }

    /// デバッグ受信側のリッスンポート。VRChat からの応答は 9001 が慣例。
    public var receiverPort: UInt16 {
        didSet { defaults.set(Int(receiverPort), forKey: Self.keyReceiverPort) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.enabled = defaults.object(forKey: Self.keyEnabled) as? Bool ?? Self.defaultEnabled
        self.host = defaults.string(forKey: Self.keyHost) ?? Self.defaultHost
        self.port = (defaults.object(forKey: Self.keyPort) as? Int).flatMap { UInt16(exactly: $0) }
            ?? Self.defaultPort
        self.receiverEnabled = defaults.object(forKey: Self.keyReceiverEnabled) as? Bool
            ?? Self.defaultReceiverEnabled
        self.receiverPort = (defaults.object(forKey: Self.keyReceiverPort) as? Int).flatMap { UInt16(exactly: $0) }
            ?? Self.defaultReceiverPort
    }

    // MARK: - UserDefaults keys

    private static let keyEnabled = "vrchat_osc.enabled"
    private static let keyHost = "vrchat_osc.host"
    private static let keyPort = "vrchat_osc.port"
    private static let keyReceiverEnabled = "vrchat_osc.receiverEnabled"
    private static let keyReceiverPort = "vrchat_osc.receiverPort"
}
