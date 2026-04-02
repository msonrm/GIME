# GIME -- ゲームパッド日本語入力アプリ仕様書

## 概要

GIME (Gamepad IME) は、iPad + ゲームパッドで日本語・英語・韓国語・中国語簡体字を入力できる実験的アプリである。

- KeyLogicKit の IME エンジン（InputManager, IMETextView）を利用し、かな漢字変換を実現
- GCController でゲームパッド入力を受け取り、KeyRouter をバイパスして InputManager に直接かなを注入する
- ソフトウェアキーボードは非表示。フォールバックとしてハードウェアキーボード入力（ローマ字 US 配列）も受け付ける

## アーキテクチャ

### データフロー

```
GCController
  → valueChangedHandler（non-Sendable の GCExtendedGamepad を値コピー）
  → GamepadSnapshot（Sendable な値型）
  → GamepadInputManager.handleSnapshot()（@MainActor）
  → モード別処理:
      日本語:     GamepadResolver → InputManager.appendDirectKana / replaceDirectKana
      英語:       englishTable → onDirectInsert（IME バイパス）
      韓国語:     KoreanComposer → onDirectInsert（IME バイパス）
      中国語簡体: englishTable → PinyinEngine.lookup → CandidatePopup → onDirectInsert
```

### UI 構成

```
GIMEApp (@main)
  └── ContentView
        ├── IMETextViewRepresentable（KeyLogicKit のエディタ）
        │     └── CandidatePopup（変換候補、selecting 時のみ表示）
        └── GamepadVisualizerView（接続時のみ表示、画面下部）
```

### 状態管理

- `InputManager`（KeyLogicKit、@Observable）: 変換状態の唯一の管理元
- `GamepadInputManager`（@Observable）: ゲームパッド接続状態、入力モード、ビジュアライザ用の UI 状態
- `KoreanComposer`（struct、値型）: ハングル音節合成状態（GamepadInputManager が所有）
- `PinyinEngine`（@Observable）: abbreviated pinyin 辞書ロード・検索（App.swift が所有、GamepadInputManager に注入）
- コールバック（`onCursorMove`, `onDeleteBackward`, `onDirectInsert`）で ContentView 側のテキスト操作を実行

## ファイル構成

| ファイル | 役割 |
|----------|------|
| `App.swift` | @main エントリポイント。ContentView で IMETextViewRepresentable + GamepadVisualizerView を配置。GamepadInputManager の初期化とコールバック接続を行う |
| `GamepadResolver.swift` | かなテーブル（10行x5段）、拗音/濁点/半濁点マップ、英語 T9 テーブル、韓国語子音テーブル、GamepadAction enum、子音行・母音解決関数。Web 版 `gamepad-kana-table.ts` の Swift 移植 |
| `GamepadInputManager.swift` | GCController 接続監視、GamepadSnapshot によるボタン状態取得、モード別入力処理（日本語/英語/韓国語/中国語）、アクション実行。入力パイプラインの中核 |
| `KoreanComposer.swift` | ハングル音節合成エンジン（2ボル式ベース）。Unicode Hangul Syllables ブロックの合成式で文字を生成。子音/母音テーブル、サイクルマップ、複合母音マップを定義 |
| `PinyinEngine.swift` | Abbreviated pinyin 検索エンジン。CC-CEDICT + OpenSubtitles 頻度リストから生成した辞書 JSON をロードし、ピンイン頭文字で候補を検索。簡体/繁体の variant 切替に対応 |
| `GamepadVisualizerView.swift` | SwiftUI ビジュアライザ。モード別の D-pad/フェイスボタンラベル表示、プレビュー文字、操作ガイド。Web 版 `GamepadVisualizer.tsx` の Swift 移植 |

## 入力モード

4つのモードを Start ボタンでサイクルする。初期モードは日本語。

```
日本語 → 韓国語 → 英語 → 中国語簡体 → 日本語
```

モード切替時に以下をリセットする:
- composing 中のテキストを全確定
- eager output バッファをクリア
- 英語シフト状態（Shift / SmartCaps / CapsLock）をクリア
- 韓国語合成状態を確定（commit）
- 中国語ピンインバッファ・候補をクリア

## 日本語モード

フリック入力の方向規則を左右の手に分離した設計。

### 子音行（左手: D-pad + LB）

| 操作 | 行 |
|------|----|
| ニュートラル | あ行 |
| D-pad ← | か行 |
| D-pad ↑ | さ行 |
| D-pad → | た行 |
| D-pad ↓ | な行 |
| LB 単独 | は行 |
| LB + D-pad ← | ま行 |
| LB + D-pad ↑ | や行 |
| LB + D-pad → | ら行 |
| LB + D-pad ↓ | わ行 |

