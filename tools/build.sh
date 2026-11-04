#!/usr/bin/env bash
# ClusterLab 构建：overlay(仪表卡片) + assist(仪表助理)
# 用法: ./build.sh [all|overlay|assist]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # tools/ 的上一层 = 项目根
SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/33.0.1}"
AJAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
JAVAC="${JAVAC_BIN:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home/bin/javac}"
JAR="${JAR_BIN:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home/bin/jar}"
SHZ="$ROOT/src/shizuku"
KS="$ROOT/tools/clusterlab-debug.jks"
AIDL="$SHZ/aidl/classes.jar"
API="$SHZ/api/classes.jar"
PROV="$SHZ/provider/classes.jar"

command -v zip >/dev/null || { echo "需要 zip"; exit 1; }

# $3：额外的 classpath（assist 还需要 Shizuku 的 aidl/api/provider；
# overlay 从 v4.0 起自研 ADB 客户端，不再依赖 Shizuku，所以传空）
build_app() {
  local name="$1" dir="$2"
  local extra="${3-}"
  local b="$dir/build"
  echo "==> $name"
  rm -rf "$b"; mkdir -p "$b/cls" "$b/dex"

  "$BT/aapt2" compile --dir "$dir/res" -o "$b/res.zip"
  local assets_flag=""
  [ -d "$dir/assets" ] && assets_flag="-A $dir/assets"
  # shellcheck disable=SC2086
  "$BT/aapt2" link -o "$b/base.apk" -I "$AJAR" \
      --manifest "$dir/AndroidManifest.xml" $assets_flag \
      --min-sdk-version 29 --target-sdk-version 29 \
      --no-version-vectors "$b/res.zip"

  find "$dir/src" -name '*.java' > "$b/sources.txt"
  "$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn \
      -bootclasspath "$AJAR$extra" \
      -d "$b/cls" @"$b/sources.txt"

  ( cd "$b/cls" && "$JAR" cf "$b/app.jar" . )
  local depjars=""
  if [ -n "$extra" ]; then depjars="$API $PROV $AIDL"; fi
  # shellcheck disable=SC2086
  "$BT/d8" --min-api 29 --lib "$AJAR" --output "$b/dex" "$b/app.jar" $depjars

  cp "$b/base.apk" "$b/unsigned.apk"
  ( cd "$b/dex" && zip -q -X "$b/unsigned.apk" classes*.dex )
  "$BT/zipalign" -f 4 "$b/unsigned.apk" "$b/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-key-alias debug \
      --ks-pass pass:android --key-pass pass:android \
      --out "$b/$name.apk" "$b/aligned.apk"
  "$BT/apksigner" verify --print-certs "$b/$name.apk" | head -3
  ls -lh "$b/$name.apk"
}

target="${1:-all}"
case "$target" in
  all)     build_app clusterlab "$ROOT/src/clusterlab" ""
           build_app assist "$ROOT/src/assist" ":$AIDL:$API:$PROV" ;;
  overlay) build_app clusterlab "$ROOT/src/clusterlab" "" ;;
  assist)  build_app assist "$ROOT/src/assist" ":$AIDL:$API:$PROV" ;;
  *) echo "用法: $0 [all|overlay|assist]"; exit 1 ;;
esac
echo "OK"
