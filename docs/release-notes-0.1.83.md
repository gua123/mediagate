# 0.1.83 热修发布说明

> 0.1.82 真机播放报错：`播放失败，请重试` / `错误详情：minBufferMs cannot be less than bufferForPlaybackMs`。
> 这是播放核心不可用，按发版纪律立刻修、立刻发。

## 根因

`PlaybackBufferPolicy.loadControl()` 把 Media3 的四个缓冲参数配成：

- `minBufferMs` = 目标秒数的一半；
- `bufferForPlaybackMs` = 目标秒数本身。

Media3 的 `DefaultLoadControl.Builder.build()` 要求 **minBufferMs ≥ bufferForPlaybackMs**，
否则直接抛 `IllegalArgumentException`，播放页只看到一句英文错误。这段参数是 0.1.77 引入可调缓冲时写的，
旧单测只测了"秒数夹值"，**没有真正 build 一次 LoadControl**，所以一直没红。

## 修复

- `minBufferMs` 改为等于目标秒数（起播/卡顿恢复都等目标秒数）；
- `maxBufferMs` 仍取目标 8 倍并夹在 60–300 秒；
- 新增回归：**每个缓冲档位都真的构造一次 `DefaultLoadControl`**，旧写法会在这里直接抛异常；
- 清理测试里的无效 `@OptIn` 警告。

## 验证

- 全量 **1382 通过 + 1 个 opt-in 基准跳过，0 失败**；
- 0.1.83 / versionCode 84；
- 与 0.1.82 相比只动缓冲策略与对应测试，其他缓存/解码逻辑不变。

## 更新

应用内更新，或直接安装：
https://github.com/gua123/mediagate/releases/download/v0.1.83/mediagate-0.1.83.apk