### 母音（右手: フェイスボタン + RB）

| 操作 | 母音 |
|------|------|
| RB | あ段 |
| X (左) | い段 |
| Y (上) | う段 |
| B (右) | え段 |
| A (下) | お段 |

子音行と母音の同時押しで1文字入力。

### 特殊入力

| 操作 | 出力 |
|------|------|
| LT 短押し（リリース時発火） | 拗音後置シフト（直前のかなを拗音に変換。対象外ならっ追加） |
| LT + RT 同時押し | っ |
| RT 単押し（リリース時発火） | ん |
| 右スティック ↑ | 濁点トグル（清音→濁音→半濁音→清音のサイクル） |
| 右スティック → | 長音「ー」 |
| 右スティック ↓ | 句読点（1回目=「、」、素早く2回目=「。」に差し替え） |

### 変換操作（左スティック、composing/selecting 時）

| 操作 | アクション |
|------|-----------|
| 左スティック ↓ | 変換開始 / 次の候補群 |
| 左スティック → | 次の候補 / 文節区切り拡張（selecting 時） |
| 左スティック ← | 前の候補 / 文節区切り縮小（selecting 時） |
| 左スティック ↑ | 前の候補 |

### Eager output + rollback パターン

chord ウィンドウ（300ms）内の入力変化は、前の文字を差し替え（replaceCount=1）て出力する。母音ボタンを全て離した時点で eager バッファをクリアし、確定扱いとなる。

## 英語モード

T9 ベースのレイアウト。IME をバイパスし `onDirectInsert` で直接テキスト挿入する。

### 文字テーブル（D-pad + LB で行選択、フェイスボタン + RB で列選択）

| 行 | 操作 | RB | X (左) | Y (上) | B (右) | A (下) |
|----|------|----|--------|--------|--------|--------|
| T1 | ニュートラル | 1 | ( | ? | ) | ! |
| T2 | D-pad ← | 2 | a | b | c | - |
| T3 | D-pad ↑ | 3 | d | e | f | - |
| T4 | D-pad → | 4 | g | h | i | - |
| T5 | D-pad ↓ | 5 | j | k | l | - |
| T6 | LB | 6 | m | n | o | - |
| T7 | LB + ← | 7 | p | q | r | s |
| T8 | LB + ↑ | 8 | t | u | v | - |
| T9 | LB + → | 9 | w | x | y | z |
| T0 | LB + ↓ | 0 | @ | # | - | _ |

### LT シフト（3段階）

| 操作 | 効果 |
|------|------|
| 短押し（1回） | 次の1文字だけ大文字（Shift） |
| 短押し（素早く2回、400ms 以内） | スマート Caps Lock（空白・記号入力で自動解除） |
| 長押し（500ms 以上） | 永続 Caps Lock（再度長押しで解除） |

### その他の操作

| 操作 | 出力 |
|------|------|
| RT 単押し | 数字「0」 |
| 右スティック → | スペース（素早く2回でピリオドに置換） |
| 右スティック ↓ | カンマ |
| 右スティック ↑ | アポストロフィ |

## 韓国語モード

2ボル式ベースのレイアウト。`KoreanComposer` で Unicode 音節を合成する。IME をバイパスし `onDirectInsert` で直接テキスト挿入する。

### Unicode Hangul Syllables 合成式

```
syllable = 0xAC00 + (onset * 21 + nucleus) * 28 + coda
```

- onset: 초성（子音）index 0-18
- nucleus: 중성（母音）index 0-20
- coda: 종성（받침）index 0-27（0 = 받침なし）

### 子音（D-pad + LB）

| 操作 | 子音 |
|------|------|
| ニュートラル | ㅇ |
| D-pad ← | ㄱ |
| D-pad ↑ | ㄴ |
| D-pad → | ㄷ |
| D-pad ↓ | ㄹ |
| LB 単独 | ㅁ |
| LB + D-pad ← | ㅂ |
| LB + D-pad ↑ | ㅅ |
| LB + D-pad → | ㅈ |
| LB + D-pad ↓ | ㅎ |

### 母音（フェイスボタン、字形の方向に対応）

| ボタン | 基本層 | RT シフト層（y系） |
|--------|--------|-------------------|
| RB | ㅡ (eu) | ㅣ (i) |
| X (左) | ㅓ (eo) | ㅕ (yeo) |
| Y (上) | ㅗ (o) | ㅛ (yo) |
| B (右) | ㅏ (a) | ㅑ (ya) |
| A (下) | ㅜ (u) | ㅠ (yu) |

