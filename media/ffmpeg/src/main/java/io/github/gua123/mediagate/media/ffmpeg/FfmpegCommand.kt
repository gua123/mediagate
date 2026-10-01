package io.github.gua123.mediagate.media.ffmpeg

import java.io.File
import java.util.Locale

/**
 * FFmpeg 命令的用途（R11：抽帧兜底 / 时间戳重建 / ASR 取音，plan 2 章选型、4.3 第 2 条、4.4、4.7 B）。
 *
 * 用途只用于日志、统计与测试断言，不参与 native 调用。
 */
enum class FfmpegPurpose {
    /** 抽帧兜底：`-ss <秒> -i in -frames:v 1 -vf scale=W:-2 -f image2 out`（plan 4.4）。 */
    FRAME_EXTRACTION,

    /** 时间戳重建：`-c copy -fflags +genpts -f mp4 out`（plan 4.3 第 2 条，R3/R4）。 */
    TIMESTAMP_REPAIR,

    /** ASR 取音：`-vn -ac 1 -ar 16000 -f s16le -`（plan 4.7 B，R11/R14）。 */
    ASR_PCM_DECODE,

    /** 其它自定义命令（诊断页、后续扩展）。 */
    CUSTOM,
}

/**
 * 一条 FFmpeg 命令的**可测数据表示**（R11）。
 *
 * 为什么把「要跑什么」单独建模成数据而不是直接拼字符串：
 * - 参数列表是纯 Kotlin 值对象，**JVM 单测能直接断言参数顺序与取值**，不需要设备上的 native 库；
 * - [FfmpegRunner] 的实现只负责把 [arguments] 交给 ffmpeg-kit，取消、进度、返回码解析都在外面；
 * - [outputFile] 让调用方知道去哪里取产物（管道形态为 null，见 [writesToStdout]）。
 *
 * 约定：`ffmpeg` 本体不出现在 [arguments] 里（ffmpeg-kit 自己就是 ffmpeg）；
 * 所有现成构造都以 `-hide_banner -loglevel error` 开头，避免 banner/日志噪音。
 *
 * @param arguments 完整参数列表（不含可执行文件名）。
 * @param purpose 用途（日志/断言用）。
 * @param outputFile 产物文件；null 表示输出到标准输出（管道）或不产出文件。
 * @param expectedDurationMs 输入时长估计（毫秒）；给了才能把进度换算成 0..1，未知传 null。
 */
