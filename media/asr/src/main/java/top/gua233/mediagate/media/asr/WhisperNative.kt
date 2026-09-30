package io.github.gua123.mediagate.media.asr

/**
 * whisper.cpp 的 JNI 桥（**M7-B**：R14 音转字幕 / R19 批量字幕任务中心）。
 *
 * 这一层只做四件事，别的都不暴露（plan 4.7 B 的「最小 API」）：
 * [open]（建上下文）/ [WhisperContext.close]（释放）/ [WhisperContext.transcribe]（识别）/ [isAvailable]（可用性）。
 *
 * **加载顺序**：ggml-base → ggml → ggml-cpu → whisper → 本桥（[LIBRARY_NAME]）。
 * Android 的动态链接器其实会照 DT_NEEDED 去 APK 的 native 目录里找依赖，但显式按依赖序
 * [System.loadLibrary] 有两个好处：缺哪个库在日志里一眼可见；个别 ROM 的 linker 搜索路径
 * 差异不会变成「偶尔 UnsatisfiedLinkError」。
 *
 * **兜底**：加载失败（宿主机单测、ABI 不匹配、.so 没打进包）不抛异常，而是记在 [loadError] 里，
 * [isAvailable] 返回 false；调用方据此给中文提示，绝不把用户进程带崩。
 *
 * **防呆**（与 src/main/cpp/mediagate_whisper.h 的错误码逐条对应）：
 * 重复 init、空模型、文件头/大小不符（下载不完整）、句柄失效、PCM 过短，native 一律返回错误码
 * 而不是 abort；这里把错误码翻成 [WhisperException] 与中文原因。
 */
object WhisperNative {

    /** 本桥的 .so 名（System.loadLibrary 用，不带 lib 前缀与 .so 后缀）。 */
    const val LIBRARY_NAME = "mediagate_whisper_jni"

    /** JNI 桥 ABI 版本；与 mediagate_whisper.h 的 MG_ABI_VERSION 必须一致。 */
    const val ABI_VERSION = 1

    /** 默认推理线程数（plan 4.12：默认 4，播放时降到 1）。 */
    const val DEFAULT_THREADS = 4

    /** 线程数下限（让路时就是这一档）。 */
    const val MIN_THREADS = 1

    /** 线程数上限（再多只会互相抢核）。 */
    const val MAX_THREADS = 8

    // ---- 错误码（与 native 头文件一一对应）----

    /** 模型路径为空。 */
    const val ERR_EMPTY_PATH = -1

    /** 文件不存在 / 过小 / 头校验不通过 / 与预期大小不符。 */
    const val ERR_BAD_MODEL = -2

    /** 已有存活的上下文（重复 init）。 */
    const val ERR_ALREADY_INIT = -3

    /** 上下文创建失败（模型内容损坏或内存不足）。 */
    const val ERR_INIT_FAILED = -4

    /** 句柄无效或已释放。 */
    const val ERR_BAD_HANDLE = -5

    /** PCM 为空。 */
    const val ERR_EMPTY_PCM = -6

    /** PCM 过短（不足 100 ms）。 */
    const val ERR_SHORT_PCM = -7

    /** whisper_full 返回非 0。 */
    const val ERR_TRANSCRIBE_FAILED = -8

    /** JNI 侧异常（类/签名不配套）。 */
    const val ERR_JNI_BROKEN = -9

    /** Kotlin 侧自造：.so 没加载起来（宿主单测 / ABI 不匹配 / 没打进包）。 */
    const val ERR_UNAVAILABLE = -100

    /**
     * 依赖顺序：被依赖的在前，本桥在最后。
     *
     * **声明位置很关键**：Kotlin 的 object 属性按声明顺序初始化，[loadFailure] 会用到它，
     * 所以它必须写在 [loadFailure] 前面（写反了会在宿主机单测里炸成 ExceptionInInitializerError）。
     */
    private val LIBRARIES = listOf("ggml-base", "ggml", "ggml-cpu", "whisper", LIBRARY_NAME)

    /** 第一次触碰本对象时加载 .so；null = 一切正常。 */
    private val loadFailure: Throwable? = loadLibraries()

    /** 加载失败原因（null = 正常）；诊断页/日志用。 */
    val loadError: Throwable? get() = loadFailure

