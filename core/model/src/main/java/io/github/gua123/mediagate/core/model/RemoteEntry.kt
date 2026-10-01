package io.github.gua123.mediagate.core.model

/**
 * 统一的目录项表示（R2 四协议 / R4 拖拽 / R8 连接与自测）。
 *
 * 本地（File / SAF）、WebDAV、SFTP、FTP 四个后端列目录时都产出本类型；上层浏览器、
 * 缩略图与播放逻辑只认它，不感知具体协议。字段取值约定：
 * - [size]：文件字节数；目录或未知时用 -1（不要用 0 冒充「未知」）。
 * - [mtime]：最后修改时间（Unix 毫秒）；未知时用 0。
 * - [etag]：仅支持 ETag/版本号的协议（如 WebDAV）会填；用于判断远端内容是否变化、
 *   失效缩略图与 TS 索引缓存（见 plan 4.2 的缓存 key）。
 * - [mimeType]：协议或系统能给出时才填，可为 null。
 *
 * 本类是不可变 data class，可安全跨线程/跨协程传递。
 */
data class RemoteEntry(
    /** 文件名（不含路径），用于展示与 [MediaKindGuesser] 判定媒体类型。 */
    val name: String,
    /** 后端内的完整路径（POSIX 风格、以 / 分隔，含义由各后端自行解释）。 */
    val path: String,
    /** 是否为目录（R2 列目录需要区分，用于目录优先排序与进入下一级）。 */
    val isDirectory: Boolean = false,
    /** 文件大小（字节）；目录或未知为 -1。 */
    val size: Long = -1L,
    /** 最后修改时间（Unix 毫秒）；未知为 0。 */
    val mtime: Long = 0L,
    /** 内容版本标识（ETag 等）；协议不支持时为 null。 */
    val etag: String? = null,
    /** MIME 类型；未知为 null。 */
    val mimeType: String? = null,
)
