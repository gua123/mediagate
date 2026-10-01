package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.asr.AsrEngine
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.PlaybackYieldGate
import io.github.gua123.mediagate.media.asr.WhisperModel
import java.io.File

/**
 * 字幕前台服务向应用取依赖的接口（**M7-B / R19**）。
 *
 * 为什么要有这层：Service 由系统创建，拿不到构造注入（与 :media:playback 的 PlaybackHost
 * 同一套路），只能从 application 反查。把依赖收在一个接口里，[AsrForegroundService] 就
 * 不需要知道 AppContainer 的内部结构，:app 之外也不会有人误用它。
 */
interface AsrRuntimeHost {

    /** 队列持有者（唯一真相）。 */
    val asrController: AsrQueueController

    /** 识别执行器（FFmpeg 取音 + whisper.cpp JNI + 时间轴合并）。 */
    val asrEngine: AsrEngine

    /** 播放让路闸门（R19：不影响看视频）。 */
    val asrYieldGate: PlaybackYieldGate

    /** 无写权限时字幕的落地目录（App 私有）。 */
    val asrFallbackDir: File

    /** 当前后端（远端取音/写回都走它）；null = 还没选目录/连接。 */
    val asrBackend: StorageBackend?

    /** 当前选用的模型档位。 */
    fun asrModel(): WhisperModel

    /** 已安装模型的绝对路径；没装返回 null。 */
    fun asrModelPath(model: WhisperModel): String?

    /**
     * 任务的音源（FFmpeg 认得的路径或 URL）。
     *
     * 本地文件给绝对路径；远端文件走回环代理的低优先级连接
     * （plan 4.12：远端取音复用同一 StorageBackend，不与播放抢带宽）。
     */
    fun asrSource(item: AsrItem): String?

    /** 音轨时长（毫秒）；解不出给 0（引擎据此判「无音轨」）。 */
    suspend fun asrDurationMs(item: AsrItem): Long
}
