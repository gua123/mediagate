# 自研引擎的 JNI 桥（:media/engine-native）——**R8 必须原样保留**。
#
# 为什么：native 侧用 GetMethodID **按名字与签名**找这几个方法
# （见 media/engine-native/src/main/cpp/avio_source.cpp）：
#     read      (JLjava/nio/ByteBuffer;I)I
#     length    ()J
#     lastError ()Ljava/lang/String;
# 只要 R8 把它们改名（或把接口/实现类改名），运行时 GetMethodID 就返回 null，
# 表现是「数据来源接口不匹配」——**而且只在 release 包里失败**：
# debug 不混淆、JVM 单测也不跑 R8，所以本地怎么测都是绿的。
#
# 2026-10-15 实证：加这两条之前，release APK 的 DEX 里已经查不到 NativeMediaSource / lastError。

# 接口本身（方法名就是 JNI 要找的名字）
-keep interface io.github.gua123.mediagate.media.enginenative.NativeMediaSource { *; }

# 实现类（RandomAccessMediaSource 等）：连方法体一起保留
-keep class * implements io.github.gua123.mediagate.media.enginenative.NativeMediaSource { *; }
