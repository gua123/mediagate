package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import io.github.gua123.mediagate.media.playback.PlaybackProgress
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore

/** 断点续播的 DataStore（App 私有；进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.playbackDataStore by preferencesDataStore(name = "playback_progress")

/**
 * 断点续播存储的 DataStore 实现（R18）。
 *
 * 为什么不用 Room：plan 第 7 章的 playback_progress 表还没建，而本轮只需要"记一个位置"；
 * 一张表要配套 Entity / DAO / 迁移，收益不成比例。:media:playback 只定义了
 * [PlaybackProgressStore] 接口，将来换成 Room 表时只改这一个类。
 *
 * 存储形态：一条 Preferences 字符串，记录之间用 [RECORD_SEPARATOR] 分隔、字段之间用
 * [FIELD_SEPARATOR] 分隔。这两种控制字符在真实路径里不会出现（Linux 路径不含 NUL），
 * 因此不需要转义。写入时按 updatedAt 排序，只保留最近 [MAX_RECORDS] 条。
 *
 * @param context 应用上下文。
 */
class PlaybackProgressSettings(private val context: Context) : PlaybackProgressStore {

    override suspend fun load(backendId: String, path: String): PlaybackProgress? =
        readAll()[key(backendId, path)]

    override suspend fun save(progress: PlaybackProgress) {
        val records = readAll().toMutableMap()
        records[key(progress.backendId, progress.path)] = progress
        val text = records.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_RECORDS)
            .joinToString(RECORD_SEPARATOR) { encode(it) }
        context.playbackDataStore.edit { preferences ->
            if (text.isEmpty()) {
                preferences.remove(KEY_PROGRESS)
            } else {
                preferences[KEY_PROGRESS] = text
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun readAll(): Map<String, PlaybackProgress> {
        val text = context.playbackDataStore.data.first()[KEY_PROGRESS].orEmpty()
        if (text.isEmpty()) return emptyMap()
        return text.split(RECORD_SEPARATOR)
            .mapNotNull { decode(it) }
            .associateBy { key(it.backendId, it.path) }
    }

    private fun encode(progress: PlaybackProgress): String = listOf(
        progress.backendId,
        progress.path,
        progress.positionMs.toString(),
        progress.updatedAt.toString(),
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(record: String): PlaybackProgress? {
        val parts = record.split(FIELD_SEPARATOR)
        if (parts.size != 4) return null
        val positionMs = parts[2].toLongOrNull() ?: return null
        val updatedAt = parts[3].toLongOrNull() ?: return null
        if (parts[0].isEmpty() || parts[1].isEmpty() || positionMs < 0) return null
        return PlaybackProgress(
            backendId = parts[0],
            path = parts[1],
            positionMs = positionMs,
            updatedAt = updatedAt,
        )
    }

    /** 存储 key：后端 id + 路径（两者拼起来唯一确定一个条目）。 */
    private fun key(backendId: String, path: String): String = backendId + FIELD_SEPARATOR + path

    private companion object {

        val KEY_PROGRESS = stringPreferencesKey("playback_progress_records")

        /** 字段分隔符（NUL）：真实路径里不会出现。 */
        const val FIELD_SEPARATOR = "\u0000"

        /** 记录分隔符（SOH）：真实路径里不会出现。 */
        const val RECORD_SEPARATOR = "\u0001"

        /** 最多保留多少条最近记录（超出按 updatedAt 淘汰）。 */
        const val MAX_RECORDS = 200
    }
}
