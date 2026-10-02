package io.github.gua123.mediagate.media.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException

/**
 * Media3 数据源：把 DataSpec 的字节区间映射到 [StorageBackend.openRead]（R4 拖拽 seek 的核心件）。
 *
 * 数据流向：Media3 抽取器 → 本类 → [StorageBackend] → [RangeStream]。
 * 因为四协议后端都实现了随机读，Media3 的每一次 seek 都会**重新 open 一个新的区间**——
 * 这是正常路径而不是异常路径，所以 [open] 允许被反复调用：每次先把上一段流关掉再开新的。
 *
 * 区间语义（与 [StorageBackend.openRead] 严格对齐）：
 * - [DataSpec.position] → offset（文件绝对偏移，从 0 开始）；
 * - [DataSpec.length] → length（C.LENGTH_UNSET = -1 表示「读到文件末尾」）；
 * - [open] 的返回值 = 本段可读字节数（协议给不出长度时返回 C.LENGTH_UNSET）；
 * - [read] 返回 -1 表示本段读完（EOF），与 Media3 约定一致。
 *
 * 错误口径：后端的 [StorageException] 语义子类统一包成 [DataSourceException]，
 * reason 用 [PlaybackException] 的 IO 错误码，Media3 的错误上报与「换引擎重试」都靠它。映射见 [toDataSourceException]。
 *
 * 线程模型：Media3 的 [read] 是阻塞 API，而数据层是挂起 API，这里用 runBlocking 桥接。
 * 调用方是 Media3 自己的加载线程（不是协程），且后端内部会切到 Dispatchers.IO，因此不会死锁。
 * 一个实例同一时刻只被一条加载线程使用（Media3 的约定），本类不做额外加锁。
 */
@UnstableApi
class BackendDataSource(private val backend: StorageBackend) : BaseDataSource(/* isNetwork = */ false) {

    /** 当前打开的区间流（null = 未打开或已关闭）。 */
    private var stream: RangeStream? = null

    /** 当前区间的路径（诊断用）。 */
    var path: String? = null
        private set

    /** 当前区间的伪 URI 字符串（[getUri] 用）。 */
    private var uriString: String? = null

    /** 是否已经 notify 过 transferStarted（保证 transferEnded 成对）。 */
    private var started = false

    /**
     * 本段还可读的字节数；长度未知时为 [C.LENGTH_UNSET]。
     *
     * 语义与 Media3 的 DataSource 一致：读完（或读到 -1）后为 0。
     */
    var bytesRemaining: Long = UNKNOWN_LENGTH
        private set

    /**
     * Media3 入口：把 [DataSpec] 映射成「路径 + 区间」。
     *
     * 路径来自 [DataSpec.getUri] 的伪 URI（见 [MediaUri]），因此同一个实例可以服务任意后端条目。
     *
     * @throws DataSourceException URI 不是 mediagate 伪 URI（ERROR_CODE_IO_UNSPECIFIED）、
     *   position/length 非法（[DataSourceException.POSITION_OUT_OF_RANGE]）、
     *   或后端打开失败（见 [toDataSourceException]）。
     */
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri.toString()
        val parsed = MediaUri.fromUri(uri) ?: throw DataSourceException(
            "无法识别的媒体地址（期望 " + MediaUri.SCHEME + "://<backendId>/<path>）：" + uri,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        val resolved = openRange(
            position = dataSpec.position,
            length = dataSpec.length,
            path = parsed.path,
            uri = uri,
        )
        transferStarted(dataSpec)
        started = true
        return resolved
    }

