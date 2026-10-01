package io.github.gua123.mediagate.feature.viewer.image

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.thumbnail.ImageSampling
import kotlin.math.abs

/** 尺寸（像素，Float）；纯数据类，避免把 Compose 的 Size/Offset 带进 JVM 单测。 */
data class SizeF(val width: Float, val height: Float)

/**
 * 图片查看器的纯逻辑（**M1-F**，R1）：翻页索引边界、缩放范围钳制、位移钳制、适配尺寸、采样率。
 *
 * 这里**只有数学**：不碰 Android、不碰 Compose、不碰 IO，因此可以逐条 JVM 单测。
 * 页面（[ImageViewerScreen]）与 ViewModel（[ImageViewerViewModel]）都只调用这些函数，
 * 保证「双击放大到几倍」「最后一张还能不能往后翻」这类规则只有一处定义。
 */
object ViewerMath {

    /** 最小缩放：1 倍 = 恰好适配屏幕（不允许缩得比屏幕还小）。 */
    const val MIN_SCALE = 1f

    /** 最大缩放：6 倍（再大就只剩马赛克，且 8000×6000 的图会很吃内存）。 */
    const val MAX_SCALE = 6f

    /** 双击放大的目标倍数；已是放大态时双击回到 [MIN_SCALE]。 */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 未放大时，横向滑动超过该像素距离即翻页。 */
    const val PAGE_SWIPE_THRESHOLD_PX = 120f

    /** 只保留图片（目录 / 视频 / 音频 / 字幕都不参与翻页），并按名称排序（大小写不敏感）。 */
    fun imageEntries(entries: List<RemoteEntry>): List<RemoteEntry> = entries.asSequence()
        .filter { !it.isDirectory && MediaKindGuesser.guess(it.name) == MediaKind.IMAGE }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
        .toList()

    /** 在图片列表里找 [path] 的下标；找不到返回 -1（调用方决定退化成单张显示）。 */
    fun indexOfPath(path: String, images: List<RemoteEntry>): Int =
        images.indexOfFirst { it.path == path }

    /** 路径最后一段（文件名）；用于顶栏标题。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /** 把下标钳到 [0, count-1]；列表为空时返回 0。 */
    fun clampIndex(index: Int, count: Int): Int =
        if (count <= 0) 0 else index.coerceIn(0, count - 1)

    /** 下一张的下标（已在最后一张时停在原地）。 */
    fun nextIndex(current: Int, count: Int): Int = clampIndex(current + 1, count)

    /** 上一张的下标（已在第一张时停在原地）。 */
    fun prevIndex(current: Int, count: Int): Int = clampIndex(current - 1, count)

    /** 能否往后翻（最后一张 / 只有一张 / 空列表都不行）。 */
    fun canGoNext(index: Int, count: Int): Boolean = count > 0 && index < count - 1

    /** 能否往前翻。 */
    fun canGoPrev(index: Int): Boolean = index > 0

    /** 缩放到合法区间；NaN（手势异常）退化为 [MIN_SCALE]。 */
    fun clampScale(scale: Float): Float =
        if (scale.isNaN()) MIN_SCALE else scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** 内容在某个方向上可移动的最大距离（内容比视口小则为 0，即只能居中）。 */
    fun maxOffset(contentSize: Float, viewportSize: Float): Float =
        ((contentSize - viewportSize) / 2f).coerceAtLeast(0f)

    /** 把位移钳在 [-maxOffset, +maxOffset]，保证拖不出黑边。 */
    fun clampOffset(offset: Float, contentSize: Float, viewportSize: Float): Float {
        val max = maxOffset(contentSize, viewportSize)
        if (offset.isNaN()) return 0f
        return offset.coerceIn(-max, max)
    }

    /**
     * ContentScale.Fit 的尺寸计算：等比缩放到刚好放进视口。
     *
     * 尺寸未知（<=0）时返回 0；视口未知时按原尺寸（首帧还没测到尺寸时不至于算出 NaN）。
     */
    fun fitSize(contentWidth: Float, contentHeight: Float, viewportWidth: Float, viewportHeight: Float): SizeF {
        if (contentWidth <= 0f || contentHeight <= 0f) return SizeF(0f, 0f)
        if (viewportWidth <= 0f || viewportHeight <= 0f) return SizeF(contentWidth, contentHeight)
        val scale = minOf(viewportWidth / contentWidth, viewportHeight / contentHeight)
        return SizeF(contentWidth * scale, contentHeight * scale)
    }

    /**
     * 未放大时的横向滑动是否够格翻页（手势结束时判定）。
     *
     * @param scale 当前缩放；放大状态下横向拖动是「平移画面」，不翻页。
     * @param dragX 本次手势累计的横向位移（向左为负 = 看下一张）。
     */
    fun shouldTurnPage(scale: Float, dragX: Float, threshold: Float = PAGE_SWIPE_THRESHOLD_PX): Boolean =
        scale <= MIN_SCALE && abs(dragX) >= threshold

    /**
     * 采样率（按屏幕尺寸解码，**大图不 OOM** 的关键）。
     *
     * 复用 `:media:thumbnail` 的 [ImageSampling]（同一份算法已经被那里的单测穷举过边界），
     * 这里只做一层「查看器语义」的转发：宽高都要约束，避免竖图解码后仍宽于屏幕。
     */
    fun sampleSize(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Int =
        ImageSampling.sampleSize(sourceWidth, sourceHeight, requestedWidth, requestedHeight)
}

/**
 * 极简 LRU（**M1-F** 的「当前页 ±1 预取」缓存）。
 *
 * 为什么不用 android.util.LruCache：那样本类与 [ImageViewerViewModel] 就没法在纯 JVM 上单测。
 * 这里用 accessOrder=true 的 LinkedHashMap，命中即刷新最近使用，容量按条数控制。
 *
 * 存的是已解码的 [DecodedImage]：位图交给 GC（minSdk 33，像素本来就在 Java 堆上），
 * 不主动 recycle，避免「正在显示却被回收」导致崩溃。
 */
class ViewerLruCache<V>(val capacity: Int) {

    private val entries = LinkedHashMap<String, V>(16, 0.75f, true)

    /** 取值并刷新最近使用；没有返回 null。 */
    @Synchronized
    fun get(key: String): V? = entries[key]

    /** 是否已有缓存（**不**刷新最近使用，供预取判断用）。 */
    @Synchronized
    fun contains(key: String): Boolean = entries.containsKey(key)

    /** 写入并做容量淘汰（[capacity] <= 0 时等价于不缓存）。 */
    @Synchronized
    fun put(key: String, value: V) {
        entries[key] = value
        val iterator = entries.entries.iterator()
        while (entries.size > capacity && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /** 当前条数（诊断 / 单测用）。 */
    @Synchronized
    fun size(): Int = entries.size

    /** 清空（换目录时调用，避免缓存跨目录串台）。 */
    @Synchronized
    fun clear() = entries.clear()
}
