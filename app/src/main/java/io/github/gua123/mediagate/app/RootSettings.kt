package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.gua123.mediagate.feature.browser.EntrySort
import io.github.gua123.mediagate.feature.browser.EntrySortMode
import io.github.gua123.mediagate.feature.browser.RootModeKind

/** 根目录设置的 DataStore（App 私有，进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.rootDataStore by preferencesDataStore(name = "root_settings")

/**
 * 根目录配置的持久化形态（R12 双模式）。
 *
 * @property mode 模式；[RootModeKind.NONE] 不会出现在已保存的配置里（未选择 = 没有配置）。
 * @property value SAF 是树 URI 字符串；全盘访问是挂载路径。
 * @property display 展示用短名（SAF 取目录名，全盘就是路径）。
 */
data class RootConfig(
    val mode: RootModeKind,
    val value: String,
    val display: String,
)

/**
 * 根目录设置的读写（DataStore Preferences，R12）。
 *
 * 只做「读一条 / 写一条 / 清空」，不含任何权限逻辑——权限判断与后端创建都在 [AppContainer]。
 * 数据存于 App 私有目录，卸载即清除；SAF 的持久化授权由系统在 `takePersistableUriPermission` 后保存。
 */
class RootSettings(private val context: Context) {

    /**
     * 列表排序设置（**2026-10-03 用户要求**）：方式 + 升降序。
     *
     * 存字符串而不是 ordinal：将来加排序方式时旧数据不会错位。
     */
    val sort: Flow<EntrySort> = context.rootDataStore.data.map { preferences ->
        val mode = runCatching { EntrySortMode.valueOf(preferences[KEY_SORT_MODE].orEmpty()) }.getOrNull()
            ?: EntrySortMode.NAME
        EntrySort(mode = mode, ascending = preferences[KEY_SORT_ASC] ?: true)
    }

    /**
     * LibVLC 启动探针的结论（**2026-10-03**）：null = 还没测过 / 测了但结论未知。
     *
     * 存字符串而不是布尔：将来加"未知/待重测"这类状态不用改数据类型。
     */
    val vlcProbeVerdict: Flow<String?> = context.rootDataStore.data.map { it[KEY_VLC_VERDICT] }

    /**
     * **最后一次播放的文件路径**（2026-10-03 用户要求：「在重新打开软件时，如果网络通畅，
     * 自动打开到最后一次播放的文件夹」+「最后一次播放的视频和文件夹变一个颜色」）。
     *
     * 两个用途共用一条记录：① 启动时取它的父目录当浏览页初始目录；② 浏览页高亮"上次播放"的路径链。
     */
    val lastPlayedPath: Flow<String?> = context.rootDataStore.data.map { it[KEY_LAST_PLAYED] }

    /**
     * **最后一次浏览的目录**（2026-10-03 真机修复配套）：没有播放记录时的兜底，
     * 也用于"退出重开还在原来那个文件夹"。
     */
    val lastBrowsedPath: Flow<String?> = context.rootDataStore.data.map { it[KEY_LAST_BROWSED] }

    /** 记下当前浏览的目录（空串 = 清掉）。 */
    suspend fun setLastBrowsedPath(path: String?) {
        context.rootDataStore.edit { preferences ->
            if (path.isNullOrBlank()) preferences.remove(KEY_LAST_BROWSED)
            else preferences[KEY_LAST_BROWSED] = path
        }
    }

    /** 记下最后一次播放的文件（空串 = 清掉）。 */
    suspend fun setLastPlayedPath(path: String?) {
        context.rootDataStore.edit { preferences ->
            if (path.isNullOrBlank()) preferences.remove(KEY_LAST_PLAYED)
            else preferences[KEY_LAST_PLAYED] = path
        }
    }

    /** 记下探针结论（null = 清掉，视为未知）。 */
    suspend fun setVlcProbeVerdict(verdict: String?) {
        context.rootDataStore.edit { preferences ->
            if (verdict == null) preferences.remove(KEY_VLC_VERDICT) else preferences[KEY_VLC_VERDICT] = verdict
        }
    }

    /** 保存排序设置。 */
    suspend fun setSort(sort: EntrySort) {
        context.rootDataStore.edit { preferences ->
            preferences[KEY_SORT_MODE] = sort.mode.name
            preferences[KEY_SORT_ASC] = sort.ascending
        }
    }

    /** 当前配置流；未选择根目录时发出 `null`。 */
    val config: Flow<RootConfig?> = context.rootDataStore.data.map { preferences ->
        preferences.toConfig()
    }

    /** 保存 SAF 目录授权结果。 */
    suspend fun setSaf(treeUri: String, display: String) {
        write(RootModeKind.SAF, treeUri, display)
    }

    /** 保存全盘访问根目录。 */
    suspend fun setAllFiles(path: String) {
        write(RootModeKind.ALL_FILES, path, path)
    }

    /** 清除根目录（回到「未选择」）。 */
    suspend fun clear() {
        context.rootDataStore.edit { it.clear() }
    }

    private suspend fun write(mode: RootModeKind, value: String, display: String) {
        context.rootDataStore.edit { preferences ->
            preferences[KEY_MODE] = mode.name
            preferences[KEY_VALUE] = value
            preferences[KEY_DISPLAY] = display
        }
    }

    private fun Preferences.toConfig(): RootConfig? {
        val rawMode = this[KEY_MODE] ?: return null
        val mode = runCatching { RootModeKind.valueOf(rawMode) }.getOrNull() ?: return null
        if (mode == RootModeKind.NONE) return null
        val value = this[KEY_VALUE].orEmpty()
        if (value.isEmpty()) return null
        val display = this[KEY_DISPLAY].orEmpty().ifEmpty { value }
        return RootConfig(mode = mode, value = value, display = display)
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("root_mode")
        val KEY_VALUE = stringPreferencesKey("root_value")
        val KEY_DISPLAY = stringPreferencesKey("root_display")

        /** LibVLC 启动探针的结论（2026-10-03）。 */
        val KEY_VLC_VERDICT = stringPreferencesKey("vlc_probe_verdict")

        /** 最后一次播放的文件路径（2026-10-03）：启动恢复目录 + 浏览页高亮共用。 */
        val KEY_LAST_PLAYED = stringPreferencesKey("last_played_path")

        /** 最后一次浏览的目录（2026-10-03）：重开 App 回到这里。 */
        val KEY_LAST_BROWSED = stringPreferencesKey("last_browsed_path")

        /** 列表排序（2026-10-03）：方式 + 升降序。 */
        val KEY_SORT_MODE = stringPreferencesKey("list_sort_mode")
        val KEY_SORT_ASC = booleanPreferencesKey("list_sort_ascending")
    }
}
