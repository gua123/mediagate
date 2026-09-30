package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot
import io.github.gua123.mediagate.media.asr.AsrYieldSettings
import io.github.gua123.mediagate.media.asr.WhisperModel
import io.github.gua123.mediagate.media.asr.WhisperNative

/** 音转字幕设置的 DataStore（App 私有，进程内单例由 preferencesDataStore 委托保证）。 */
private val Context.asrDataStore by preferencesDataStore(name = "asr_settings")

/**
 * 音转字幕的全部用户设置（**M7-B / R14 / R19**）。
 *
 * @property modelId 模型档位（tiny/base/small，默认 small）。
 * @property threads 推理线程数（默认 4，plan 4.12）。
 * @property concurrency 队列并发（默认 1，可调 2）。
 * @property yieldEnabled 「播放时自动让路」总开关（默认开）。
 * @property pauseWhilePlaying 让路方式：true = 播放时直接暂停识别。
 */
data class AsrPreferences(
    val modelId: String = WhisperModel.DEFAULT.id,
    val threads: Int = WhisperNative.DEFAULT_THREADS,
    val concurrency: Int = AsrQueueSnapshot.DEFAULT_CONCURRENCY,
    val yieldEnabled: Boolean = true,
    val pauseWhilePlaying: Boolean = false,
) {
    /** 模型档位对象。 */
    val model: WhisperModel get() = WhisperModel.of(modelId)

    /** 让路设置（给 PlaybackYieldGate 用）。 */
    val yieldSettings: AsrYieldSettings get() = AsrYieldSettings(yieldEnabled, pauseWhilePlaying)
}

/**
 * 音转字幕设置的读写（DataStore Preferences）。
 *
 * 只做「读一条 / 写一条」，不含任何业务逻辑——模型下载、队列、让路判断都在 :media:asr 与
 * [AsrQueueController] 里，设置页只管把用户选择存下来。
 */
class AsrSettings(private val context: Context) {

    /** 当前设置流（永远有值：读不到就是默认值）。 */
    val preferences: Flow<AsrPreferences> = context.asrDataStore.data.map { it.toAsrPreferences() }

    /** 只取让路设置（PlaybackYieldGate 关心这一小条）。 */
    val yieldSettings: Flow<AsrYieldSettings> = preferences.map { it.yieldSettings }

    suspend fun setModelId(id: String) = update { it[KEY_MODEL] = WhisperModel.of(id).id }

    suspend fun setThreads(threads: Int) = update { it[KEY_THREADS] = WhisperNative.clampThreads(threads) }

    suspend fun setConcurrency(value: Int) = update { it[KEY_CONCURRENCY] = value.coerceIn(1, AsrQueueSnapshot.MAX_CONCURRENCY) }

    suspend fun setYieldEnabled(enabled: Boolean) = update { it[KEY_YIELD] = enabled }

    suspend fun setPauseWhilePlaying(pause: Boolean) = update { it[KEY_PAUSE_WHILE_PLAYING] = pause }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.asrDataStore.edit(block)
    }

    private fun Preferences.toAsrPreferences(): AsrPreferences = AsrPreferences(
        modelId = WhisperModel.of(this[KEY_MODEL]).id,
        threads = WhisperNative.clampThreads(this[KEY_THREADS] ?: WhisperNative.DEFAULT_THREADS),
        concurrency = (this[KEY_CONCURRENCY] ?: AsrQueueSnapshot.DEFAULT_CONCURRENCY)
            .coerceIn(1, AsrQueueSnapshot.MAX_CONCURRENCY),
        yieldEnabled = this[KEY_YIELD] ?: true,
        pauseWhilePlaying = this[KEY_PAUSE_WHILE_PLAYING] ?: false,
    )

    private companion object {
        val KEY_MODEL = stringPreferencesKey("asr_model_id")
        val KEY_THREADS = intPreferencesKey("asr_threads")
        val KEY_CONCURRENCY = intPreferencesKey("asr_concurrency")
        val KEY_YIELD = booleanPreferencesKey("asr_yield_enabled")
        val KEY_PAUSE_WHILE_PLAYING = booleanPreferencesKey("asr_pause_while_playing")
    }
}
