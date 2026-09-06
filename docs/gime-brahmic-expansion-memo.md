# GIME Brahmic + Abjad 拡張 設計メモ

**状態**: **Devanagari iOS / Android 両対応**（Android: 2026-04-23、PR #508、
iOS: 2026-04-24、PR #517 で Swift 移植）。
`सत्यमेव जयते` / `ओम्` / `नमः` / `अतः` / `दुःख` 等が実機で正しく
入力できることを確認。
**発端**: セッション 2026-04-22 の余談から発展した設計討議を記録として残す

**更新履歴**:
- 2026-04-23: 子音 varga / stop / 母音の全レイヤーを
  **varnamala 時計回り**方式に改訂（朗唱順と指の運動を同期させる設計原則）
- 2026-04-23: 合成モデルを **「conjunct は明示的 halant」** に変更。
  当初案の「子音連続 = auto-conjunct」は `नम` (namaste 語頭) 等の通常語を
  `न्म` にしてしまう致命的不具合があるため、ITRANS / Google Hindi IME 等と
  同様に halant (RT) 明示方式へ修正。
- 2026-04-23: 実装中に判明した設計修正:
  - **LS をトグルラッチ化**: 左親指で LS と D-pad を同時操作不可能な物理
    制約のため。同方向 flick で toggle off、別方向 flick で上書き
  - **L3 one-shot 非 varga サブレイヤー**: 1 子音 emit で自動 OFF
    （連続非 varga は再度 L3 を押す）
  - **LB は非 varga 状態と独立**: 常に現 LS latch の varga 鼻音を発火
  - **RB = ओ 単押し / LT+RB = nukta**: ओ が Hindi 頻出なので LT シフトを外す
  - **LT + A = ऋ**: Sanskrit 用の低頻度字を LT シフトに隔離
  - **RT + LS = カーソル移動**: RT release 時に LS が使われていれば halant 抑止
  - **LT + RT = visarga ः**: Sanskrit / 文語 Hindi 用
- 2026-04-24: 関連ドキュメント更新、PR #508 を ready for review に
- 2026-09-03: **candra（`ॉ` / `ॅ`）を `LT + RS ↑` に追加**（英語由来語が軒並み打てなかった）。
  あわせて **LS latch が「前置シフト」である**ことを明記（メモに落ちていた）
- 2026-09-05: **非 varga サブレイヤーの層選択を `LT` から `L3` の巡回に変更**
  （`OFF → semivowel → sibilant → OFF`）。実機の手応えから。理由は 2 つ:
  - **シフトの規律が層内で揃った** —— LS latch は前置シフト、L3 は one-shot なのに
    **LT だけが押しっぱなしのホールド**で、周りが「押して離してから打つ」なのに
    ここだけ握ったまま打っていた
  - **`LT` の意味が「土着でない音」で揃った** —— LT+RB = nukta（ペルシア・アラビア借用）/
    LT+RS↑ = candra（英語借用）/ LT+A = ऋ・LT+RT = visarga（サンスクリット）。
    **`श ष स ह` だけが日常語の芯**（शहर / समय / है）で素性から外れていた
  ★**時間窓は入れていない**。「素早く 2 回」ではなく巡回なので打鍵の速さに依存せず、
  プラットフォーム間でずれる定数も増えない
- 2026-09-06: **Higgins（PortMaster 版）も同じ形に追随**。実機（R36H）で
  **開発機と画素まで一致**することを確認済み。★移植側では `LT` を渡していた
  2 つの窓（見出し字・図の札）から引数ごと落とした —— **層は state が持つ**ので、
  ★**渡せる形のまま残すと「LT で切り替わる版」を書ける余地が残る**

## 概要

GIME は既に CJK + 英語 + 韓国語をカバーしているが、既存アーキテクチャ
（mode + resolver + composer パターン）は**アジア系の構造化スクリプト全般**
に適用可能性があり、射程を Devanagari → 他の Brahmic 系 → Abjad 系へ
拡張すれば **世界人口の 40% 強** を覆うことができる。

### 市場規模

