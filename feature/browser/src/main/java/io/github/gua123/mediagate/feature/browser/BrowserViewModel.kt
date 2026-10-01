package io.github.gua123.mediagate.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * 目录浏览的 ViewModel（R2 列目录 / R12 双模式 / R5 缩略图数据源）。
 *
 * 职责边界：
 * - 只做「取根目录 → 列目录 → 翻译成 [BrowserEvent] → 归约成 [BrowserUiState]」，
 *   状态迁移逻辑全部在 [reduce] 里（纯函数，JVM 单测覆盖）；
 * - 真实 IO 一律 `withContext(io)`（默认 [Dispatchers.IO]），组合函数里不碰 IO；
 * - 根目录变化（用户换目录 / 换模式）时自动重新列目录：换根回到根目录，同根保留当前路径；
 * - 每次列目录都会取消上一次（快速连点子目录不会串台），协程取消沿后端一路生效（plan 4.4 离屏取消）。
 *
 * 后端（File / SAF）的生命周期由 :app 的 AppContainer 持有，本类只消费 [BrowserEnvironment.root]。
 *
 * @param environment 宿主能力（根目录 + 缩略图仓库），见 [BrowserEnvironment]。
 * @param initialPath 进入时展示的目录（来自路由参数，缺省根目录）。
 * @param initialFilter 进入时的类型过滤（首页「视频/音乐/图片」卡片带入，可清除）。
 * @param io 列目录所用的调度器；单测注入测试调度器。
 */
class BrowserViewModel(
    private val environment: BrowserEnvironment,
    private val initialPath: String = "",
    initialFilter: MediaKind? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BrowserUiState(filter = initialFilter).reduce(
            BrowserEvent.LoadStarted(path = initialPath, rootLabel = ""),
        ),
    )

    /** 页面唯一状态源（StateFlow，见 plan 第 3 章）。 */
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    /** 当前根目录（后端实例变化即视为换根）。 */
    private var currentRoot: BrowserRootState? = null

    /** 是否还没收到过根目录的第一帧（用于决定要不要用 [initialPath]）。 */
    private var awaitingFirstRoot = true

    /** 正在进行的列目录任务；新请求先取消它。 */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            environment.root.collect { root -> onRootChanged(root) }
        }
        // 排序设置由 :app 持久化：进页面先拿当前值，用户改了也跟着状态走
        viewModelScope.launch {
            environment.sort.collect { sort -> _state.update { it.reduce(BrowserEvent.SortChanged(sort)) } }
        }
    }

    /**
     * 改排序（2026-10-03 用户要求）：先更新界面，再落盘（落盘失败不影响本次浏览）。
     */
    fun setSort(sort: EntrySort) {
        _state.update { it.reduce(BrowserEvent.SortChanged(sort)) }
        viewModelScope.launch { environment.setSort(sort) }
    }

    /** 进入子目录（[path] 为相对根目录的 POSIX 路径）。 */
    fun open(path: String) {
        load(path, refreshing = false)
    }

    /** 返回上级；已在根目录时什么都不做。 */
    fun up() {
        val parent = BrowserPaths.parentOf(_state.value.path) ?: return
        load(parent, refreshing = false)
    }

    /** 下拉刷新：保留当前内容，只转圈。 */
    fun refresh() {
        load(_state.value.path, refreshing = true)
    }

    /** 出错后重试当前目录。 */
    fun retry() {
        load(_state.value.path, refreshing = false)
    }

    /** 清除类型过滤（首页带进来的那个）。 */
    fun clearFilter() {
        _state.update { it.reduce(BrowserEvent.FilterChanged(null)) }
    }

    // ------------------------------------------------------------------ 内部

    private fun onRootChanged(root: BrowserRootState?) {
        val previousBackend = currentRoot?.backend
        currentRoot = root
        if (root == null) {
            loadJob?.cancel()
            _state.update { it.reduce(BrowserEvent.RootMissing) }
            return
        }
        val sameBackend = previousBackend === root.backend
        val path = when {
            awaitingFirstRoot -> initialPath
            sameBackend -> _state.value.path
            else -> ""
        }
        awaitingFirstRoot = false
        load(path, refreshing = false)
    }

    private fun load(path: String, refreshing: Boolean) {
        val root = currentRoot
        loadJob?.cancel()
        if (root == null) {
            _state.update { it.reduce(BrowserEvent.RootMissing) }
            return
        }
        val target = BrowserPaths.normalize(path)
        _state.update {
            it.reduce(
                BrowserEvent.LoadStarted(path = target, rootLabel = root.label, refreshing = refreshing),
            )
        }
        loadJob = viewModelScope.launch {
            val outcome = withContext(io) {
                try {
                    LoadOutcome.Success(root.backend.list(target, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    LoadOutcome.Failure(classify(t), t.message)
                }
            }
            _state.update { state ->
                when (outcome) {
                    is LoadOutcome.Success -> state.reduce(BrowserEvent.LoadSucceeded(outcome.entries))
                    is LoadOutcome.Failure -> state.reduce(BrowserEvent.LoadFailed(outcome.kind, outcome.detail))
                }
            }
        }
    }

    /** 后端异常 → 页面错误分类（R2 / R12：无权限要能引导去授权）。 */
    private fun classify(throwable: Throwable): BrowserErrorKind = when (throwable) {
        is StorageException.AccessDenied -> BrowserErrorKind.ACCESS_DENIED
        is StorageException.NotFound -> BrowserErrorKind.NOT_FOUND
        is StorageException.NotSupported -> BrowserErrorKind.NOT_SUPPORTED
        // 认证失败单独一类：界面要给出"去哪儿改密码"的可照做指引（真机反馈过）
        is StorageException.Auth -> BrowserErrorKind.AUTH_FAILED
        else -> BrowserErrorKind.UNKNOWN
    }

    /** 列目录结果（成功 / 失败二选一，避免在协程里做异常控制流）。 */
    private sealed interface LoadOutcome {
        data class Success(val entries: List<RemoteEntry>) : LoadOutcome
        data class Failure(val kind: BrowserErrorKind, val detail: String?) : LoadOutcome
    }
}
