#!/bin/sh
set -e
cd "$(dirname "$0")/.."
ADB="${ADB:-/Users/liamchan/Library/Android/sdk/platform-tools/adb}"
DEV="${DEV:-192.168.46.1:5555}"
"$ADB" connect "$DEV" >/dev/null 2>&1 || true
"$ADB" -s "$DEV" install -r apk/ClusterHUD-1.0-v4.10.apk
"$ADB" -s "$DEV" install -r apk/ClusterAssist-1.0-v4.10.apk >/dev/null 2>&1 || true
"$ADB" -s "$DEV" shell am start-activity --display 1 -n com.byd.clusterlab/.CardActivity >/dev/null 2>&1 || true
sleep 2
"$ADB" -s "$DEV" shell "ps -A | grep clusterlab" || true
echo "装好了。验证：熄火锁车 1 分钟再点火，看 :keep 是否还活着、卡片是否回来。"
