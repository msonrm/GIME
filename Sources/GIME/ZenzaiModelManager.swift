import Foundation
import Observation

/// Zenzai モデルのダウンロード・管理
///
/// トグル ON で HuggingFace から最新の GGUF モデルを自動ダウンロードし、
/// Application Support に保存する。ダウンロード済みなら即座に有効化。
@Observable
final class ZenzaiModelManager {

    // MARK: - 定数

    /// HuggingFace のモデルダウンロード URL
    private static let modelRemoteURL = URL(
        string: "https://huggingface.co/Miwa-Keita/zenz-v3.1-small-gguf/resolve/main/ggml-model-Q5_K_M.gguf"
    )!

    /// ローカル保存ファイル名
    private static let modelFileName = "ggml-model-Q5_K_M.gguf"

    /// モデルサイズ（表示用、約 74 MB）
    static let modelSizeMB = 74

    // MARK: - 状態

    enum State: Equatable {
        case notDownloaded
        case downloading(progress: Double)
        case downloaded
        case error(String)
    }

    /// ダウンロード状態
    private(set) var state: State = .notDownloaded

    /// ユーザーが Zenzai を有効にしているか（UserDefaults 永続化）
    var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: "zenzaiEnabled")
            if isEnabled {
                startDownloadIfNeeded()
            }
        }
    }

    /// ダウンロード済みモデルのローカル URL（未ダウンロードまたはファイルが無効なら nil）
    var modelURL: URL? {
        guard case .downloaded = state,
              Self.isValidModelFile(at: localModelURL) else { return nil }
        return localModelURL
    }

    // MARK: - Private

    private let localModelURL: URL
    private var downloadTask: URLSessionDownloadTask?

    // MARK: - 初期化

    init() {
        // 保存先: Application Support/Zenzai/
        let appSupport = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first!
        let zenzaiDir = appSupport.appendingPathComponent("Zenzai", isDirectory: true)
        try? FileManager.default.createDirectory(at: zenzaiDir, withIntermediateDirectories: true)
        localModelURL = zenzaiDir.appendingPathComponent(Self.modelFileName)

        // UserDefaults から復元
        isEnabled = UserDefaults.standard.bool(forKey: "zenzaiEnabled")

        // ダウンロード済みかチェック（ファイルサイズも検証し、壊れたファイルを除外）
        if Self.isValidModelFile(at: localModelURL) {
            state = .downloaded
        } else if FileManager.default.fileExists(atPath: localModelURL.path) {
            // 壊れたファイルを削除
            try? FileManager.default.removeItem(at: localModelURL)
        }

        // 有効かつ未ダウンロードなら開始
        if isEnabled && state == .notDownloaded {
            startDownloadIfNeeded()
        }
    }

    // MARK: - ダウンロード

    /// モデルが未ダウンロードならダウンロードを開始する
    func startDownloadIfNeeded() {
        guard state == .notDownloaded || isErrorState else { return }
        state = .downloading(progress: 0)

        let request = URLRequest(url: Self.modelRemoteURL)
        let session = URLSession(
            configuration: .default,
            delegate: DownloadDelegate(manager: self),
            delegateQueue: nil
        )
        let task = session.downloadTask(with: request)
        downloadTask = task
        task.resume()
    }

    /// ダウンロードをキャンセルする
    func cancelDownload() {
        downloadTask?.cancel()
        downloadTask = nil
        state = .notDownloaded
    }

    /// ダウンロード済みモデルを削除する
    func deleteModel() {
        try? FileManager.default.removeItem(at: localModelURL)
        state = .notDownloaded
    }

    /// モデルファイルが存在し、最低限のサイズがあるか検証する
    ///
    /// GGUF モデルは約 74 MB。1 MB 未満のファイルは破損とみなす。
    private static let minimumModelFileSize: UInt64 = 1_000_000

    static func isValidModelFile(at url: URL) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? UInt64 else { return false }
        return size >= minimumModelFileSize
    }

    private var isErrorState: Bool {
        if case .error = state { return true }
        return false
    }

    // MARK: - ダウンロードデリゲート

    /// URLSessionDownloadDelegate（進捗通知 + 完了処理）
    private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
        private nonisolated(unsafe) weak var manager: ZenzaiModelManager?

        init(manager: ZenzaiModelManager) {
            self.manager = manager
        }

        func urlSession(
            _ session: URLSession,
            downloadTask: URLSessionDownloadTask,
            didFinishDownloadingTo location: URL
        ) {
            guard let manager = manager else { return }
            do {
                // 既存ファイルがあれば削除
                let dest = manager.localModelURL
                if FileManager.default.fileExists(atPath: dest.path) {
                    try FileManager.default.removeItem(at: dest)
                }
                try FileManager.default.moveItem(at: location, to: dest)
                DispatchQueue.main.async {
                    manager.state = .downloaded
                }
            } catch {
                DispatchQueue.main.async {
                    manager.state = .error(error.localizedDescription)
                }
            }
        }

        func urlSession(
            _ session: URLSession,
            downloadTask: URLSessionDownloadTask,
            didWriteData bytesWritten: Int64,
            totalBytesWritten: Int64,
            totalBytesExpectedToWrite: Int64
        ) {
            guard totalBytesExpectedToWrite > 0 else { return }
            let progress = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
            DispatchQueue.main.async { [weak self] in
                self?.manager?.state = .downloading(progress: progress)
            }
        }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            didCompleteWithError error: (any Error)?
        ) {
            guard let error = error else { return }
            // キャンセル時はエラー表示しない
            if (error as NSError).code == NSURLErrorCancelled { return }
            DispatchQueue.main.async { [weak self] in
                self?.manager?.state = .error(error.localizedDescription)
            }
        }
    }
}