    /**
     * 桥是否可用。
     *
     * 三重判定：.so 加载成功、nativeProbe 能调通（真链接上了，而不只是 dlopen 成功）、
     * ABI 版本与 [ABI_VERSION] 一致。任何一步不对都返回 false，调用方给中文提示而不是崩。
     */
    fun isAvailable(): Boolean = runCatching {
        loadFailure == null && nativeProbe() == ABI_VERSION
    }.getOrDefault(false)

    /** 不可用的中文原因；可用时为 null。 */
    fun unavailableReason(): String? =
        if (isAvailable()) null else "本地识别引擎不可用（" + (loadFailure?.message ?: "native 库与代码版本不配套") + "）"

    /** 底层 whisper.cpp 版本号；不可用时返回「未知」。 */
    fun version(): String = runCatching { nativeVersion() }.getOrDefault("未知")

    /** 最近一次 native 失败的中文原因。 */
    fun lastError(): String = runCatching { nativeLastError() }.getOrDefault("未知错误")

    /**
     * 打开一个 whisper 上下文（[WhisperContext] 是 AutoCloseable，请用 use 块保证释放）。
     *
     * @param modelPath 模型文件绝对路径（filesDir/models/ggml-*.bin）。
     * @param threads 默认线程数（记进日志；每窗实际线程数在 transcribe 时给）。
     * @param useGpu 是否走 GPU 后端；**本轮恒 false**（plan 4.7 B 只跑 CPU，不依赖 NNAPI/GPU）。
     * @param expectedSizeBytes > 0 时校验模型字节数（下载完整性），不符合直接报错而不是硬解。
     * @throws WhisperException 加载失败 / 模型不合法 / 已有上下文。
     */
    fun open(
        modelPath: String,
        threads: Int = DEFAULT_THREADS,
        useGpu: Boolean = false,
        expectedSizeBytes: Long = -1L,
    ): WhisperContext {
        val failure = loadFailure
        if (failure != null) {
            throw WhisperException(ERR_UNAVAILABLE, "本地识别引擎不可用：" + failure.message)
        }
        val handle = nativeInit(modelPath, clampThreads(threads), useGpu, expectedSizeBytes)
        if (handle <= 0L) {
            throw WhisperException(handle.toInt(), lastError())
        }
        return WhisperContext(handle)
    }

    /** 线程数夹到 MIN..MAX（**纯函数**，单测覆盖）。 */
    fun clampThreads(threads: Int): Int = threads.coerceIn(MIN_THREADS, MAX_THREADS)

    /** 错误码 → 中文说明（**纯函数**）。 */
    fun describeError(code: Int): String = when (code) {
        ERR_EMPTY_PATH -> "模型路径为空"
        ERR_BAD_MODEL -> "模型文件不完整或格式不对，请删除后重新下载"
        ERR_ALREADY_INIT -> "识别引擎正在使用中，请稍后重试"
        ERR_INIT_FAILED -> "模型加载失败：文件损坏或设备内存不足"
        ERR_BAD_HANDLE -> "识别引擎已释放"
        ERR_EMPTY_PCM -> "音频数据为空"
        ERR_SHORT_PCM -> "音频片段过短"
        ERR_TRANSCRIBE_FAILED -> "识别失败"
        ERR_JNI_BROKEN -> "native 与 Kotlin 版本不配套"
        ERR_UNAVAILABLE -> "本地识别引擎不可用"
        else -> "识别失败（错误码 " + code + "）"
    }

    /** 供 [WhisperContext] 调用的 native 包装（private external 只能在本对象内访问）。 */
    internal fun transcribeNative(
        handle: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        offsetMs: Long,
    ): Array<WhisperSegment>? =
        nativeTranscribe(handle, pcm, language, translate, threads, offsetMs.toInt().coerceAtLeast(0))

    /** 供 [WhisperContext] 调用的 native 包装。 */
    internal fun freeNative(handle: Long) = nativeFree(handle)

    private fun loadLibraries(): Throwable? {
        for (library in LIBRARIES) {
            try {
                System.loadLibrary(library)
            } catch (e: UnsatisfiedLinkError) {
                return e
            } catch (e: SecurityException) {
                return e
            }
        }
        return null
    }

    // ---- native 声明（符号名必须与 whisper_jni.cpp 一致）----

    /** JNI 桥 ABI 版本。 */
    private external fun nativeProbe(): Int

    /** whisper.cpp 版本号。 */
    private external fun nativeVersion(): String

    /** 建上下文：> 0 句柄；< 0 错误码。 */
    private external fun nativeInit(modelPath: String, threads: Int, useGpu: Boolean, expectedSize: Long): Long

