/// CJK 入力候補検索エンジン
///
/// Abbreviated pinyin (简拼) / abbreviated zhuyin (注音首) で候補を検索する。
/// 簡体字は CC-CEDICT + OpenSubtitles、繁体字は libchewing をデータソースとする。

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

/// 簡体字候補エントリ（pinyin_abbrev.json）
private struct SimplifiedEntry: Codable {
    let w: String  // 簡体字
    let t: String  // 繁体字
    let p: String  // ピンイン
}

/// 繁体字候補エントリ（zhuyin_abbrev.json）
private struct TraditionalEntry: Codable {
    let w: String  // 繁体字
    let z: String  // 注音
    let p: String  // abbreviated pinyin key
}

/// 統一候補型（外部に公開）
struct PinyinCandidate: Sendable {
    /// 表示テキスト（variant に応じて簡体字 or 繁体字）
    let word: String
    /// 読み表示（簡体字=ピンイン、繁体字=注音）
    let reading: String
}

// MARK: - 検索エンジン

/// CJK 候補検索エンジン
///
/// `pinyin_abbrev.json`（簡体字）と `zhuyin_abbrev.json`（繁体字）をロードし、
/// abbreviated key で頻度順の候補リストを返す。
@MainActor
@Observable
final class PinyinEngine {
    /// 中国語変異体（簡体/繁体の切り替え）
    var variant: ChineseVariant = .simplified

    /// 辞書がロード済みかどうか
    private(set) var isSimplifiedLoaded = false
    private(set) var isTraditionalLoaded = false

    /// abbreviated key → 候補リスト
    private var simplifiedIndex: [String: [PinyinCandidate]] = [:]
    private var traditionalIndex: [String: [PinyinCandidate]] = [:]

    /// 簡体字辞書をロードする
    func loadSimplified() {
        guard !isSimplifiedLoaded else { return }
        guard let url = Bundle.module.url(forResource: "pinyin_abbrev", withExtension: "json") else {
            print("[PinyinEngine] pinyin_abbrev.json not found")
            return
        }
        do {
            let data = try Data(contentsOf: url)
            let raw = try JSONDecoder().decode([String: [SimplifiedEntry]].self, from: data)
            simplifiedIndex = raw.mapValues { entries in
                entries.map { PinyinCandidate(word: $0.w, reading: $0.p) }
            }
            isSimplifiedLoaded = true
            print("[PinyinEngine] Simplified: \(simplifiedIndex.count) keys")
        } catch {
            print("[PinyinEngine] Failed to load simplified: \(error)")
        }
    }

    /// 繁体字辞書をロードする
    func loadTraditional() {
        guard !isTraditionalLoaded else { return }
        guard let url = Bundle.module.url(forResource: "zhuyin_abbrev", withExtension: "json") else {
            print("[PinyinEngine] zhuyin_abbrev.json not found")
            return
        }
        do {
            let data = try Data(contentsOf: url)
            let raw = try JSONDecoder().decode([String: [TraditionalEntry]].self, from: data)
            traditionalIndex = raw.mapValues { entries in
                entries.map { PinyinCandidate(word: $0.w, reading: $0.z) }
            }
            isTraditionalLoaded = true
            print("[PinyinEngine] Traditional: \(traditionalIndex.count) keys")
        } catch {
            print("[PinyinEngine] Failed to load traditional: \(error)")
        }
    }

    /// 両方の辞書をロードする
    func load() {
        loadSimplified()
        loadTraditional()
    }

    /// abbreviated key で候補を検索する
    func lookup(_ abbreviation: String, limit: Int = 30) -> [PinyinCandidate] {
        guard !abbreviation.isEmpty else { return [] }
        let key = abbreviation.lowercased()

        let index = variant == .simplified ? simplifiedIndex : traditionalIndex
        if let candidates = index[key] {
            return Array(candidates.prefix(limit))
        }
        return []
    }

    /// 候補の表示テキストを返す
    func displayText(for candidate: PinyinCandidate) -> String {
        candidate.word
    }
}
