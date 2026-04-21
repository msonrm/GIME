import Foundation
import Observation

/// カスタム typing アクションの値の型。`UserDefaults` に rawValue で永続化する。
public enum CustomOscValueType: String, CaseIterable, Sendable {
    case int
    case float
    case bool
}

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
    public static let defaultCommitOnlyMode: Bool = false
    public static let defaultTypingIndicatorEnabled: Bool = true
    public static let defaultCustomTypingEnabled: Bool = false
    public static let defaultCustomTypingAddress: String = "/avatar/parameters/VRCEmote"
    public static let defaultCustomTypingValueType: CustomOscValueType = .int
    public static let defaultCustomTypingStartValue: String = "7"
    public static let defaultCustomTypingEndValue: String = "0"

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

    /// 確定時のみ OSC 送信するモード（下書き/typing indicator を送らない）。
    ///
    /// VRChat Mobile は `/chatbox/input ... sendMessage=false`（下書き）を受け取ると
    /// chatbox の入力 UI を開いてしまう。このモードを ON にすると composing 中は
    /// 一切送らず、LS 確定時のみ `sendMessage=true` で送信する。
    public var commitOnlyMode: Bool {
        didSet { defaults.set(commitOnlyMode, forKey: Self.keyCommitOnly) }
    }

    /// composing 中に `/chatbox/typing` を送るか。
    ///
    /// ON: 文字を打ち始めたタイミングで typing=true、確定/クリアで typing=false。
    ///   アバター頭上に 3 点 typing インジケータが表示される。
    /// OFF: typing 系パケットは一切送らない。
    ///
    /// `commitOnlyMode` とは独立。
    public var typingIndicatorEnabled: Bool {
        didSet { defaults.set(typingIndicatorEnabled, forKey: Self.keyTypingIndicator) }
    }

    /// typing の開始/終了エッジで任意の avatar parameter を叩くモード。
    ///
    /// 例: VRCEmote=7（悲しみ）を "考え中ポーズ" として使い、composing 開始で 7、
    /// 終了で 0 に戻す。アバター側に対応するアニメーションが組まれている必要あり。
    public var customTypingEnabled: Bool {
        didSet { defaults.set(customTypingEnabled, forKey: Self.keyCustomTypingEnabled) }
    }

    /// カスタム typing 時に叩く OSC アドレス（例: `/avatar/parameters/VRCEmote`）。
    public var customTypingAddress: String {
        didSet { defaults.set(customTypingAddress, forKey: Self.keyCustomTypingAddress) }
    }

    /// 値の型。`customTypingStartValue` / `customTypingEndValue` の解釈に使う。
    public var customTypingValueType: CustomOscValueType {
        didSet { defaults.set(customTypingValueType.rawValue, forKey: Self.keyCustomTypingValueType) }
    }

    /// typing 開始時に送る値（文字列。type に従って parse）。
    public var customTypingStartValue: String {
        didSet { defaults.set(customTypingStartValue, forKey: Self.keyCustomTypingStartValue) }
    }

    /// typing 終了時に送る値（文字列。type に従って parse）。
    public var customTypingEndValue: String {
        didSet { defaults.set(customTypingEndValue, forKey: Self.keyCustomTypingEndValue) }
    }

    /// start / end 値を `OscArgument` に解決した組を返す。parse 失敗時や
    /// `customTypingEnabled == false`、アドレスが空のときは `nil`。
    public func resolvedCustomTypingMessages() -> (start: (String, OscArgument), end: (String, OscArgument))? {
        guard customTypingEnabled else { return nil }
        let trimmed = customTypingAddress.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("/") else { return nil }
        guard let startArg = Self.parseArgument(customTypingStartValue, as: customTypingValueType) else { return nil }
        guard let endArg = Self.parseArgument(customTypingEndValue, as: customTypingValueType) else { return nil }
        return ((trimmed, startArg), (trimmed, endArg))
    }

    private static func parseArgument(_ raw: String, as type: CustomOscValueType) -> OscArgument? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        switch type {
        case .int:
            guard let v = Int32(trimmed) else { return nil }
            return .int32(v)
        case .float:
            guard let v = Float(trimmed) else { return nil }
            return .float32(v)
        case .bool:
            switch trimmed.lowercased() {
            case "true", "1", "yes", "on": return .bool(true)
            case "false", "0", "no", "off": return .bool(false)
            default: return nil
            }
        }
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
        self.commitOnlyMode = defaults.object(forKey: Self.keyCommitOnly) as? Bool
            ?? Self.defaultCommitOnlyMode
        self.typingIndicatorEnabled = defaults.object(forKey: Self.keyTypingIndicator) as? Bool
            ?? Self.defaultTypingIndicatorEnabled
        self.customTypingEnabled = defaults.object(forKey: Self.keyCustomTypingEnabled) as? Bool
            ?? Self.defaultCustomTypingEnabled
        self.customTypingAddress = defaults.string(forKey: Self.keyCustomTypingAddress)
            ?? Self.defaultCustomTypingAddress
        self.customTypingValueType = (defaults.string(forKey: Self.keyCustomTypingValueType))
            .flatMap(CustomOscValueType.init(rawValue:))
            ?? Self.defaultCustomTypingValueType
        self.customTypingStartValue = defaults.string(forKey: Self.keyCustomTypingStartValue)
            ?? Self.defaultCustomTypingStartValue
        self.customTypingEndValue = defaults.string(forKey: Self.keyCustomTypingEndValue)
            ?? Self.defaultCustomTypingEndValue
    }

    // MARK: - UserDefaults keys

    private static let keyEnabled = "vrchat_osc.enabled"
    private static let keyHost = "vrchat_osc.host"
    private static let keyPort = "vrchat_osc.port"
    private static let keyReceiverEnabled = "vrchat_osc.receiverEnabled"
    private static let keyReceiverPort = "vrchat_osc.receiverPort"
    private static let keyCommitOnly = "vrchat_osc.commitOnlyMode"
    private static let keyTypingIndicator = "vrchat_osc.typingIndicatorEnabled"
    private static let keyCustomTypingEnabled = "vrchat_osc.customTypingEnabled"
    private static let keyCustomTypingAddress = "vrchat_osc.customTypingAddress"
    private static let keyCustomTypingValueType = "vrchat_osc.customTypingValueType"
    private static let keyCustomTypingStartValue = "vrchat_osc.customTypingStartValue"
    private static let keyCustomTypingEndValue = "vrchat_osc.customTypingEndValue"
}
