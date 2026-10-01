package io.github.gua123.mediagate.feature.viewer.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.RemoteEntry

/** 日志 TAG。 */
private const val TAG = "viewer-image"

/**
 * 图片查看器的 ViewModel（**M1-F**，R1）。
 *
 * 职责边界：
 * - 只做「列同目录图片 → 定位当前图片 → 按屏幕尺寸解码 → 翻译成 [ViewerEvent] → 归约成 [ViewerUiState]」，
 *   状态迁移逻辑全部在 [reduce] 里（纯函数，JVM 单测覆盖）；
 * - **所有 IO 都在 [io]（默认 [Dispatchers.IO]）**：列目录、读字节、解码一个都不落在组合函数或主线程；
 * - 翻页：[next] / [prev] 走 [ViewerMath] 的下标钳制（最后一张再点也不会越界，页面按钮也会置灰）；
 * - 预取「当前页 ±1」：解码完成后顺手把相邻两张解码进 [ViewerLruCache]，翻页时先查缓存，
 *   命中就不必再读远端；快速连翻时旧预取任务会被取消；
 * - 体积保护：目录项已知大小超过 [MAX_VIEWER_IMAGE_BYTES] 时**不读字节**，直接给「图片过大」。
 *
 * 后端（File / SAF / 远端）的生命周期由 :app 的 AppContainer 持有，本类只消费
 * [ImageViewerEnvironment]。
 *
 * @param environment 宿主能力（同目录图片 + 读取字节），见 [ImageViewerEnvironment]。
 * @param initialPath 进入时展示的图片路径（来自路由参数）。
 * @param viewportWidthPx 屏幕宽（像素）：解码采样率按它算，大图不会整张进内存。
 * @param viewportHeightPx 屏幕高（像素）。
 * @param decoder 解码器；单测注入假实现（真机用 [BitmapImageViewerDecoder]）。
 * @param io 列目录 / 读字节 / 解码所在的调度器；单测注入测试调度器。
 * @param prefetchCapacity 预取缓存容量（条数）。
 */
