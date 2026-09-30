package io.github.gua123.mediagate.feature.viewer.image

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.IOException

/**
 * 图片查看器需要的宿主能力（**M1-F**，R1「图片可缩放翻页」）。
 *
 * 为什么要有这层接口：`:feature:viewer-image` 不能反向依赖 `:app`，页面也不知道根目录从哪来。
 * 照抄 `:feature:browser` 的注入范式——`:app` 的 AppContainer 实现本接口，在导航宿主里用
 * [LocalImageViewerEnvironment] 提供一次（手写 DI，不引 Hilt）。
 *
 * 线程约定：两个挂起函数**内部自己切 `Dispatchers.IO`**，调用方（ViewModel）也会再包一层
 * `withContext(io)`；实现不得在调用方线程上做阻塞 IO。
 *
 * 失败约定：统一抛 [io.github.gua123.mediagate.data.storage.api.StorageException] 的语义子类
 * （无权限 / 不存在 / 不支持 / 网络 / 认证），页面按分类给中文原因（[ViewerErrorKind]）。
 */
interface ImageViewerEnvironment {

    /**
     * 列出 [path] 所在目录里的**图片**（同目录兄弟），由实现按名称排序。
     *
     * 只返回图片：查看器左右翻页只翻图片，混进视频/音频会让「第 i / n 张」失去意义。
     * 目录为空或 [path] 是根目录下的文件时，返回包含它自己的单元素列表即可。
     *
     * @param path 当前图片的路径（相对根目录的 POSIX 路径，与 StorageBackend 口径一致）。
     * @throws io.github.gua123.mediagate.data.storage.api.StorageException 列目录失败（无权限 / 不存在…）。
     */
    suspend fun siblings(path: String): List<RemoteEntry>

    /**
     * 读取 [path] 的**原始字节**（M1-F 选择 ByteArray 的理由见下）。
     *
     * 为什么不是 RandomAccessSource / Bitmap：
     * - `BitmapFactory` 需要可重复读的完整字节（先 inJustDecodeBounds 再真正解码，两次扫描），
     *   而 [io.github.gua123.mediagate.data.storage.api.RandomAccessSource] 的挂起读没法直接喂给它；
     * - 解码必须发生在 ViewModel 的 IO 协程里（组合函数零 IO），不能返回已解码的 Bitmap；
     * - 单张图片的体积用 [MAX_VIEWER_IMAGE_BYTES] 兜住，超限由实现抛异常、页面提示「图片过大」。
     *
     * @return 图片原始字节（非空）。
     * @throws io.github.gua123.mediagate.data.storage.api.StorageException 读取失败（无权限 / 不存在 / 网络…）。
     * @throws ImageTooLargeException 字节数超过 [MAX_VIEWER_IMAGE_BYTES]（读到一半就中止，不占内存）。
     */
    suspend fun open(path: String): ByteArray

    /** 当前根目录的展示名（面包屑/顶栏用）；尚未选择根目录时为空串。 */
    val rootLabel: StateFlow<String>
}

/**
 * 单张图片允许读进内存的字节上限（**M1-F**）：超过就不读，直接提示「图片过大」。
 *
 * 64 MB 与浏览页图片直读的口径一致；正常手机照片（含 8000×6000 的大图）都在 20 MB 以内。
 */
const val MAX_VIEWER_IMAGE_BYTES: Long = 64L * 1024 * 1024

/**
 * 图片体积超过 [MAX_VIEWER_IMAGE_BYTES]（**M1-F**）。
 *
 * 由 [ImageViewerEnvironment.open] 的实现抛出（目录项没给出大小时只能边读边判断），
 * 页面据此给「图片过大」而不是笼统的「加载失败」（见 [ViewerErrors.classify]）。
 *
 * @param sizeBytes 已经读到的字节数；未知为 -1。
 */
class ImageTooLargeException(val sizeBytes: Long = -1L) :
    IOException("图片过大：超过 " + MAX_VIEWER_IMAGE_BYTES / (1024 * 1024) + " MB")

/**
 * 由 `:app` 在导航宿主处提供的查看器依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 `MediaGateApp()` 里 `CompositionLocalProvider(LocalImageViewerEnvironment provides container)`。
 */
val LocalImageViewerEnvironment = staticCompositionLocalOf<ImageViewerEnvironment> {
    error("LocalImageViewerEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（M1-F）")
}
