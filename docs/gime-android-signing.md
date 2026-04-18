# GIME Android 署名・リリースビルド手順

GitHub Actions で署名済み APK / AAB を自動生成するための設定手順。
既存の Play Console 開発者アカウントに紐づく `.keystore` ファイルを **upload key**
として使い、Play App Signing に実際の署名鍵を委ねる構成を想定。

## 1. Play Console 側の準備

1. Play Console で **新規アプリを作成**（パッケージ名: `com.gime.android`）
2. 「リリース > セットアップ > アプリ署名」で **Play App Signing** を有効化
3. 既存 `.keystore` を upload key として登録（または新規生成でも可）
4. 内部テストトラックを作成しておく（後で `fastlane supply` や
   `r0adkll/upload-google-play` で自動アップロードする際に使う）

## 2. ローカルで keystore を base64 化

```bash
# 既存 keystore を base64 にエンコード
base64 -i ~/.keystore/gime-upload.jks -o gime-keystore.base64
# または（macOS）
base64 ~/.keystore/gime-upload.jks | pbcopy
```

中身を後で `ANDROID_KEYSTORE_BASE64` secret に貼り付ける。

## 3. GitHub 側の Repo secrets を設定

`Settings > Secrets and variables > Actions > New repository secret` で以下を登録:

| Secret 名 | 値 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 手順 2 で作った base64 文字列 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore のパスワード |
| `ANDROID_KEY_ALIAS` | 鍵エイリアス（`keytool -list -v -keystore ...` で確認可能） |
| `ANDROID_KEY_PASSWORD` | 鍵のパスワード（keystore パスワードと同じ場合もあり） |

## 4. リリースビルドの実行

### 手動トリガー

GitHub Actions の `Build Android` workflow → `Run workflow` →
**Build signed release** のチェックを入れて実行。
Artifacts から `gime-release.zip`（APK + AAB）をダウンロード。

### タグ push で自動

```bash
git tag android-v0.1.0
git push origin android-v0.1.0
```

タグが `android-v*` にマッチすると release ビルドが自動実行される。

## 5. Booth / GitHub Releases への配布

**推奨**: Play Console にアプリをリリース後、**「リリース > ダウンロード」から
Google 署名済み APK（universal APK）を取得**し、それを GitHub Releases に
添付 + Booth ページからリンクする。
→ Play Store 版とサイドロード版で署名が一致し、相互アップデート可能。

GitHub Actions で生成した APK（upload key 署名）を直接配布する場合は
Play Store 版とは**別アプリ扱い**になるため、併用する場合は注意。

## 6. 鍵の保管

- `.keystore` ファイル本体と各パスワードは **オフラインで複数箇所にバックアップ**
  （鍵紛失 = アップデート配布不能）
- 1Password / Bitwarden 等のパスワードマネージャに `.keystore` 自体を添付保管するのが無難
- Play App Signing を有効化していれば、万が一 upload key を失っても
  Google に問い合わせて再発行可能（app signing key は Google が保管）

## トラブルシュート

- `apksigner verify` が失敗: `ANDROID_KEY_PASSWORD` と `ANDROID_KEYSTORE_PASSWORD`
  の取り違えがよくある原因。keystore 作成時に同一にした場合は両方に同じ値を入れる
- `keystoreFile is null` エラー: `ANDROID_KEYSTORE_FILE` env が正しく
  `$RUNNER_TEMP/keystore/release.jks` に展開されているか workflow ログで確認
- ローカル開発で release ビルドを試したい場合は `~/.gradle/gradle.properties` に
  `RELEASE_STORE_FILE=/path/to/keystore.jks` 等を書けば動作する（ `android/app/build.gradle.kts` 参照）
