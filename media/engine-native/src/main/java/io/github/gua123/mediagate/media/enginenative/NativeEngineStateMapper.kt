package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.media.engine.EngineState

/**
 * 自研引擎的 native 状态 → [EngineState]（**纯函数，JVM 可测**）。
 *
 * 为什么单独抽出来：状态映射错了，界面就会"卡在准备中"或"明明在播却显示暂停"，
 * 而这类 bug 在真机上只能靠肉眼发现。native 侧的状态串是固定几个词，
 * 所以这里可以逐条钉死（见 NativeEngineStateMapperTest）。
 */
object NativeEngineStateMapper {

    /** native 状态串里的状态词（与 engine_jni.cpp 的 stateName 一一对应）。 */
    const val STATE_IDLE = "空闲"
    const val STATE_PLAYING = "播放中"
    const val STATE_PAUSED = "已暂停"
    const val STATE_STOPPED = "已停止"
    const val STATE_ENDED = "已播完"
    const val STATE_ERROR = "出错"

    /**
     * 映射规则：
     * - 解析不出状态串（null）⇒ 报错，而不是静默当成空闲（那会让界面永远停在"准备中"）；
     * - 带 "错误：…" 前缀 ⇒ [EngineState.Error]（原因是中文，native 侧已经翻好）；
     * - 播放中 / 已暂停 ⇒ [EngineState.Ready]（"暂停"在引擎状态机里仍是 ready，只是没在播）；
     * - 已播完 ⇒ [EngineState.Ended]；空闲 / 已停止 ⇒ [EngineState.Idle]。
     */
    fun map(status: NativePlayerStatus?): EngineState {
        if (status == null) return EngineState.Error("自研引擎状态不可读（native 返回值不是认识的格式）")
        status.error?.let { return EngineState.Error(it) }
        return when (status.state) {
            STATE_PLAYING, STATE_PAUSED -> EngineState.Ready
            STATE_ENDED -> EngineState.Ended
            STATE_IDLE, STATE_STOPPED -> EngineState.Idle
            STATE_ERROR -> EngineState.Error("自研引擎播放出错（原因未记录）")
            else -> EngineState.Error("自研引擎状态未知：" + status.state)
        }
    }
}
