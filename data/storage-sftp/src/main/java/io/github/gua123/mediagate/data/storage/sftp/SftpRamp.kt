package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * **并行建连**（2026-11-20「多连接加速」的第二步）。
 *
 * 2026-11-18 开发机实测：20 条 SSH 会话**串行**建立要 18 秒（每条约 0.9 s），
 * 用户看到的就是「点了播放先等 18 秒才开始」——建连开销直接吃掉多连接带来的收益。
 * 办法很土但有效：一批 `async` 同时握手，20 条 ≈ 5 批 × 一次握手的时间。
 *
 * 为什么单独抽出来：它是纯调度逻辑（不碰 JSch / 网络），可以用**假建连**计时单测
 * 证明"确实同时建了 N 条"，不需要真服务器；也避免把并发扩容写死在池子的锁里。
 *
 * 口径：
 * - 每批最多 [parallel] 条**同时**创建；
 * - 单条失败不连累同批其它条（返回 false，由调用方决定要不要重试/摘掉）；
 * - 协程取消照常向上传播（不把 CancellationException 吞成 false）。
 */
internal object SftpRamp {

    /**
     * 并行创建 [count] 条连接。
     *
     * @param count 要创建几条。
     * @param parallel 同时最多几条（<= 0 视为 1，防止一次把服务器打爆）。
     * @param createOne 真正建一条；返回 false 表示这条没建成（异常也按没建成处理）。
     * @return 建成的条数（0..count）。
     */
    suspend fun warmUp(count: Int, parallel: Int, createOne: suspend (Int) -> Boolean): Int {
        if (count <= 0) return 0
        val limit = parallel.coerceAtLeast(1)
        var created = 0
        var index = 0
        while (index < count) {
            val size = minOf(limit, count - index)
            val batch = (index until index + size).toList()
            index += size
            val results = coroutineScope {
                batch.map { item ->
                    async {
                        try {
                            createOne(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            false
                        }
                    }
                }.awaitAll()
            }
            created += results.count { it }
        }
        return created
    }
}
