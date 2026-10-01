package io.github.gua123.mediagate.media.tsext

import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource

/**
 * 数据层到 TS 索引的适配（R3/R4，plan 4.3 第 1 条与第 3 章不变量）。
 *
 * `StorageBackend.openRead` 产出的是顺序型 [io.github.gua123.mediagate.data.storage.api.RangeStream]，
 * 索引器要的是可随机读的 [RandomAccessSource]；storage-api 已提供
 * [asRandomAccessSource] 适配器，本函数把「stat 拿长度 + openRead + 适配」三步合成一步，
 * 并且用 `stat` 的 size（比流自己报的长度更可信）作为已知长度。
 *
 * 生命周期：返回的 source 由调用方负责 [RandomAccessSource.close]。
 *
 * @throws StorageException.NotFound 文件不存在。
 * @throws StorageException.NotSupported 目标是目录。
 */
suspend fun StorageBackend.openRandomAccessSource(path: String): RandomAccessSource {
    val entry = stat(path)
    if (entry.isDirectory) {
        throw StorageException.NotSupported("目标是目录，不能作为 TS 源：$path")
    }
    val stream = openRead(path, 0, -1)
    val knownSize = if (entry.size >= 0L) entry.size else stream.length
    return stream.asRandomAccessSource(knownSize)
}
