# GiME -- ゲームパッド日本語入力アプリ仕様書

## 概要

GiME (Gamepad IME) は、iPhone / iPad + ゲームパッドで多言語テキスト入力を行う実験的アプリである（Universal build、iPad はエディタ・ビジュアライザとも大きめフォント、iPhone は compact 幅で縮小レイアウト）。

対応モード:

- 日本語（かな漢字変換）
- 英語（T9 ベース）
- 韓国語（2ボル式 + 자모 모드）
- 中国語簡体字（abbreviated pinyin / 简拼）
- 中国語繁體字（abbreviated zhuyin / 注音首）
- Devanagari（Sanskrit / Hindi / Marathi / Nepali 等、実験的）

共通する設計方針:

- KeyLogicKit の IME エンジン（InputManager, IMETextView）を利用し、日本語のかな漢字変換を実現
- GCController でゲームパッド入力を受け取り、KeyRouter をバイパスして InputManager にかなを注入（日本語以外は IME をバイパスし `onDirectInsert` で直接挿入）
- ソフトウェアキーボードは非表示。フォールバックとしてハードウェアキーボード入力（ローマ字 US 配列）も受け付ける
- 入力内容を VRChat へ OSC 経由で送信するオプトイン機能を搭載（デフォルト OFF、詳細は `docs/gime-vrchat-osc.md`）

## アーキテクチャ

### データフロー

```
GCController
  → valueChangedHandler（non-Sendable の GCExtendedGamepad を値コピー）
  → GamepadSnapshot（Sendable な値型）
  → GamepadInputManager.handleSnapshot()（@MainActor）
  → モード別処理:
      日本語:       GamepadResolver → InputManager.appendDirectKana / replaceDirectKana
      英語:         englishTable → onDirectInsert（IME バイパス）
      韓国語:       KoreanComposer → onDirectInsert（IME バイパス）
                    ※ 자모 모드時は合成をバイパスし互換 Jamo を直接 emit
      中国語簡体:   englishTable → PinyinEngine.lookup → CandidatePopup → onDirectInsert
      中国語繁體:   注音テーブル → PinyinEngine.lookup（libchewing variant）→ onDirectInsert
      Devanagari:   GamepadResolver の Devanagari テーブル → DevanagariComposer
                    → onDirectInsert（halant 明示方式）
  → VRChat OSC 出力（opt-in）:
      composing/確定テキスト → VrChatOscOutput → /chatbox/input, /chatbox/typing
```

### UI 構成

```
GIMEApp (@main)
  └── ContentView
        ├── IMETextViewRepresentable（KeyLogicKit のエディタ）
        │     └── CandidatePopup（変換候補、selecting 時のみ表示）
        ├── GamepadVisualizerView（接続時のみ表示、画面下部）
        └── VrChatSettingsView（シート、OSC 設定 + テスト送信 + デバッグ受信ログ）
```

### 状態管理

- `InputManager`（KeyLogicKit、@Observable）: 変換状態の唯一の管理元
- `GamepadInputManager`（@Observable）: ゲームパッド接続状態、入力モード、ビジュアライザ用の UI 状態、자모 모드フラグ
- `KoreanComposer`（struct、値型）: ハングル音節合成状態（GamepadInputManager が所有）
- `DevanagariComposer`（struct、値型）: Devanagari の cluster 合成・halant 状態・長母音 post-shift（GamepadInputManager が所有）
- `PinyinEngine`（@Observable）: abbreviated pinyin 辞書ロード・検索（App.swift が所有、GamepadInputManager に注入）
- `VrChatOscOutput` / `VrChatOscSettings`（@Observable）: OSC 設定・送信ステート（App.swift が所有、opt-in）
- `ZenzaiModelManager`（@Observable）: Zenzai モデルの自動ダウンロード・有効化管理
- コールバック（`onCursorMove`, `onCursorMoveVertical`, `onDeleteBackward`, `onDirectInsert`, `onShareText`, `onIdleConfirm`）で ContentView 側に UI 操作を委譲

## ファイル構成

