#!/bin/bash
# 真机调试需要用户操作时，在手机上提醒
#
# 2026-11-18 用户要求：**用闹钟/电话/铃声的方式，不要直接播放音频文件**
# （直接 am start 播音频会弹出音乐播放器，打断用户手上的事）。
#
# 做法：设一个 1 分钟后的闹钟（SKIP_UI=true，不弹设置界面）+ 发一条通知留痕。
# 闹钟响铃走系统闹钟通道，音量足够、能穿透静音以外的场景。
#
# 用法：./ring.sh "请在手机上点一下播放"
set -u
A="${ADB:-/opt/android-sdk/platform-tools/adb}"
MSG="${1:-需要你在手机上操作一下}"

# 1) 唤醒屏幕（息屏时也能看到通知）
"$A" shell input keyevent KEYCODE_WAKEUP 2>/dev/null

# 2) 发通知留痕（文字说明这次要做什么）
"$A" shell "cmd notification post -t 'mediagate 调试' -S bigtext '$MSG' '请查看手机'" 2>/dev/null

# 3) 设一个 1 分钟后的闹钟（真的响铃；不弹闹钟设置界面）
H=$("$A" shell date +%-H | tr -d '\r')
M=$("$A" shell date +%-M | tr -d '\r')
NEXT_M=$(( (M + 1) % 60 ))
if [ "$NEXT_M" -eq 0 ]; then H=$(( (H + 1) % 24 )); fi
"$A" shell "am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR $H \
  --ei android.intent.extra.alarm.MINUTES $NEXT_M \
  --ez android.intent.extra.alarm.SKIP_UI true" >/dev/null 2>&1

echo "已提醒（1 分钟后闹钟响铃）：$MSG"
