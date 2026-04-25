# Privacy Policy — GiME

*Last updated: April 25, 2026*

## Overview

GiME ("the App") is a multilingual text input tool for iPad that uses a game controller. The App can optionally route input to VRChat via OSC. This privacy policy explains how the App handles user data.

## Data Collection

**The App does not collect, store, or transmit any personal data to the developer or any third party.**

Specifically:

- **No keystroke logging to external servers** — The App processes game controller button inputs solely for real-time text input. Inputs are not recorded for any remote transmission, analytics, or telemetry.
- **No analytics or tracking** — The App contains no analytics SDKs, no tracking pixels, and no telemetry of any kind.
- **No user accounts** — The App does not require or support user accounts or sign-in.

## Network Communication (OSC Feature)

The App contains an **opt-in** VRChat OSC integration feature. This feature is **disabled by default**. The behavior is:

- **When OSC mode is OFF** (default): The App makes no network communication. No sockets are opened.
- **When OSC mode is ON** (user-enabled in settings):
  - The App opens a UDP socket and sends OSC messages to the IP address and port that the user explicitly configured in the settings screen (default `127.0.0.1:9000`).
  - The data sent consists exclusively of:
    - The text the user is composing or has confirmed for chatbox
    - Typing indicator status (true / false)
    - Optional custom avatar parameter values (when the user enables the custom typing feature)
  - The destination is **only the user-specified IP and port**. No data is sent to the developer's servers, third-party analytics, or other destinations.
  - The transmission is over UDP and **not encrypted**. Users should only use this feature on networks they trust (typically a home LAN).
- **Optional debug receiver** (also opt-in): When enabled, the App opens a UDP socket to receive OSC messages on the user-specified port. Received messages are displayed in the in-app debug log only and are not stored or transmitted elsewhere.

All text conversion and dictionary lookups themselves happen entirely on-device regardless of OSC state.

## Optional Zenzai Model Download

Zenzai (neural kana-kanji conversion) is **disabled by default**. When the user enables it in the settings screen, the App downloads a language model file (~74MB GGUF) from Hugging Face once, saves it to Application Support, and uses it on-device. Only the Hugging Face model URL is contacted, and only when the user explicitly opts in. No user text is sent during this download.

## On-Device Storage

The App stores the following data locally on your device only:

- **Editor text** — The current text in the editor is stored via UserDefaults for App Intent (Shortcuts) integration.
- **User preferences** — Language mode cycle order, OSC settings (enabled state, IP, port, typing indicator toggle, custom typing preset), and other settings, stored via UserDefaults.
- **Zenzai model** — If downloaded by the user, stored in Application Support.

This data never leaves your device and is automatically deleted when you uninstall the App.

## Permissions

- **Local Network** (`NSLocalNetworkUsageDescription`) — Required only for the optional VRChat OSC feature (UDP send/receive). iOS will prompt for permission on first use. When OSC mode is OFF, no network sockets are opened and no prompt appears.

## Third-Party Services

The App does not integrate with any third-party services, advertising networks, or analytics platforms.

## Open-Source Components

The App uses the following open-source components, all of which run entirely on-device:

- **AzooKeyKanaKanjiConverter** (MIT License) — A dictionary-based kana-kanji conversion engine for Japanese input.
- **CC-CEDICT** (CC BY-SA 4.0) — A Chinese-English dictionary used as the data source for Simplified Chinese vocabulary and pinyin information.
- **libchewing** (LGPL v2.1) — A Traditional Chinese input method library, used as the data source for Traditional Chinese vocabulary and zhuyin information.

## Children's Privacy

The App does not collect any data from any users, including children.

## Changes to This Policy

If this privacy policy is updated, the revised version will be posted here with an updated date.

## Contact

Masao Narumi — msonrm@icloud.com

---

# プライバシーポリシー — GiME

*最終更新: 2026年4月25日*

## 概要

GiME（以下「本アプリ」）は、iPad でゲームコントローラーを使って多言語テキスト入力を行うツールです。オプションで入力内容を VRChat へ OSC 経由で送信することもできます。このプライバシーポリシーは、本アプリがユーザーデータをどのように取り扱うかを説明します。

## データの収集

**本アプリは、個人データを開発者および第三者に対して収集・保存・送信することは一切ありません。**