    /**
     * [open] 的核心：按字节区间打开后端流（**JVM 可直接调用**）。
     *
     * 为什么单独开这个入口：Media3 的 [DataSpec] 字段类型是 android.net.Uri，
     * 纯 JVM 单测里造不出可用的 Uri 实例（android.jar 是桩），业务逻辑不该因此不可测；
     * 于是把「区间 → openRead」这段真正的映射单独暴露，[open] 只是它的一层薄适配。
     * 将来的回环 HTTP 代理（给 LibVLC / 外部播放器）也能直接用它按 Range 取流。
     *
     * 可以被多次调用：每次先关闭上一段流，再打开新区间（seek 就是这条路径）。
     *
     * @param position 文件内起始偏移；负数抛 POSITION_OUT_OF_RANGE（2008）。
     * @param length 期望长度；[C.LENGTH_UNSET]（-1）表示读到末尾。
     * @param path 后端内路径。
     * @param uri 该区间的伪 URI 字符串；给了就让 [getUri] 返回它（可空）。
     * @return 本段可读字节数；未知返回 [C.LENGTH_UNSET]。
     */
    fun openRange(position: Long, length: Long, path: String, uri: String? = null): Long {
        // POSITION_OUT_OF_RANGE == DataSourceException.POSITION_OUT_OF_RANGE（同为 2008），
        // 但前者没被标记 @Deprecated，Media3 自己的抽取器也用这个常量。
        if (position < 0) {
            throw DataSourceException(
                "起始位置不能为负：position=" + position,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            )
        }
        if (length < UNKNOWN_LENGTH) {
            throw DataSourceException(
                "读取长度非法：length=" + length,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            )
        }
        releaseStream()
        val opened = try {
            runBlocking { backend.openRead(path, position, length) }
        } catch (e: StorageException) {
            throw e.toDataSourceException()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } catch (e: RuntimeException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        stream = opened
        this.path = path
        uriString = uri
        bytesRemaining = if (opened.length < 0) UNKNOWN_LENGTH else opened.length
        return bytesRemaining
    }

    /**
     * 读取一段字节。
     *
     * @return 实际读到的字节数；本段读完返回 -1（EOF）；未被 [open] 就调用抛 [DataSourceException]。
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val current = stream ?: throw DataSourceException(
            "数据源尚未打开（open 之前不能 read）",
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        // 已知长度时绝不越过本段末尾：多读会被后端当成越界，少读则 Media3 会以为是 EOF
        val toRead = if (bytesRemaining == UNKNOWN_LENGTH) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }
        val read = try {
            runBlocking { current.read(buffer, offset, toRead) }
        } catch (e: StorageException) {
            throw e.toDataSourceException()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } catch (e: RuntimeException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        when {
            read > 0 -> {
                bytesTransferred(read)
                if (bytesRemaining != UNKNOWN_LENGTH) bytesRemaining -= read
            }
            // 后端提前给 EOF：把剩余量归零，后续 read 直接返回 -1，不再打远端
            read < 0 -> bytesRemaining = 0
        }
        return read
    }

    /**
     * 当前区间的地址；没打开过时返回 [Uri.EMPTY]。
     *
     * 返回伪 URI（mediagate://<backendId>/<path>）而不是后端真实地址：日志、通知栏与
     * 「分享播放地址」都不该泄漏本地绝对路径，同时保留可定位性。
     */
    override fun getUri(): Uri = uriString?.let { Uri.parse(it) } ?: Uri.EMPTY

    /** 关闭当前区间（可重复调用）；成对地通知 transferEnded。 */
    override fun close() {
        releaseStream()
        if (started) {
            started = false
            transferEnded()
        }
    }

    /** 关掉底层流并复位剩余量；关闭失败只吞掉异常（不覆盖真正的读取错误）。 */
    private fun releaseStream() {
        val current = stream ?: return
        stream = null
        bytesRemaining = UNKNOWN_LENGTH
        try {
            current.close()
        } catch (e: IOException) {
            // 关闭失败不影响上层：区间已经不可读，下一次 open 会重新建流
        }
    }

    /**
     * 后端异常 → Media3 异常（R2/R8 的错误分类到这里收口）。
     *
     * 映射口径（reason 用 [PlaybackException] 的 IO 错误码，Media3 的状态机与错误上报认它）：
     * - [StorageException.NotFound] → ERROR_CODE_IO_FILE_NOT_FOUND（文件被删/改名）
     * - [StorageException.AccessDenied] → ERROR_CODE_IO_NO_PERMISSION（SAF 授权失效、无读权限）
     * - [StorageException.Network] → ERROR_CODE_IO_NETWORK_CONNECTION_FAILED（远端连不上/超时）
     * - [StorageException.Auth] → ERROR_CODE_IO_BAD_HTTP_STATUS（账号密码/密钥被拒，按协议握手失败归类）
     * - [StorageException.NotSupported] / [StorageException.Unknown] → ERROR_CODE_IO_UNSPECIFIED
     *
     * 越界（position 为负、length < -1）不走这里，直接抛 [DataSourceException.POSITION_OUT_OF_RANGE]：
     * 那是**调用方**的问题，与后端无关。
     */
    private fun StorageException.toDataSourceException(): DataSourceException = when (this) {
        is StorageException.NotFound ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        is StorageException.AccessDenied ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)

        is StorageException.Network ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        // 超时（2026-10-03 加的 TimeoutStorageBackend）：归到"网络连接失败"一类，
        // Media3 会走它自己的重试/错误上报，界面显示中文原因而不是一直转圈。
        is StorageException.Timeout ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)

        is StorageException.Auth ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        is StorageException.NotSupported ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        is StorageException.Unknown ->
            DataSourceException(this, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    private companion object {

        /** 长度未知，与 [C.LENGTH_UNSET] 同值（-1）。 */
        val UNKNOWN_LENGTH: Long = C.LENGTH_UNSET.toLong()
    }
}
