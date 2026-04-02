import AppIntents

/// GIME のエディタテキストを取得する App Intent
///
/// ショートカットアプリに自動公開される。
/// テキストを結果として返すので、後続アクション（翻訳、SNS 投稿等）にチェーンできる。
struct SendTextIntent: AppIntent {
    static let title: LocalizedStringResource = "テキストを取得"
    static let description: IntentDescription = "GIME エディタのテキスト全文を取得します"
    static let openAppWhenRun: Bool = true

    @Parameter(title: "テキスト")
    var text: String

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        .result(value: text)
    }
}

/// App Shortcuts でショートカットアプリに自動登録
struct GIMEShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SendTextIntent(),
            phrases: [
                "Get text from \(.applicationName)",
                "\(.applicationName)のテキストを取得"
            ],
            shortTitle: "テキストを取得",
            systemImageName: "text.quote"
        )
    }
}
