/// Abbreviated pinyin (简拼) 検索エンジン
///
/// CC-CEDICT + OpenSubtitles 頻度リストから生成した辞書 JSON をロードし、
/// abbreviated pinyin キーで候補を検索する。
///
/// 将来的に繁体字対応を追加可能（辞書 JSON に `t` フィールドを含む）。

import Foundation
import Observation

#if !SWIFT_PACKAGE
/// XcodeGen ビルド用 Bundle.module 互換シム
private class _BundleToken {}
extension Bundle {
    static let module = Bundle(for: _BundleToken.self)
}
#endif

// MARK: - 中国語変異体

/// 中国語変異体（簡体/繁体）
enum ChineseVariant: Sendable {
    case simplified
    case traditional
}

// MARK: - 候補データ

/// ピンイン候補エントリ
struct PinyinCandidate: Codable, Sendable {
    /// 簡体字
    let w: String
    /// 繁体字
    let t: String
    /// ピンイン（声調番号付き、例: "ni3 hao3"）
    let p: String
}

// MARK: - 検索エンジン

/// Abbreviated pinyin 検索エンジン
///
/// バンドルされた `pinyin_abbrev.json` をロードし、
/// abbreviated pinyin キーで頻度順の候補リストを返す。
@MainActor
@Observable
final class PinyinEngine {
    /// 中国語変異体（簡体/繁体の切り替え）
    var variant: ChineseVariant = .simplified

    /// 辞書がロード済みかどうか
    private(set) var isLoaded = false

    /// abbreviated pinyin → 候補リスト（頻度順）
    private var index: [String: [PinyinCandidate]] = [:]

    /// バンドルされた辞書 JSON をロードする
    func load() {
        guard !isLoaded else { return }

        guard let url = Bundle.module.url(
            forResource: "pinyin_abbrev",
            withExtension: "json"
        ) else {
            print("[PinyinEngine] pinyin_abbrev.json not found in bundle")
            return
        }

        do {
            let data = try Data(contentsOf: url)
            index = try JSONDecoder().decode(
                [String: [PinyinCandidate]].self,
                from: data
            )
            isLoaded = true
            print("[PinyinEngine] Loaded \(index.count) keys")
        } catch {
            print("[PinyinEngine] Failed to load dictionary: \(error)")
        }
    }

    /// abbreviated pinyin キーで候補を検索する
    ///
    /// - Parameters:
    ///   - abbreviation: abbreviated pinyin 文字列（例: "nh"）
    ///   - limit: 返す候補の最大数（デフォルト 9）
    /// - Returns: 頻度順の候補リスト
    func lookup(_ abbreviation: String, limit: Int = 9) -> [PinyinCandidate] {
        guard !abbreviation.isEmpty else { return [] }
        let key = abbreviation.lowercased()

        if let candidates = index[key] {
            return Array(candidates.prefix(limit))
        }
        return []
    }

    /// variant に応じた表示テキストを返す
    func displayText(for candidate: PinyinCandidate) -> String {
        switch variant {
        case .simplified:
            return candidate.w
        case .traditional:
            return candidate.t
        }
    }
}
