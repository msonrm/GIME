# GIME × VRChat OSC セットアップガイド

ゲームパッドで GIME を使って、ヘッドセット外さずに VRChat の chatbox に
日本語/韓国語/中国語/英語を入力する方法。

## 全体像

```
[BT ゲームパッド] ──BT──→ [スマホ + GIME] ──UDP/OSC──→ [Quest / PC + VRChat]
                                                              ▲
[Quest Touch Controllers] ─OpenXR─────────────────────────────┘
```

- GIME は **スマホやタブレット** 側で動く Android IME
- VRChat 本体（Quest / PC / Mobile）とは LAN 越しに OSC で繋がる
- 入力デバイスを物理的に分けることで、アバター操作と文字入力が衝突しない

## 必要なもの

| 役割 | 候補 |
|---|---|
| **入力端末** | GIME を入れた Android スマホ or タブレット |
| **入力デバイス** | Bluetooth ゲームパッド (DualSense / Xbox / 8BitDo 等) |
| **VRChat 端末** | Quest 単独 / PC VRChat / VRChat Mobile (4GB+ RAM 推奨) |
| **ネットワーク** | 同一 Wi-Fi (ゲスト Wi-Fi の Client Isolation は不可) |

## 使い分けシナリオ

VRChat のプラットフォームと手元の端末によって、最適な運用形態と設定が
変わります。セットアップを始める前に、自分の環境を下表で確認してください。

| VRChat が動く端末 | GIME 側端末 | 推奨モード | `commitOnlyMode` | composing 可視化 |
|---|---|---|---|---|
| Quest 単独 / PC VRChat (Desktop) | Android スマホ | IME or バブル | **OFF** | VRChat アバターの吹き出し preview（候補 cycle も反映）|
| Quest 単独 / PC VRChat (Desktop) | iPad (GiME iOS) | GiME iOS アプリ | OFF | GiME iOS のアプリ画面内 |
| VRChat Mobile (Android) | 同じ Android | **バブルモード** | **ON** | バブルの ✈ プレビュー行 |
| VRChat Mobile (iPad) | **別の Android スマホ**（推奨）| バブルモード | ON | バブル |
| VRChat Mobile (iPad) | 同じ iPad (Stage Manager) | GiME iOS | ON | アプリ画面、フォーカス切替が必要 |
| Quest に GIME を sideload | 同じ Quest | 非推奨 | — | VR セッション中は Android オーバーレイが描画されない |

※ **GiME iOS は現在 iPad 専用**（`TARGETED_DEVICE_FAMILY: "2"`、iPadOS 18.0+）。
iPhone は未対応。iPhone で OSC 送信したい場合は Android 端末で GIME を使う
か、iPhone 対応リクエストを Issue で送ってください。

### なぜ `commitOnlyMode` を切り替えるのか

`/chatbox/input <text> <sendImmediately> <playSound>` の 2 番目 `sendImmediately`
の挙動がプラットフォームで食い違うためです:

| 値 | Quest / PC (Desktop) | VRChat Mobile |
|---|---|---|
| `false` (下書き) | アバター頭上の吹き出しに preview 表示（ログに残らない）| **chat 入力 UI が自動展開**され IME フォーカスが奪われる |
| `true` (確定) | チャットログに送信 + 吹き出しに表示 | UI を開かずに送信（"bypass the keyboard"）|

- **Desktop/Quest 相手なら** `commitOnlyMode = OFF` で下書き送信が有効になり、
  打鍵・変換・候補 cycle がすべて相手の吹き出しにリアルタイムに反映される。
  これが「バブルが無くても候補を選べる」理由。
- **VRChat Mobile 相手なら** 下書きが UI を開いてしまうので `commitOnlyMode = ON`
  にして LS 確定時のみ `true` で送る。composing は OSC 経由では見せられないので、
  Android バブル（自分の画面内）で確認する。

### iPad VRChat が構造的にツラい理由

- iPadOS には Android の `SYSTEM_ALERT_WINDOW` 相当のオーバーレイ API が存在せず、
  GIME バブルに該当する機能を iOS に実装できない
- VRChat Mobile 側の挙動で OSC 下書き preview も塞がれている
- 結果、iPad 単体で VRChat をやりながら快適に日本語を打つ手段が無い

現実的な緩和策:

1. **別の Android スマホを併用**（最も快適）: iPad はフルスクリーン維持、
   Android バブル側で composing を見ながら LS で送信
2. **Stage Manager で iPad 分割**: iPad VRChat をウィンドウ化、GiME iOS を横並び。
   ゲームパッド入力は都度フォーカスされた側にしか届かないのでピンポンが発生
