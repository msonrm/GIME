import SwiftUI
import Observation

/// OSC 受信ログの状態保持クラス。`@Sendable` クロージャから MainActor 越しに
/// 更新できるよう、MainActor 分離した @Observable として独立させている。
@Observable
@MainActor
final class OscReceiverLogStore {
    var lines: [String] = []

    func append(_ line: String) {
        lines.insert(line, at: 0)
        if lines.count > 50 {
            lines.removeLast(lines.count - 50)
        }
    }

    func clear() {
        lines.removeAll()
    }
}

/// VRChat OSC 連携の設定画面。
///
/// - 有効化トグル + 送信先 IP/Port 設定
/// - テスト送信（設定した送信先 / loopback の両方）
/// - デバッグ受信ログ表示（iPad 単独で送受信確認可能）
///
/// 初回送信時に iOS が Local Network 許可ダイアログを表示する。
/// ユーザーが拒否した場合は OSC 機能は無効化される（再有効化は
/// 設定 → プライバシー → ローカルネットワーク で実施）。
@MainActor
struct VrChatSettingsView: View {
    @Bindable var settings: VrChatOscSettings
    @Environment(\.dismiss) private var dismiss

    @State private var statusMessage: String?
    @State private var logStore = OscReceiverLogStore()
    @State private var receiver: OscReceiver?

    var body: some View {
        NavigationStack {
            Form {
                descriptionSection
                enableSection
                targetSection
                sendOptionsSection
                testSection
                receiverSection
                logSection
                privacySection
            }
            .navigationTitle("VRChat OSC 連携")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
            .onChange(of: settings.receiverEnabled) { _, _ in
                restartReceiverIfNeeded()
            }
            .onChange(of: settings.receiverPort) { _, _ in
                restartReceiverIfNeeded()
            }
            .onAppear {
                restartReceiverIfNeeded()
            }
            .onDisappear {
                stopReceiver()
            }
        }
    }

    // MARK: - Sections

