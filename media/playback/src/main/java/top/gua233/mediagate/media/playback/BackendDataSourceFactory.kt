package io.github.gua123.mediagate.media.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import io.github.gua123.mediagate.data.storage.api.StorageBackend

/**
 * [BackendDataSource] 的工厂（Media3 的 DataSource.Factory 约定：每次 open 都要一个新实例）。
 *
 * 两副面孔：
 * - [BackendDataSourceFactory] 构造：固定后端（浏览页/播放页已经知道用哪个后端时直接用）；
 * - 主构造：每次 [createDataSource] 现取后端（后台播放服务用——服务比「当前根目录」活得久，
 *   用户换根目录后不该要求重启服务）。
 *
 * @param backendProvider 返回当前后端；返回 null 表示尚未选择根目录（R12），
 *   此时给出一个「打开必失败」的数据源，让 Media3 走正常的错误上报，而不是在工厂里抛异常。
 */
@UnstableApi
class BackendDataSourceFactory(
    private val backendProvider: () -> StorageBackend?,
) : DataSource.Factory {

    /** 固定后端的便捷构造。 */
    constructor(backend: StorageBackend) : this({ backend })

    override fun createDataSource(): DataSource {
        val backend = backendProvider() ?: return MissingBackendDataSource()
        return BackendDataSource(backend)
    }
}

/**
 * 「还没有后端」的占位数据源（R12：未选择根目录）。
 *
 * 只在 [BackendDataSourceFactory] 拿不到后端时出现：open 立刻抛带中文说明的
 * [DataSourceException]，Media3 会把它当成播放错误上报（页面提示「请先选择媒体根目录」），
 * 而不是让服务在后台崩掉。
 */
@UnstableApi
internal class MissingBackendDataSource : BaseDataSource(/* isNetwork = */ false) {

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = throw DataSourceException(
        "尚未选择媒体根目录（R12），无法读取：" + dataSpec.uri,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw DataSourceException(
        "尚未选择媒体根目录（R12）",
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    override fun getUri(): Uri = Uri.EMPTY

    override fun close() = Unit
}
