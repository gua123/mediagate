#!/bin/bash
# 真机调试需要用户操作时，在手机上提醒（响铃 + 通知 + 唤醒屏幕）
# 用法：./ring.sh "请在手机上点一下播放"
set -u
A="${ADB:-/opt/android-sdk/platform-tools/adb}"
MSG="${1:-需要你在手机上操作一下}"

# 1) 唤醒屏幕（息屏时也能看到通知）
"$A" shell input keyevent KEYCODE_WAKEUP 2>/dev/null

# 2) 发一条通知（静默，作为文字留痕）
"$A" shell "cmd notification post -t 'mediagate 调试' -S bigtext '$MSG' '请查看手机'" 2>/dev/null

# 3) 播系统铃声（真的出声；播 6 秒后关掉播放器，避免占着前台）
"$A" shell "am start -a android.intent.action.VIEW -d 'content://media/internal/audio/media/272' -t 'audio/*'" >/dev/null 2>&1
sleep 6
"$A" shell "am force-stop com.miui.player" 2>/dev/null
"$A" shell "am force-stop com.android.music" 2>/dev/null

echo "已提醒：$MSG"
