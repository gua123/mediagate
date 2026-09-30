package io.github.gua123.mediagate.media.asr

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 模型文件的落盘抽象（**M7-B / R14**：下载、断点续传、删除、导入都要一个可替换的文件层）。
 *
 * 之所以是接口而不是直接用 java.io.File：单测要能模拟「写到一半磁盘满」「续传文件长度为 0」
 * 这些真实文件系统不好造的场景；真机实现见 [FileModelStore]，它同时也是 JVM 单测里
 * 用临时目录跑**真实文件 IO** 的那一份（java.io.File 在 JVM 上本来就是通的）。
 */
interface ModelStore {

    /** 模型目录（真机上是 filesDir/models；纯内存实现可以是 null）。 */
    val directory: File?

    /** 目录下的文件名列表（不含子目录）。 */
    fun names(): List<String>

    /** 文件是否存在且是普通文件。 */
    fun exists(name: String): Boolean

    /** 文件字节数；不存在返回 -1。 */
    fun size(name: String): Long

    /** 删除（幂等）；返回是否真的删掉了。 */
    fun delete(name: String): Boolean

    /** 改名（覆盖同名目标）；返回是否成功。 */
    fun rename(from: String, to: String): Boolean

    /** 打开写入流；append = true 时接着已有内容写（断点续传）。 */
    fun openWrite(name: String, append: Boolean): OutputStream

    /** 打开读取流。 */
    fun openRead(name: String): InputStream

    /** 计算 SHA-256（小写十六进制）；不存在或读失败返回 null。 */
    fun sha256(name: String): String?

    /** 绝对路径；纯内存实现返回 null。 */
    fun pathOf(name: String): String?

    companion object {

        /** 校验和分块大小（256 KiB，跟缩略图那边同一个量级）。 */
        const val DEFAULT_BUFFER_BYTES = 256 * 1024

        /** 分块计算校验和（两个实现共用；**只做 IO，没有别的副作用**）。 */
        fun digestOf(stream: InputStream, algorithm: String = "SHA-256"): String {
            val digest = MessageDigest.getInstance(algorithm)
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

/**
 * 基于 [java.io.File] 的模型存储（**真机用这一份**，JVM 单测用临时目录也是它）。
 *
 * 目录不存在会自动建；所有方法对不存在的文件都是安全的（返回 -1 / false / null），
 * 不抛异常——下载流程里到处是「文件可能还没建出来」的时刻。
 */
class FileModelStore(override val directory: File) : ModelStore {

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    private fun fileOf(name: String): File = File(directory, name)

    override fun names(): List<String> =
        directory.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    override fun exists(name: String): Boolean = fileOf(name).isFile

    override fun size(name: String): Long = fileOf(name).let { if (it.isFile) it.length() else -1L }

    override fun delete(name: String): Boolean = fileOf(name).let { if (!it.exists()) false else it.delete() }

    override fun rename(from: String, to: String): Boolean {
        val source = fileOf(from)
        if (!source.isFile) return false
        val target = fileOf(to)
        if (target.exists()) target.delete()
        return source.renameTo(target)
    }

    override fun openWrite(name: String, append: Boolean): OutputStream {
        if (!directory.exists()) directory.mkdirs()
        return FileOutputStream(fileOf(name), append)
    }

    override fun openRead(name: String): InputStream = FileInputStream(fileOf(name))

    override fun sha256(name: String): String? {
        val file = fileOf(name)
        if (!file.isFile) return null
        return runCatching { ModelStore.digestOf(FileInputStream(file)) }.getOrNull()
    }

    override fun pathOf(name: String): String = fileOf(name).absolutePath
}
