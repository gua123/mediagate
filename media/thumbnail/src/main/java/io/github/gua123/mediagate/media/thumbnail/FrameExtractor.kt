package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.data.storage.api.RandomAccessSource

/**
 * 视频抽帧抽象（R5 缩略图，plan 4.4 的「主策略 / 兜底」两行）。
 *
 * 约定（两条实现都必须遵守）：
 * - **不抛异常**：任何失败（格式不支持、远端断开、native 报错）都返回 null，由
 *   [ThumbnailRepository] 决定换位重试 / 换兜底实现 / 写负缓存；只有协程取消
 *   （[kotlinx.coroutines.CancellationException]）允许向上抛，保证「离屏取消」能生效；
 * - **不持有 source 的生命周期**：source 由调用方打开与关闭，实现只读不关；
 * - 读数据一律走 [RandomAccessSource]（plan 第 3 章不变量：所有媒体数据只经过这一个抽象）。
 *
 * 抽帧器通过构造参数注入 [ThumbnailRepository]，真机用 MMR / FFmpeg，单测用假实现。
 */
interface FrameExtractor {

    /**
     * 在 [positionMs] 处抽一帧并编码为图片字节。
     *
     * @param source 随机访问数据源（调用方持有生命周期）。
     * @param mimeHint 目录项给出的 MIME 提示，可为 null（仅作提示，不得依赖）。
     * @param positionMs 目标时间点（毫秒）；负数表示「任意一帧」（MMR 的约定语义）。
     * @param targetWidth 期望宽度（像素）；<=0 表示按原始尺寸。
     * @return 图片字节；失败返回 null（不抛异常）。
     */
    /**
     * **本地文件直读取帧**（可选能力）：给实现一个"文件路径"的机会，比 [extract] 的 MediaDataSource
     * 路径快一个量级——滑动手势要即时反馈，慢一步就等于没有（2026-10-03 真机反馈后新增）。
     *
     * 默认实现返回 null（表示"不支持"），调用方据此回退到 [extract]。
     */
    suspend fun extractFromFile(file: java.io.File, positionMs: Long, targetWidth: Int): ByteArray? = null

    suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray?
}

/**
 * 抽帧器的**可选**附加能力：报告媒体时长（R5）。
 *
 * plan 4.4 要求「主策略取 10% 帧，失败换 1% / 25% 重试」，这需要一个时长才能把比例换算成毫秒。
 * [FrameExtractor] 本身不返回时长（接口保持最小），因此能顺便给出时长的实现（如
 * [MediaMetadataRetrieverFrameExtractor]）再实现本接口即可；仓库通过
 * `primary as? MediaDurationProbe` 自动识别，拿不到时长时退化为固定位置探测。
 */
interface MediaDurationProbe {

    /**
     * 读取媒体总时长。
     *
     * @return 时长（毫秒）；未知或失败返回 null（不抛异常，取消除外）。
     */
    suspend fun durationMs(source: RandomAccessSource, mimeHint: String?): Long?
}