class ImageViewerViewModel(
    private val environment: ImageViewerEnvironment,
    private val initialPath: String,
    private val viewportWidthPx: Int,
    private val viewportHeightPx: Int,
    private val decoder: ImageViewerDecoder = BitmapImageViewerDecoder(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    prefetchCapacity: Int = PREFETCH_CAPACITY,
) : ViewModel() {

    /** 当前页 ±1 的解码结果缓存（M1-F 预取）。 */
    private val cache = ViewerLruCache<DecodedImage>(prefetchCapacity)

    private val _state = MutableStateFlow(
        ViewerUiState(path = initialPath, name = ViewerMath.fileNameOf(initialPath)),
    )

    /** 页面唯一状态源（StateFlow，见 plan 第 3 章）。 */
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** 列目录任务；新请求先取消它。 */
    private var loadJob: Job? = null

    /** 当前页解码任务。 */
    private var renderJob: Job? = null

    /** 预取任务（一次串行解相邻两张）。 */
    private var prefetchJob: Job? = null

    init {
        load(initialPath)
    }

    /** 失败后重试当前图片。 */
    fun retry() {
        load(_state.value.path.ifEmpty { initialPath })
    }

    /** 下一张（已在最后一张时停在原地）。 */
    fun next() {
        goTo(_state.value.index + 1)
    }

    /** 上一张（已在第一张时停在原地）。 */
    fun prev() {
        goTo(_state.value.index - 1)
    }

    /**
     * 跳到第 [target] 张（下标从 0 开始）；越界会被 [ViewerMath.clampIndex] 钳住。
     *
     * 已经在目标页且不是错误态时什么都不做（避免重复解码）。
     */
    fun goTo(target: Int) {
        val current = _state.value
        val images = current.siblings
        if (images.isEmpty()) return
        val index = ViewerMath.clampIndex(target, images.size)
        if (index == current.index && current.status != ViewerStatus.ERROR) return
        val entry = images.getOrNull(index) ?: return
        open(images, index, entry)
    }

    // ------------------------------------------------------------------ 内部

    /** 列同目录图片 → 定位当前图 → 解码。 */
    private fun load(path: String) {
        loadJob?.cancel()
        renderJob?.cancel()
        prefetchJob?.cancel()
        cache.clear()
        _state.update { it.reduce(ViewerEvent.LoadStarted(path, ViewerMath.fileNameOf(path))) }
        loadJob = viewModelScope.launch {
            val outcome = withContext(io) { listSiblings(path) }
            when (outcome) {
                is SiblingOutcome.Failure -> _state.update {
                    it.reduce(ViewerEvent.LoadFailed(outcome.kind, outcome.detail))
                }

                is SiblingOutcome.Success -> {
                    // 目录里一张图片都没列到时退化成一页：至少把用户点的那张显示出来，不给空白
                    val images = outcome.images.ifEmpty {
                        listOf(RemoteEntry(name = ViewerMath.fileNameOf(path), path = path))
                    }
                    val found = ViewerMath.indexOfPath(path, images)
                    val index = ViewerMath.clampIndex(if (found >= 0) found else 0, images.size)
                    _state.update { it.reduce(ViewerEvent.SiblingsLoaded(images, index)) }
                    val entry = images.getOrNull(index) ?: return@launch
                    open(images, index, entry)
                }
            }
        }
    }

    /** 展示 [images] 里第 [index] 张：先查预取缓存，未命中再解码。 */
    private fun open(images: List<RemoteEntry>, index: Int, entry: RemoteEntry) {
        renderJob?.cancel()
        prefetchJob?.cancel()
        _state.update {
            it.reduce(ViewerEvent.PageShown(siblings = images, index = index, path = entry.path, name = entry.name))
        }

        cache.get(entry.path)?.let { cached ->
            _state.update { it.reduce(ViewerEvent.LoadSucceeded(cached)) }
            prefetch(images, index)
            return
        }

        // 目录项已给出体积且超限：不读字节，直接提示（避免先读 200 MB 再报错）
        if (entry.size > MAX_VIEWER_IMAGE_BYTES) {
            AppLog.w(TAG, "图片超过查看器上限：" + entry.path + " size=" + entry.size)
            _state.update { it.reduce(ViewerEvent.LoadFailed(ViewerErrorKind.TOO_LARGE)) }
            return
        }

        renderJob = viewModelScope.launch {
            val outcome = withContext(io) { openAndDecode(entry.path) }
            // 期间可能已经翻到别的页：过期的结果直接丢掉
            if (_state.value.path != entry.path) return@launch
            when (outcome) {
                is DecodeOutcome.Success -> {
                    cache.put(entry.path, outcome.image)
                    _state.update { it.reduce(ViewerEvent.LoadSucceeded(outcome.image)) }
                    prefetch(images, index)
                }

                is DecodeOutcome.Failure -> _state.update {
                    it.reduce(ViewerEvent.LoadFailed(outcome.kind, outcome.detail))
                }
            }
        }
    }

    /** 预取「当前页 ±1」（M1-F）：串行解码相邻两张，已缓存或超限的跳过。 */
    private fun prefetch(images: List<RemoteEntry>, index: Int) {
        prefetchJob?.cancel()
        val currentPath = _state.value.path
        val neighbours = listOf(index - 1, index + 1)
            .mapNotNull { images.getOrNull(it) }
            .filter { it.path != currentPath && !cache.contains(it.path) && it.size <= MAX_VIEWER_IMAGE_BYTES }
        if (neighbours.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            for (entry in neighbours) {
                currentCoroutineContext().ensureActive()
                val outcome = withContext(io) { openAndDecode(entry.path) }
                if (outcome is DecodeOutcome.Success) cache.put(entry.path, outcome.image)
            }
        }
    }

    /** 列同目录图片（异常翻译成分类，不向 UI 抛）。 */
    private suspend fun listSiblings(path: String): SiblingOutcome = try {
        SiblingOutcome.Success(ViewerMath.imageEntries(environment.siblings(path)))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "列出同目录图片失败：" + path, t)
        SiblingOutcome.Failure(ViewerErrors.classify(t), t.message)
    }

    /** 读字节 + 解码（异常翻译成分类）。 */
    private suspend fun openAndDecode(path: String): DecodeOutcome = try {
        val bytes = environment.open(path)
        val image = decoder.decode(bytes, viewportWidthPx, viewportHeightPx)
        if (image == null) {
            DecodeOutcome.Failure(ViewerErrorKind.DECODE_FAILED)
        } else {
            DecodeOutcome.Success(image)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取图片失败：" + path, t)
        DecodeOutcome.Failure(ViewerErrors.classify(t), t.message)
    }

    /** 列目录结果（成功 / 失败二选一，避免在协程里做异常控制流）。 */
    private sealed interface SiblingOutcome {
        data class Success(val images: List<RemoteEntry>) : SiblingOutcome
        data class Failure(val kind: ViewerErrorKind, val detail: String?) : SiblingOutcome
    }

    /** 解码结果。 */
    private sealed interface DecodeOutcome {
        data class Success(val image: DecodedImage) : DecodeOutcome
        data class Failure(val kind: ViewerErrorKind, val detail: String? = null) : DecodeOutcome
    }

    companion object {

        /** 预取缓存容量：当前页 + 相邻两张 + 1 张余量（来回翻同一对图不必重新解码）。 */
        const val PREFETCH_CAPACITY = 4
    }
}
