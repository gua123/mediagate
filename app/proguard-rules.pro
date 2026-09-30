# mediagate 混淆规则（M0 起步版，随模块增加补充）

# 保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }

# JSch / Commons Net 反射用到的字段
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-keep class com.jcraft.jsch.** { *; }
-keep class org.apache.commons.net.** { *; }

# com.github.mwiede:jsch 里的可选集成（Windows Pageant / log4j2 / Kerberos GSSAPI / junixsocket）
# 在 Android 上根本不存在这些类，只影响桌面场景，直接 dontwarn（R8 missing_rules 生成）
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
