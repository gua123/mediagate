package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 「当前连接」的 DataStore（App 私有，进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.connectionDataStore by preferencesDataStore(name = "connection_settings")

/**
 * 「当前连接」的持久化（**R8** / **R7**）。
 *
 * 只存一个 id：连接的名称、地址、规则、密文都在 `connection` / `address` / `network_rule` 三张表里，
 * 这里存 id 就够了（避免两份数据不一致）。id 指向的行被删掉时，[AppContainer] 会自动回落到
 * 首页选择的本地根目录。
 *
 * 与 [RootSettings] 分开两个 DataStore 文件：根目录（R12）与当前连接（R8）是两件独立的事，
 * 换连接不该动本地根目录配置。
 */
class ConnectionSettings(private val context: Context) {

    /** 当前连接 id；null = 没有指定。 */
    val currentId: Flow<Long?> = context.connectionDataStore.data.map { preferences ->
        preferences[KEY_ID]?.takeIf { it > 0L }
    }

    /** 设置当前连接（null = 清除）。 */
    suspend fun setCurrent(id: Long?) {
        context.connectionDataStore.edit { preferences ->
            if (id == null || id <= 0L) preferences.remove(KEY_ID) else preferences[KEY_ID] = id
        }
    }

    private companion object {
        val KEY_ID = longPreferencesKey("current_connection_id")
    }
}