| ファイル | 役割 |
|----------|------|
| `App.swift` | @main エントリポイント。ContentView で IMETextViewRepresentable + GamepadVisualizerView を配置。GamepadInputManager / VrChatOscOutput / ZenzaiModelManager の初期化とコールバック接続を行う |
| `GamepadResolver.swift` | かなテーブル（10行x5段）、拗音/濁点/半濁点マップ、英語 T9 テーブル、注音テーブル、韓国語子音テーブル、Devanagari varnamala/非 varga/母音テーブル、GamepadAction enum、子音行・母音解決関数 |
| `GamepadInputManager.swift` | GCController 接続監視、GamepadSnapshot によるボタン状態取得、モード別入力処理（日本語/英語/韓国語/中国語簡体・繁體/Devanagari）、アクション実行、자모 모드管理、LS debounce。入力パイプラインの中核 |
| `KoreanComposer.swift` | ハングル音節合成エンジン（2ボル式ベース）。Unicode Hangul Syllables ブロックの合成式で文字を生成。子音/母音テーブル、サイクルマップ、複合母音マップを定義 |
| `DevanagariComposer.swift` | Devanagari cluster 合成エンジン。halant 明示方式で conjunct を構成し、母音記号（matra）・anusvara/chandrabindu・長母音 post-shift を適用。Android 版（Phase A9）の Swift 移植 |
| `PinyinEngine.swift` | Abbreviated pinyin 検索エンジン。CC-CEDICT + OpenSubtitles 頻度リストから生成した辞書 JSON をロードし、ピンイン頭文字で候補を検索。簡体/繁体の variant 切替に対応 |
| `GamepadVisualizerView.swift` | SwiftUI ビジュアライザ。モード別の D-pad/フェイスボタンラベル表示、プレビュー文字、操作ガイド、VRChat OSC バッジ |
| `ZenzaiModelManager.swift` | Zenzai モデル（GGUF）の HuggingFace からの自動ダウンロード・Application Support への保存・`inputManager.zenzaiWeightURL` への設定を管理 |
| `SendTextIntent.swift` | App Intent。ショートカットアプリからエディタのテキスト取得を可能にする |
| `OSC/OscPacket.swift` | OSC 1.0 encode/decode の自前実装（外部依存なし） |
| `OSC/OscSender.swift` | Network framework の NWConnection を使った UDP 送信 |
| `OSC/OscReceiver.swift` | NWListener を使ったデバッグ用 UDP 受信 |
| `OSC/VrChatOscOutput.swift` | chatbox 専用ラッパー。typing indicator / 144 文字制限 / 100ms debounce / カスタム avatar parameter 送信 |
| `OSC/VrChatOscSettings.swift` | UserDefaults ベースの OSC 設定永続化（@Observable） |
| `UI/VrChatSettingsView.swift` | OSC 設定画面 + テスト送信 + デバッグ受信ログ |

## 入力モード

最大 6 モードを Start ボタンでサイクルする。初期モードは日本語。ユーザーは設定画面で使用モードと順序をカスタマイズ可能（`GimeModeSettings` / UserDefaults に永続化）。

```
日本語 → 韓国語 → 英語 → 中国語簡体 → 中国語繁體 → Devanagari → 日本語
```

モード切替時に以下をリセットする:
- composing 中のテキストを全確定
- eager output バッファをクリア
- 英語シフト状態（Shift / SmartCaps / CapsLock）をクリア
- 韓国語合成状態を確定（commit）、자모 모드も全解除（Lock 含む）
- 中国語ピンインバッファ・候補をクリア
- Devanagari composer を commit、非 varga サブレイヤーを OFF

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
| 右スティック ↓ | 句読点（1回目=「、」、2回目=「。」、3回目=空白に差し替え） |

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
| 右スティック → | スラッシュ (/) |
| 右スティック ↑ | アポストロフィ |
| 右スティック ↓ | 空白→ピリオド→カンマ（多段タップで差し替え） |

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
| RT 単押し（ㅣ付加） | ㅏ→ㅐ, ㅓ→ㅔ, ㅑ→ㅒ, ㅕ→ㅖ, ㅗ→ㅚ, ㅘ→ㅙ, ㅜ→ㅟ, ㅝ→ㅞ, ㅡ→ㅢ |
| 右スティック →（ㅏ/ㅓ付加） | ㅗ→ㅘ, ㅚ→ㅙ, ㅜ→ㅝ, ㅟ→ㅞ |
| 右スティック ↓ | 空白→ピリオド（多段タップで差し替え） |

### 자모 모드（子音・母音単体入力）

`ㅋㅋㅋ` のようなチャット表現を入力するための単体 jamo モード。Android 版から逆輸入し、iOS 版でも動作する。

| 操作 | 効果 |
|------|------|
| LT 長押し | Jamo Lock（持続、再度長押しで解除） |
| LT を 2 連続短押し | Smart Jamo（一時、空白・句読点・削除・カーソル・LS・モード切替で自動解除） |

