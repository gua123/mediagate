package io.github.gua123.mediagate.data.storage.local

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * 本地「SAF 模式」存储后端（R2 本地协议 / R12 双模式之一）。
 *
 * 基于 [DocumentFile] + ContentResolver：适用于未开「所有文件访问」、只能通过
 * 系统目录授权（ACTION_OPEN_DOCUMENT_TREE）访问媒体的场景（Android 13+ 的主流路径）。
 *
 * 随机读：ContentResolver 打开 fd 后用 FileInputStream.channel.position(offset) 定位；
 * 部分 provider（云盘、管道型文档）不支持 seek，此时 [openRead] 自动退回顺序跳过，
 * 后退定位则抛 [StorageException.NotSupported]，交由上层走分段缓存降级（plan 4.1）。
 *
 * 写权限：能力里的 writable 与 [write] 都依据 [DocumentFile.canWrite]；
 * 无写权限时抛 [StorageException.AccessDenied]，上层据此把字幕落本地缓存并提示（R14）。
 *
 * 路径语义：相对树根的 POSIX 风格路径（空串或 / 表示树根），例如 Movies/2026/a.ts。
 *
 * @param context 任意 Context（内部只用 contentResolver，不持有 Activity）。
 * @param treeUri ACTION_OPEN_DOCUMENT_TREE 授权得到的树 URI 字符串。
 */
class SafStorageBackend(
    private val context: Context,
    private val treeUri: String,
) : StorageBackend {

    private val rootUri: Uri = Uri.parse(treeUri)

    override val id: String = "local-saf:$treeUri"

    override val caps: Caps
        get() = Caps(
            // 能 seek 的 provider 就是精确随机读；不能 seek 的由 openRead 内部降级
            randomAccess = true,
            rangeHeader = false,
            resumeByRest = false,
            // SAF 每次读都要过 ContentResolver，并发不宜过高
            maxParallelReads = 4,
            writable = runCatching { rootOrNull()?.canWrite() == true }.getOrDefault(false),
        )

    @Volatile
    private var closed = false

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureOpen()
        val base = normalizeRelativePath(dir)
        val target = resolve(base) ?: throw StorageException.NotFound("目录不存在：$dir")
        if (!target.isDirectory) throw StorageException.NotSupported("不是目录：$dir")
        val children = guard("列目录") { target.listFiles() }.mapNotNull { child ->
            val childName = child.name ?: return@mapNotNull null
            child.toEntry(joinPath(base, childName))
        }
        paginate(children.sortedWith(DIRECTORY_FIRST), page)
    }

    override suspend fun stat(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        ensureOpen()
        val rel = normalizeRelativePath(path)
        val target = resolve(rel) ?: throw StorageException.NotFound("路径不存在：$path")
        target.toEntry(rel)
    }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = withContext(Dispatchers.IO) {
        ensureOpen()
        if (offset < 0) throw StorageException.Unknown("offset 不能为负：$offset")
        val target = resolve(normalizeRelativePath(path))
            ?: throw StorageException.NotFound("文件不存在：$path")
        if (target.isDirectory) throw StorageException.NotSupported("目录不能读取：$path")
        val pfd = guard("打开文件") { context.contentResolver.openFileDescriptor(target.uri, "r") }
            ?: throw StorageException.NotFound("无法打开文件：$path")
        val input = FileInputStream(pfd.fileDescriptor)
        try {
            val total = run {
                val statSize = pfd.statSize
                if (statSize >= 0) statSize else target.length().takeIf { it > 0 } ?: -1L
            }
            val seekable = positionAt(input, offset)
            SafRangeStream(
                input = input,
                pfd = pfd,
                seekable = seekable,
                length = effectiveLength(total, offset, length),
            )
        } catch (t: Throwable) {
            input.close()
            pfd.close()
            throw t
        }
    }

    override suspend fun write(path: String, data: InputStream): Unit = withContext(Dispatchers.IO) {
        ensureOpen()
        val rel = normalizeRelativePath(path)
        val parentPath = rel.substringBeforeLast('/', "")
        val fileName = rel.substringAfterLast('/')
        if (fileName.isEmpty()) throw StorageException.NotSupported("不能写入树根：$path")
        val parent = resolve(parentPath) ?: throw StorageException.NotFound("父目录不存在：" + (if (parentPath.isEmpty()) "/" else parentPath))
        if (!parent.isDirectory) throw StorageException.NotFound("父目录不是目录：$parentPath")
        if (!parent.canWrite()) throw StorageException.AccessDenied("无写权限：" + (if (parentPath.isEmpty()) "/" else parentPath))
        val existing = guard("查找文件") { parent.findFile(fileName) }
        val target = when {
            existing == null -> guard("创建文件") { parent.createFile(mimeOf(fileName), fileName) }
                ?: throw StorageException.AccessDenied("无法创建文件：$path")
            existing.isDirectory -> throw StorageException.NotSupported("目标是目录，不能覆盖写：$path")
            !existing.canWrite() -> throw StorageException.AccessDenied("无写权限：$path")
            else -> existing
        }
        try {
            // "wt" = 截断写，保证覆盖语义（R14 字幕重生成时不会残留旧内容）
            val out = context.contentResolver.openOutputStream(target.uri, "wt")
                ?: throw StorageException.AccessDenied("无法打开写入流：$path")
            out.use { data.copyTo(it) }
        } catch (e: FileNotFoundException) {
            throw StorageException.AccessDenied("无法写入：$path", e)
        } catch (e: IOException) {
            throw StorageException.Unknown("写入失败：$path", e)
        }
    }

    /** SAF 是本地目录，没有 DNS / TCP 三段；这里只做「树还在不在、有没有读权限」的自检。 */
    override suspend fun probe(): ProbeReport = withContext(Dispatchers.IO) {
        val root = runCatching { rootOrNull() }.getOrNull()
        when {
            root == null -> ProbeReport(ok = false, message = "无法访问 SAF 目录（未授权或已失效）：$treeUri")
            !root.exists() -> ProbeReport(ok = false, message = "SAF 目录已不存在：$treeUri")
            !root.canRead() -> ProbeReport(ok = false, message = "SAF 目录无读权限：$treeUri")
            else -> ProbeReport(ok = true)
        }
    }

    override fun close() {
        // 每次 openRead 自带独立的 fd，后端本身没有长生命周期资源
        closed = true
    }

    private fun rootOrNull(): DocumentFile? = DocumentFile.fromTreeUri(context, rootUri)

    /** 按树内相对路径逐级查找；任一级不存在返回 null。 */
    private fun resolve(relative: String): DocumentFile? {
        var current: DocumentFile = rootOrNull() ?: return null
        if (relative.isEmpty()) return current
        for (segment in relative.split('/')) {
            current = guard("查找子项") { current.findFile(segment) } ?: return null
        }
        return current
    }

    private fun DocumentFile.toEntry(path: String): RemoteEntry {
        val isDir = isDirectory
        return RemoteEntry(
            name = this.name ?: path.substringAfterLast('/'),
            path = path,
            isDirectory = isDir,
            size = if (isDir) -1L else length(),
            mtime = lastModified(),
            etag = null,
            mimeType = if (isDir) null else type,
        )
    }

    /**
     * 把流定位到 [offset]。
     *
     * @return true = 支持通道定位（后续可任意 seek）；false = 管道型文档，已按顺序跳过 [offset] 字节。
     */
    private fun positionAt(input: FileInputStream, offset: Long): Boolean = try {
        input.channel.position(offset)
        true
    } catch (e: IOException) {
        // 部分 provider 不支持 seek：退回顺序跳过（读到哪算哪，后续只能前进）
        var remaining = offset
        val scratch = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) break
            remaining -= n
        }
        false
    }

    /** 按扩展名猜 MIME（SAF 创建文件必须给类型）；猜不到用通用二进制类型。 */
    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun ensureOpen() {
        if (closed) throw StorageException.Unknown("后端已关闭：$id")
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw StorageException.AccessDenied("SAF 未授权（$what）：$treeUri", e)
    }
}

