package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.Closeable
import java.io.InputStream

/**
 * 存储后端统一抽象（R2 本地 / WebDAV / SFTP / FTP，plan 4.1）。
 *
 * 四协议各自实现本接口，向上只暴露「列目录 / stat / 打开一段字节流 / 写回 / 连通性测试」
 * 五个动作；协议差异（Range、REST、通道池、SAF 授权）全部关在实现里。
 * 路径统一用 POSIX 风格的字符串，各后端自行解释为绝对路径或 SAF 树内相对路径。
 *
 * 生命周期：probe → list/stat → openRead（可多个）→ [close]。
 * 实现须保证挂起函数不阻塞调用方线程（内部切 Dispatchers.IO）。
 */
interface StorageBackend : Closeable {

    /**
     * 后端唯一标识，例如 local-file:/storage/emulated/0。
     *
     * 用作缓存 key 的一部分（TS 索引用 (backendId, path, size, mtime)、缩略图索引、
     * 目录缓存，见 plan 第 7 章），因此同一物理位置必须给出稳定且互不相同的 id。
     */
    val id: String

    /** 后端能力声明（随机读、Range、并行度、可写），上层据此选策略（plan 4.1 降级链）。 */
    val caps: Caps

    /**
     * 列举 [dir] 下的直接子项（**不递归**），目录优先 + 名称排序由实现保证。
     *
     * @param page 分页参数；null 表示一次取全（大目录仍建议分页，plan 4.10）。
     * @throws StorageException.NotFound 目录不存在。
     * @throws StorageException.AccessDenied 无读权限（如 SAF 未授权）。
     */
    suspend fun list(dir: String, page: Page? = null): List<RemoteEntry>

    /**
     * 查询单个路径的元信息（size / mtime / isDirectory），用于播放前的存在性与变更判断。
     *
     * @throws StorageException.NotFound 路径不存在。
     */
    suspend fun stat(path: String): RemoteEntry

    /**
     * 打开 [path] 的一段字节流（R4 拖拽 seek 的入口）。
     *
     * @param offset 起始偏移；0 表示从头。
     * @param length 期望读取的字节数；-1 表示读到文件末尾。
     * @throws StorageException.NotFound 文件不存在。
     * @throws StorageException.NotSupported 目标是目录或不支持随机读。
     */
    suspend fun openRead(path: String, offset: Long = 0, length: Long = -1): RangeStream

    /**
     * 覆盖写入 [path]（R14 字幕写回视频同目录）。
     *
     * 语义：父目录必须已存在（不存在抛 [StorageException.NotFound]），同名文件直接覆盖。
     * 传入的 [data] 由**调用方**负责关闭，实现不关闭它。
     *
     * @throws StorageException.AccessDenied 无写权限（R14：上层据此落本地缓存并提示）。
     */
    suspend fun write(path: String, data: InputStream)

    /**
     * 连通性测试（R8，plan 4.5 三段计时：DNS / TCP / 协议握手）。
     *
     * 本方法不抛异常，失败信息放在 [ProbeReport.message] 里，便于「测试全部」并行跑完。
     */
    suspend fun probe(): ProbeReport
}
