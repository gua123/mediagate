package io.github.gua123.mediagate.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.feature.player.video.SubtitleHost
import io.github.gua123.mediagate.feature.player.video.SubtitleWriteResult
import io.github.gua123.mediagate.media.subtitle.SubtitleCandidate
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleLocator
import io.github.gua123.mediagate.media.subtitle.SubtitleParseResult
import io.github.gua123.mediagate.media.subtitle.SubtitleReader
import io.github.gua123.mediagate.media.subtitle.SubtitleWriter
import java.io.File

/**
 * 字幕宿主（R14）的 :app 实现：把 :media:subtitle 的**定位器 / 解析器 / 写回**接到"当前后端"上。
 *
 * 关键点：
 * - 本地与远端**同一套代码**：列目录、开流、写回都走 [StorageBackend]，所以 WebDAV/SFTP/FTP 上的
 *   字幕与视频同目录时，能列目录就能匹配、能开流就能加载（远端字幕要显示加载中，见播放页）；
 * - **无写权限不静默**（R14）：写回时 [StorageException.AccessDenied] / [StorageException.NotSupported]
 *   会被翻译成"落到 App 私有目录"（[fallbackDir]，filesDir 下，不会被系统清理），
 *   界面据此提示「已保存到本地，可分享/稍后重试」；
 * - 后端是**懒取值**（[backend] 传函数而不是实例）：换根目录 / 换连接后自动跟随，不用重建本对象。
 *
 * @param backend 当前根目录/当前连接的后端；尚未选择时为 null（按"无访问权限"处理）。
 * @param fallbackDir 无写权限时的落地目录（App 私有）。
 */
class AppSubtitleHost(
    private val backend: () -> StorageBackend?,
    private val fallbackDir: File,
) : SubtitleHost {

    override suspend fun list(dir: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        requireBackend().list(dir, null)
    }

    override suspend fun discover(videoPath: String): List<SubtitleCandidate> = withContext(Dispatchers.IO) {
        SubtitleLocator.discover(requireBackend(), videoPath)
    }

    override suspend fun load(path: String): SubtitleParseResult = withContext(Dispatchers.IO) {
        SubtitleReader.read(requireBackend(), path)
    }

    /**
     * 写回视频同目录的同名字幕；无权限时落本地缓存（R14）。
     *
     * @return [SubtitleWriteResult.Written] 写回成功；[SubtitleWriteResult.LocalFallback] 无写权限已落本地；
     *   [SubtitleWriteResult.Failed] 其它失败（网络中断等），什么都没有写。
     */
    override suspend fun writeBack(
        videoPath: String,
        format: SubtitleFormat,
        cues: List<SubtitleCue>,
    ): SubtitleWriteResult = withContext(Dispatchers.IO) {
        val target = SubtitleWriter.siblingPathOf(videoPath, format)
        try {
            SubtitleWriter.writeBack(requireBackend(), target, format, cues)
            SubtitleWriteResult.Written(target)
        } catch (e: StorageException.AccessDenied) {
            fallback(target, format, cues, e)
        } catch (e: StorageException.NotSupported) {
            fallback(target, format, cues, e)
        } catch (e: StorageException) {
            AppLog.w(TAG, "字幕写回失败：$target", e)
            SubtitleWriteResult.Failed(e.message)
        }
    }

    /** 无写权限的兜底：写进 App 私有目录并回传绝对路径（界面提示可分享/稍后重试）。 */
    private fun fallback(
        target: String,
        format: SubtitleFormat,
        cues: List<SubtitleCue>,
        cause: Throwable,
    ): SubtitleWriteResult {
        val file = File(fallbackDir, SubtitleLocator.fileNameOf(target))
        AppLog.i(TAG, "无写权限，字幕改存本地：" + cause.message + " → " + file.absolutePath)
        return runCatching { SubtitleWriter.writeLocal(file, format, cues) }
            .map { SubtitleWriteResult.LocalFallback(it.absolutePath) }
            .onFailure { AppLog.w(TAG, "字幕落本地也失败：" + file.absolutePath, it) }
            .getOrElse { SubtitleWriteResult.Failed(cause.message) }
    }

    /** 当前后端；尚未选择根目录时按"无访问权限"抛（与图片查看器同一口径）。 */
    private fun requireBackend(): StorageBackend =
        backend() ?: throw StorageException.AccessDenied("尚未选择媒体根目录")

    private companion object {

        const val TAG = "app-subtitle"
    }
}