    /** 释放上下文（幂等）。 */
    private external fun nativeFree(handle: Long)

    /** 识别一段 16 kHz 单声道 float PCM；失败返回 null。 */
    private external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        offsetMs: Int,
    ): Array<WhisperSegment>?

    /** 最近一次失败的中文原因。 */
    private external fun nativeLastError(): String
}

/**
 * 一次识别返回的字幕段（**M7-B / R14**）。
 *
 * 时间戳单位是毫秒，[WhisperSegment.startMs] 相对**本窗**起点；拼成整段音轨的绝对时间由
 * [AsrPipeline.absoluteSegments] 负责（纯逻辑，JVM 可测）。
 *
 * 注意：本类名与构造签名 (JJLjava/lang/String;)V 被 native 侧硬编码引用
 * （见 whisper_jni.cpp 的 kSegmentClass / kSegmentCtorSig），改名前先看那边。
 */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 识别引擎异常（错误码见 [WhisperNative] 的 ERR_* 常量）。
 *
 * @property code native 返回的错误码；[WhisperNative.ERR_UNAVAILABLE] 表示 .so 没加载起来。
 */
class WhisperException(
    val code: Int,
    detail: String?,
) : IllegalStateException(
    if (detail.isNullOrBlank()) {
        WhisperNative.describeError(code)
    } else {
        WhisperNative.describeError(code) + "：" + detail
    },
)

/**
 * 一次 whisper 上下文的生命周期封装（**AutoCloseable**，务必用 use 块）。
 *
 * 为什么单独一个类而不是裸 long：whisper small 一份上下文就 ~500 MB，忘一次 free 就是 OOM；
 * 句柄只活在 native 注册表里，[close] 是幂等的（重复 close 不会 double free）。
 *
 * 线程约定：**同一实例不可并发 transcribe**（whisper_context 不是线程安全的），
 * AsrQueue 默认串行（并发 1）正是为此。
 */
class WhisperContext internal constructor(private val handle: Long) : AutoCloseable {

    @Volatile
    private var closed = false

    /** 句柄是否还有效。 */
    val isValid: Boolean get() = !closed && handle > 0L

    /** native 句柄（诊断/日志用，不要拿去拼别的调用）。 */
    val nativeHandle: Long get() = if (closed) 0L else handle

    /**
     * 识别一段 float PCM（16 kHz 单声道，取值 [-1, 1]）。
     *
     * @param pcm 采样点。
     * @param language "zh" / "en" / ""（空 = 自动检测）。
     * @param translate true = 翻译成英语（whisper 的 translate 任务）。
     * @param threads 本次线程数（播放让路时降到 1）。
     * @param offsetMs 本窗在整段音轨里的起点；落在返回时间戳上，便于上层直接拼时间轴。
     * @throws WhisperException 句柄已释放 / PCM 过短 / 识别失败。
     */
    fun transcribe(
        pcm: FloatArray,
        language: String = DEFAULT_LANGUAGE,
        translate: Boolean = false,
        threads: Int = WhisperNative.DEFAULT_THREADS,
        offsetMs: Long = 0L,
    ): List<WhisperSegment> {
        check(!closed) { "识别引擎已释放" }
        val result = WhisperNative.transcribeNative(
            handle = handle,
            pcm = pcm,
            language = language,
            translate = translate,
            threads = WhisperNative.clampThreads(threads),
            offsetMs = offsetMs,
        ) ?: throw WhisperException(-1, WhisperNative.lastError())
        return result.toList()
    }

    /** [ShortArray] 形态的便利重载（把 s16 采样转成 float，见 [AsrPcm.floatsFromShorts]）。 */
    fun transcribe(
        pcm: ShortArray,
        language: String = DEFAULT_LANGUAGE,
        translate: Boolean = false,
        threads: Int = WhisperNative.DEFAULT_THREADS,
        offsetMs: Long = 0L,
    ): List<WhisperSegment> = transcribe(
        pcm = AsrPcm.floatsFromShorts(pcm),
        language = language,
        translate = translate,
        threads = threads,
        offsetMs = offsetMs,
    )

    /** 释放 native 上下文；幂等。 */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { WhisperNative.freeNative(handle) }
    }

    companion object {

        /** plan 4.7 B：默认中文。 */
        const val DEFAULT_LANGUAGE = "zh"

        /** 自动检测语言（传空串给 whisper）。 */
        const val LANGUAGE_AUTO = ""
    }
}