3. **物理的にスマホを覗き込む**: スマホ版 GIME で入力、画面を横目で確認

### Quest に GIME を sideload する案

APK 自体はサイドロード可能だが、Quest OS は VR アプリ実行中に他 Android アプリを
オーバーレイ表示する API を開放していない。かつ同じゲームパッドをアバター操作と
文字入力で取り合うため実用的でない。**入力端末は物理的に分けるのが鉄則**。

## セットアップ手順

### 1. VRChat 側の OSC 有効化

Quest / PC VRChat:

1. ランチパッドメニューを開く
2. Options → OSC → "Enabled" を ON
3. （PC 版なら Send Port = 9000、Receive Port = 9001 がデフォルト）

VRChat Mobile:

1. メニュー → 設定 → OSC
2. 受信ポート（通常 9000）を確認・有効化
3. ※ iPad Gen10 等 4GB 未満の端末はメモリ不足でワールドに入れない場合あり

### 2. 端末の IP アドレス確認

VRChat を動かしている端末（Quest / PC / iPad）の IP を確認:

- **Quest**: 設定 → Wi-Fi → 接続中ネットワーク → 詳細
- **PC (Win)**: コマンドプロンプトで `ipconfig` → Wi-Fi の IPv4
- **PC (macOS)**: システム設定 → ネットワーク → Wi-Fi → 詳細
- **iPad**: 設定 → Wi-Fi → 接続中ネットワーク横の i マーク

例: `192.168.1.123`

### 3. GIME 側の設定

1. GIME アプリを起動
2. ヘッダーの「VRChat」ボタン
3. 「VRChat OSC モード」を **ON**
4. 送信先 IP に手順 2 で確認した IP を入力
5. ポートは `9000`（VRChat デフォルト）

ON にすると以降、ビジュアライザ左上に紫カプセルの **「✈️ VRChat OSC」バッジ** が表示されます（Activity 上ではタップで VRChat 設定画面を再オープン、IME 上では表示のみ）。バッジが出ている=送信準備完了の目印。

### 4. GIME の使い方: IME モード / バブルモード

GIME には VRChat OSC と連携できる 2 通りの使い方があります。

#### A. IME モード（Android システム IME として動作）

1. GIME アプリのヘッダーから「IME設定」→ システム設定で GIME を ON
2. 「IME切替」→ GIME を選択
3. 任意のテキスト欄（GIME アプリ内のエディタでも可）にフォーカス

テキスト欄への入力と並行して chatbox に OSC が飛ぶ dual output。
テキスト編集もしたい用途向け。

#### B. バブルモード（フローティングオーバーレイ）

1. ヘッダーの「バブル」ボタンをタップ
2. 初回は「他のアプリの上に重ねて表示」権限を付与（Android 16+ の
   サイドロード APK は ⋮ → 「制限付き設定を許可」が先に必要）
3. VRChat を起動し、バブルをタップしてフォーカスを与える

バブルはビジュアライザだけの小さなフローティング窓。IME を経由せず
直接 OSC で chatbox に送るので、VRChat Mobile のように「IME 入力を
検知すると chat UI を自動で開いてしまう」環境でも運用できる。

バブルをタップ = 入力受付（不透明）、バブル外タップ = 休止（半透明、
ゲームパッド入力は下の VRChat に戻る）の二状態で、VRChat 操作と
文字入力を切替える。

### 5. ゲームパッドペアリング

スマホの Bluetooth 設定で、ゲームパッドをペアリング。
ペアリング先は **VRChat の端末ではなく、GIME のスマホ** に注意。

## 使い方

### 基本フロー

1. 任意のテキスト欄にフォーカス（GIME アプリの編集欄が便利、または
   メモアプリ等）
2. ゲームパッドで日本語入力（既存の GIME と同じ操作）
3. 入力中、VRChat 側のアバター頭上に **「...」typing indicator** + **下書きテキスト**
   が表示される
4. 入力途中で何度 LS（左スティッククリック）を押しても、下書きが
   chatbox に蓄積される（複数文節の確定が可能）
5. 入力欄が空になっている状態で **もう一度 LS を押すと送信**
   - chatbox に確定メッセージとして表示（通知音付き）
6. typing indicator は自動で OFF

### 例