子音と母音の同時押しで音節を入力する。

### 받침（2ボル式スタイル）

音節入力後に子音単独入力（母音ボタンなし）で받침を追加する。chord（子音+母音同時押し）で初声+中声が一括入力されるため、キーボード2ボル式の連音処理は不要。

| 操作 | 効果 |
|------|------|
| D-pad 単独（母音なし） | その子音の받침を追加 |
| LT 単独 | ㅇ받침を追加 |

받침がある状態でさらに子音単独入力すると겹받침テーブルを参照し、有効な組合せ（ㄳ, ㄵ, ㄶ, ㄺ, ㄻ, ㄼ, ㄽ, ㄾ, ㄿ, ㅀ, ㅄ の11種）なら合成、無効なら現音節を確定する。

### 子音サイクル（右スティック ↑）

받침があれば받침を、なければ초성を平音 → 激音 → 濃音のサイクルで変換する。

**초성サイクル**:

| サイクル |
|---------|
| ㄱ → ㅋ → ㄲ → ㄱ |
| ㄷ → ㅌ → ㄸ → ㄷ |
| ㅂ → ㅍ → ㅃ → ㅂ |
| ㅅ → ㅆ → ㅅ |
| ㅈ → ㅊ → ㅉ → ㅈ |

**받침サイクル**（濃音が종성に存在しないものは2段階）:

| サイクル |
|---------|
| ㄱ → ㅋ → ㄲ → ㄱ |
| ㄷ → ㅌ → ㄷ |
| ㅂ → ㅍ → ㅂ |
| ㅅ → ㅆ → ㅅ |
| ㅈ → ㅊ → ㅈ |

### 複合母音

| 操作 | 変換 |
|------|------|
| RT 単押し（ㅣ付加） | ㅏ→ㅐ, ㅓ→ㅔ, ㅑ→ㅒ, ㅕ→ㅖ, ㅗ→ㅚ, ㅜ→ㅟ, ㅡ→ㅢ |
| 右スティック →（ㅏ/ㅓ付加） | ㅗ→ㅘ, ㅚ→ㅙ, ㅜ→ㅝ, ㅟ→ㅞ |
| 右スティック ↓ | スペース→カンマ→ピリオド（トグル、400ms 以内の連打で差し替え） |

## 中国語簡体モード（简拼 = Abbreviated Pinyin）

英語 T9 テーブルを再利用してアルファベットを入力し、abbreviated pinyin（ピンインの頭文字）で候補を検索する。IME をバイパスし `onDirectInsert` で直接テキスト挿入する。

### Abbreviated Pinyin の概念

単語を構成する各漢字のピンイン頭文字（声母）だけを打って候補から選ぶ入力方式。

| 入力 | 候補例 | ピンイン |
|------|--------|---------|
| `nh` | 你好 | nǐ hǎo |
| `zd` | 知道 | zhī dào |
| `yg` | 一个 | yī gè |
| `xh` | 喜欢 | xǐ huān |
| `aq` | 安全、爱情 | ān quán, ài qíng |

声母抽出ルール:
- 2文字声母（zh, ch, sh）は1文字目のみ使用（z, c, s）
- y, w は声母として扱う
- 零声母（母音始まり）は最初の母音文字を使用（爱→a, 二→e）

### 文字入力

英語モードと同じ T9 テーブルで小文字アルファベットを入力する。入力した文字はピンインバッファに追加され、バッファ全体で `PinyinEngine.lookup()` を呼び出して候補を更新する。

### 候補操作

| 操作 | アクション |
|------|-----------|
| 左スティック ↓ | 次の候補を選択 |
| 左スティック ↑ | 前の候補を選択 |
| LS 押込み | 選択中の候補を確定・挿入 |
| RS 押込み | ピンインバッファ・候補をクリア（キャンセル） |
| 右スティック ← | バッファ末尾1文字削除（空ならバックスペース） |
| 右スティック → | スペース（バッファがあれば先頭候補を暗黙確定してから挿入） |
| 右スティック ↓ | 句読点（「，」、素早く2回で「。」に差し替え。バッファがあれば先頭候補を暗黙確定） |

### 辞書

`pinyin_abbrev.json`（~224KB）をバンドルリソースとして同梱。CC-CEDICT をピンイン情報源、OpenSubtitles 頻度リストをランキング源として `scripts/generate_pinyin_dict.py` で生成。

