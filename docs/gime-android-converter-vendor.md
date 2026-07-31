# GIME Android かな漢字変換エンジン — vendor 素性と更新手順

GIME Android の日本語かな漢字変換は、上流の OSS キーボードアプリ
**[KazumaProject/JapaneseKeyboard](https://github.com/KazumaProject/JapaneseKeyboard)**
（通称「スミレ」、MIT License）の変換モジュールを**ソースコピー (vendor)** して
利用している。本ドキュメントはその素性（どこから・いつの版を取ったか）と、
**上流の改善を安全に取り込む手順**を記録する。

> これまで素性が記録されておらず「上流に改善が出ても取り込めない」状態だった。
> 本ドキュメント + `android/scripts/sync-kazuma-converter.sh` でその問題を解消する。

---

## 1. 何を vendor しているか

| 項目 | 値 |
|---|---|
| 上流リポジトリ | https://github.com/KazumaProject/JapaneseKeyboard |
| ライセンス | MIT License（Copyright (c) 2024 Kazuma Naka） |
| **取り込み基点** | commit `4995505ad2523a7157998fd72f631b0d5bdc12ca`（2026-04-03, ≈ tag `v1.7.34` の直前） |
| vendor 先 | `android/app/src/main/java/com/kazumaproject/` |
| ファイル数 | 57 `.kt`（変換エンジン本体 + 補助型のサブセット） |
| 辞書 assets | `android/app/src/main/assets/`（system / single_kanji / emoji ほか 約15MB、mozc 由来） |

エンジンの実体は **LOUDS trie（読み引き）+ N-gram 言語モデル + Viterbi（連接コスト最短経路）**。
辞書データは mozc 由来。我々はこれをロードして `getCandidatesWithoutPrediction` を
呼ぶだけの薄いファサード `com/gime/android/engine/JapaneseConverter.kt` を持つ。

上流アプリ全体ではなく、変換に必要なパッケージのサブセットだけを取り込んでいる:

- `markdownhelperkeyboard/converter/**` — 変換エンジン本体（LOUDS / graph / path_algorithm / dictionary / bitset ほか）
- `markdownhelperkeyboard/ime_service/extensions/**` — 数字変換等の補助拡張
- `markdownhelperkeyboard/user_dictionary/PosMapper.kt` — 品詞マッピング
- `markdownhelperkeyboard/repository/**` — ★ **我々が差し替えた**（後述）
- `core/`, `data/`, `domain/` — 絵文字・記号・文字拡張の補助型（上流は後にこれらを
  `core/` / `symbol_keyboard/` Gradle モジュールへ分割した。再同期時はパッケージ末尾で解決する）

---

## 1-b. 辞書データ (assets) の provenance とライセンス

`android/app/src/main/assets/`（約15MB）の辞書データは、上流スミレが利用する
**[google/mozc](https://github.com/google/mozc)（Google 日本語入力の OSS 版）の
system 辞書**由来。LOUDS trie + TokenArray 形式に変換されているが、語彙・連接コスト・
品詞（`id.def` / `connectionId.dat` / `pos_table.dat`）の出どころは mozc である。
上流 README も *"Powered by the large-scale dictionary from mozc"* と明記し、
謝辞で `google/mozc (BSD-3-Clause)` を挙げている。

### 同梱している辞書

| assets | 内容 | 由来 |
|---|---|---|
| `system/`（tango / token / yomi） | 基本語彙辞書 | mozc system dictionary |
| `single_kanji/` | 単漢字 | mozc |
| `connectionId.dat` / `id.def` / `pos_table.dat` | 連接コスト・品詞 | mozc |
| `emoji/` `emoticon/` `symbol/` `kotowaza/` `reading_correction/` `english/` | 補助辞書 | スミレ（上流）同梱 |

### ライセンス

mozc の `LICENSE` は次の 3 条項の複合で、**いずれも再配布自由**（コピーレフトの
継承義務なし・商用配布可、warranty disclaimer と copyright 表記の保持が条件）:

- **BSD-3-Clause**（Copyright 2010-2018, Google Inc.）— コード全般
- **NAIST License**（`src/data/dictionary*`、ICOT Free Software 条項を含む）— 辞書エントリの大部分
- **Public Domain**（`src/data/dictionary*` の一部）— 無制限

→ 全文は [`GIME.ACKNOWLEDGEMENTS.md`](../GIME.ACKNOWLEDGEMENTS.md) に同梱。

### Mozc UT 辞書（CC BY-SA）は同梱していない

上流スミレは Mozc UT 拡張辞書（人名 / 住所 / Web / Wiki / Neologd、**CC BY-SA**）を
**オプション**で読める。CC BY-SA は継承（ShareAlike）義務があり配布条件が重くなるが、
**GIME は一切同梱・使用していない**。二重に確認できる:

1. ファサード `engine/JapaneseConverter.kt` が `buildEngine` へ
   `mozcUtPersonName = null` / `mozcUTPlaces = null` / `mozcUTWiki = null` /
   `mozcUTNeologd = null` / `mozcUTWeb = null` を渡す（UT 辞書ロードを無効化）。
2. `assets/` に UT / neologd / person / place / wiki / web 系ファイルが存在しない。

→ GIME が配布する辞書は **mozc 本体（BSD-3 / NAIST / Public Domain、SA 継承なし）のみ**で、
公開・商用配布上のコピーレフト継承リスクはない。

---

## 2. ローカル改修（上流と意図的に分岐しているファイル）

vendor したファイルのうち、**我々が実際に手を入れたのは次の 2 ファイルだけ**。
それ以外の 55 ファイルは上流 `4995505` のコピーそのまま（無改修）。

| ファイル | 改修内容 | 理由 |
|---|---|---|
| `markdownhelperkeyboard/repository/LearnRepository.kt` | 上流スタブ → 自前 Room 実装 | 学習辞書（Phase A4a） |
| `markdownhelperkeyboard/repository/UserDictionaryRepository.kt` | 上流スタブ → 自前 Room 実装 | ユーザー辞書（Phase A4a） |

この 2 ファイルは `GraphBuilder` が **name-based** で参照する（`LearnEntity` /
`UserWord` のプロパティ名・型を維持していればよい）ため、エンジン本体に一切
手を入れずに学習・ユーザー語をラティスへ注入できる。Room 実体（DB / DAO）は
vendor 外の `com/gime/android/learn/GimeDatabase.kt` 側にある。

→ **再同期スクリプトはこの 2 ファイルを `PROTECTED` として上書きしない。**

---

## 3. 上流の改善を取り込む手順

`android/scripts/sync-kazuma-converter.sh` がワンコマンド再同期を行う。
我々が**現在追跡しているファイルだけ**を更新し、保護ファイルは温存し、
取り込み後に差分を出す（モジュール再編にも強いパッケージ末尾一致方式）。

```bash
# 現在の基点を再現（検証用 no-op に近い）
bash android/scripts/sync-kazuma-converter.sh

# 上流改善を取り込む: 新しい tag を指定して再実行 → 差分レビュー → ビルド/実機検証
KAZUMA_REF=v1.7.82 bash android/scripts/sync-kazuma-converter.sh
git diff --stat android/app/src/main/java/com/kazumaproject
```

取り込み後に**必ず確認すること**:

1. **ファサードの整合**: `JapaneseConverter.kt` が呼ぶ公開 API のシグネチャ変化。
   特に `kanaKanjiEngine.buildEngine(...)`（多数の trie / SuccinctBitVector 引数）と
   `getCandidatesWithoutPrediction(... mozcUTNeologd ...)` /
   `getCandidatesWithoutPredictionWithBunsetsu(...).primarySplitPositions`。
2. **辞書 assets の整合**: 上流が辞書フォーマットを変えた場合、`assets/` 側も
   差し替えが要る（`buildEngineFromAssets` の読み込みが失敗する）。
3. **ビルド + 実機検証**（CLAUDE.md の方針どおり実機重視）。
4. 問題なければ `KAZUMA_REF`（スクリプト冒頭）と本ドキュメントの「取り込み基点」を更新してコミット。

### 取り込み基点 → 最新 の規模感（2026-06 時点）

基点 `4995505`（2026-04-03）から上流 HEAD（v1.7.82 系）までで、変換モジュールには
実質的な改良が積まれている（参考: KanaKanjiEngine.kt 約 +834 行、EnglishEngine.kt 約 +353 行、
新規 `converter/glide/`＝スワイプ入力デコーダ群）。ファサードが使う公開 API は概ね互換だが、
コア +800 行規模のため**段階的に上げて毎回実機検証**するのが安全。

---

## 4. なぜ git submodule / subtree ではないのか

- 取り込んでいるのは上流アプリの**サブセット**（変換に要るパッケージのみ）で、
  かつ上流はその後マルチモジュール（`core/` / `symbol_keyboard/` 等）へ再編した。
  submodule / subtree は「上流の 1 ディレクトリ ↔ 我々の 1 ディレクトリ」を前提とするため
  この構造に噛み合わない。
- `repository/` の 2 ファイルは上流スタブを**削除して差し替えた**ため、subtree pull の度に
  衝突する。
- → 「追跡対象ファイルだけをパッケージ末尾一致でコピーし、保護ファイルを温存する」
  専用スクリプトの方が、サブセット + 部分差し替えという実態に合う。

iOS 版（GIME）は別系統で、AzooKeyKanaKanjiConverter を **SPM の正規依存**として
`Package.swift` でコミット固定している（`revision:` を上げるだけで更新可能）。
Android のこの vendor 方式は、上流が Maven/JitPack 公開していない現状での次善策。
将来上流が converter を JitPack 等で公開したら、Gradle 依存へ移行するのが理想。

---

## 5. 参照

- 上流: https://github.com/KazumaProject/JapaneseKeyboard （スミレ、MIT）
- 上流作者の関連リポジトリ: `kotlin-kana-kanji-converter`（mozc → .dat 辞書ビルダー）、
  `swift-kana-kanaji`、`kana-kanji-conversion-c-plus-plus`
- 同期スクリプト: `android/scripts/sync-kazuma-converter.sh`
- ファサード: `android/app/src/main/java/com/gime/android/engine/JapaneseConverter.kt`
- ライセンス表示: `android/app/src/main/java/com/gime/android/ui/SettingsScreen.kt`