/**
 * [SafStorageBackend.openRead] 返回的读流。
 *
 * 支持 seek 的 provider 走 FileChannel 精确定位；不支持 seek 的只能顺序前进
 * （后退定位抛 [StorageException.NotSupported]）。
 */
private class SafRangeStream(
    private val input: FileInputStream,
    private val pfd: ParcelFileDescriptor,
    private val seekable: Boolean,
    override val length: Long,
) : RangeStream {

    @Volatile
    private var pos: Long = 0L

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = withContext(Dispatchers.IO) {
        if (len == 0) return@withContext 0
        val remaining = if (length < 0) Long.MAX_VALUE else length - pos
        if (remaining <= 0) return@withContext -1
        val n = input.read(buf, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) pos += n
        n
    }

    override suspend fun seek(position: Long) = withContext(Dispatchers.IO) {
        val target = if (length < 0) position.coerceAtLeast(0) else position.coerceIn(0, length)
        if (seekable) {
            input.channel.position(target)
        } else {
            if (target < pos) {
                throw StorageException.NotSupported("该 SAF 提供方不支持后退定位，请走分段缓存：$target < $pos")
            }
            var remaining = target - pos
            val scratch = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (n < 0) break
                remaining -= n
                pos += n
            }
            return@withContext
        }
        pos = target
    }

    override fun position(): Long = pos

    override fun close() {
        // 先关流再关 pfd；两者指向同一 fd，重复关闭要静默容忍
        runCatching { input.close() }
        runCatching { pfd.close() }
    }
}
