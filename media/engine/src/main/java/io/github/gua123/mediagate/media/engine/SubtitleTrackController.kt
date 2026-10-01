package io.github.gua123.mediagate.media.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 字幕轨道状态（R14 外挂字幕单轨 + 时间轴微调；R9 换内核时要保持）。
 *
 * @property enabled 字幕总开关。
 * @property offsetMs 时间轴微调（正数 = 字幕延后）。
 * @property uri 外挂字幕文件的 `mediagate://` 伪 URI（null = 只用内嵌字幕轨）。
 * @property label 展示用标签（文件名或内嵌轨语言）。
 */
data class SubtitleState(
    val enabled: Boolean = false,
    val offsetMs: Long = 0L,
    val uri: String? = null,
    val label: String? = null,
) {
    companion object {

        /** 时间轴微调下限。 */
        const val MIN_OFFSET_MS = -60_000L

        /** 时间轴微调上限。 */
        const val MAX_OFFSET_MS = 60_000L

        /** 把偏移量夹到允许区间（UI 按 ±0.5 s 步进，这里只兜底防越界）。 */
        fun clampOffset(offsetMs: Long): Long = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
    }
}

/**
 * 字幕轨道控制器（plan 4.6 `subtitles(): SubtitleTrackController`）。
 *
 * 接口只表达「用户意图 + 当前状态」，两个内核各自落地：
 * - Media3：`MediaItem.SubtitleConfiguration`（外挂轨）+ `TrackSelectionParameters` 开关；
 *   **偏移量 Media3 没有 API**，由 UI 渲染 Cue 时应用（见 ExoPlayerEngine 注释），状态仍在这里保留；
 * - LibVLC：`addSlave(Subtitle, uri)` + `setSpuTrack(-1)` 关轨 + `setSpuDelay` 直接生效。
 */
interface SubtitleTrackController {

    /** 当前字幕状态（切换内核时由切换器读取并恢复）。 */
    val state: StateFlow<SubtitleState>

    /** 开关字幕。 */
    fun setEnabled(enabled: Boolean)

    /** 时间轴微调（毫秒，正数 = 字幕延后）；越界会被夹到 [SubtitleState.MIN_OFFSET_MS]..[MAX_OFFSET_MS]。 */
    fun setOffsetMs(offsetMs: Long)

    /** 选择外挂字幕轨道（`mediagate://` 伪 URI）；null = 清空外挂轨，回到内嵌轨。 */
    fun selectTrack(uri: String?)
}

/**
 * 默认实现：把状态收敛在一处，任何变更都回调给内核落地（[onApply]）。
 *
 * 抽出来是为了让两个内核共用同一份状态机与边界处理（夹取、去空串），
 * 内核只需要实现「怎么把状态应用到自己的播放器」。
 *
 * @param onApply 状态变更回调；由引擎实现负责实际生效（可能抛异常，由调用方决定是否上报）。
 */
class DefaultSubtitleTrackController(
    private val onApply: (SubtitleState) -> Unit = {},
) : SubtitleTrackController {

    private val _state = MutableStateFlow(SubtitleState())

    override val state: StateFlow<SubtitleState> = _state.asStateFlow()

    override fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    override fun setOffsetMs(offsetMs: Long) = update { it.copy(offsetMs = SubtitleState.clampOffset(offsetMs)) }

    override fun selectTrack(uri: String?) = update { it.copy(uri = uri?.takeIf { value -> value.isNotBlank() }) }

    /**
     * 一次性恢复整份状态（引擎切换用，R9）。
     *
     * 整体赋值而不是逐项调用：`label` 之类的展示字段也要原样带过去，
     * 且内核只需要处理**一次**状态落地（两个内核的实现都是读整份状态）。
     */
    fun restore(target: SubtitleState) {
        update { target.copy(offsetMs = SubtitleState.clampOffset(target.offsetMs)) }
    }

    /**
     * 状态变更的唯一入口：先算新值、写回、再回调内核落地。
     *
     * 只在主线程调用（与两个内核的调用约定一致），因此不需要 CAS 循环。
     */
    private fun update(transform: (SubtitleState) -> SubtitleState) {
        val next = transform(_state.value)
        _state.value = next
        onApply(next)
    }
}
