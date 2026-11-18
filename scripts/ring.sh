#!/bin/bash
# 真机调试需要用户操作时，在手机上提醒（播一次系统铃声）
#
# 演进（2026-11-18 一天内两次调整）：
#   ① 最初用 am start 播音频 → 会弹出音乐播放器，用户觉得打断；
#   ② 改成设闹钟（SET_ALARM + SKIP_UI）→ 能响，但太重；
#   ③ **最终：播一次正常通知铃声**（用户原话「还是设置为播放铃声吧，
#      播放一次就好，正常通知铃声，或者一个声音」）。
#
# 用法：./ring.sh "请在手机上点一下播放"
set -u
A="${ADB:-/opt/android-sdk/platform-tools/adb}"
MSG="${1:-需要你在手机上操作一下}"

# 1) 唤醒屏幕
"$A" shell input keyevent KEYCODE_WAKEUP 2>/dev/null

# 2) 发通知留痕（文字说明这次要做什么）
"$A" shell "cmd notification post -t 'mediagate 调试' -S bigtext '$MSG' '请查看手机'" 2>/dev/null

# 3) 播一次铃声（3 秒后关掉播放器，避免占着前台）
"$A" shell "am start -a android.intent.action.VIEW -d 'content://media/internal/audio/media/272'" >/dev/null 2>&1
sleep 3
"$A" shell "am force-stop com.miui.player" 2>/dev/null
"$A" shell "am force-stop com.android.music" 2>/dev/null

echo "已提醒（响一声）：$MSG"
