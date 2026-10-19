package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * **缓存队列的序列化**（纯函数）：把"待处理的任务"存成字符串集合，进程被杀之后还能续上。
 *
 * 编码用 \u0001 当分隔符（文件名里不可能出现控制字符），所以"路径 + 显示名"两段都能原样还原。
 */
object CacheQueueCodec {

    private const val SEPARATOR = '\u0001'

    /** 编码成可持久化的集合（保持顺序无所谓：恢复时按原顺序排不回来，但队列顺序对用户不重要）。 */
    fun encode(entries: List<Pair<String, String>>): Set<String> =
        entries.map { (path, name) -> path + SEPARATOR + name }.toSet()

    /** 解码；丢掉的坏行（没有分隔符 / 空路径）直接跳过，不让坏数据把队列恢复搞崩。 */
    fun decode(raw: Set<String>): List<Pair<String, String>> = raw.mapNotNull { line ->
        val index = line.indexOf(SEPARATOR)
        if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
    }
}

/** DataStore 键：待处理任务（path + name）。 */
private val PENDING = stringSetPreferencesKey("pending")

/** 缓存队列的持久化（**只存待处理项**：已完成/失败的没必要跨进程记着）。 */
class CacheQueueStore(private val context: Context) {

    private val store = context.cacheQueueDataStore

    /** 读一次待处理项（失败返回空，绝不因为它把启动搞挂）。 */
    suspend fun load(): List<Pair<String, String>> = runCatching {
        CacheQueueCodec.decode(store.data.first()[PENDING].orEmpty())
    }.getOrElse { emptyList() }

    /** 覆盖保存（失败只记日志：丢的是"续传机会"，不该影响正在跑的下载）。 */
    suspend fun save(entries: List<Pair<String, String>>) {
        runCatching { store.edit { it[PENDING] = CacheQueueCodec.encode(entries) } }
    }
}

/** DataStore 实例（按文件名复用；放在这里是为了让 [CacheQueueStore] 干净）。 */
private val Context.cacheQueueDataStore by preferencesDataStore(name = "cache_queue")