자모 모드中は KoreanComposer による合成をバイパスし、互換 Jamo（U+3131..U+3163）を直接 emit する。

| 操作 | 出力 |
|------|------|
| D-pad / LB | 互換 Jamo 子音（U+3131..U+314E） |
| フェイスボタン | 母音（U+314F..U+3163） |
| 右スティック → | 直前 jamo の連打（`ㅋㅋㅋ`） |
| 右スティック ↑ | 直前子音の 평→격→경 サイクル（ㄲㄸㅃㅆㅉ / ㅋㅌㅍㅊ アクセス） |
| LT 単独短押し | ㅇ 互換 jamo を emit |

자모 모드突入時は composing を確定する。ビジュアライザの LT ラベルは通常=「ㅇ」/ Smart=「자모」/ Lock=「LOCK」。

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
| 右スティック → | 顿号「、」（バッファがあれば先頭候補を暗黙確定してから挿入） |
| 右スティック ↓ | 句読点（「，」→「。」→空白、多段タップで差し替え。バッファがあれば先頭候補を暗黙確定） |

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

## 中国語繁體モード（注音首 = Abbreviated Zhuyin）

注音符号テーブルから声母を入力し、abbreviated zhuyin（注音の頭文字）で候補を検索する。IME をバイパスし `onDirectInsert` で直接テキスト挿入する。台湾語彙（軟體、資訊、計程車等）に最適化された辞書を使用。

### 注音テーブル（D-pad + LB で行選択、フェイスボタン + RB で列選択）

| 行 | 操作 | RB | X | Y | B | A |
|----|------|----|---|---|---|---|
| 唇音 | ニュートラル | ㄅ | ㄆ | ㄇ | ㄈ | — |
| 舌尖音 | D-pad ← | ㄉ | ㄊ | ㄋ | ㄌ | — |
| 舌根音 | D-pad ↑ | ㄍ | ㄎ | ㄏ | — | — |
| 舌面音 | D-pad → | ㄐ | ㄑ | ㄒ | — | — |
| そり舌音 | D-pad ↓ | ㄓ | ㄔ | ㄕ | ㄖ | — |
| 舌歯音 | LB | ㄗ | ㄘ | ㄙ | — | — |
| 単母音 | LB + ← | ㄚ | ㄛ | ㄜ | ㄝ | — |
| 複母音 | LB + ↑ | ㄞ | ㄟ | ㄠ | ㄡ | — |
| 鼻母音 | LB + → | ㄢ | ㄣ | ㄤ | ㄥ | ㄦ |
| 介母 | LB + ↓ | ㄧ | ㄨ | ㄩ | — | — |

### 入力フロー

注音記号を入力すると、内部で対応する pinyin 頭文字に変換されてバッファに追加される。候補検索は簡体モードと同じ `PinyinEngine.lookup()` を使用するが、辞書は台湾語彙（libchewing ベース）を参照。

```
注音入力: ㄒ + ㄒ
  → 内部変換: "xx"
  → PinyinEngine.lookup("xx", variant: .traditional)
  → 候補: 學校, 訊息, 信箱, 學系, 學習...（libchewing 頻度順）
```

注音声母 → pinyin 頭文字の変換は1対1対応。ただし `ㄓ/ㄗ→z`, `ㄔ/ㄘ→c`, `ㄕ/ㄙ→s` は衝突する（abbreviated pinyin の制約）。

### 候補操作

簡体モードと共通。左スティック ↑↓ で選択、LS click で確定、RS click でキャンセル。

### 辞書

`zhuyin_abbrev.json`（~330KB）をバンドルリソースとして同梱。台湾のオープンソース注音入力エンジン libchewing の `tsi.csv` から `scripts/generate_zhuyin_dict.py` で生成。

JSON フォーマット:
```json
{
  "xx": [
    {"w": "學校", "z": "ㄒㄩㄝˊ ㄒㄧㄠˋ", "p": "xx"},
    {"w": "訊息", "z": "ㄒㄩㄣˋ ㄒㄧˊ", "p": "xx"}
  ]
}
```

### 簡体モードとの違い

| | 簡体（简拼） | 繁體（注音首） |
|---|---|---|
| 入力テーブル | 英語 T9（アルファベット） | 注音テーブル（ㄅㄆㄇㄈ配列） |
| 辞書ソース | CC-CEDICT + OpenSubtitles | libchewing（台湾語彙） |
| 読み表示 | ピンイン (ni3 hao3) | 注音 (ㄋㄧˇ ㄏㄠˇ) |
| バッジ | 简体（赤） | 繁體（オレンジ） |

