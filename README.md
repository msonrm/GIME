# GIME — Gamepad IME

iPad + ゲームパッドで日本語・英語・韓国語・中国語簡体字・中国語繁體字を入力できる実験的アプリ。

[KeyLogicKit](https://github.com/msonrm/KeyLogicKit) の IME エンジンを利用し、GCController でゲームパッド入力を受け取り、かな漢字変換を実現する。

## 特徴

- **5モード対応**: 日本語（かな漢字変換）・英語（T9 ベース）・韓国語（2ボル式ハングル合成）・中国語簡体字（abbreviated pinyin）・中国語繁體字（abbreviated zhuyin）
- **フリック風ゲームパッド入力**: 左手（D-pad + LB）で子音行、右手（フェイスボタン + RB）で母音を同時押しで入力
- **かな漢字変換**: KeyLogicKit の InputManager による辞書ベース変換
- **ビジュアライザ**: モード別の動的レイヤー切替表示
- **キーボードフォールバック**: ハードウェアキーボード（ローマ字 US 配列）入力にも対応

## 要件

- iPadOS 18.0+
- Swift 6.1+
- ゲームパッド（MFi / Xbox / PlayStation / Switch Pro Controller 等）

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

## Acknowledgements

- [KeyLogicKit](https://github.com/msonrm/KeyLogicKit) — IME エンジンライブラリ (MIT License, Copyright (c) 2026 Narumi Masao)
- [AzooKeyKanaKanjiConverter](https://github.com/azooKey/AzooKeyKanaKanjiConverter) — かな漢字変換エンジン (MIT License, Copyright (c) 2023 Miwa / Ensan)

詳細は [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) を参照してください。

## ライセンス

MIT License — Copyright (c) 2026 Narumi Masao

詳細は [LICENSE](LICENSE) を参照してください。
