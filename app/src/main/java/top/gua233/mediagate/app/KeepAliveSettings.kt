package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.gua123.mediagate.feature.settings.KeepAliveItemKind

/** 保活确认的 DataStore（App 私有，进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.keepAliveDataStore by preferencesDataStore(name = "keep_alive_settings")

/**
 * 「需手动确认」两项的持久化（**R18** 澎湃 OS 保活引导）。
 *
 * 为什么只有这两项要落盘：通知权限与省电策略**系统查得到**（每次进设置页现查即可），
 * 而自启动 / 后台弹出界面在厂商设置里没有公开 API，只能记住"用户说他已经开了"。
 */
class KeepAliveSettings(private val context: Context) {

    /** 已确认开启的项目集合。 */
    val confirmed: Flow<Set<KeepAliveItemKind>> = context.keepAliveDataStore.data.map { preferences ->
        buildSet {
            if (preferences[KEY_AUTO_START] == true) add(KeepAliveItemKind.AUTO_START)
            if (preferences[KEY_BACKGROUND_POPUP] == true) add(KeepAliveItemKind.BACKGROUND_POPUP)
        }
    }

    /** 记录/取消一项的确认（只有自启动、后台弹出界面两项会被写入）。 */
    suspend fun setConfirmed(kind: KeepAliveItemKind, confirmed: Boolean) {
        val key = when (kind) {
            KeepAliveItemKind.AUTO_START -> KEY_AUTO_START
            KeepAliveItemKind.BACKGROUND_POPUP -> KEY_BACKGROUND_POPUP
            else -> return
        }
        context.keepAliveDataStore.edit { preferences -> preferences[key] = confirmed }
    }

    private companion object {
        val KEY_AUTO_START = booleanPreferencesKey("auto_start_confirmed")
        val KEY_BACKGROUND_POPUP = booleanPreferencesKey("background_popup_confirmed")
    }
}
