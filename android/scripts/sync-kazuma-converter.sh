#!/usr/bin/env bash
#
# sync-kazuma-converter.sh
# ------------------------------------------------------------------------------
# GIME Android の vendored かな漢字変換エンジン
# (KazumaProject/JapaneseKeyboard, MIT) を上流の指定リビジョンへ再同期する。
#
# 背景:
#   `android/app/src/main/java/com/kazumaproject/` は上流アプリ JapaneseKeyboard
#   の「変換モジュールのサブセット」をソースコピー (vendor) したもの。
#   従来は「いつ・どのコミットから取ったか」が記録されておらず、上流の改善を
#   取り込めなかった。本スクリプトはその素性を `KAZUMA_REF` に固定し、
#   ワンコマンドで再同期できるようにする。
#
# 設計:
#   - 我々が *現在追跡しているファイル* だけを更新する (勝手に肥大化させない)。
#   - 上流のモジュール再編 (core/ や symbol_keyboard/ への分割) に強いよう、
#     `com/kazumaproject/<package tail>` の末尾一致でソースを解決する。
#   - PROTECTED のファイル (Room 実装に差し替え済み = 我々の所有物) は上書きしない。
#   - 取り込み後に `git diff --stat` を表示し、レビューしてからコミットする。
#   - さらに「追跡パッケージ内にあるが我々が持っていない上流の新規ファイル」を
#     列挙し、新機能を取り込む判断材料にする。
#
# 使い方:
#   # 1. 現在の基点を再現 (検証用 no-op に近い):
#   bash android/scripts/sync-kazuma-converter.sh
#
#   # 2. 上流改善を取り込む: 新しい tag を指定して再実行 → 差分をレビュー → ビルド/実機検証:
#   KAZUMA_REF=v1.7.82 bash android/scripts/sync-kazuma-converter.sh
#
# 取り込み後は必ず:
#   - JapaneseConverter.kt (ファサード) のコンパイルが通るか
#     (buildEngine / getCandidatesWithoutPrediction 等のシグネチャ変化に注意)
#   - 実機で変換が動くか
#   を確認し、本ファイル冒頭の KAZUMA_REF と docs/gime-android-converter-vendor.md を更新する。
# ------------------------------------------------------------------------------
set -euo pipefail

UPSTREAM_REPO="https://github.com/KazumaProject/JapaneseKeyboard"

# 取り込んだ上流リビジョン (commit SHA または tag)。
# 改善を取り込むときは環境変数 KAZUMA_REF で上書きするか、ここを書き換える。
#   現在の基点: 4995505 (2026-04-03, ≈ v1.7.34 の直前)
KAZUMA_REF="${KAZUMA_REF:-4995505ad2523a7157998fd72f631b0d5bdc12ca}"

# 我々が所有していて上書きしてはいけないファイル (上流スタブを Room 実装へ差し替え済み)。
# 末尾 (com/kazumaproject/ 以下) で指定する。
PROTECTED=(
  "markdownhelperkeyboard/repository/LearnRepository.kt"
  "markdownhelperkeyboard/repository/UserDictionaryRepository.kt"
)

# ------------------------------------------------------------------------------
# パス解決 (スクリプトの場所からリポジトリルートを求める)
# ------------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VENDOR_ROOT="$REPO_ROOT/android/app/src/main/java/com/kazumaproject"

if [[ ! -d "$VENDOR_ROOT" ]]; then
  echo "ERROR: vendor ルートが見つかりません: $VENDOR_ROOT" >&2
  exit 1
fi

is_protected() {
  local tail="$1"
  for p in "${PROTECTED[@]}"; do
    [[ "$tail" == "$p" ]] && return 0
  done
  return 1
}

