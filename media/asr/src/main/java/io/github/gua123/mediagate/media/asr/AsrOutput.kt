package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleLocator
import io.github.gua123.mediagate.media.subtitle.SubtitleWriter
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 字幕产出格式（**M7-B / R14**：默认 SRT，可另存 VTT 或纯文本稿）。
 *
 * 与 [SubtitleFormat] 分开是因为多了「纯文本」这一档——它不是字幕格式（不能挂到播放器上），
 * 只是给人看的识别稿；SRT/VTT 的序列化**一律复用** :media:subtitle 的 [SubtitleWriter]，
 * 不另写一套（R14 的往返单测已经把那套格式覆盖过了）。
 */
enum class AsrOutputFormat(val extension: String, val label: String) {

    /** SubRip，默认（与视频同名同目录）。 */
    SRT("srt", "SRT 字幕"),

    /** WebVTT（另存）。 */
    VTT("vtt", "WebVTT 字幕"),

    /** 纯文本稿（另存，只保留文本行）。 */
    TEXT("txt", "纯文本稿"),
    ;

    /** 对应的 :media:subtitle 格式；纯文本没有对应项（null）。 */
    val subtitleFormat: SubtitleFormat?
        get() = when (this) {
            SRT -> SubtitleFormat.SRT
            VTT -> SubtitleFormat.VTT
            TEXT -> null
        }

    companion object {

        /** plan 4.7 B：默认 SRT。 */
        val DEFAULT: AsrOutputFormat = SRT

        /** 从字幕格式推产出格式。 */
        fun of(format: SubtitleFormat): AsrOutputFormat = when (format) {
            SubtitleFormat.VTT -> VTT
            else -> SRT
        }
    }
}

/** 字幕写出的结果类别（**R14：无写权限时落 App 私有目录并提示，绝不静默丢弃**）。 */
enum class AsrWriteKind {

    /** 写进了视频同目录（本地或远端后端）。 */
    WRITTEN,

    /** 无写权限，落到了 App 私有目录（界面要提示可分享/稍后重试）。 */
    LOCAL_FALLBACK,
}

/**
 * 一次字幕写出的结果。
 *
 * @property path 实际落点（后端路径或本地绝对路径）。
 * @property kind 写回还是本地兜底。
 * @property message 中文提示（界面直接显示）。
 */
data class AsrWriteResult(
    val path: String,
    val kind: AsrWriteKind,
    val message: String,
) {
    /** 是否写到了视频同目录。 */
    val isWritten: Boolean get() = kind == AsrWriteKind.WRITTEN
}

/**
 * 字幕产出（**M7-B / R14**）。
 *
 * 三条硬口径：
 * 1. **一次只产出一个文件**（用户口径）：[write] 每次只写一个格式、返回一个路径；
 * 2. **默认与视频同名同目录**：路径由 [SubtitleWriter.siblingPathOf] 生成；
 * 3. **无写权限绝不静默丢弃**：远端只读 / SAF 未授权时落到 App 私有目录（filesDir/subtitles），
 *    并把中文提示放在 [AsrWriteResult.message] 里让界面显示。
 */
object AsrOutput {

    /** 渲染字幕文本（**纯函数**）：SRT/VTT 交给 :media:subtitle，纯文本只留台词。 */
    fun render(cues: List<SubtitleCue>, format: AsrOutputFormat = AsrOutputFormat.DEFAULT): String = when (format) {
        AsrOutputFormat.SRT -> SubtitleWriter.serializeSrt(cues)
        AsrOutputFormat.VTT -> SubtitleWriter.serializeVtt(cues)
        // 纯文本稿与 :media:subtitle 的 TXT 序列化是同一份实现（避免两处漂移）
        AsrOutputFormat.TEXT -> SubtitleWriter.serializeTxt(cues)
    }

    /** 产出文件名（与视频同名，换扩展名）。 */
    fun fileNameFor(videoPath: String, format: AsrOutputFormat = AsrOutputFormat.DEFAULT): String =
        SubtitleLocator.baseNameOf(SubtitleLocator.fileNameOf(videoPath)) + "." + format.extension

    /** 产出路径（与视频同目录同名）；纯文本稿也放同目录，便于用户自己找。 */
    fun siblingPathFor(videoPath: String, format: AsrOutputFormat = AsrOutputFormat.DEFAULT): String =
        SubtitleLocator.directoryOf(videoPath)
            .let { if (it.isEmpty()) fileNameFor(videoPath, format) else it + "/" + fileNameFor(videoPath, format) }

    /**
     * 写出字幕（**R14**）。
     *
     * @param backend 当前后端；null 表示还没有可用后端（按「无写权限」走本地兜底）。
     * @param videoPath 视频在后端内的路径。
     * @param fallbackDir 无写权限时的落地目录（App 私有，:app 用 filesDir/subtitles）。
     * @throws StorageException 网络中断之类**真正的失败**照抛，由上层分类成 [AsrFailureKind]。
     */
    suspend fun write(
        backend: StorageBackend?,
        videoPath: String,
        format: AsrOutputFormat,
        cues: List<SubtitleCue>,
        fallbackDir: File,
    ): AsrWriteResult {
        val target = siblingPathFor(videoPath, format)
        val text = render(cues, format)
        if (backend != null) {
            try {
                backend.write(target, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                return AsrWriteResult(target, AsrWriteKind.WRITTEN, "字幕已生成：" + target)
            } catch (e: StorageException.AccessDenied) {
                return fallback(videoPath, format, text, fallbackDir, e)
            } catch (e: StorageException.NotSupported) {
                return fallback(videoPath, format, text, fallbackDir, e)
            }
        }
        return fallback(videoPath, format, text, fallbackDir, null)
    }

    /** 无写权限的兜底：写进 App 私有目录，返回绝对路径与中文提示。 */
    private fun fallback(
        videoPath: String,
        format: AsrOutputFormat,
        text: String,
        fallbackDir: File,
        cause: Throwable?,
    ): AsrWriteResult {
        val file = File(fallbackDir, fileNameFor(videoPath, format))
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        val reason = cause?.message?.takeIf { it.isNotBlank() } ?: "目标目录不可写"
        return AsrWriteResult(
            path = file.absolutePath,
            kind = AsrWriteKind.LOCAL_FALLBACK,
            message = "无写入权限（" + reason + "），字幕已保存到 App 目录：" + file.absolutePath,
        )
    }
}