具体的には:

- **外部サーバーへのキー入力記録なし** — ゲームコントローラーのボタン入力はリアルタイムのテキスト入力処理にのみ使用されます。分析・テレメトリ・遠隔送信のために記録することはありません。
- **分析・トラッキングなし** — 分析 SDK、トラッキングピクセル、テレメトリは一切含まれていません。
- **ユーザーアカウント不要** — 本アプリはユーザーアカウントやサインインを必要としません。

## ネットワーク通信（OSC 機能）

本アプリにはオプトインの VRChat OSC 連携機能があります。この機能は**デフォルトで無効**です。挙動は以下の通りです:

- **OSC が OFF の場合**（デフォルト）: 本アプリはネットワーク通信を行いません。ソケットも開かれません。
- **OSC を ON にした場合**（ユーザーが設定画面で有効化した場合）:
  - ユーザーが設定画面で明示的に指定した IP アドレスとポート（デフォルト `127.0.0.1:9000`）に対して UDP ソケットを開き、OSC メッセージを送信します。
  - 送信されるデータは以下に限定されます:
    - ユーザーが composing 中、または chatbox に確定したテキスト
    - Typing indicator の状態（true / false）
    - カスタム avatar parameter 値（「カスタム typing」機能を有効化した場合のみ）
  - 送信先は**ユーザーが指定した IP とポートのみ**です。開発者のサーバーやサードパーティ分析等へのデータ送信は一切ありません。
  - UDP での送信であり**暗号化されません**。信頼できるネットワーク（通常は家庭内 LAN）でのみご利用ください。
- **デバッグ受信機能**（同じくオプトイン）: 有効化すると、ユーザー指定のポートで OSC メッセージを受信する UDP ソケットを開きます。受信したメッセージはアプリ内のデバッグログにのみ表示され、保存や転送は行いません。

テキスト変換・辞書検索自体は OSC の有効/無効に関わらず、すべてデバイス上で完結します。

## Zenzai モデルのダウンロード（任意）

Zenzai（ニューラル系かな漢字変換）は**デフォルトで無効**です。ユーザーが設定画面で有効化した場合、Hugging Face から 1 回だけ言語モデルファイル（約 74MB の GGUF）をダウンロードし、Application Support に保存してからオンデバイスで使用します。接続先は Hugging Face のモデル URL のみで、ユーザーのテキストがダウンロード時に送信されることはありません。

## デバイス上のデータ保存

本アプリは以下のデータをデバイス上にのみ保存します:

- **エディタテキスト** — エディタの現在のテキストを UserDefaults に保存します（App Intent / ショートカット連携用）。
- **ユーザー設定** — 言語モードの切替順序、OSC 設定（有効状態・IP・ポート・typing indicator・カスタム typing プリセット）、その他の設定を UserDefaults に保存します。
- **Zenzai モデル** — ユーザーがダウンロードを実行した場合のみ、Application Support に保存します。

これらのデータはデバイス外に送信されることはなく、アプリを削除すると自動的に消去されます。

## 権限

- **ローカルネットワーク** (`NSLocalNetworkUsageDescription`) — VRChat OSC 連携機能でのみ使用します（UDP 送受信）。初回使用時に iOS が許可ダイアログを表示します。OSC が OFF の間はソケットを開かず、ダイアログも表示されません。

## サードパーティサービス

本アプリはサードパーティサービス、広告ネットワーク、分析プラットフォームとの連携を行いません。

## オープンソースコンポーネント

本アプリは以下のオープンソースコンポーネントを使用しています。すべてデバイス上でのみ動作します:

- **AzooKeyKanaKanjiConverter** (MIT License) — 辞書ベースのかな漢字変換エンジン。
- **CC-CEDICT** (CC BY-SA 4.0) — 中国語簡体字の語彙・ピンイン情報のデータソースとして使用。
- **libchewing** (LGPL v2.1) — 中国語繁體字の語彙・注音情報のデータソースとして使用。

## お子様のプライバシー

本アプリはお子様を含むすべてのユーザーからデータを収集しません。

## ポリシーの変更

本プライバシーポリシーが更新された場合、更新日とともにこのページに掲載します。

## お問い合わせ

Masao Narumi — msonrm@icloud.com