## Devanagari モード（実験的）

Sanskrit / Hindi / Marathi / Nepali 等を直接打鍵するモード。Android 版 Phase A9 の Swift 移植（`DevanagariComposer.swift` + `GamepadResolver.swift` の Devanagari テーブル群）。

**合成モデル**: ITRANS / Google Hindi IME と同じく conjunct は halant (RT tap で `्`) を明示的に打つ。自動 conjunct はしない（`नम` を `न्म` にしないため）。辞書・追加リソース不要（Unicode 合成のみ）。

### varnamala 時計回り配置

क→च→ट→त→प を時計回りに配置し、LS トグルラッチで varga（子音行）を選択する。左親指で LS と D-pad を同時操作できない物理制約への対応として、LS を direction に flick すると latch し、中立に戻しても保持、同方向再 flick で toggle off、別方向で上書きする。

各 varga 内の 平→気→濁→濁気（क→ख→ग→घ 等）は LS の flick 方向で選択する。母音は a→i→u→e を時計回りに配置。

### 非 varga サブレイヤー（L3 one-shot）

L3（LS click）で非 varga サブレイヤーに enter し、य/र/ल/व/श/ष/स/ह のいずれかを 1 文字 emit すると自動 OFF。cluster 途中で子音クラスを切替える必要がある場合（例: स्त्य = sibilant → varga → semivowel）のために state を保持する。

### 修飾子

| 操作 | 出力 |
|------|------|
| RT tap | halant（्） |
| RT + LS 方向 | カーソル移動 |
| LT + RT | visarga（ः） |
| RB 単押し | ओ |
| LT + RB | nukta（़） |
| LT + A | ऋ |
| 右スティック ↑ | anusvara（ं）↔ chandrabindu（ँ） |
| 右スティック → | 長母音 post-shift（a→ā, e→ai, o→au 等） |
| 右スティック ↓ | ␣/。/॥ サイクル |
| 右スティック ← | composer backspace |

設計詳細は `docs/gime-brahmic-expansion-memo.md` を参照。

## VRChat OSC 連携（opt-in）

入力内容を VRChat の chatbox に OSC 経由で送信する機能。デフォルト OFF で、ユーザーが `VrChatSettingsView` で明示的に有効化するまでソケットは open しない。詳細: `docs/gime-vrchat-osc.md`。

### 出力

| OSC アドレス | タイミング | ペイロード |
|-------------|----------|-----------|
| `/chatbox/input` | composing / 確定テキスト変化時（debounce 100ms） | `<text> false false`（下書き） |
| `/chatbox/input` | LS 単押し（idle 時）で確定送信 | `<text> true true`（発話+SE）→ エディタクリア |
| `/chatbox/typing` | composing 開始/終了エッジ | `true` / `false` |
| カスタム avatar parameter | composing 開始/終了エッジ（opt-in） | `int` / `float` / `bool`（VRCEmote=7 プリセット同梱） |

### 運用トグル（`VrChatOscSettings`）

| 設定 | 役割 |
|------|------|
| `commitOnlyMode` | `/chatbox/input` 下書き抑制（VRChat Mobile で chatbox UI が開くのを回避） |
| `typingIndicatorEnabled` | `/chatbox/typing` 送信の独立トグル |
| `customTypingEnabled` | composing 開始/終了エッジで任意の avatar parameter を送る（"考え中ポーズ" 等に活用） |

`Info.plist` に `NSLocalNetworkUsageDescription` を宣言し、初回送信時に iOS が Local Network 許可ダイアログを出す。

## 共通操作

全モードで共通のボタン割り当て。

| 操作 | アクション |
|------|-----------|
| LS 押込み | 確定（composing/selecting 時、文節単位の部分確定対応） / 改行（idle 時）。Devanagari では非 varga サブレイヤーのトグル。中国語で候補表示中は選択候補の確定 |
| RS 押込み | キャンセル（composing 破棄、中国語ではピンインバッファクリア） |
| Back ボタン | スペース挿入（日本語は IME 経由で appendDirectKana、その他は onDirectInsert。中国語はバッファがあれば先頭候補を暗黙確定） |
| Start + Back 同時押し | テキスト共有（composing 確定後、共有シート表示。App Intent 経由でショートカットアプリ連携も可） |
| 右スティック ← | バックスペース（全モード共通。idle 時は UITextView 側で削除、composing 時は InputManager / composer で削除） |
| 左スティック ←/→ | カーソル移動（idle 時。英語/韓国語/中国語/Devanagari は常時カーソル移動。中国語で候補表示中は ↑↓ が候補選択に） |
| Start ボタン | モード切替（Start+Back 同時押し時はスキップ）。モード順序は設定画面でカスタマイズ可能 |