| 文字系統 | 話者数（概算） |
|---|---|
| CJK（漢字・仮名・ハングル） | ~16 億 |
| Devanagari（Hindi / Marathi / Nepali 等） | ~7.5 億 |
| 他の Brahmic（Bengali / Tamil / Telugu / Gujarati / Kannada / Malayalam / Punjabi / Thai / Burmese / Tibetan 等） | ~8 億 |
| Abjad（Arabic / Persian / Urdu / Hebrew 等） | ~4 億 |
| **合計** | **~35 億** |

### なぜゲームパッドが構造化スクリプトに効くか

Latin 系と違い、アジア系スクリプトは全て **「子音群 × 母音群」の 2 次元積**
で記述できる:

- **音節文字**（仮名・ハングル）: 子音 × 母音マトリクス
- **アブギダ**（Devanagari, Thai, Tamil, Burmese, Tibetan 等）: 子音 + 母音
  ダイアクリティカル
- **アブジャド**（Arabic, Hebrew）: 子音 + 母音記号

これは**ゲームパッドの「レイヤー × グリッド」入力モデルと同型**で、
加えて発音位置による**音声学的な自然分類**（軟口蓋・歯・唇・etc.）が
レイヤー選択の mnemonic になる。VR での blind typing では「どこに何が
あるか」を phonological 直感で導出できる利点が致命的に効く。

### Leapfrog 論

アジア圏は既に物理キーボード時代に**Latin QWERTY + IME patch 文化**で
しのいできた（romaji→仮名、pinyin/zhuyin→漢字、ITRANS→Devanagari）。
**誰もネイティブキーボードを持っていない**。VR 入力はこれを reset する
機会で、先行者利得のない市場の方が構造最適解（gamepad-native）に
早く移行する可能性が高い。歴史的 leapfrog（固定電話 → モバイル、
クレジットカード → UPI/Alipay）と同型パターン。

---

## Devanagari 入力モデル

### 合成アルゴリズム

Devanagari の音韻論的前提:

- 子音は固有の **schwa (a)** を背負っている（क 単独で "ka"）
- conjunct は **virama (्)** で schwa を殺して作る（क + ् + ष = क्ष "kṣa"）
- 母音 matra が来ると schwa を置換して akshara 確定（क + ि = कि "ki"）

### 重要な設計判断: conjunct は明示的 halant

当初案は「子音連続 = 自動 conjunct」だったが、`नम` (namaste 語頭) のような
「両方 inherent schwa 付きの連続子音」を誤って `न्म` にしてしまう致命的な
欠陥があった。**実際の Hindi / Sanskrit テキストでは non-conjunct な
子音連続の方が多い**（कमल, भारत, हिन्दी 等）ため、auto-conjunct は機能しない。

採用する方式（ITRANS / Google Hindi IME 等と同じ）:

- **子音連続 = 別アクシャラ**（両方 schwa 付き）
- **conjunct = halant (RT) を明示的に打つ**

```
[user types]           [buffer]         [意味]
  क                     क               "ka" (schwa 付き)
  + ष                   कष              "ka-ṣa" (2 アクシャラ)
  + RT(halant)          कष्             "ka-ṣ" (末尾 virama)
  + ट                   कष्ट             "ka-ṣṭa" (ष と ट が conjunct、क は独立)
```

conjunct `क्ष` の入力は `क → RT → ष`。コストはキー 1 つ増えるだけだが、
曖昧性が無くなり「指が朗唱に同期する」原則は維持できる。

### 確定トリガー

akshara cluster が確定する条件:

1. **母音 matra 入力** → 末尾 schwa を matra で置換して確定
2. **独立母音入力** → 現 cluster を inherent schwa のまま確定し、新 cluster 開始
3. **明示的 halant キー** → schwa 無しで確定（Sanskrit 文末等）
4. **スペース / 句読点** → 現 cluster を確定

---

## ゲームパッドレイアウト（提案）

### 前提

- GIME 既存の mode 切替で Devanagari モードに入る前提
- LS / LB 等の意味は Devanagari モード時に再割当て（韓国語 자모 모드 と同じ作法）

### 設計原則: varnamala 時計回り

Devanagari ネイティブにとって最強の prior knowledge は **वर्णमाला
(varnamala) の朗唱順**（就学期から刷り込まれる）。音声学的マトリクス
として美しい配置より、**朗唱順を時計回り（↑→→→↓→←→中立）に辿れる**
配置の方が blind typing で圧倒的に強い。指は暗唱と同期して動けば
迷わない。この原則を全レイヤー（varga / stop / 母音）で貫く。

