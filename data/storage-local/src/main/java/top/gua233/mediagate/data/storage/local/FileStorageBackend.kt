package io.github.gua123.mediagate.data.storage.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * 本地「File 模式」存储后端（R2 本地协议 / R12 双模式之一）。
 *
 * 基于 [File] + [RandomAccessFile]：指针 seek 精确到位，是拖拽 seek（R4）最快的一条路径；
 * 通过 MANAGE_EXTERNAL_STORAGE 授权后可全盘浏览（R12「所有文件访问」）。
 *
 * 能力：randomAccess = true、writable = true；rangeHeader / resumeByRest 对本地无意义，恒 false。
 *
 * 路径语义：所有方法接收的路径都是**相对 [root] 的树内路径**（POSIX 风格，空串或 / 表示根）。
 * 只有本模块（JVM 单测可直接跑）不依赖任何 Android API。
 *
 * @param root 根目录，通常是 /storage/emulated/0 或某个具体子目录。
 */
class FileStorageBackend(private val root: File) : StorageBackend {

    override val id: String = "local-file:" + root.absolutePath

    override val caps: Caps = Caps(
        randomAccess = true,
        rangeHeader = false,
        resumeByRest = false,
        // 本地文件系统随机读没有会话/连接约束，给足并发（播放 + 抽帧 + 缩略图并行）
        maxParallelReads = 8,
        writable = true,
    )

    @Volatile
    private var closed = false

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureOpen()
        val base = normalizeRelativePath(dir)
        val target = resolve(base)
        if (!target.exists()) throw StorageException.NotFound("目录不存在：$dir")
        if (!target.isDirectory) throw StorageException.NotSupported("不是目录：$dir")
        val children = target.listFiles()
            ?.map { it.toEntry(joinPath(base, it.name)) }
            .orEmpty()
        paginate(children.sortedWith(DIRECTORY_FIRST), page)
    }

    override suspend fun stat(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        ensureOpen()
        val rel = normalizeRelativePath(path)
        val target = resolve(rel)
        if (!target.exists()) throw StorageException.NotFound("路径不存在：$path")
        target.toEntry(rel)
    }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = withContext(Dispatchers.IO) {
        ensureOpen()
        if (offset < 0) throw StorageException.Unknown("offset 不能为负：$offset")
        val target = resolve(normalizeRelativePath(path))
        if (!target.exists()) throw StorageException.NotFound("文件不存在：$path")
        if (target.isDirectory) throw StorageException.NotSupported("目录不能读取：$path")
        if (!target.canRead()) throw StorageException.AccessDenied("无读权限：$path")
        val raf = RandomAccessFile(target, "r")
        try {
            val total = raf.length()
            val streamLength = effectiveLength(total, offset, length)
            // seek 到末尾之后是合法操作，read 会返回 -1（越界读不抛异常）
            raf.seek(offset)
            FileRangeStream(raf, start = offset, length = streamLength)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    override suspend fun write(path: String, data: InputStream): Unit = withContext(Dispatchers.IO) {
        ensureOpen()
        val rel = normalizeRelativePath(path)
        val target = resolve(rel)
        if (target.isDirectory) throw StorageException.NotSupported("目标是目录，不能覆盖写：$path")
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) {
            throw StorageException.NotFound("父目录不存在：" + (parent?.path ?: rel))
        }
        try {
            // FileOutputStream 默认截断，天然是「覆盖写」语义（R14 字幕写回）
            FileOutputStream(target).use { out -> data.copyTo(out) }
        } catch (e: FileNotFoundException) {
            throw StorageException.AccessDenied("无法写入：$path", e)
        } catch (e: IOException) {
            throw StorageException.Unknown("写入失败：$path", e)
        }
    }

    /** 本地后端没有 DNS / TCP / 协议握手三段，恒 ok 且耗时计 0（plan 4.5）。 */
    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() {
        closed = true
    }

    /** 把树内相对路径解析成 [File]；越界由 [normalizeRelativePath] 拦截。 */
    private fun resolve(relative: String): File = if (relative.isEmpty()) root else File(root, relative)

    private fun File.toEntry(path: String): RemoteEntry = RemoteEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = if (isDirectory) -1L else length(),
        mtime = lastModified(),
        // 本地没有 ETag；缓存失效靠 (path, size, mtime) 三元组（plan 4.2 / 第 7 章）
        etag = null,
        mimeType = null,
    )

    private fun ensureOpen() {
        if (closed) throw StorageException.Unknown("后端已关闭：$id")
    }
}

/**
 * [FileStorageBackend.openRead] 返回的随机读流。
 *
 * [position] / [seek] 都以**本流起点**为 0（即 openRead 传入的 offset 处），不暴露文件绝对偏移，
 * 这样上层做 Range 拆分时不必关心起点。
 */
private class FileRangeStream(
    private val file: RandomAccessFile,
    private val start: Long,
    override val length: Long,
) : RangeStream {

    @Volatile
    private var pos: Long = start

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = withContext(Dispatchers.IO) {
        if (len == 0) return@withContext 0
        val remaining = if (length < 0) Long.MAX_VALUE else length - (pos - start)
        if (remaining <= 0) return@withContext -1
        val n = file.read(buf, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) pos += n
        n
    }

    override suspend fun seek(position: Long) = withContext(Dispatchers.IO) {
        val target = if (length < 0) position.coerceAtLeast(0) else position.coerceIn(0, length)
        file.seek(start + target)
        pos = start + target
    }

    override fun position(): Long = pos - start

    override fun close() {
        file.close()
    }
}