JSON フォーマット（繁体字フィールド `t` を含む、将来の繁体字モード対応用）:
```json
{
  "nh": [
    {"w": "你好", "t": "你好", "p": "ni3 hao3"},
    {"w": "女孩", "t": "女孩", "p": "nu:3 hai2"}
  ]
}
```

### 将来拡張: 繁体字対応

- `GamepadInputMode.chineseTraditional` を追加してバッジ「繁體」で区別
- `PinyinEngine.variant` を `.traditional` に切り替えると `t` フィールドの繁体字を表示
- 辞書 JSON は既に繁体字フィールドを含むため、データ追加不要

## 共通操作

全モードで共通のボタン割り当て。

| 操作 | アクション |
|------|-----------|
| LS 押込み | 確定（composing/selecting 時、文節単位の部分確定対応） / 改行（idle 時） |
| RS 押込み | キャンセル（composing 破棄） |
| Back ボタン | スペース（日本語は IME 経由で appendDirectKana、英語/韓国語/中国語は onDirectInsert。中国語はバッファがあれば先頭候補を暗黙確定） |
| Start + Back 同時押し | テキスト共有（composing 確定後、共有シート表示。App Intent 経由でショートカットアプリ連携も可） |
| 右スティック ← | バックスペース（全モード共通。idle 時は UITextView 側で削除、composing 時は InputManager で削除） |
| 左スティック ←/→ | カーソル移動（idle 時。英語/韓国語/中国語は常時カーソル移動。中国語で候補表示中は ↑↓ が候補選択に） |
| Start ボタン | モード切替（日本語 → 韓国語 → 英語 → 中国語簡体 → 日本語）※ Start+Back 同時押し時はスキップ |

## ビジュアライザ

`GamepadVisualizerView` はゲームパッド接続時に画面下部に表示される。

### 表示要素

- **モードバッジ**: 最上部にカプセル型で表示
  - 青 = 日本語
  - 緑 = EN
  - 紫 = 한국어
  - 赤 = 简体（中国語簡体字モード時、バッジ横にピンインバッファを表示）
- **D-pad グリッド（左側）**: モードとレイヤー（LB 押下時）に応じたラベルを表示
- **フェイスボタングリッド（右側）**: モードに応じた文字ラベルを表示
- **プレビュー文字（中央）**: 現在の子音行名 + 入力候補文字を大きく表示
- **LT/RT ボタン**: 物理コントローラー準拠の配置（LT=外側、LB=内側、RB=内側、RT=外側）
- **右スティックグリッド**: コンパクトな十字型でモード別の操作ラベルを表示（入力時ハイライト）
- **ゲームパッド名**: 接続中のコントローラー名を表示

### モード別の反映

- 英語モード: Shift / SmartCaps / CapsLock 状態がフェイスボタンのラベル（大文字/小文字）と LT ラベル（"SHIFT" / "Caps" / "CAPS"）に反映される
- 韓国語モード: RT 押下中はフェイスボタンが y系母音ラベルに切り替わる
- 日本語モード: LB 押下で D-pad ラベルが は行〜わ行レイヤーに切り替わる
- 中国語簡体モード: 英語モードと同じ十字配置ラベル（シフトなし）。LT/RT は「—」（未使用）

## エディタ

- `EditorStyle`: 28pt monospaced フォント、lineSpacing 4（動画撮影用に大きめ）
- `hidesSoftwareKeyboard: true` でソフトウェアキーボードを非表示
- `CandidatePopup`: 変換候補をカーソル直下にポップアップ表示（selecting 状態時のみ）
- フォールバックとして `KeyRouter`（ローマ字 US 配列）によるハードウェアキーボード入力も受け付ける

## 内部パラメータ

| パラメータ | 値 | 用途 |
|-----------|-----|------|
| `chordWindow` | 300ms | eager output の差し替え猶予 |
| `doubleTapWindow` | 400ms | 2度押し判定（句読点、LT ダブルタップ、スペース→ピリオド） |
| `stickThreshold` | 0.5 | スティック・トリガーの入力判定閾値 |
| `longPressThreshold` | 500ms | LT 長押し判定（英語 Caps Lock） |

## 今後の構想

- **Vision Pro 対応**: visionOS でのゲームパッド入力体験
- **App Intents 連携**: 全文を他アプリに送信するショートカット
- **記号パレット**: Select ボタンから呼び出す記号・絵文字選択 UI
- **設定画面**: モードサイクルのカスタマイズ、chord ウィンドウ調整等
- **中国語繁体字モード**: `ChineseVariant.traditional` + バッジ「繁體」で台湾向け対応。辞書 JSON は既に繁体字フィールドを含むため、PinyinEngine の variant 切替のみで実現可能