### 子音レイヤー: パンチャヴァルガ (पञ्चवर्ग)

Devanagari 子音は音声学的に 5 分類 (varga) + 非 varga 8 個 = 33。
LS 5 位置に varnamala 順（क→च→ट→त→प）を時計回りに載せる:

| varnamala 順 | varga | 子音 | LS 方向 |
|---|---|---|---|
| 1 | कवर्ग (軟口蓋) | क ख ग घ ङ | **LS ↑** |
| 2 | चवर्ग (口蓋) | च छ ज झ ञ | **LS →** |
| 3 | टवर्ग (そり舌) | ट ठ ड ढ ण | **LS ↓** |
| 4 | तवर्ग (歯) | त थ द ध न | **LS ←** |
| 5 | पवर्ग (唇) | प फ ब भ म | **LS 中立** |

各 varga 内も同じ原理: **varnamala 順の 4 stop を時計回りに**。
鼻音は各 varga に 1 個のみなので D-pad 不要で LB 単独に直送:

| varnamala 順 | 分類 | क行 例 | D-pad 方向 |
|---|---|---|---|
| 1 | 無気無声 | क | **↑** |
| 2 | 有気無声 | ख | **→** |
| 3 | 無気有声 | ग | **↓** |
| 4 | 有気有声 | घ | **←** |
| 5 | 鼻音 | ङ | **LB 単独** |

cardinal 直結（「この方向 = この子音」の 1 対 1）方式を採用し、
**1 子音 = 1 アクション**を死守する（conjunct 連鎖の流暢さ優先）。

### 非 varga 子音 8 個: L3 の巡回で 2 層

य र ल व (semivowel 4) + श ष स ह (sibilant + h の 4) を収める:

```
OFF ──L3──▶ semivowel (य र ल व) ──L3──▶ sibilant (श ष स ह) ──L3──▶ OFF
```

- **L3 (LS 押し込み)** を押すたびに層が巡回する。**2 回押せば sibilant**
- **D-pad 4 方向** で各層内の 4 子音を確定
- ★**LT は層の選択に関与しない**（2026-09-05 に外した。更新履歴を参照）。
  D-pad に出ている字そのものが「いまどちらの層か」の表示を兼ねる
- ★**時間窓を持たない**。ダブルクリック判定ではなく巡回なので、打鍵の速さに
  依存せず、実装ごとにずれる定数も生まない
- **L3 は LS latch のリセットを兼ねる**: 押下毎に `devaLsDir` を NEUTRAL に戻す
  ので、「押し込み中の傾けが背後で残る」「傾け状態のまま 2 回 click すると latch
  が温存される」といった非直感を排除（`prevDevaRawLsDir` は触らないので、再 latch
  には一度 LS を中立に戻してから再 flick する必要がある）

### ★★LS latch は「前置シフト」—— 左手側の出力 1 つで中立へ戻る

> ★**2026-09-03 に足した。** それまでこのメモは **L3 のリセットしか書いておらず、
> 消費のほうが落ちていた**。★実際、Higgins（PortMaster 版）への移植で
> **この 1 行が落ちて latch が残り続けていた** —— 移植した側が実装ではなく
> このメモを読んだのだとすれば、**メモの穴が次の移植者にも同じ落とし方をさせる**。

- **左手側の出力（D-pad の子音 / LB の鼻音）を 1 つ出したら `devaLsDir` は NEUTRAL に戻る。**
  次の子音は再度 LS を flick して指定する
- ★**モードではない。** 押しっぱなしにできない（左親指で LS と D-pad を同時に操作
  できない）から latch にしてあるのであって、状態として持ち続ける設計ではない
- 正典は `handleDevanagariInput()` の **2 か所**（子音 emission と 鼻音 emission）。
  ★片方だけだと「D-pad なら消えるが LB では残る」という一番説明しにくい形になる

### ★★移植の罠 —— 「末尾の印」を言語の「文字」で取ってはいけない

matra / anusvara / virama / nukta は Unicode 上**前の字にくっつく印**なので、
**末尾の印だけを見る・落とす操作**（長音化・anusvara 巡回・candra・backspace）は
**必ず Unicode スカラー単位**で書く。言語の「末尾 1 文字」を使うと言語ごとに割れる:

