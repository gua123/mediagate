
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
 * 存的是"人话"（缓存上限、预读段数）；**段大小已内置，不再提供设置入口**。
 * 旧版本持久化的 segment_mb 仍读取，纯粹是为了不让已有段缓存按新段宽错位；新安装默认 4 MB。
 * 读出来一律经 [CacheTuning.fromStored] 归一到合法值。
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
                // 优先读 MB 键（能表达任意大小）；老版本存的 GB 键按 ×1024 迁移
                cacheMb = prefs[KEY_CACHE_MB] ?: prefs[KEY_CACHE_GB]?.let { it * 1024 },
                readAheadSegments = prefs[KEY_READ_AHEAD],
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, CacheTuning())

    /**
     * 缓存总上限（**MB 存储**：这样"输入框里填 3.5 GB"这种任意值也能表达）。
     *
     * 2026-10-03 用户要求「网络缓冲上限增加一个可以输入的窗口」⇒ 从"只能选档位"升级为"任意值 + 快捷档位"。
     */
    suspend fun setCacheMb(valueMb: Int) {
        context.networkTuningDataStore.edit {
            it[KEY_CACHE_MB] = valueMb
            // 顺手清掉老键，避免"改了不生效"的困惑
            it.remove(KEY_CACHE_GB)
        }
    }

    suspend fun setReadAheadSegments(value: Int) {
        context.networkTuningDataStore.edit { it[KEY_READ_AHEAD] = value.coerceIn(0, CacheTuning.MAX_READ_AHEAD) }
    }

    /** 恢复默认（缓存上限/预读；段大小已内置，不再随「恢复默认」改动，避免旧缓存错位）。 */
    suspend fun reset() {
        context.networkTuningDataStore.edit { prefs ->
            prefs.remove(KEY_CACHE_GB)
            prefs.remove(KEY_CACHE_MB)
            prefs.remove(KEY_READ_AHEAD)
        }
    }

    private companion object {
        val KEY_SEGMENT_MB = intPreferencesKey("segment_mb")
        val KEY_CACHE_GB = intPreferencesKey("cache_gb")
        val KEY_CACHE_MB = intPreferencesKey("cache_mb")
        val KEY_READ_AHEAD = intPreferencesKey("read_ahead_segments")
    }
}
