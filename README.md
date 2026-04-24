# GiME — Gamepad IME

iPad / Android + ゲームパッドで日本語・英語・韓国語・中国語簡体字・中国語繁體字・**Devanagari（Android のみ）** を入力できる実験的アプリ。

iPad 版は [AzooKey](https://github.com/azooKey/AzooKeyKanaKanjiConverter) の IME エンジン、Android 版は [KazumaProject/JapaneseKeyboard](https://github.com/KazumaProject/JapaneseKeyboard) の変換エンジン（vendored）を利用。どちらもゲームパッド入力を直接受け取り、自前のパイプラインで日本語入力を実現する。

- **iPad 版**: `Sources/GIME/` / Swift Playgrounds or Xcode でビルド
- **Android 版**: `android/` / Android Studio でビルド、または [Releases](https://github.com/msonrm/GIME/releases) から署名済み APK をダウンロード
  - Android アプリ名: **GIME**（applicationId: `com.msonrm.gime`）
  - Android 10 以上
  - システム IME として任意アプリに入力、または VRChat OSC で chatbox に直接送信可能

## 特徴

- **6モード対応**: 日本語（かな漢字変換）・英語（T9 ベース）・韓国語（2ボル式ハングル合成）・中国語簡体字（abbreviated pinyin）・中国語繁體字（abbreviated zhuyin）・**Devanagari**（varnamala 時計回り、Android のみ）
- **フリック風ゲームパッド入力**: 左手（D-pad + LB）で子音行、右手（フェイスボタン + RB）で母音を同時押しで入力
- **かな漢字変換**: KeyLogicKit の InputManager による辞書ベース変換
- **ビジュアライザ**: モード別の動的レイヤー切替表示
- **キーボードフォールバック**: ハードウェアキーボード（ローマ字 US 配列）入力にも対応

## 要件

- iPadOS 18.0+
- Swift 6.1+
- ゲームパッド（MFi / Xbox / PlayStation（DualSense）/ Switch（Proコントローラー）等）

## インストール

Swift Playgrounds または Xcode で開く:

```
git clone https://github.com/msonrm/GIME.git
open GIME
```

## 入力モード

Start ボタンでモードをサイクルする（日本語 → 韓国語 → 英語 → 中国語簡体 → 中国語繁體 → 日本語）。

### 日本語モード

| 左手（子音行） | 右手（母音） |
|---|---|
| ニュートラル = あ行 | RB = あ段 |
| D-pad ← = か行 | X = い段 |
| D-pad ↑ = さ行 | Y = う段 |
| D-pad → = た行 | B = え段 |
| D-pad ↓ = な行 | A = お段 |
| LB = は行 | |
| LB + D-pad = ま/や/ら/わ行 | |

拗音（LT）、ん（RT）、濁点トグル（右スティック ↑）、句読点（右スティック ↓）で補助入力。

### 英語モード

T9 ベース。D-pad + LB で行選択、フェイスボタン + RB で列選択。LT で Shift / SmartCaps / CapsLock の3段階シフト。

### 韓国語モード

2ボル式ベース。子音（D-pad + LB）と母音（フェイスボタン）の同時押しで音節入力。받침は子音単独入力で追加。RT シフトで y系母音。

### 中国語簡体モード

Abbreviated pinyin（简拼）。英語 T9 テーブルでピンインの頭文字を入力し、候補リストから選択。例: `nh` → 你好、`zd` → 知道。

### 中国語繁體モード

Abbreviated zhuyin（注音首）。注音符号テーブル（ㄅㄆㄇㄈ配列）で注音の声母を入力し、候補リストから選択。例: ㄋㄏ → 女孩、ㄒㄒ → 學校。台湾語彙（軟體、資訊、計程車等）に最適化。

### Devanagari モード（Android のみ、Phase A9）

Sanskrit / Hindi / Marathi / Nepali 等を gamepad で直接打鍵。**varnamala（वर्णमाला）朗唱順を時計回りに配置** した layout で、Devanagari ネイティブが朗唱と指の動きを同期させて blind typing できる。

- **LS 方向 (toggle latch) → varga**: ↑क →च ↓ट ←त 中立प
- **D-pad ↑→↓← → varga 内 stop**: 無気無声 / 有気無声 / 無気有声 / 有気有声
- **LB → 現 varga の鼻音**: ङ/ञ/ण/न/म（LS latch に動的追随）
- **Face buttons (Y/B/A/X) → 母音**: अ/इ/उ/ए、LT + A = ऋ、RB = ओ、LT + RB = nukta ़
- **RT tap → halant ्** / **RT + LS → カーソル移動** / **LT + RT → visarga ः**
- **RS ↑ → anusvara ं ↔ chandrabindu ँ** / **RS → → 長母音 post-shift** (a→ā, e→ai, o→au 等)
- **L3 (LS click) → 非 varga サブレイヤー one-shot**: D-pad で य/र/ल/व（LT OFF）/ श/ष/स/ह（LT ON）
- **合成モデル**: ITRANS / Google Hindi IME と同じく conjunct は halant を明示（`नम` を `न्म` にしないため）

検証済み: `सत्यमेव जयते` / `ओम्` / `नमः` / `अतः` / `दुःख`

詳細設計: [`docs/gime-brahmic-expansion-memo.md`](docs/gime-brahmic-expansion-memo.md)

### テキスト操作モード

Back ボタン（idle 時）でトグル。文単位のナビゲーション・選択・並べ替えを行う。モード中は通常の文字入力を受け付けない。

| 操作 | アクション |
|------|-----------|
| Back ボタン | テキスト操作モード解除 |
| 左スティック ←/↑ | 前の文頭へカーソル移動 |
| 左スティック →/↓ | 次の文末へカーソル移動 |
| RB + 左スティック ←→ | 1文字ずつカーソル移動 |
| RB + 左スティック ↑↓ | 1行ずつカーソル移動 |
| RT + 左スティック ←/↑ | 文を前へ入れ替え |
| RT + 左スティック →/↓ | 文を後ろへ入れ替え |
| D-pad → | スマート選択 拡大（括弧内→括弧含む→文） |
| D-pad ← | スマート選択 縮小 |
| D-pad ↑ | 文単位選択: 後方に伸ばす |
| D-pad ↓ | 文単位選択: 前方に伸ばす |

## アーキテクチャ

```
GCController
  → GamepadSnapshot（Sendable な値型）
  → GamepadInputManager（モード別処理）
  → 日本語:     GamepadResolver → InputManager（かな漢字変換）
    英語:       T9 テーブル → 直接テキスト挿入
    韓国語:     KoreanComposer → 直接テキスト挿入
    中国語簡体: T9 テーブル → PinyinEngine → 候補選択 → 直接テキスト挿入
```

## ファイル構成

| ファイル | 役割 |
|----------|------|
| `App.swift` | @main、IMETextView + GamepadVisualizer の配置 |
| `GamepadResolver.swift` | かな/英語/韓国語テーブル、アクション enum |
| `GamepadInputManager.swift` | GCController → 入力パイプライン（4モード） |
| `KoreanComposer.swift` | ハングル音節合成エンジン（2ボル式） |
| `PinyinEngine.swift` | Abbreviated pinyin 辞書検索エンジン |
| `GamepadVisualizerView.swift` | SwiftUI ビジュアライザ |

詳細仕様は [SPEC.md](Sources/GIME/SPEC.md) を参照。

## 将来構想

- **カメラモード**: iPad のカメラで Hand Pose Detection を行い、空箱を持った手のジェスチャーでゲームパッド入力を代替するバーチャルコントローラー（プロトタイプ実装済み）
- **Apple Vision Pro 対応**: ハンドトラッキングで同じ入力パイプラインを visionOS に移植
- **AR Box Keyboard**: 空箱の表面にキーボードレイアウトを AR オーバーレイで投影する仮想キーボード — [コンセプト文書](docs/ar-box-keyboard-concept.md)

## Acknowledgements

- [KeyLogicKit](https://github.com/msonrm/KeyLogicKit) — IME エンジンライブラリ (MIT License, Copyright (c) 2026 Narumi Masao)
- [AzooKeyKanaKanjiConverter](https://github.com/azooKey/AzooKeyKanaKanjiConverter) — かな漢字変換エンジン (MIT License, Copyright (c) 2023 Miwa / Ensan)

詳細は [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) を参照してください。

## ライセンス

MIT License — Copyright (c) 2026 Narumi Masao

詳細は [LICENSE](LICENSE) を参照してください。