```
1. ゲームパッドで「きょうは」と入力 → 変換「今日は」
   → VRChat 上の自分の頭上に「今日は」（下書き、… マーク付き）
2. LS 押下で確定 → 累積に「今日は」追加
3. 「げんき」と入力 → 変換「元気」
   → VRChat 上「今日は元気」（下書き）
4. 「？」を入力 → 累積「今日は元気？」
5. 何も composing 中でない状態で LS 押下
   → /chatbox/input "今日は元気？" true true
   → VRChat 上に通知音つきで確定送信、誰でも見える
```

## トラブルシュート

### chatbox に何も出ない

1. **GIME 設定で「自受信へ送信 (loopback)」ボタン** を押す → ログに出れば
   GIME の OSC 送信機能は動作している
2. **iPad などに OSC モニタアプリ（Protokol 等）** を入れて、GIME の
   送信先をその端末の IP にして送信 → 受信できるかネットワーク到達性確認
3. 受信できているのに VRChat に出ない場合:
   - VRChat 側 OSC 設定が ON か再確認
   - VRChat のワールドが OSC を許可しているか（一部ワールドで制限あり）
   - VRChat が同一 Wi-Fi に繋がっているか
4. 同じ Wi-Fi なのに届かない場合:
   - ルーターの "Client Isolation" / "AP Isolation" 設定を確認
   - ゲスト Wi-Fi は通信遮断されることが多い

### VRChat Mobile で chatbox の「入力 UI」が自動で開いてしまう

VRChat Mobile は `/chatbox/input` の下書き（sendMessage=false）を受信すると
chat 入力欄の UI を自動展開し、そこに Android システム IME のフォーカスが
奪われてしまう。

対策: GIME の VRChat 設定画面で **「確定時のみ送信」を ON**。下書きを一切
送らず LS 確定時だけ `sendMessage=true` で送るので UI が開かない（はず。
ドキュメント仕様では `b=true` は "bypass the keyboard"）。

副作用として composing 中は VRChat 側に何も出ないため、バブル表示の
✈ プレビュー行か IME の composing 下線を頼りに入力することになる。

### 送信後に VRChat に戻りたい

バブル表示限定で、VRChat 設定画面の **「送信後にフォーカスを戻す」**
(デフォルト ON) で、LS 送信後に自動でバブルを非アクティブ化し、ゲーム
パッド入力を下の VRChat に返す運用ができる。連続で話したい場合は OFF。

### タイピングインジケータを OFF にしたい / ON にしたい

VRChat 設定画面の **「タイピングインジケータ」** で `/chatbox/typing`
の送信可否を単独トグル可能。「確定時のみ送信」と独立しており、
commitOnly 時でも typing だけは送れる、逆に常時 OFF にもできる。

### 日本語が変な文字列で送られる

- T9 英語モードの rollback が一瞬流れる場合あり（debounce 100ms で
  対策済みだが完全には消えない）
- LS で確定すれば最終的な変換結果が確定送信される

### Quest 単独で GIME を直接動かしたい

技術的には Quest に sideload できるが、入力競合（同じゲームパッドが
VRChat の avatar 操作と GIME の文字入力で取り合う）が発生するため
推奨しない。**スマホ + ゲームパッド分離構成** が安定。

## 動作確認済みの構成

| 端末 A (GIME 側) | 端末 B (VRChat 側) | 状態 |
|---|---|---|
| Pixel 10 + DualSense | (Protokol 受信のみ、VRChat 未起動) | ✅ パケット到達確認 |
| Pixel 10 + DualSense | iPad VRChat Mobile | iPad メモリ不足で未検証 |
| Pixel 10 + DualSense | PC VRChat (Steam) | 未検証 |
| Pixel 10 + DualSense | Quest 3 / Quest 2 単独 | 未検証 |

実機動作報告募集中（GitHub Issues 等で）。

## 仕様詳細

- 送信プロトコル: OSC 1.0 (UDP)
- 送信アドレス:
  - `/chatbox/typing <bool>` — typing indicator の on/off
  - `/chatbox/input <string> <bool sendImmediately> <bool playSound>` —
    chatbox メッセージ
- 文字数制限: 144 文字（VRChat 仕様、超過分は自動でトリム）
- debounce: 下書き送信は 100ms 間引き
- 設定トグル:
  - `commitOnlyMode` — /chatbox/input 下書きを抑制
  - `typingIndicatorEnabled` — /chatbox/typing の送信可否（独立）
  - `autoReleaseAfterSend` — バブル限定、LS 送信後の自動非アクティブ化

## プライバシー

OSC 送信先はユーザーが設定した IP:port のみ。第三者サーバーへのデータ
送信は一切ありません。詳細は [プライバシーポリシー](gime-android-privacy-policy.md)
参照。
