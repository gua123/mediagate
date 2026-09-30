package io.github.gua123.mediagate.media.tsext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * TS 索引缓存的 key（R3/R4，plan 4.3 第 1 条与第 7 章 `ts_index` 表口径一致）：
 * `(backendId, path, size, mtime)`。
 *
 * 只要 size 或 mtime 变了（文件被覆盖/重录），key 就变——旧索引自然失效，不会拿错表去 seek。
 */
data class TsIndexKey(
    val backendId: String,
    val path: String,
    val size: Long,
    val mtime: Long,
) {

    /** 稳定哈希（SHA-256 前 16 字节的十六进制），用作缓存文件名。 */
    val hash: String get() = hashOf(backendId, path, size, mtime)

    companion object {

        /** 从目录项构造（size/mtime 未知时传 -1/0，同样是一个合法 key）。 */
        fun of(backendId: String, entry: RemoteEntry): TsIndexKey =
            TsIndexKey(backendId, entry.path, entry.size, entry.mtime)

        /** 纯函数：把四元组映射成 32 位十六进制字符串（与缩略图 key 同一套做法）。 */
        fun hashOf(backendId: String, path: String, size: Long, mtime: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(backendId.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(mtime.toString().toByteArray(Charsets.UTF_8))
            val bytes = digest.digest()
            val builder = StringBuilder(32)
            for (index in 0 until 16) {
                builder.append(HEX[(bytes[index].toInt() ushr 4) and 0x0F])
                builder.append(HEX[bytes[index].toInt() and 0x0F])
            }
            return builder.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * TS 索引的落盘缓存（R3/R4，plan 4.3 第 1 条「index=key(backendId,path,size,mtime)，命中即秒回」）。
 *
 * 形态：`rootDir/<keyHash>.tsidx`，文件内容 = 小清单（key 四元组）+ [TsIndexCodec] 的二进制索引。
 * 加载时**复核清单里的 key**：哈希碰撞、文件被别人覆盖、mtime 变了，都按「未命中」处理并顺手删掉旧文件。
 *
 * 容量与 LRU：超过 [capacity] 个文件时，按**文件最后修改时间**淘汰最旧的（load 命中会 touch，
 * 所以是「最近使用」而不是「最近写入」）。用文件 mtime 而不是内存 LRU 表，是为了跨进程/重启仍然有效。
 *
 * 线程与调度：所有方法都是挂起函数，内部切 [io]；同名 key 并发写会各自写临时文件再改名，结果一致。
 */
class TsIndexStore(
    private val rootDir: File,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        require(capacity > 0) { "capacity 必须为正：$capacity" }
    }

    /** 纯函数：某个 key 对应的缓存文件（不创建目录、不访问磁盘）。 */
    fun fileFor(key: TsIndexKey): File = File(rootDir, key.hash + FILE_SUFFIX)

    /**
     * 读取缓存。
     *
     * @return null 表示未命中 / 文件损坏 / key 已变（大小或 mtime 变化）——调用方重新扫描即可。
     */
    suspend fun load(key: TsIndexKey): TsIndex? = withContext(io) {
        val file = fileFor(key)
        if (!file.isFile) return@withContext null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext null
        }
        val decoded = decodeFile(bytes)
        if (decoded == null) {
            file.delete()
            return@withContext null
        }
        val (storedKey, index) = decoded
        if (storedKey != key) {
            // 哈希碰撞或被替换：按失效处理，绝不返回错表
            file.delete()
            return@withContext null
        }
        file.setLastModified(System.currentTimeMillis())
        index
    }

    /** 写入缓存（覆盖同名 key）；返回落盘文件。 */
    suspend fun save(key: TsIndexKey, index: TsIndex): File = withContext(io) {
        ensureRoot()
        val target = fileFor(key)
        val bytes = encodeFile(key, index)
        val temporary = File(rootDir, target.name + TEMP_SUFFIX)
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            // 某些文件系统不允许覆盖式 rename：退化为直接写
            target.writeBytes(bytes)
            temporary.delete()
        }
        prune()
        target
    }

    /** 删除某个 key 的缓存；返回是否真的删掉了。 */
    suspend fun invalidate(key: TsIndexKey): Boolean = withContext(io) {
        fileFor(key).delete()
    }

    /** 清空全部缓存；返回删除的文件数。 */
    suspend fun clear(): Int = withContext(io) {
        var removed = 0
        for (file in indexFiles()) {
            if (file.delete()) removed++
        }
        removed
    }

    /** 当前缓存文件数（超过 [capacity] 时会在下次 save 时淘汰）。 */
    suspend fun count(): Int = withContext(io) { indexFiles().size }

    /** 已缓存的所有 key（诊断页展示用；读清单，不解析点数组）。 */
    suspend fun cachedKeys(): List<TsIndexKey> = withContext(io) {
        indexFiles().mapNotNull { file ->
            try {
                decodeFile(file.readBytes())?.first
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
    }

    private fun ensureRoot() {
        if (!rootDir.isDirectory && !rootDir.mkdirs()) {
            throw IOException("无法创建 TS 索引缓存目录：${rootDir.absolutePath}")
        }
    }

    private fun indexFiles(): List<File> =
        rootDir.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }?.toList() ?: emptyList()

    /** 超出容量时按最后修改时间淘汰最旧的。 */
    private fun prune() {
        val files = indexFiles()
        if (files.size <= capacity) return
        val victims = files.sortedBy { it.lastModified() }.take(files.size - capacity)
        for (victim in victims) victim.delete()
    }

    private fun encodeFile(key: TsIndexKey, index: TsIndex): ByteArray {
        val blob = TsIndexCodec.encode(index)
        val out = ByteArrayOutputStream(blob.size + MANIFEST_BYTES)
        DataOutputStream(out).use { stream ->
            stream.writeInt(FILE_MAGIC)
            stream.writeByte(FILE_VERSION)
            stream.writeUTF(key.backendId)
            stream.writeUTF(key.path)
            stream.writeLong(key.size)
            stream.writeLong(key.mtime)
            stream.writeInt(blob.size)
            stream.write(blob)
        }
        return out.toByteArray()
    }

    /** 解析文件 = 清单 + 索引块；任何异常都返回 null（调用方按失效处理）。 */
    private fun decodeFile(bytes: ByteArray): Pair<TsIndexKey, TsIndex>? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != FILE_MAGIC) {
                null
            } else if (input.readByte().toInt() != FILE_VERSION) {
                null
            } else {
                val backendId = input.readUTF()
                val path = input.readUTF()
                val size = input.readLong()
                val mtime = input.readLong()
                val blobLength = input.readInt()
                if (blobLength <= 0 || blobLength > bytes.size) {
                    null
                } else {
                    val blob = ByteArray(blobLength)
                    input.readFully(blob)
                    val index = TsIndexCodec.decode(blob)
                    if (index == null) null else TsIndexKey(backendId, path, size, mtime) to index
                }
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    companion object {

        /** 缓存文件名后缀。 */
        const val FILE_SUFFIX = ".tsidx"

        /** 写盘临时文件后缀（写完改名，避免读到半截文件）。 */
        const val TEMP_SUFFIX = ".tmp"

        /** 文件 magic："TSIX"。 */
        const val FILE_MAGIC = 0x54534958

        const val FILE_VERSION = 1

        /** 默认容量：64 个索引文件（每个几十 KB ~ 几百 KB，plan 4.10 的缓存预算内）。 */
        const val DEFAULT_CAPACITY = 64

        /** 清单部分的估算长度（String 长度前缀 + 两个 long + 两个 int）。 */
        private const val MANIFEST_BYTES = 4 + 1 + 64 + 256 + 8 + 8 + 4
    }
}
