package io.github.gua123.mediagate.feature.viewer.image

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException

/** 查看器状态机（**M1-F**，R1）：加载中 / 已就绪 / 失败。 */
enum class ViewerStatus {

    /** 正在列同目录图片或解码当前图片。 */
    LOADING,

    /** 已解码，可以绘制。 */
    READY,

    /** 失败（分类见 [ViewerErrorKind]）。 */
    ERROR,
}

/**
 * 加载失败的语义分类（**M1-F**，R1「失败提示按分类给中文原因」）。
 *
 * 与 [io.github.gua123.mediagate.data.storage.api.StorageException] 一一对应，
 * 另加两个查看器特有的分类：[TOO_LARGE]（超过体积上限）与 [DECODE_FAILED]（解不出来）。
 */
enum class ViewerErrorKind {

    /** 无读权限（SAF 授权失效等）。 */
    ACCESS_DENIED,

    /** 图片不存在（被删除 / 改名）。 */
    NOT_FOUND,

    /** 后端不支持读取（例如把目录当文件读）。 */
    NOT_SUPPORTED,

    /** 网络不可用（远端后端）。 */
    NETWORK,

    /** 认证失败（远端后端）。 */
    AUTH,

    /** 图片体积超过 [MAX_VIEWER_IMAGE_BYTES]，不读进内存。 */
    TOO_LARGE,

    /** 读到了字节但解不出图片（格式不支持 / 文件损坏）。 */
    DECODE_FAILED,

    /** 其余未分类错误。 */
    UNKNOWN,
}

/**
 * 查看页状态（**M1-F**，R1）。
 *
 * 不可变 + 纯数据：所有变更都走 [reduce]，因此状态迁移可以在纯 JVM 单测里覆盖。
 * [siblings] 是**同目录图片列表**（按名称排序，由 [ViewerMath.imageEntries] 保证），
 * [index] 是当前图片在其中的下标；列表为空时按「单张」展示（[count] 仍是 1）。
 */
data class ViewerUiState(
    /** 状态机。 */
    val status: ViewerStatus = ViewerStatus.LOADING,
    /** 当前图片的相对路径。 */
    val path: String = "",
    /** 当前图片的文件名（顶栏标题）。 */
    val name: String = "",
    /** 同目录图片（按名称排序）。 */
    val siblings: List<RemoteEntry> = emptyList(),
    /** 当前图片在 [siblings] 中的下标（从 0 开始）。 */
    val index: Int = 0,
    /** 已解码的图片；[ViewerStatus.READY] 时非空。 */
    val image: DecodedImage? = null,
    /** 失败分类；[ViewerStatus.ERROR] 时非空。 */
    val errorKind: ViewerErrorKind? = null,
    /** 后端给出的具体错误信息（诊断用，可为 null）。 */
    val errorDetail: String? = null,
) {

    /** 总张数；列表为空时按 1 张算，页面不会显示「第 1 / 0 张」。 */
    val count: Int get() = if (siblings.isEmpty()) 1 else siblings.size

    /** 当前第几张（从 1 开始，用于「第 i / n 张」）。 */
    val position: Int get() = (index + 1).coerceIn(1, count)

    /** 是否有多张可翻（只有一张时不显示翻页条）。 */
    val hasPages: Boolean get() = count > 1

    /** 能否往前翻。 */
    val canGoPrev: Boolean get() = ViewerMath.canGoPrev(index)

    /** 能否往后翻。 */
    val canGoNext: Boolean get() = ViewerMath.canGoNext(index, siblings.size)
}

/** 状态事件：ViewModel 只把外部结果翻译成事件，状态迁移全在 [reduce] 里。 */
sealed interface ViewerEvent {

    /** 开始加载某张图片（首次进入 / 重试）：清空兄弟列表，回到加载中。 */
    data class LoadStarted(val path: String, val name: String) : ViewerEvent

    /** 同目录图片列好了（还没解码）。 */
    data class SiblingsLoaded(val siblings: List<RemoteEntry>, val index: Int) : ViewerEvent

    /** 翻到某一页：先转圈，再等解码结果。 */
    data class PageShown(
        val siblings: List<RemoteEntry>,
        val index: Int,
        val path: String,
        val name: String,
    ) : ViewerEvent

    /** 解码成功。 */
    data class LoadSucceeded(val image: DecodedImage) : ViewerEvent

    /** 失败（已分类）。 */
    data class LoadFailed(
        val kind: ViewerErrorKind,
        val detail: String? = null,
    ) : ViewerEvent
}

/**
 * 状态归约（纯函数）：`新状态 = 旧状态.reduce(事件)`。
 *
 * 不抛异常、不碰 IO、不依赖 Android，方便单测覆盖所有分支。
 */
fun ViewerUiState.reduce(event: ViewerEvent): ViewerUiState = when (event) {
    is ViewerEvent.LoadStarted -> copy(
        status = ViewerStatus.LOADING,
        path = event.path,
        name = event.name,
        siblings = emptyList(),
        index = 0,
        image = null,
        errorKind = null,
        errorDetail = null,
    )

    is ViewerEvent.SiblingsLoaded -> copy(
        siblings = event.siblings,
        index = ViewerMath.clampIndex(event.index, event.siblings.size),
    )

    is ViewerEvent.PageShown -> copy(
        status = ViewerStatus.LOADING,
        path = event.path,
        name = event.name,
        siblings = event.siblings,
        index = ViewerMath.clampIndex(event.index, event.siblings.size),
        image = null,
        errorKind = null,
        errorDetail = null,
    )

    is ViewerEvent.LoadSucceeded -> copy(
        status = ViewerStatus.READY,
        image = event.image,
        errorKind = null,
        errorDetail = null,
    )

    is ViewerEvent.LoadFailed -> copy(
        status = ViewerStatus.ERROR,
        image = null,
        errorKind = event.kind,
        errorDetail = event.detail,
    )
}

/** 后端异常 → 页面错误分类（**M1-F**，纯函数）。 */
object ViewerErrors {

    /**
     * 把 [StorageException] 的语义子类（以及查看器自己的 [ImageTooLargeException]）翻译成页面分类；
     * 不认识的一律 [ViewerErrorKind.UNKNOWN]。
     */
    fun classify(throwable: Throwable): ViewerErrorKind = when (throwable) {
        is ImageTooLargeException -> ViewerErrorKind.TOO_LARGE
        is StorageException.AccessDenied -> ViewerErrorKind.ACCESS_DENIED
        is StorageException.NotFound -> ViewerErrorKind.NOT_FOUND
        is StorageException.NotSupported -> ViewerErrorKind.NOT_SUPPORTED
        is StorageException.Network -> ViewerErrorKind.NETWORK
        is StorageException.Auth -> ViewerErrorKind.AUTH
        else -> ViewerErrorKind.UNKNOWN
    }
}