| 言語 | 末尾 1 文字が返すもの | `कि` の末尾 | 結果 |
|---|---|---|---|
| **Swift** | 書記素クラスタ（`Character`） | **`कि` まるごと** | 印が取れず**黙って no-op**。落とすと**基字ごと**消える |
| **Kotlin** | UTF-16 コード単位（`Char`） | `ि` | 動く |

★**2026-09-05 に実機で発覚**（`कि` → RS→ が `की` にならない / `ं` の次が `ँ` にならない）。
**同じソースなのに Android では動き、iOS だけが壊れていた**。
★**症状が「何も起きない」なので気づきにくい** —— 素の子音への**追加**は末尾を見ないので
動いてしまい、**置換だけが黙って死ぬ**（candra は出るのに長音化が効かない、が典型）。
Swift 側は `lastScalarChar` / `dropLastScalar()` に集約して直した。
★**C へ移植するときも同じ罠**（UTF-8 のバイト列を後ろから 1 バイト見ても印にならない）。

### 母音レイヤー

短母音 6 個（a, i, u, ṛ, e, o）+ 長母音 6 個（ā, ī, ū, ṝ, ai, au）+ 独立形
の表と matra の出し分け。

face button 4 個も **varnamala 順（a→i→u→e）を時計回り**に配置:

| varnamala 順 | 母音 | face button |
|---|---|---|
| 1 | a (अ) | **↑（Y / △）** |
| 2 | i (इ) | **→（B / ○）** |
| 3 | u (उ) | **↓（A / ×）** |
| 4 | e (ए) | **←（X / □）** |

残る母音は RS に配置:

- **RS ←** = o（ओ）
- **RS ↓** = ṛ（ऋ、低頻度だが Sanskrit で必須）
- **RS →** = **長母音 post-shift**（直前母音を長母音に昇格:
  a→ā, i→ī, u→ū, ṛ→ṝ, e→ai, o→au）
  - `ए/ऐ` と `ओ/औ` は音声学的には別母音だが、guna/vrddhi 系列的には
    ペアなので「伸ばす」操作で対応する設計を採用（Sanskrit 純粋主義には
    譲歩を要求するが、実用入力としては筋が通る）

独立母音 vs matra の分岐は**合成状態から自動判定**（cluster 開始時点なら
独立母音、子音入力後なら matra）。

### 修飾子マッピング

| 修飾子 | スロット | 備考 |
|---|---|---|
| **Halant (्)** | **RT tap** | 明示的 schwa 無し終端。使用頻度中 |
| **Nukta (़)** | **RB tap**（直前子音に後置） | क → क़ 等の Persian 借用音 |
| **Anusvara (ं) ↔ Chandrabindu (ँ)** | **RS ↑ cycle**（2-step） | 鼻音強度の系列、Korean 평격경 と同型 |
| **Visarga (ः)** | **LT + RT 同時** | Sanskrit 専用の超低頻度 |
| **改行 / 確定** | **RT + LS click**（RT 押下中に LS 押し込み） | RT 単独の halant 自動挿入は `devaRtUsedForCursor=true` で抑止 |

全修飾子を単一の LS↑ サイクルに押し込むのは **不自然**（halant は構造、
nukta は借用音 dot、anusvara/chandrabindu は鼻音、visarga は Sanskrit 終端
と、機能が全て別系統）。**機能別に配る**のが正解。

### ★★candra（`ॉ` / `ॅ`）—— **LT + RS ↑**（2026-09-03 に追加）

`डॉक्टर` `कॉलेज` `ऑफिस` `कॉफ़ी` —— **英語由来語に軒並み付く `ॉ`（U+0949）が打てなかった。**
★フォントにはあり、無いのは**入力表のほう**だった（＝ 移植の抜けではなく**入力方式そのものの穴**）。

置き場所を **RS ↑ の系統**にした理由が 2 つ:

- **見た目で揃う** —— `ं` `ँ` `ॉ` `ॅ` は**どれも行の上に付く印**で、RS ↑ は既に
  「上に付ける印」の場所になっている
- **名前でも揃う** —— **चन्द्रबिन्दु = candra + bindu**。chandrabindu の隣に candra を
  置くのは**同じ一族に戻すこと**

