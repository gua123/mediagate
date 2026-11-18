#!/bin/bash
# 真机调试需要用户操作时，在手机上提醒
#
# 演进（2026-11-18 一天内三次调整）：
#   ① 播音频文件 -> 会弹音乐播放器，打断；
#   ② 设闹钟（SET_ALARM + SKIP_UI）-> 能响但太重；
#   ③ **播一次正常铃声**（当前）；
#   ④ **22:00–07:00 静音**（用户：晚上10点过后到早上7点之前不使用铃声通知）。
#
# 用法：./ring.sh "请在手机上点一下播放"
set -u
A="${ADB:-/opt/android-sdk/platform-tools/adb}"
MSG="${1:-需要你在手机上操作一下}"

# 静音时段：22:00–06:59 只发通知，不响铃
HOUR=$(date +%-H)
QUIET=0
if [ "$HOUR" -ge 22 ] || [ "$HOUR" -lt 7 ]; then QUIET=1; fi

# 1) 唤醒屏幕
"$A" shell input keyevent KEYCODE_WAKEUP 2>/dev/null

# 2) 发通知留痕（任何时候都发，静默）
"$A" shell "cmd notification post -t 'mediagate 调试' -S bigtext '$MSG' '请查看手机'" 2>/dev/null

# 3) 静音时段不响铃
if [ "$QUIET" -eq 1 ]; then
  echo "已提醒（静音时段 $HOUR 点，只发通知不响铃）：$MSG"
  exit 0
fi

# 4) 播一次铃声（3 秒后关掉播放器）
"$A" shell "am start -a android.intent.action.VIEW -d 'content://media/internal/audio/media/272'" >/dev/null 2>&1
sleep 3
"$A" shell "am force-stop com.miui.player" 2>/dev/null
"$A" shell "am force-stop com.android.music" 2>/dev/null

echo "已提醒（响一声）：$MSG"