    private var descriptionSection: some View {
        Section {
            Text("VRChat Mobile や PC VRChat の chatbox に OSC 経由で文字を送信します。通信は指定した送信先だけで、第三者サーバーには一切流れません。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var enableSection: some View {
        Section {
            Toggle("VRChat OSC モード", isOn: $settings.enabled)
        } footer: {
            Text("オフの間は OSC ソケットも一切 open しません。")
                .font(.caption2)
        }
    }

    private var targetSection: some View {
        Section("送信先") {
            LabeledContent("IP") {
                TextField("IP アドレス", text: $settings.host)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .monospaced()
            }
            LabeledContent("Port") {
                TextField("9000", text: portBinding)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .monospaced()
            }
            if isExternalIP(settings.host) {
                Text("⚠ 外部 IP を指定しています。意図した送信先か確認してください。")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    /// `UInt16` のポート番号を `TextField` で編集するための String ブリッジ。
    /// 数字以外は捨て、範囲外は無視する。
    private var portBinding: Binding<String> {
        Binding(
            get: { String(settings.port) },
            set: { newValue in
                let filtered = newValue.filter(\.isNumber)
                if let parsed = Int(filtered), (1...65535).contains(parsed),
                   let port = UInt16(exactly: parsed) {
                    settings.port = port
                }
            }
        )
    }

    private var receiverPortBinding: Binding<String> {
        Binding(
            get: { String(settings.receiverPort) },
            set: { newValue in
                let filtered = newValue.filter(\.isNumber)
                if let parsed = Int(filtered), (1...65535).contains(parsed),
                   let port = UInt16(exactly: parsed) {
                    settings.receiverPort = port
                }
            }
        )
    }

    private var sendOptionsSection: some View {
        Section {
            Toggle("確定時のみ送信", isOn: $settings.commitOnlyMode)
            Toggle("タイピングインジケーター", isOn: $settings.typingIndicatorEnabled)
        } header: {
            Text("送信オプション")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("確定時のみ送信: ON にすると composing 中の下書き（/chatbox/input sendMessage=false）を送らず、LS 確定時のみ送信します。VRChat Mobile で chatbox の入力 UI が開いてしまう場合に有効。")
                Text("タイピングインジケーター: ON でアバター頭上の 3 点インジケータ（/chatbox/typing）を送信。OFF で一切送らない。")
            }
            .font(.caption2)
        }
    }

    private var testSection: some View {
        Section {
            Button {
                sendTestToTarget()
            } label: {
                Label("送信先へテスト送信", systemImage: "paperplane")
            }
            Button {
                sendTestLoopback()
            } label: {
                Label("自受信へ送信 (loopback)", systemImage: "arrow.triangle.2.circlepath")
            }
            if let statusMessage {
                Text(statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("テスト送信")
        }
    }

    private var receiverSection: some View {
        Section {
            Toggle("デバッグ受信", isOn: $settings.receiverEnabled)
            LabeledContent("Listen Port") {
                TextField("9001", text: receiverPortBinding)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .monospaced()
            }
        } header: {
            Text("デバッグ受信")
        } footer: {
            Text("loopback テストや VRChat からの応答確認に使用します。")
                .font(.caption2)
        }
    }

    @ViewBuilder
    private var logSection: some View {
        if settings.receiverEnabled || !logStore.lines.isEmpty {
            Section {
                if logStore.lines.isEmpty {
                    Text("受信待機中…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(logStore.lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(
                                line.hasPrefix("!") ? Color.red : Color.primary
                            )
                            .lineLimit(3)
                    }
                    Button("ログクリア") {
                        logStore.clear()
                    }
                    .font(.caption)
                }
            } header: {
                Text("受信ログ")
            }
        }
    }

    private var privacySection: some View {
        Section {
            Text("・ユーザーが有効化するまでソケット open / 送信は行いません。")
            Text("・送信先はこの画面で指定した宛先のみです。")
            Text("・第三者サーバーや解析サービスへのデータ送信はありません。")
        } header: {
            Text("プライバシー")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }

    // MARK: - アクション

    private func sendTestToTarget() {
        let sender = OscSender(host: settings.host, port: settings.port)
        sender.send(
            "/test/ping",
            .string("hello from GIME"), .bool(true), .bool(false)
        )
        statusMessage = "送信: /test/ping → \(settings.host):\(settings.port)"
        // 短寿命なのでクローズ（実送信は非同期に完了）
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            sender.close()
        }
    }

    private func sendTestLoopback() {
        let port = settings.receiverPort
        let sender = OscSender(host: "127.0.0.1", port: port)
        sender.send(
            "/test/loopback",
            .string("self-test"), .int32(42), .float32(3.14), .bool(true)
        )
        statusMessage = "送信: /test/loopback → 127.0.0.1:\(port)"
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            sender.close()
        }
    }

    private func restartReceiverIfNeeded() {
        stopReceiver()
        guard settings.receiverEnabled else { return }
        let port = settings.receiverPort
        // `logStore` は @MainActor クラス参照で Sendable のため @Sendable
        // クロージャに安全にキャプチャできる。
        let store = logStore
        let r = OscReceiver(port: port) { event in
            let line: String
            switch event {
            case .received(let message, let fromHost):
                line = "← \(message.debugDescription) (from \(fromHost))"
            case .error(let reason):
                line = "! \(reason)"
            }
            Task { @MainActor in
                store.append(line)
            }
        }
        r.start()
        self.receiver = r
    }

    private func stopReceiver() {
        receiver?.stop()
        receiver = nil
    }

    // MARK: - ヘルパー

    private func isExternalIP(_ host: String) -> Bool {
        guard !host.isEmpty else { return false }
        if host.hasPrefix("127.") { return false }
        if host.hasPrefix("10.") { return false }
        if host.hasPrefix("192.168.") { return false }
        // 172.16.0.0/12 の簡易判定
        if host.hasPrefix("172.") {
            let parts = host.split(separator: ".")
            if parts.count >= 2, let second = Int(parts[1]), (16...31).contains(second) {
                return false
            }
        }
        if host == "localhost" { return false }
        return true
    }
}
