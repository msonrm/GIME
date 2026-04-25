# Support — GiME

## About

GiME (Gamepad IME) is a multilingual text input app for iPad that uses a game controller. It supports Japanese, English, Korean, Simplified Chinese, and Traditional Chinese input, plus an experimental Devanagari layout (Sanskrit / Hindi / Marathi / Nepali).

## Requirements

- iPadOS 18.0 or later
- A game controller (MFi, Xbox, PlayStation, Switch Pro Controller, etc.)

## Frequently Asked Questions

### How do I connect a game controller?

Pair your game controller via Bluetooth in Settings > Bluetooth. Once connected, GiME will automatically detect the controller and display the visualizer.

### How do I switch input languages?

Press the **Start** button to cycle through enabled languages. You can customize which languages are included and their order in the settings (gear icon).

### The controller is connected but nothing happens?

Make sure the controller is recognized by iPadOS (check Settings > General > Game Controller). Try disconnecting and reconnecting the controller.

### How do I use Japanese kana-kanji conversion?

After entering kana with the gamepad, use the left stick up/down to select candidates and press LS click to confirm.

### What is the Devanagari mode?

An experimental layout for typing Devanagari scripts (Sanskrit, Hindi, Marathi, Nepali). It uses a clockwise varnamala arrangement and requires you to type halant (्) explicitly to form conjuncts — the same mental model as ITRANS and Google Hindi IME. Detailed mapping lives in the project documentation.

### How do I share the text I typed?

Press **Start + Back** at the same time to open the share sheet. You can also call GiME from the Shortcuts app via the provided App Intent to fetch the editor text programmatically.

## Bug Reports & Feature Requests

Please report issues or suggest features on GitHub:

https://github.com/msonrm/GIME/issues

## Contact

Masao Narumi — msonrm@icloud.com

---

# サポート — GiME

## 概要

GiME（Gamepad IME）は、iPad でゲームコントローラーを使って多言語テキスト入力を行うアプリです。日本語・英語・韓国語・中国語簡体字・中国語繁體字の入力に対応し、Sanskrit / Hindi / Marathi / Nepali 等を直接打鍵できる Devanagari モード（実験的）も搭載しています。

## 動作要件

- iPadOS 18.0 以降
- ゲームコントローラー（MFi / Xbox / PlayStation（DualSense）/ Switch（Proコントローラー）等）

## よくある質問

### ゲームコントローラーの接続方法は？

設定 > Bluetooth からゲームコントローラーを Bluetooth ペアリングしてください。接続すると、GiME が自動的にコントローラーを検出してビジュアライザを表示します。

### 入力言語の切り替え方は？

**Start ボタン** を押すと、有効な言語が順番に切り替わります。設定（歯車アイコン）から、使用する言語と切替順序をカスタマイズできます。

### コントローラーが接続されているのに反応しない場合は？

iPadOS がコントローラーを認識しているか確認してください（設定 > 一般 > ゲームコントローラ）。コントローラーの接続を解除して再接続をお試しください。

### 日本語のかな漢字変換はどう使いますか？

ゲームパッドでかなを入力した後、左スティック上下で変換候補を選択し、LS クリックで確定します。

### Devanagari モードとは？

Sanskrit / Hindi / Marathi / Nepali 等を直接打鍵できる実験的モードです。varnamala（子音表）を時計回りに配置し、conjunct（結合文字）は halant（्）を明示的に打つ方式で、ITRANS や Google Hindi IME と同じ感覚で入力できます。詳しいマッピングはプロジェクトのドキュメントを参照してください。

### 入力したテキストを他のアプリに渡すには？

**Start + Back** を同時押しすると共有シートが開きます。ショートカットアプリから App Intent 経由でエディタのテキストを取得することもできます。

## 不具合報告・機能要望

GitHub の Issue でご報告ください:

https://github.com/msonrm/GIME/issues

## お問い合わせ

Masao Narumi — msonrm@icloud.com