### LS debounce

`lsDebounceInterval = 250ms`。DualSense 等の機械式スティックボタンの BT 経由チャタリング対策。立ち下がりエッジから 250ms 以内の再発火を無視する（Android 版と同じ方針）。

## カメラモード（将来実装計画）

> **実装ステータス**: 2026-04 時点でコードからは削除済み（`GamepadOperationMode.camera`
> ごと整理）。Apple DTS のフィードバックで「空箱の背面に隠れた指は iPad カメラ
> から認識しづらい」と判明したため、実装コストに見合う精度が出ない見込み。
> 本セクションは**先行アイデア**として残す。visionOS への移行時に、内蔵
> ハンドトラッキング経由で再挑戦する想定。

iPad のカメラ映像から Vision Hand Pose Detection で手の関節点を検出し、
ゲームパッド入力に変換するバーチャルコントローラー。
前面/背面カメラ切替可能。ビジュアライザ領域にカメラプレビュー + ボーンオーバーレイを表示。

プロトタイプ実装済み（`CameraInputManager.swift`, `CameraModeView.swift`, `CameraPreviewView.swift`）。
`Archived/Camera/` 以下に保存、ビルドからは除外。

### 設計原理: 空箱ホールド

四角い空箱（画面消灯した iPhone など）を横向きに両手で持つことを前提とする。
物理的な箱を持つことで以下の利点が得られる:

- **座標安定性**: 両手の位置関係が箱で固定されるため、Hand Pose の関節点が安定する。空中ジェスチャーに比べて誤検出が大幅に減少する
- **中指の自然な隠蔽**: 通常は中指が箱の背面に隠れるため、カメラから不可視。LT/RT 判定に利用できる（後述）
- **触覚フィードバック**: iPhone を箱として使えば、Core Haptics でタップ連動の振動フィードバックを返せる。物理コントローラーのボタン押下感に近い体験が可能

### 検出方式

| 入力 | 検出方法 |
|------|----------|
| 親指（D-pad / フェイスボタン） | 4方向（上下左右）+ ニュートラル。wrist 基準の相対位置で判定 |
| 同時打鍵 | 両親指の速度ピークが時間窓（100ms）内で同期すること。片方の指の空振り（速度ピークなし）は無視できるため、両指が「同時に急激に動き、同時に静止する」パターンのみを検出する |
| 人差し指（LB/RB） | 屈曲角度の2値判定 |
| 中指（LT/RT） | 箱の背面に隠れている中指が一瞬カメラに現れ、すぐに消える遷移をタップとして検出。両中指が同時に現れれば LT+RT 同時押し |

### iPhone 触覚フィードバック（構想）

画面消灯した iPhone を空箱として横向きに持つことで:

- Hand Pose 検出の座標アンカーとして機能
- BLE / Multipeer Connectivity で iPad と連携し、入力検出に連動して Core Haptics で振動パターンを返す
- ボタン押下・同時打鍵成立・変換確定などのイベントに応じた異なる触覚パターンで、視覚に頼らないフィードバックを実現

### Apple Vision Pro 対応（構想）

カメラモードの Hand Pose 検出は、Apple Vision Pro のハンドトラッキングと本質的に同じパイプラインである。
Vision Pro では OS レベルで高精度なハンドトラッキングが常時提供されるため、
iPad のカメラモードで検証した空箱ホールド + 同時打鍵検出のアルゴリズムを
visionOS の ARKit Hand Tracking API にそのまま移植できる。

- **カメラ不要**: Vision Pro は内蔵センサーでハンドトラッキングを行うため、カメラセッション管理が不要。検出精度も iPad カメラより大幅に向上
- **空間 UI**: ビジュアライザを空間上にフローティング表示し、手の位置に追従させることが可能
- **触覚フィードバック**: 空箱として iPhone を持つ方式に加え、将来的には触覚デバイスとの連携も視野に入る
- **入力パイプラインの共通化**: `GamepadInputManager` のコールバックインターフェースはプラットフォーム非依存なので、入力ソース（GCController / Vision Hand Pose / ARKit Hand Tracking）を差し替えるだけで変換エンジン以降を共有できる
- **空箱オーバーレイキーボード**: 手に持った空箱の表面に QWERTY 等のキーボードレイアウトを AR オーバーレイで投影する。詳細は [AR Box Keyboard コンセプト文書](../../docs/ar-box-keyboard-concept.md) を参照

