package io.github.gua123.mediagate.feature.viewer.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.RemoteEntry

/** 假的解码结果（真机是 [BitmapDecodedImage]，JVM 单测里造不出 Bitmap）。 */
internal class FakeDecodedImage(
    override val width: Int = 1080,
    override val height: Int = 1920,
) : DecodedImage

/** 假解码器：记录每次请求的尺寸，可注入失败。 */
internal class FakeImageViewerDecoder(
    private val image: DecodedImage? = FakeDecodedImage(),
) : ImageViewerDecoder {

    /** 每次 decode 的 (requestedWidth, requestedHeight)，按调用顺序。 */
    val requests = mutableListOf<Pair<Int, Int>>()

    /** decode 被调用的总次数。 */
    val calls: Int get() = requests.size

    override suspend fun decode(bytes: ByteArray, requestedWidth: Int, requestedHeight: Int): DecodedImage? {
        requests += requestedWidth to requestedHeight
        return image
    }
}

/**
 * 假环境：按目录给出预设条目，记录 [open] 的调用，可分别给「列目录失败」与「读文件失败」注入异常。
 *
 * 与真机实现同口径：只返回图片目录项，并按名称排序（[ViewerMath.imageEntries] 会再兜一层）。
 */
internal class FakeImageViewerEnvironment(
    var entries: Map<String, List<RemoteEntry>> = emptyMap(),
) : ImageViewerEnvironment {

    /** open 被调用的路径，按顺序（验证「缓存命中 / 预取不重复读远端」）。 */
    val opened = mutableListOf<String>()

    /** 列目录失败注入：key 是目录路径。 */
    val failSiblings = mutableMapOf<String, Throwable>()

    /** 读文件失败注入：key 是文件路径。 */
    val failOpen = mutableMapOf<String, Throwable>()

    override val rootLabel: StateFlow<String> = MutableStateFlow("内部存储")

    override suspend fun siblings(path: String): List<RemoteEntry> {
        val dir = path.substringBeforeLast('/', "")
        failSiblings[dir]?.let { throw it }
        return entries[dir].orEmpty()
    }

    override suspend fun open(path: String): ByteArray {
        opened += path
        failOpen[path]?.let { throw it }
        return ByteArray(64) { 1 }
    }
}

/** 造一个图片目录项（size 参与「过大就不读」的判断）。 */
internal fun photoEntry(path: String, size: Long = 1024L): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = size,
    mtime = 0L,
)

/** 造一个非图片目录项（用于验证查看器只翻图片）。 */
internal fun videoEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 1024L,
    mtime = 0L,
)