★ただし**同じ巡回には混ぜない**（LT の修飾層に載せる）。理由も 2 つ:

- **操作の対象が違う** —— anusvara / chandrabindu は cluster 末尾に**足す**（`क` → `कं`）が、
  candra は**母音記号を差し替える**（`े` → `ॅ`／素の子音なら `ॉ` を付ける）。混ぜると
  「もう一度押すと外れる」の意味が揃わない（`कॉ` はどこへ戻るのか）
- **打鍵数** —— `डॉक्टर` は日常語なのに RS ↑ を 3 回押すことになる

`LT` は既に修飾の層（`LT+RB` = nukta ／ `LT+✕` = ऋ ／ `LT+RT` = visarga）なので系統も一貫し、
**RS ↑ の「上に付ける印」という意味も保たれ**、既存の巡回に触らず 1 打鍵で出る。

差し替えの対（`applyCandra()` / `DEVA_MATRA_TO_CANDRA` / `DEVA_VOWEL_TO_CANDRA`）:

| もとの母音 | candra 形 |
|---|---|
| 無し（inherent schwa）| `ॉ` を**足す**（`डॉक्टर` の `ड`）|
| `ा` ／ `ो` | `ॉ` |
| `े` | `ॅ`（`बॅंक`。★Marathi でよく使う）|
| `अ` ／ `आ` ／ `ओ` | `ऑ` |
| `ए` | `ऍ` |

★**長短の対（`applyLongShift`）と同じ「後から差し替える」形**にしてある。
★既に candra 形 or 対象外なら no-op（戻り先が無いのでトグルにはしない）。

### 物理入力の消費一覧

> ★**2026-09-03 に実装と突き合わせて直した**（Higgins への移植の下調べ）。
> 3 か所ずれていた —— `RS ←` は **o ではなく backspace**、`RS ↓` は **ṛ ではなく
> 句読点サイクル**（ṛ は **LT + ✕**）、`RB` は **nukta ではなく `ओ`**（nukta は LT + RB）。
> ★**設計メモは「決めたとき」の記録なので、実装が動いたあと更新されていなかった。**
> 正典は `GamepadInputManager.swift` の `handleDevanagariInput()`。

| 入力 | Devanagari モードでの意味 |
|---|---|
| LS 4 方向 + 中立 | varga 選択（5） |
| L3 (LS 押し込み) | 非 varga 層の巡回 `OFF → semivowel → sibilant → OFF`（RT 非押下時）|
| RT + L3 (LS 押し込み) | 改行（RT 押下中の LS click は newline 専用）|
| LT | modifier 系の組合せ。**意味は「土着でない音」で揃えてある**（nukta / candra / ऋ / visarga）。★非 varga 層の選択には関与しない |
| D-pad 4 方向 | varga 内子音（stop 4）/ 非 varga 内子音 |
| LB | 鼻音直送（varga モード時）/ 修飾子組合せ |
| face buttons 4 | 主要短母音 4（a, i, u, e） |
| RS ← | **backspace**（合成中は composer の buffer を 1 字戻す） |
| RS ↓ | **句読点サイクル**（空白 → `।` danda → `,` → `॥` double danda・多段タップ）。★`,` は 2026-09-05 に Higgins へ合わせて追加（iOS のみ・Android 未追随） |
| RS → | 長母音 post-shift |
| RS ↑ | anusvara ↔ chandrabindu cycle ／ **LT + RS ↑ = candra（`ॉ` `ॅ`）** |
| RB | **`ओ` / `ो`**（単押し）／ **LT + RB = nukta** |
| RT | halant（明示的終端）。★**何にも使われずに離されたときだけ**（RT+LS のカーソル移動 / RT+L3 の改行 / LT+RT の visarga に使われたら抑止） |
| R3 | 予備（候補系 / モード切替） |

**33 子音 + 11 母音 + 5 修飾子 = 49 の意味単位**をこの空間で取り切れる
見込み。

---

## 実装指針

### アーキテクチャ

既存の engine / resolver / composer 3 層構造を踏襲:

- **`DevanagariComposer`**: `KoreanComposer` (Android/iOS 両実装あり) の
  アナロジー。akshara buffer + 即時適用 + 巻き戻しを内部で管理
