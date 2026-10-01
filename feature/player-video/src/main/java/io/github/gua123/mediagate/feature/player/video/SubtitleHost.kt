package io.github.gua123.mediagate.feature.player.video

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.subtitle.SubtitleCandidate
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleParseResult

/**
 * 字幕宿主能力（R14 外挂字幕 + 写回）。
 *
 * 由 :app 用 :media:subtitle 装配（定位器 [SubtitleLocator]、解析器 [SubtitleReader]、
 * 写回 [SubtitleWriter]），在本模块里只暴露三件事：**列同目录 → 读解析 → 写回**。
 * 这样一个接口就覆盖了「本地与远端同一套 StorageBackend」的要求：
 * 远端字幕不需要任何额外代码，能列目录就能匹配、能开流就能加载。
 *
 * 线程约定：三个方法都是挂起函数，实现内部切 IO；调用方（ViewModel）在 [io] 调度器上调用。
 * [io.github.gua123.mediagate.data.storage.api.StorageException] 由实现原样抛出，
 * 播放页据此给中文提示（R16），不吞异常、不静默。
 */
interface SubtitleHost {

    /** 列出 [dir] 下的直接子项（与视频同一个后端）。失败抛 StorageException。 */
    suspend fun list(dir: String): List<RemoteEntry>

    /**
     * 按 R14 优先级匹配 [videoPath] 同目录的字幕候选（自动匹配在前、手动候选在后）。
     *
     * 一次返回**多候选**交给界面选择；播放时只加载一条轨道（[SubtitleHost.load]）。
     */
    suspend fun discover(videoPath: String): List<SubtitleCandidate>

    /** 读取并解析 [path]（本地/远端同口径）。失败抛 StorageException。 */
    suspend fun load(path: String): SubtitleParseResult

    /**
     * 写回字幕（R14）：微调后的时间轴写进视频同目录的同名文件。
     *
     * **无写权限不吞**：实现负责把内容落到 App 私有目录并返回 [SubtitleWriteResult.LocalFallback]，
     * 让界面提示「已保存到本地，可分享/稍后重试」——绝不静默丢弃。
     */
    suspend fun writeBack(videoPath: String, format: SubtitleFormat, cues: List<SubtitleCue>): SubtitleWriteResult
}

/**
 * 字幕写回结果（R14）。
 *
 * 三个分支对应三种界面提示：写回远端成功 / 无权限落本地（可分享、可稍后重试）/ 其它失败。
 */
sealed interface SubtitleWriteResult {

    /** 已写回原目录（本地文件或远端目录皆算）。 */
    data class Written(val path: String) : SubtitleWriteResult

    /** 无写权限：已落到 App 私有目录（R14 的兜底路径，绝不静默丢弃）。 */
    data class LocalFallback(val path: String) : SubtitleWriteResult

    /** 其它失败（网络中断、目录不存在…），什么都没写。 */
    data class Failed(val reason: String?) : SubtitleWriteResult
}
