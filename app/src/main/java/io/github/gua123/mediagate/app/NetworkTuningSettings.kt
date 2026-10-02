
package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import io.github.gua123.mediagate.data.storage.api.CacheTuning

/** 网络/缓冲可调参数的 DataStore（App 私有）。 */
private val Context.networkTuningDataStore by preferencesDataStore(name = "network_tuning_settings")

/**
 * 「网络与缓冲」偏好（**2026-10-03 用户要求**：「我自己调一下并发数，你加到设置里吧」）。
 *
 * 存的是"人话"（段大小 MB、块大小 KB、并发个数、预读段数），读出来一律经
 * [CacheTuning.fromStored] 归一到合法值——旧版本升级上来没有这些键，会拿到全套默认。
 */
class NetworkTuningSettings(
    private val context: Context,
    scope: CoroutineScope,
) {

    /** 当前参数（改一个键就会重新发射）。 */
    val tuning: StateFlow<CacheTuning> = context.networkTuningDataStore.data
        .map { prefs ->
            CacheTuning.fromStored(
                segmentMb = prefs[KEY_SEGMENT_MB],
                cacheGb = prefs[KEY_CACHE_GB],
                readAheadSegments = prefs[KEY_READ_AHEAD],
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, CacheTuning())

    suspend fun setSegmentMb(value: Int) {
        context.networkTuningDataStore.edit { it[KEY_SEGMENT_MB] = value }
    }

    /** 缓存总上限（**GB**，用户要求按 GB 给选项）。 */
    suspend fun setCacheGb(value: Int) {
        context.networkTuningDataStore.edit { it[KEY_CACHE_GB] = value }
    }

    suspend fun setReadAheadSegments(value: Int) {
        context.networkTuningDataStore.edit { it[KEY_READ_AHEAD] = value.coerceIn(0, CacheTuning.MAX_READ_AHEAD) }
    }

    /** 恢复默认（把四个键一起清掉，读出来就是 [CacheTuning] 的默认值）。 */
    suspend fun reset() {
        context.networkTuningDataStore.edit { prefs ->
            prefs.remove(KEY_SEGMENT_MB)
            prefs.remove(KEY_CACHE_GB)
            prefs.remove(KEY_READ_AHEAD)
        }
    }

    private companion object {
        val KEY_SEGMENT_MB = intPreferencesKey("segment_mb")
        val KEY_CACHE_GB = intPreferencesKey("cache_gb")
        val KEY_READ_AHEAD = intPreferencesKey("read_ahead_segments")
    }
}