- `GamepadResolver` に Devanagari 用テーブルを追加（mode enum に拡張）
- `InputManager` / `GamepadInputManager` は非依存（onDirectInsert
  コールバック経由で composer の出力を受け取る）
- engine 層は pure（Android / iOS プラットフォーム非依存）を維持

### PoC 実装結果（2026-04-23）

1 セッションで Android 実装を完走:

- ✅ 33 子音 + 11 母音 matra + halant + anusvara + chandrabindu + visarga +
  nukta + 長母音 post-shift + backspace + space/।/॥ + カーソル移動
- ✅ 検証文: `सत्यमेव जयते`（インド国章、Sanskrit）
- ✅ `ओम्` (Om), `नमः` (namaḥ), `अतः` (ataḥ), `दुःख` (duḥkha) も確認
- ✅ conjunct 生成: 明示的 halant 方式で任意の conjunct が組める
- ✅ 独立母音 vs matra の自動分岐: composer 状態で判定

**実装ファイル**:

Android (PR #508 〜 #513):
- `android/app/src/main/java/com/gime/android/engine/DevanagariComposer.kt`
- `android/app/src/main/java/com/gime/android/engine/GamepadResolver.kt`
  (Devanagari テーブル群)
- `android/app/src/main/java/com/gime/android/input/GamepadInputManager.kt`
  (`handleDevanagariInput` + 各 dispatch 点)
- `android/app/src/main/java/com/gime/android/ui/GimeApp.kt`
  (DevaDpadCluster + DevaFaceButtons + ラベル)

iOS (PR #517、Android からの直訳移植):
- `Sources/GIME/DevanagariComposer.swift`（Kotlin → Swift、285 行）
- `Sources/GIME/GamepadResolver.swift`（`.devanagari` enum case +
  varga テーブル + LS/D-pad 方向解決ヘルパー）
- `Sources/GIME/GamepadInputManager.swift`
  (`handleDevanagariInput()` + 内部状態 + LS click で非 varga トグル +
  RS ← で composer backspace + RS ↓ 多段タップに danda サイクル)
- `Sources/GIME/GamepadVisualizerView.swift`
  (動的 LB/RT/RB/LT ラベル + LS latch ベースの D-pad クラスタ +
  modeBadgeColor + RS ヒント)

**次フェーズ候補** (未着手):
- Bengali / Tamil / Telugu / Gujarati / Kannada / Malayalam 等の Brahmic
  展開。`BrahmicComposerCore` に切り出して言語別テーブルだけ差し替える構造に
- Devanagari 数字 `० १ २` サブモード（現状は Start cycle で EN モードの ASCII 数字）
- Avagraha ऽ / ZWJ / ZWNJ 等の低頻度字
- 実機 ergonomics 長期使用テスト

---

## Brahmic 系への展開

### 共通原理

Devanagari で確立した **「akshara buffer + 即時適用+巻き戻し + halant 自動挿入」**
アルゴリズムは他の Brahmic 系にそのまま適用可能:

- **Bengali** (বাংলা): 子音 ~34 + 母音 ~11 + matra、同じ abugida 構造。
  字体が異なるだけで入力モデルは共通
- **Tamil** (தமிழ்): 子音少なめ（~18、声区別なし）で単純化可能、
  pulli (virama) ベース
- **Telugu** (తెలుగు): Devanagari と近い構造
- **Gujarati** (ગુજરાતી): Devanagari と最も近い、語頭シロス簡略化のみ差分
- **Kannada** (ಕನ್ನಡ) / **Malayalam** (മലയാളം): 同系統
- **Punjabi (Gurmukhi)** (ਪੰਜਾਬੀ): やや変則、子音クラスタ扱いが異なる
- **Thai** (ไทย): Brahmic 系だが声調記号が加わる。子音 44 + 母音 ~15 +
  声調記号 4（重要）。声調を RS↑ サイクルに近い扱いに
- **Burmese** (မြန်မာ): Brahmic 系、conjunct の表記法が独特（stacking）
- **Tibetan** (བོད་): 子音 stacking が特殊で conjunct 表現が垂直方向。
  入力モデルは踏襲可能だが表示は Tibetan 固有

実装戦略: **共通の `BrahmicComposerCore` を作り、言語別テーブル
（子音・母音・連接規則）だけ差し替える**構造にすれば、1 言語追加
コストを大幅削減可能。

### varga 分類は言語横断で通用

パンチャヴァルガ（5 分類）構造は**全ての Brahmic 系に引き継がれている**
（言語によって子音数の多寡はあるが、分類軸は同じ）。したがって
**LS 方向 = varga** のマッピングは全 Brahmic 言語で共通使用可能。

---

## Abjad 系（補記）

Arabic / Persian / Urdu / Hebrew 等の abjad は**子音主体 + 母音記号
（harakat / nikud）がオプション**という構造で、Brahmic とは違う部分も
多いが、基本思想は共通して流用できる:

### Arabic

- 子音 28 個（声門 / 咽頭 / 軟口蓋 / 口蓋 / 歯茎 / 歯 / 唇 の発音位置分類）
- 母音記号 3 基本 + 長母音マーカー + shadda（重子音）+ sukun（無母音）
- **右から左**の表示は表示層のみの問題で入力モデルは影響しない
- **文字の位置による 4 形態変化**（独立 / 頭 / 中 / 尾）は Unicode
  レイヤで自動処理されるため入力側では意識しない
- 子音分類を LS 方向にマップ、母音 harakat を修飾子扱いにすれば
  基本設計は流用可能

### 設計差分

| 観点 | Brahmic | Abjad |
|---|---|---|
| 母音の扱い | akshara 確定に必須 | 任意（通常は省略） |
| schwa の扱い | 子音単独で存在 | 子音単独で無音 |
| 合成規則 | halant 自動挿入 | shadda / sukun は明示 |

母音省略運用も考えると、**Abjad では「母音未入力でも子音連続 commit 可能」**
というモードを追加する必要がある。Brahmic の自動 schwa 付与ロジックを
「自動無母音（sukun）付与」に置き換える発想で扱える。

この方向は **Devanagari が動いた後のフェーズ 2** の位置づけで、まず
Brahmic 系の汎用性を固めてからの適用が合理的。

---

## 未解決の設計判断 / 要検証項目

1. **e/ai, o/au を length pair 扱いにするか**: RS→ で伸ばす仕様にするか、
   別スロットで独立させるか。実用性 vs 純粋性のトレードオフ
2. **独立母音と matra の切替ロジック**: buffer 状態からの自動判定で
   十分か、明示モードキーが必要か
3. **L3 + LT サブモードの ergonomics**: LS 押し込みは操作として重い。
   代替として RB 長押し等の検討
4. **Devanagari モード中の LS 候補 cycle 機能**: 既存の候補移動機能を
   どこに退避させるか
5. **学習辞書の必要性**: Devanagari 変換は仮名漢字変換ほど曖昧性がない
   （綴り = 発音）ので、変換エンジン自体は不要。ただし**予測変換**で
   単語頭数文字から候補提示する機構はあると打鍵効率が上がる
6. **Zero-width joiner (ZWJ) / non-joiner (ZWNJ)** の扱い: conjunct を
   わざと分解したい専門用途向け

---

## 関連ドキュメント

- `CLAUDE.md` — 既存 GIME アーキテクチャ
- [`keymap-format.md`](https://github.com/msonrm/KeyLogicKit/blob/main/docs/keymap-format.md) —
  キーマップ定義仕様（Devanagari 用拡張の参考）。この doc は GIME ではなく
  KeyLogicKit 側にミラーされているので、リンク先はそちら
- `docs/gamepad-mapping.md` — 日本語ゲームパッドマッピング（同じ枠組で
  Devanagari 用 mapping 仕様書を書き起こす）
- `Sources/GIME/KoreanComposer.swift` — akshara-cluster 風合成の先例
- `android/.../engine/KoreanComposer.kt` — 同上

---

## 戦略的位置付け（再掲）

GIME は既に「アジア特化ニッチ」に見えるが、実際には**世界人口の
半分弱**を射程に収めている。Devanagari → Brahmic 系 → Abjad 系の
展開で:

- **VR 入力の leapfrog moment** を捉える
- **"世界で最もユニバーサルな VR 入力装置"** というポジション
- 音声入力が使えない無言勢 + VR シナリオで英語が一番苦手言語という
  皮肉な構図を維持（Asian scripts の追加ほど GIME の独自性が強化される）

余談から発展した設計だが、取りこぼすには惜しい芯の話。
