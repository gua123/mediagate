package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「播放时让路」的设置项（**M7-B / R19 的关键约束：不影响看视频**）。
 *
 * @property enabled 总开关（关了就一直满速跑，用户自己承担卡顿）。
 * @property pauseWhilePlaying 让路方式：false = 降线程（默认，识别变慢但不中断），
 *   true = 直接暂停识别，等播放结束再继续。
 */
data class AsrYieldSettings(
    val enabled: Boolean = true,
    val pauseWhilePlaying: Boolean = false,
)

/**
 * 一次识别该用多少资源（**纯数据**，由 [AsrYieldPolicy.of] 算出来）。
 *
 * @property threadCount 本次 whisper 用的线程数。
 * @property backgroundPriority 是否把线程降到后台优先级（ASR **永远**降，plan 4.12 明确要求）。
 * @property hold 是否要让路等待（暂停识别直到播放结束）。
 * @property yielding 是否正因为播放而在让路（界面提示用）。
 */
data class AsrYieldPolicy(
    val threadCount: Int,
    val backgroundPriority: Boolean,
    val hold: Boolean,
    val yielding: Boolean,
) {
    companion object {

        /** 让路时的线程数（plan 4.12：降到 1）。 */
        const val YIELD_THREADS = 1

        /**
         * 决策（**纯函数**，单测穷举四种组合）。
         *
         * - 播放中 + 开关打开 → 线程降到 1；勾了「暂停识别」时进一步 [hold]；
         * - 播放中 + 开关关闭 → 满线程（用户自己选的）；
         * - 没播放 → 满线程；
         * - 任何情况下 ASR 都跑后台优先级（不与前台 UI/播放抢调度）。
         */
        fun of(playing: Boolean, settings: AsrYieldSettings, requestedThreads: Int): AsrYieldPolicy {
            val yielding = playing && settings.enabled
            return AsrYieldPolicy(
                threadCount = if (yielding) YIELD_THREADS else WhisperNative.clampThreads(requestedThreads),
                backgroundPriority = true,
                hold = yielding && settings.pauseWhilePlaying,
                yielding = yielding,
            )
        }
    }
}

/**
 * 播放让路闸门（**M7-B / R19**）。
 *
 * 它只做一件事：把「前台是否正在播放」这一个外部信号，变成「本次识别用几个线程 / 要不要先等等」
 * 这一个决策。决策本身是 [AsrYieldPolicy.of] 这个纯函数，所以四种组合都能在 JVM 单测里覆盖；
 * 真机上只剩「谁把 playing 喂进来」——:app 把音频后台服务与视频播放页的状态合成一条
 * [StateFlow]，模型层与队列层都不需要知道播放器的存在。
 *
 * @param playing 前台是否正在播放（音频或视频任一）。
 * @param settings 让路设置（设置页可改；默认开）。
 */
class PlaybackYieldGate(
    private val playing: StateFlow<Boolean>,
    private val settings: StateFlow<AsrYieldSettings>,
) {

    /** 是否正在因为播放而让路（界面显示「播放中，已降速」）。 */
    val yielding: Flow<Boolean> = combine(playing, settings) { isPlaying, current ->
        isPlaying && current.enabled
    }

    /** 是否该暂停识别等播放结束。 */
    val holding: Flow<Boolean> = combine(playing, settings) { isPlaying, current ->
        isPlaying && current.enabled && current.pauseWhilePlaying
    }

    /** 当下的决策（同步取值版，执行循环每个窗口开始时调一次）。 */
    fun policy(requestedThreads: Int): AsrYieldPolicy =
        AsrYieldPolicy.of(playing.value, settings.value, requestedThreads)

    /**
     * 让路等待：勾了「暂停识别」且正在播放时挂起，直到播放结束（或用户关掉开关）。
     *
     * 没在播、或没勾暂停时**立刻返回**（不引入任何延迟）。
     */
    suspend fun awaitTurn() {
        holding.first { !it }
    }

    /** 让路状态的中文说明（通知栏/界面用；不在让路时是空串）。 */
    fun describe(policy: AsrYieldPolicy = policy(WhisperNative.DEFAULT_THREADS)): String =
        if (policy.yielding) "正在播放，识别已让路（" + policy.threadCount + " 线程）" else ""
}

/** 便捷扩展：把一条布尔流转成让路闸门（设置项由调用方另给）。 */
fun StateFlow<Boolean>.asYieldGate(settings: StateFlow<AsrYieldSettings>): PlaybackYieldGate =
    PlaybackYieldGate(this, settings)

/** 便捷：把让路状态映射成一句中文（组合函数零 IO，这里只是纯映射）。 */
fun PlaybackYieldGate.describeFlow(): Flow<String> = yielding.map { if (it) "播放中，识别已让路" else "" }