# ------------------------------------------------------------------------------
# 上流を blob:none + sparse で取得 (java ソースだけ。15MB+ の辞書 assets は取らない)
# ------------------------------------------------------------------------------
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 上流取得: $UPSTREAM_REPO @ $KAZUMA_REF"
git clone --quiet --filter=blob:none --no-checkout "$UPSTREAM_REPO" "$WORK/up"
git -C "$WORK/up" sparse-checkout init --no-cone >/dev/null
# 全モジュールの com/kazumaproject 配下の java ソースだけに絞る
git -C "$WORK/up" sparse-checkout set "*/src/main/java/com/kazumaproject/**" >/dev/null
git -C "$WORK/up" checkout --quiet "$KAZUMA_REF"

UP_SHA="$(git -C "$WORK/up" rev-parse HEAD)"
UP_DATE="$(git -C "$WORK/up" log -1 --format=%ci HEAD)"
echo "    上流 HEAD: $UP_SHA ($UP_DATE)"

# ------------------------------------------------------------------------------
# 追跡中ファイルを 1 つずつ末尾一致で解決してコピー
# ------------------------------------------------------------------------------
updated=0 unchanged=0 protected=0 unresolved=0
declare -a UNRESOLVED=()

while IFS= read -r our; do
  tail="${our#"$VENDOR_ROOT"/}"             # com/kazumaproject/ 以下の相対パス
  if is_protected "$tail"; then
    protected=$((protected + 1))
    continue
  fi
  # 上流側で */com/kazumaproject/<tail> に一致するファイルを探す (一意であること)
  mapfile -t hits < <(find "$WORK/up" -path "*/com/kazumaproject/$tail" -type f 2>/dev/null)
  if [[ "${#hits[@]}" -eq 0 ]]; then
    UNRESOLVED+=("$tail  (上流に該当なし)")
    unresolved=$((unresolved + 1))
    continue
  elif [[ "${#hits[@]}" -gt 1 ]]; then
    UNRESOLVED+=("$tail  (上流で複数一致: ${#hits[@]} 件 → 手動確認)")
    unresolved=$((unresolved + 1))
    continue
  fi
  up="${hits[0]}"
  if cmp -s "$up" "$our"; then
    unchanged=$((unchanged + 1))
  else
    cp "$up" "$our"
    updated=$((updated + 1))
  fi
done < <(find "$VENDOR_ROOT" -name '*.kt' -type f | sort)

# ------------------------------------------------------------------------------
# レポート
# ------------------------------------------------------------------------------
echo
echo "==> 同期結果"
echo "    更新     : $updated"
echo "    変更なし : $unchanged"
echo "    保護(skip): $protected   (= 我々の Room 実装)"
echo "    未解決   : $unresolved"
if [[ "${#UNRESOLVED[@]}" -gt 0 ]]; then
  printf '      - %s\n' "${UNRESOLVED[@]}"
fi

# 追跡パッケージ内の上流新規ファイル (取り込み候補) を列挙
echo
echo "==> 参考: 追跡中パッケージにある上流の新規ファイル (未取り込み)"
# 我々が持つトップレベルパッケージ集合を作る
mapfile -t our_pkgs < <(find "$VENDOR_ROOT" -name '*.kt' -type f \
  | sed "s#$VENDOR_ROOT/##; s#/[^/]*\$##" | sort -u)
new_count=0
for pkg in "${our_pkgs[@]}"; do
  while IFS= read -r upf; do
    base_tail="${upf##*/com/kazumaproject/}"
    [[ -f "$VENDOR_ROOT/$base_tail" ]] && continue
    echo "    + $base_tail"
    new_count=$((new_count + 1))
  done < <(find "$WORK/up" -path "*/com/kazumaproject/$pkg/*.kt" -type f 2>/dev/null)
done
[[ "$new_count" -eq 0 ]] && echo "    (なし)"

echo
echo "==> 次の手順"
echo "    1. git -C $REPO_ROOT diff --stat android/app/src/main/java/com/kazumaproject"
echo "    2. JapaneseConverter.kt のシグネチャ整合を確認 (buildEngine 等)"
echo "    3. ビルド + 実機で変換を検証"
echo "    4. 問題なければ KAZUMA_REF=$KAZUMA_REF を本スクリプトと"
echo "       docs/gime-android-converter-vendor.md に反映してコミット"
