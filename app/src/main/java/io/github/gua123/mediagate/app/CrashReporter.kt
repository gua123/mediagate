package io.github.gua123.mediagate.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.gua123.mediagate.core.common.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获（**2026-10-03 真机"一播 MP4 就闪退"之后补的**）。
 *
 * 自用 sideload 的 App 拿不到 logcat——用户只能描述"闪退了"，而堆栈才是唯一能定位的东西。
 * 这里挂一个 [Thread.setDefaultUncaughtExceptionHandler]：把**设备信息 + 堆栈 + 出事前的日志**
 * 写进 `filesDir/crashes/`，再交回系统默认处理（该闪还是闪，不改变系统行为）。
 *
 * 设置页的「诊断」卡片能直接看/复制这段文本，用户粘给我即可。
 */
object CrashReporter {

    /** 保留最近几份崩溃报告。 */
    const val MAX_REPORTS: Int = 5

    private const val DIR_NAME = "crashes"

    /** 安装全局处理器（Application.onCreate 调一次；重复调用无害）。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // 交回系统/上一个处理器：不吞异常，该显示系统崩溃提示就显示
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃目录。 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * 抓上一次进程退出的原因（**Android 11+ 的 ApplicationExitInfo**）。
     *
     * 为什么必须有它：Java 层的 [install] 只能抓 Java 崩溃——**原生崩溃（MediaCodec / ffmpeg / VLC 的 .so）
     * 会让进程直接消失，Java 处理器根本不会跑**。用户看到的同样是"闪退"，而我们的诊断卡会是空的。
     * ApplicationExitInfo 由系统记录，能拿到 REASON_CRASH_NATIVE 与它的 native 堆栈（API 31+ 的 traceInputStream），
     * 不需要 root、不需要 adb。
     *
     * **2026-10-03 放宽口径**：用户反馈"点内核闪退却没捕捉到日志"——原来的过滤只认
     * 崩溃/原生崩溃/ANR，而 LibVLC 那种"要么原生崩、要么被系统按低内存/未知原因杀掉"的情形会落到别的 reason 上，
     * 于是什么都没有。现在**只要不是"主动退出/用户强行停止"，一律落一份报告**（含 reason 名与描述），
     * 并且把磁盘上的[面包屑][breadcrumb]一起带上——原生崩溃时内存里的日志随进程消失，只有面包屑留在盘上，
     * 它能告诉我们"最后走到哪一步"（例如"创建 LibVLC 内核（未返回）"）。
     */
    fun captureLastExit(context: Context) {
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val info = historicalExits(manager).firstOrNull { processNameOf(it) == context.packageName } ?: return
            val reason = reasonOf(info)
            // 只跳过"正常的主动退出"——其余（崩溃/原生崩溃/ANR/低内存/被系统杀/未知）都值得留证据
            if (reason == ApplicationExitInfo.REASON_EXIT_SELF) return
            val stamp = timestampOf(info)
            val target = File(dir(context), "exit-" + stamp + "-" + reasonName(reason) + ".txt")
            if (target.exists()) return
            val directory = dir(context)
            if (!directory.exists()) directory.mkdirs()
            target.writeText(
                renderExit(
                    device = deviceInfo(context),
                    reasonZh = exitReasonZh(reason),
                    description = descriptionOf(info),
                    timestampMs = stamp,
                    trace = traceOf(info),
                    breadcrumbs = readBreadcrumbs(context),
                    logLines = AppLog.snapshot(),
                ),
                Charsets.UTF_8,
            )
            prune(directory)
        }
    }

    // ---- ApplicationExitInfo 的反射访问 ----
    // 为什么用反射：本机编译用的 android.jar（compileSdk 37.2 的 minor 版）里没有
    // ActivityManager.getHistoricalProcessExitInfos 这个方法（编译期直接 Unresolved），
    // 而它在 Android 11+ 真机上是有的。反射 + runCatching 既能在真机上拿到数据，又不影响编译。

    private fun historicalExits(manager: ActivityManager): List<Any> = runCatching {
        val method = ActivityManager::class.java.getMethod("getHistoricalProcessExitInfos")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(manager) as? List<Any>).orEmpty()
    }.getOrDefault(emptyList())

    private fun reasonOf(info: Any): Int = read(info, "getReason") as? Int ?: -1

    private fun timestampOf(info: Any): Long = read(info, "getTimestamp") as? Long ?: 0L

    private fun processNameOf(info: Any): String? = read(info, "getProcessName") as? String

    private fun descriptionOf(info: Any): String? = read(info, "getDescription") as? String

    /** native 堆栈：API 31+ 才有 getTraceInputStream（拿不到就返回 null）。 */
    private fun traceOf(info: Any): String? = runCatching {
        val stream = read(info, "getTraceInputStream") as? java.io.InputStream ?: return null
        stream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun read(target: Any, method: String): Any? = runCatching {
        target.javaClass.getMethod(method).invoke(target)
    }.getOrNull()

    /** 退出原因的可读名（写进文件名，方便一眼看出是原生崩溃还是 ANR）。 */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "java-crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        else -> "reason-" + reason
    }

    /** 渲染"上次异常退出"报告（**纯函数**，可 JVM 单测）。 */
    fun renderExit(
        device: DeviceInfo,
        reasonZh: String,
        description: String?,
        timestampMs: Long,
        trace: String?,
        breadcrumbs: List<String> = emptyList(),
        logLines: List<String>,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
        val builder = StringBuilder()
        builder.append("== mediagate 异常退出报告 ==\n")
        builder.append("时间：").append(time).append('\n')
        builder.append("版本：").append(device.versionName).append("（versionCode ").append(device.versionCode).append("）\n")
        builder.append("包名：").append(device.packageName).append('\n')
        builder.append("设备：").append(device.manufacturer).append(' ').append(device.model)
            .append(" / Android ").append(device.release).append("（SDK ").append(device.sdkInt).append("）\n")
        builder.append("退出原因：").append(reasonZh).append('\n')
        if (!description.isNullOrBlank()) builder.append("系统描述：").append(description).append('\n')
        builder.append('\n').append("== 系统记录的堆栈/轨迹 ==\n")
        builder.append(trace?.takeIf { it.isNotBlank() } ?: "（这次拿不到轨迹：可能是 Java 崩溃已由崩溃处理器记录，或系统未提供）\n")
        builder.append('\n').append("== 最后走到哪一步（面包屑，写盘，原生崩溃也留得下）==\n")
        if (breadcrumbs.isEmpty()) {
            builder.append("（没有面包屑：可能崩在很早期，或这次启动还没写）\n")
        } else {
            breadcrumbs.forEach { builder.append(it).append('\n') }
        }
        builder.append('\n').append("== 上次进程的日志（本机内存缓冲，可能为空）==\n")
        if (logLines.isEmpty()) {
            builder.append("（没有日志：进程被原生崩溃直接带走了）\n")
        } else {
            logLines.forEach { builder.append(it).append('\n') }
        }
        return builder.toString()
    }

    /** 退出原因的中文说明（诊断卡与报告都用）。 */
    fun exitReasonZh(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃（ApplicationExitInfo）"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃（native crash，例如 MediaCodec / ffmpeg / VLC 的 .so）"
        ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统杀掉（加载大库/大模型时常见）"
        ApplicationExitInfo.REASON_EXIT_SELF -> "主动退出"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "被用户强行停止"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀掉（可能是原生崩溃）"
        ApplicationExitInfo.REASON_UNKNOWN -> "未知原因（系统没给细节，多为被回收）"
        else -> "原因码 " + reason
    }

    // ------------------------------------------------------------ 面包屑（原生崩溃也留得下）

    private const val BREADCRUMB_FILE = "breadcrumbs.txt"

    /** 最多留几行面包屑（够回溯"最后几步"就行）。 */
    const val MAX_BREADCRUMBS: Int = 40

    private val breadcrumbLock = Any()

    /**
     * 记一条面包屑（**2026-10-03**：用户报"点内核闪退但没日志"之后加的）。
     *
     * 内存里的 [AppLog] 环形缓冲会随进程一起消失——原生崩溃（例如 LibVLC 的 .so）恰好就是这种情况。
     * 面包屑是**同步写到磁盘**的一行小字，写在关键步骤之前，于是"最后走到哪一步"能留下来。
     * 调用点要少而关键（建内核、装载、起播、切内核…），别拿它当日志用。
     */
    fun breadcrumb(context: Context, text: String) {
        runCatching {
            synchronized(breadcrumbLock) {
                val file = File(dir(context), BREADCRUMB_FILE)
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val lines = if (file.exists()) file.readLines() else emptyList()
                val next = (lines + (stamp + " " + text)).takeLast(MAX_BREADCRUMBS)
                file.writeText(next.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        }
    }

    /** 读面包屑（旧 → 新）。 */
    fun readBreadcrumbs(context: Context, limit: Int = 20): List<String> = runCatching {
        val file = File(dir(context), BREADCRUMB_FILE)
        if (!file.exists()) emptyList() else file.readLines().takeLast(limit)
    }.getOrDefault(emptyList())

    /**
     * 上次进程退出的摘要（诊断卡用；**打开设置页时现读**，不必等下次崩溃才写文件）。
     *
     * @return 形如 `低内存被杀（…） · 10-02 07:13`；拿不到（或上次是正常退出）返回 null。
     */
    fun lastExitSummary(context: Context): String? = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = historicalExits(manager).firstOrNull { processNameOf(it) == context.packageName } ?: return null
        val reason = reasonOf(info)
        if (reason == ApplicationExitInfo.REASON_EXIT_SELF) return null
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestampOf(info)))
        val desc = descriptionOf(info)?.takeIf { it.isNotBlank() }?.let { "（" + it.take(80) + "）" }.orEmpty()
        exitReasonZh(reason) + desc + " · " + time
    }.getOrNull()

    /** 最近一份崩溃报告；没有返回 null。 */
    fun latest(context: Context): File? =
        dir(context).listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    /** 全部报告（新 → 旧）。 */
    fun reports(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** 清空全部报告。 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val directory = dir(context)
        if (!directory.exists()) directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(directory, "crash-" + stamp + ".txt")
        target.writeText(render(context, thread, throwable), Charsets.UTF_8)
        prune(directory)
    }

    /** 只留最近 [MAX_REPORTS] 份。 */
    private fun prune(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_REPORTS).forEach { it.delete() }
    }

    /** 渲染报告文本（**纯函数**，可 JVM 单测；设备信息由 [DeviceInfo] 传入）。 */
    fun render(
        device: DeviceInfo,
        thread: Thread,
        throwable: Throwable,
        logLines: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
        val builder = StringBuilder()
        builder.append("== mediagate 崩溃报告 ==\n")
        builder.append("时间：").append(time).append('\n')
        builder.append("版本：").append(device.versionName).append("（versionCode ").append(device.versionCode).append("）\n")
        builder.append("包名：").append(device.packageName).append('\n')
        builder.append("设备：").append(device.manufacturer).append(' ').append(device.model)
            .append(" / Android ").append(device.release).append("（SDK ").append(device.sdkInt).append("）\n")
        builder.append("线程：").append(thread.name).append("（id ").append(thread.id).append("）\n")
        builder.append("\n== 异常 ==\n")
        builder.append(throwable.javaClass.name).append(": ").append(throwable.message ?: "(无 message)").append('\n')
        builder.append(throwable.stackTraceToString())
        builder.append("\n== 出事前的日志（旧 → 新，最多 ").append(AppLog.RING_CAPACITY).append(" 行）==\n")
        if (logLines.isEmpty()) {
            builder.append("（没有日志：可能崩在日志系统就绪之前）\n")
        } else {
            logLines.forEach { builder.append(it).append('\n') }
        }
        return builder.toString()
    }

    private fun render(context: Context, thread: Thread, throwable: Throwable): String =
        render(deviceInfo(context), thread, throwable, AppLog.snapshot())

    /** 读设备/版本信息（拿不到就填占位，绝不因为读信息把崩溃处理再搞崩）。 */
    private fun deviceInfo(context: Context): DeviceInfo = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        DeviceInfo(
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            packageName = context.packageName,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }.getOrDefault(
        DeviceInfo(
            versionName = "未知",
            versionCode = 0L,
            packageName = context.packageName,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        ),
    )
}

/** 崩溃报告里的设备与版本信息（抽出来是为了让 [CrashReporter.render] 能在 JVM 上测）。 */
data class DeviceInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdkInt: Int,
)
