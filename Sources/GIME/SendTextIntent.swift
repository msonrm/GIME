import AppIntents
import UIKit

/// テキスト全文を共有する App Intent
///
/// ショートカットアプリに自動公開される。
/// デフォルト動作: 共有シート表示（SNS 投稿画面等）。
/// ショートカットアプリでカスタムルーチンを組むことも可能。
struct SendTextIntent: AppIntent {
    static let title: LocalizedStringResource = "テキストを共有"
    static let description: IntentDescription = "エディタのテキスト全文を共有します"
    static let openAppWhenRun: Bool = true

    @Parameter(title: "テキスト")
    var text: String

    @MainActor
    func perform() async throws -> some IntentResult {
        // テキストをクリップボードにコピー
        UIPasteboard.general.string = text

        // 共有シートを表示
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first,
              let rootVC = windowScene.windows.first?.rootViewController else {
            return .result()
        }

        let activityVC = UIActivityViewController(
            activityItems: [text],
            applicationActivities: nil
        )

        // iPad ではポップオーバーのソースが必要
        if let popover = activityVC.popoverPresentationController {
            popover.sourceView = rootVC.view
            popover.sourceRect = CGRect(
                x: rootVC.view.bounds.midX,
                y: rootVC.view.bounds.midY,
                width: 0, height: 0
            )
            popover.permittedArrowDirections = []
        }

        // 最前面の ViewController を取得
        var presenter = rootVC
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(activityVC, animated: true)

        return .result()
    }
}

/// App Shortcuts でショートカットアプリに自動登録
struct GIMEShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SendTextIntent(),
            phrases: [
                "Share text from \(.applicationName)",
                "\(.applicationName)のテキストを共有"
            ],
            shortTitle: "テキストを共有",
            systemImageName: "square.and.arrow.up"
        )
    }
}
