#!/bin/bash
# hechima のネイティブ成果物（Mozc）を取ってきて Gradle が拾える場所に置く。
#
#   bash android/scripts/fetch-hechima-native.sh
#
# 置き場:
#   app/src/main/jniLibs/<abi>/libhechima.so   … 変換エンジン本体（JNI 層込み）
#   app/src/main/assets/mozc.data              … 辞書 18.9MB
#
# どちらも **git には入れない**（.gitignore 済み）。hechima-wasm と同じ方針で、
# 成果物はリポジトリにコミットせずタグ付き Release に置く。
#
# ⚠ 現状 Release は **private リポジトリ（logical-layout-labo）** にある。
#   公開ミラー（msonrm/GIME）だけを clone した人はこのスクリプトが通らない。
#   公開ミラー側の Release にも同じ成果物を置くのが本筋で、それは未対応。
#   それまでは、公開ミラーからビルドしたい場合は
#   `JapaneseConverter.backend` が KAZUMA へ自動で倒れるので、
#   .so と辞書が無くても**アプリ自体はビルド・動作する**（旧エンジンで変換される）。
#
# 環境変数:
#   HECHIMA_NATIVE_TAG   … 取得する Release タグ（既定 = 下の DEFAULT_TAG）
#   HECHIMA_NATIVE_REPO  … 取得元リポジトリ（既定 = msonrm/logical-layout-labo）
#   HECHIMA_NATIVE_ABIS  … 空白区切りの ABI（既定 = "arm64-v8a x86_64"）
set -e

DEFAULT_TAG="hechima-native-v0.1.0"
TAG=${HECHIMA_NATIVE_TAG:-$DEFAULT_TAG}
REPO=${HECHIMA_NATIVE_REPO:-msonrm/logical-layout-labo}
ABIS=${HECHIMA_NATIVE_ABIS:-"arm64-v8a x86_64"}

HERE=$(cd "$(dirname "$0")" && pwd)
APP="$HERE/../app"
ASSETS="$APP/src/main/assets"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

command -v gh > /dev/null || { echo "[fetch-hechima-native] gh CLI が要ります" >&2; exit 1; }

echo "[fetch-hechima-native] repo=$REPO tag=$TAG abis=$ABIS"

for ABI in $ABIS; do
  DEST="$APP/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  # Release には ABI 名を付けて置いてある（libhechima-arm64-v8a.so 等）
  gh release download "$TAG" --repo "$REPO" \
    --pattern "libhechima-$ABI.so" --dir "$TMP" --clobber
  mv "$TMP/libhechima-$ABI.so" "$DEST/libhechima.so"
  echo "[fetch-hechima-native] $DEST/libhechima.so  ($(du -h "$DEST/libhechima.so" | cut -f1))"
done

mkdir -p "$ASSETS"
gh release download "$TAG" --repo "$REPO" --pattern "mozc.data" --dir "$TMP" --clobber
mv "$TMP/mozc.data" "$ASSETS/mozc.data"
echo "[fetch-hechima-native] $ASSETS/mozc.data  ($(du -h "$ASSETS/mozc.data" | cut -f1))"

echo "[fetch-hechima-native] 完了"