data class FfmpegCommand(
    val arguments: List<String>,
    val purpose: FfmpegPurpose = FfmpegPurpose.CUSTOM,
    val outputFile: File? = null,
    val expectedDurationMs: Long? = null,
) {

    /** 输出到标准输出（管道形态：末尾参数是 `-`），例如 ASR 的 PCM 与转封装 mp4 流。 */
    val writesToStdout: Boolean get() = arguments.lastOrNull() == "-"

    /** 便于日志与报错展示的一行命令（只作展示，不用于执行）。 */
    val commandLine: String
        get() = buildString(64) {
            append("ffmpeg")
            for (argument in arguments) {
                append(' ')
                if (argument.isEmpty() || argument.any { it == ' ' || it == '"' }) {
                    append('"').append(argument.replace("\"", "\\\"")).append('"')
                } else {
                    append(argument)
                }
            }
        }

    /** 复制并把时长估计换成 [ms]（进度换算用）。 */
    fun withExpectedDuration(ms: Long?): FfmpegCommand = copy(expectedDurationMs = ms)

    companion object {

        /** 所有现成命令共用的前缀：不打印 banner，只保留 error 级日志。 */
        val BASE_ARGUMENTS: List<String> = listOf("-hide_banner", "-loglevel", "error")

        /** 时间戳重建输出到管道时使用的 mp4 标记：分片 + 空 moov，播放器才能边收边解。 */
        const val FRAGMENTED_MP4_FLAGS = "frag_keyframe+empty_moov"

        /** whisper.cpp 输入口径：16 kHz 单声道（plan 4.7 B）。 */
        const val DEFAULT_ASR_SAMPLE_RATE = 16_000

        /**
         * 毫秒 → `-ss` 参数字符串（**纯函数**，单测直接覆盖）：保留 3 位小数，负数夹到 0。
         *
         * 传字符串而不是 Double，是为了避免不同 Locale 下小数点变成逗号（ffmpeg 只认 `.`）。
         */
        fun secondsArgument(positionMs: Long): String =
            String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1000.0)

        /**
         * 抽帧兜底（plan 4.4 表格的「FFmpeg 简版抽帧」）。
         *
         * 参数形状：`-ss <秒> -i <输入> -frames:v 1 [-vf scale=W:-2] -f image2 <输出>`。
         * `-ss` 放在 `-i` **之前**：快速定位，不必解完前面的所有帧。
         *
         * @param targetWidth 期望宽度；<=0 表示按原始尺寸（不加 `-vf`）。
         */
        fun frameExtraction(
            input: File,
            output: File,
            positionMs: Long,
            targetWidth: Int = 0,
            overwrite: Boolean = true,
        ): FfmpegCommand {
            val args = buildList {
                addAll(BASE_ARGUMENTS)
                add("-ss"); add(secondsArgument(positionMs))
                add("-i"); add(input.absolutePath)
                add("-frames:v"); add("1")
                if (targetWidth > 0) {
                    // -2 让高度按比例取偶（多数编码器要求偶数）
                    add("-vf"); add("scale=$targetWidth:-2")
                }
                add("-f"); add("image2")
                if (overwrite) add("-y")
                add(output.absolutePath)
            }
            return FfmpegCommand(args, FfmpegPurpose.FRAME_EXTRACTION, output)
        }

        /**
         * 时间戳重建：无 PCR / 拼接流经 `-c copy -fflags +genpts` 转封装成 MP4（plan 4.3 第 2 条，R3/R4）。
         *
         * `-c copy` 不重编码，只重建时间戳，速度约等于磁盘/网络速度。
         */
        fun timestampRepairToFile(
            input: File,
            output: File,
            overwrite: Boolean = true,
        ): FfmpegCommand {
            val args = buildList {
                addAll(BASE_ARGUMENTS)
                add("-i"); add(input.absolutePath)
                add("-c"); add("copy")
                add("-fflags"); add("+genpts")
                add("-f"); add("mp4")
                if (overwrite) add("-y")
                add(output.absolutePath)
            }
            return FfmpegCommand(args, FfmpegPurpose.TIMESTAMP_REPAIR, output)
        }

        /**
         * 时间戳重建的**管道形态**：产物写标准输出，供回环 HTTP 服务边读边转（plan 4.3 第 2 条）。
         *
         * mp4 走管道必须分片（`-movflags frag_keyframe+empty_moov`），否则 moov 在文件末尾，
         * 播放器收不到就开始不了解码。ffmpeg-kit 侧用命名管道
         * （FFmpegKitConfig.registerNewFFmpegPipe）把它变成可读字节流，具体接线留给上层。
         */
        fun timestampRepairToStdout(
            input: File,
            fragmented: Boolean = true,
        ): FfmpegCommand {
            val args = buildList {
                addAll(BASE_ARGUMENTS)
                add("-i"); add(input.absolutePath)
                add("-c"); add("copy")
                add("-fflags"); add("+genpts")
                add("-f"); add("mp4")
                if (fragmented) {
                    add("-movflags"); add(FRAGMENTED_MP4_FLAGS)
                }
                add("-")
            }
            return FfmpegCommand(args, FfmpegPurpose.TIMESTAMP_REPAIR, outputFile = null)
        }

        /**
         * ASR 取音（plan 4.7 B）：任意音视频轨 → 单声道 16 kHz PCM 交 whisper.cpp。
         *
         * 参数形状：`-vn -ac 1 -ar 16000 -acodec pcm_s16le -f s16le -`。
         */
        fun asrPcmDecode(
            input: File,
            sampleRateHz: Int = DEFAULT_ASR_SAMPLE_RATE,
            channels: Int = 1,
            sampleFormat: String = "s16le",
        ): FfmpegCommand {
            val args = buildList {
                addAll(BASE_ARGUMENTS)
                add("-i"); add(input.absolutePath)
                add("-vn")
                add("-ac"); add(channels.toString())
                add("-ar"); add(sampleRateHz.toString())
                add("-acodec"); add("pcm_$sampleFormat")
                add("-f"); add(sampleFormat)
                add("-")
            }
            return FfmpegCommand(args, FfmpegPurpose.ASR_PCM_DECODE, outputFile = null)
        }

        /** 自定义命令（调用方保证参数自洽）；[outputFile] 为 null 表示产物走标准输出或不需要文件。 */
        fun custom(
            arguments: List<String>,
            outputFile: File? = null,
            expectedDurationMs: Long? = null,
        ): FfmpegCommand = FfmpegCommand(
            arguments = BASE_ARGUMENTS + arguments,
            purpose = FfmpegPurpose.CUSTOM,
            outputFile = outputFile,
            expectedDurationMs = expectedDurationMs,
        )
    }
}