## ビジュアライザ

`GamepadVisualizerView` はゲームパッド接続時に画面下部に表示される。

### 表示要素

- **モードバッジ**: 最上部にカプセル型で表示
  - 青 = 日本語
  - 緑 = EN
  - 紫 = 한국어（자모 모드時は「자모」/「LOCK」表示）
  - 赤 = 简体（中国語簡体字モード時、バッジ横にピンインバッファを表示）
  - 橙 = 繁體（中国語繁體字モード時、注音バッファを表示）
  - 橙 = DEV（Devanagari モード時）
- **D-pad グリッド（左側）**: モードとレイヤー（LB 押下時、Devanagari は LS latch）に応じたラベルを表示
- **フェイスボタングリッド（右側）**: モードに応じた文字ラベルを表示
- **プレビュー文字（中央）**: 現在の子音行名 + 入力候補文字を大きく表示
- **LT/RT ボタン**: 物理コントローラー準拠の配置（LT=外側、LB=内側、RB=内側、RT=外側）
- **右スティックグリッド**: コンパクトな十字型でモード別の操作ラベルを表示（入力時ハイライト）
- **VRChat OSC バッジ**: OSC 有効時に送信状態（Idle / Sending / Error）を表示
- **ゲームパッド名**: 接続中のコントローラー名を表示

### モード別の反映

- 英語モード: Shift / SmartCaps / CapsLock 状態がフェイスボタンのラベル（大文字/小文字）と LT ラベル（"SHIFT" / "Caps" / "CAPS"）に反映される
- 韓国語モード: RT 押下中はフェイスボタンが y系母音ラベルに切り替わる。자모 모드時は互換 Jamo ラベルに切り替わり、LT ラベルは「ㅇ」/「자모」/「LOCK」を状態に応じて表示
- 日本語モード: LB 押下で D-pad ラベルが は行〜わ行レイヤーに切り替わる
- 中国語簡体モード: 英語モードと同じ十字配置ラベル（シフトなし）。LT/RT は「—」（未使用）
- 中国語繁體モード: 注音テーブル配置のラベル
- Devanagari モード: LS latch 方向で varga を選択するため、D-pad ラベルは latch 状態に応じて書き換わる。非 varga サブレイヤー有効時は य/र/ल/व/श/ष/स/ह の配置に切替

## エディタ

- `EditorStyle`: 28pt monospaced フォント、lineSpacing 4（動画撮影用に大きめ）
- `hidesSoftwareKeyboard: true` でソフトウェアキーボードを非表示
- `CandidatePopup`: 変換候補をカーソル直下にポップアップ表示（selecting 状態時のみ）
- フォールバックとして `KeyRouter`（ローマ字 US 配列）によるハードウェアキーボード入力も受け付ける

## 内部パラメータ

| パラメータ | 値 | 用途 |
|-----------|-----|------|
| `chordWindow` | 300ms | eager output の差し替え猶予 |
| `doubleTapWindow` | 400ms | 多段タップ判定（R🕹↓ 句読点サイクル、LT ダブルタップ） |
| `stickThreshold` | 0.5 | スティック・トリガーの入力判定閾値 |
| `longPressThreshold` | 500ms | LT 長押し判定（英語 Caps Lock、韓国語 Jamo Lock） |
| `lsDebounceInterval` | 250ms | LS クリックの立ち下がりエッジ debounce（DualSense チャタリング対策） |
| OSC debounce | 100ms | `/chatbox/input` 下書き送信間隔 |
| `MAX_CHATBOX_LEN` | 144 文字 | VRChat chatbox の制限に合わせたトリム |

## 今後の構想

- **Vision Pro 対応**: visionOS でのゲームパッド入力体験
- **Devanagari の他 Brahmic スクリプトへの拡張**: Bengali / Tamil / Malayalam 等（`docs/gime-brahmic-expansion-memo.md` 参照）
- **注音フル入力モード**: abbreviated zhuyin では候補が多すぎる場合に、韻母・介母も入力して候補を絞り込めるハイブリッド方式
- **記号パレット**: Select ボタンから呼び出す記号・絵文字選択 UI
- **Apple Vision Pro 対応**: 空箱ホールドの発想を visionOS の ARKit Hand Tracking へ移植する構想（下記「カメラモード」参照）
