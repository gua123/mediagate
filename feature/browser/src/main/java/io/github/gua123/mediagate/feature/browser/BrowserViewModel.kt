package io.github.gua123.mediagate.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * @param initialStartAtRoot **true = 起点固定为连接根目录**（首页三个媒体入口 / 底部「浏览」页签），
 *   不再走 [rememberedStartPath] 的推断；false = 保持原行为（从上次播放/浏览的目录起）。
 * @param io 列目录所用的调度器；单测注入测试调度器。
 */
class BrowserViewModel(
    private val environment: BrowserEnvironment,
    private val initialPath: String = "",
    initialFilter: MediaKind? = null,
    private val initialStartAtRoot: Boolean = false,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * 打开时从哪个目录开始。
     *
     * **2026-10-03 用户要求**：「在重新打开软件时，如果网络通畅，自动打开到最后一次播放的文件夹」
     * ⇒ 路由没带路径（＝从底部导航进来的）时，退回"**最后一次播放文件所在的那个文件夹**"；
     * 谁都没有记录才回根目录。网络不通时照旧由根目录装载流程给出中文错误与重试。
     *
     * **2026-11-18**：路由显式要求从根目录开始（`startAtRoot=true`）时，这条推断一律不生效 ——
     * 首页三个媒体入口和底部「浏览」页签都带这个标记，否则两边都会落到"上次那个文件夹"上，
     * 渲染成同一份列表（用户反馈的现象）。显式路径（带 path 的来路）仍然优先，与 startAtRoot 不冲突。
     */
    private val startPath: String = if (initialStartAtRoot) "" else initialPath.ifBlank {
        rememberedStartPath()
    }

    /** 有播放记录就进它所在的文件夹；否则回到上次浏览的目录；都没有就回根目录。 */
    private fun rememberedStartPath(): String {
        val playedDir = BrowserPaths.parentOf(environment.lastPlayedPath.orEmpty()).orEmpty()
        if (playedDir.isNotBlank()) return playedDir
        return environment.lastBrowsedPathFlow.value.orEmpty()
    }

    private val _state = MutableStateFlow(
        BrowserUiState(filter = initialFilter, lastPlayedPath = environment.lastPlayedPath).reduce(
            BrowserEvent.LoadStarted(path = startPath, rootLabel = ""),
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

    /** 同一目录只自愈一次，避免重建后仍失败时无限递归。 */
    private var recoveredPath: String? = null

    /** 封面空闲预热的协程（用户一操作就取消，见 [scheduleCoverWarmup]）。 */
    private var warmupJob: Job? = null

    init {
        viewModelScope.launch {
            environment.root.collect { root -> onRootChanged(root) }
        }
        // 排序设置由 :app 持久化：进页面先拿当前值，用户改了也跟着状态走
        viewModelScope.launch {
            environment.sort.collect { sort -> _state.update { it.reduce(BrowserEvent.SortChanged(sort)) } }
        }
        // **2026-10-03 真机修复**：记住的目录是**异步**从 DataStore 读出来的，
        // 冷启动时构造这个 VM 那一刻往往还是空 ⇒ 先按根目录渲染，等它到了再"补跳"一次。
        // 只在用户还没自己进过任何目录（path 仍为空）、路由没指定路径、且**没要求固定从根开始**时才跳，
        // 绝不打断用户，也绝不把 startAtRoot 的页面从根目录拽走（2026-11-18）。
        if (initialPath.isBlank() && !initialStartAtRoot) {
            viewModelScope.launch {
                environment.lastPlayedPathFlow.collect { played ->
                    val dir = BrowserPaths.parentOf(played.orEmpty()).orEmpty()
                    if (dir.isNotBlank()) jumpIfStillAtRoot(dir)
                }
            }
            viewModelScope.launch {
                environment.lastBrowsedPathFlow.collect { browsed ->
                    if (!browsed.isNullOrBlank()) jumpIfStillAtRoot(browsed)
                }
            }
        }
    }

    /** 只在"还停在根目录、且没有任何在途/已完成的用户导航"时补跳一次。 */
    private fun jumpIfStillAtRoot(dir: String) {
        val current = _state.value
        if (current.path.isNotEmpty()) return
        if (current.status == BrowserStatus.LOADING) return
        load(dir, refreshing = false)
    }

    /**
     * 改排序（2026-10-03 用户要求）：先更新界面，再落盘（落盘失败不影响本次浏览）。
     */
    fun setSort(sort: EntrySort) {
        warmupJob?.cancel()
        _state.update { it.reduce(BrowserEvent.SortChanged(sort)) }
        viewModelScope.launch { environment.setSort(sort) }
    }

    /** 进入子目录（[path] 为相对根目录的 POSIX 路径）。 */
    fun open(path: String) {
        warmupJob?.cancel()
        load(path, refreshing = false)
    }

    /** 返回上级；已在根目录时什么都不做。 */
    fun up() {
        warmupJob?.cancel()
        val parent = BrowserPaths.parentOf(_state.value.path) ?: return
        load(parent, refreshing = false)
    }

    /** 下拉刷新：保留当前内容，只转圈。 */
    fun refresh() {
        warmupJob?.cancel()
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

    /** 在浏览页内切换类型过滤（全部 / 视频 / 音频 / 图片）。 */
    fun setFilter(filter: MediaKind?) {
        _state.update { it.reduce(BrowserEvent.FilterChanged(filter)) }
    }

    // ------------------------------------------------------------------ 内部

    private fun onRootChanged(root: BrowserRootState?) {
        val previousRoot = currentRoot
        val previousBackend = previousRoot?.backend
        currentRoot = root
        if (root == null) {
            loadJob?.cancel()
            _state.update { it.reduce(BrowserEvent.RootMissing) }
            return
        }
        val sameBackend = previousBackend === root.backend
        // 自愈重建后 backend 实例会换，但连接 label 不变：保留当前目录，不要突然跳回根。
        val sameConnection = previousRoot?.label == root.label && previousRoot.mode == root.mode
        val path = when {
            // 首帧：远端连接不能套用本地记住的目录（2026-11-15 用户反馈：
            // 切到 LAN 后路径变成 "本地视频文件夹/..."）。远端只认路由显式带来的路径，
            // 没带就回根目录；本地仍按记住的起始目录恢复。
            awaitingFirstRoot && root.mode == RootModeKind.NONE ->
                if (initialStartAtRoot) "" else initialPath
            // 首帧用**算好的**起始目录（曾经这里写成 initialPath ⇒ 路由没带路径时永远回根目录）；
            // startAtRoot=true 时 startPath 就是 ""（连接根目录），不受记住的目录影响。
            awaitingFirstRoot -> startPath
            sameBackend || sameConnection -> _state.value.path
            else -> ""
        }
        awaitingFirstRoot = false
        load(path, refreshing = false)
    }

    private fun load(path: String, refreshing: Boolean) {
        val root = currentRoot
        warmupJob?.cancel()
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
                    // 列目录必须有上限：公网 SFTP 的 native 读可能卡住，15s 超时后走自愈重建，
                    // 不能让界面无限“正在加载…”（2026-11-15 用户反馈）。
                    withTimeout(15_000L) { root.backend.list(target, null) }.let { LoadOutcome.Success(it) }
                } catch (e: TimeoutCancellationException) {
                    // withTimeout 抛的 TimeoutCancellationException 本身就是 CancellationException 的子类，
                    // 若被下面的 CancellationException 分支原样 rethrow，就永远走不到 LoadFailed ——
                    // 界面会永远停在「正在加载…」（2026-11-17 真机实测卡 45 分钟）。
                    LoadOutcome.Failure(BrowserErrorKind.TIMEOUT, e.message)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    LoadOutcome.Failure(classify(t), t.message)
                }
            }
            // 超时/未知错误先自愈一次：公网 SFTP 静默断开时重建连接再重列，避免用户手动去连接页重连。
            if (outcome is LoadOutcome.Failure &&
                (outcome.kind == BrowserErrorKind.TIMEOUT || outcome.kind == BrowserErrorKind.UNKNOWN) &&
                recoveredPath != target
            ) {
                recoveredPath = target
                // 自愈本身也必须限时：公网 SFTP 静默断开时重建连接会卡住，
                // 之前没有上限导致界面永远停在「正在加载…」（2026-11-16 真机实测 5.5 分钟无返回）。
                val recovered = withTimeoutOrNull(RECOVER_TIMEOUT_MS) { environment.recoverRemoteRoot() } ?: false
                if (recovered) {
                    load(target, refreshing = false)
                    return@launch
                }
            }
            _state.update { state ->
                when (outcome) {
                    is LoadOutcome.Success -> state.reduce(BrowserEvent.LoadSucceeded(outcome.entries))
                    is LoadOutcome.Failure -> state.reduce(BrowserEvent.LoadFailed(outcome.kind, outcome.detail))
                }
            }
            // 列表出来了：空闲预热"视频开头封面"（用户 2026-10-14 的要求）
            if (outcome is LoadOutcome.Success) {
                recoveredPath = null
                scheduleCoverWarmup(root, target)
            }
        }
    }

    /**
     * 空闲预热封面（**2026-10-14 用户要求**：打开软件的空闲时先取视频文件开头，用开头画面当封面，
     * 取到后刷新列表里的封面）。
     *
     * 三条纪律（都在 [CoverWarmupPolicy] 里定死，可单测）：
     * ① **先等一会儿**再动手（用户多半正在翻列表）；② **同一远端连接最多 2 个并发**
     * （用户问"多线程会不会更快"——会，但开太多会互相抢带宽、还可能被服务端限流，反而拖慢播放）；
     * ③ **用户一操作就整体取消**（切目录/下拉刷新/改排序都取消），绝不给正在看的人添堵。
     *
     * 预热本身走的还是 [ThumbnailRepository]——**已缓存的会直接命中、不发网络请求**。
     */
    private fun scheduleCoverWarmup(root: BrowserRootState, path: String) {
        warmupJob?.cancel()
        val targets = CoverWarmupPolicy.targets(_state.value.visibleEntries)
        if (targets.isEmpty()) return
        warmupJob = viewModelScope.launch {
            delay(CoverWarmupPolicy.DELAY_AFTER_LIST_MS)
            for (batch in CoverWarmupPolicy.batches(targets)) {
                // 期间用户已经换了目录/刷新过就不要再干了
                if (_state.value.path != path || _state.value.status != BrowserStatus.CONTENT) return@launch
                batch.map { entry ->
                    async(io) {
                        // 先把开头字节缓存下来（用户要求：打开文件夹就自动缓存），再出封面——
                        // 这样封面抽帧直接命中缓存，不再为封面单独过一次网。
                        runCatching { environment.warmUpFile(entry.path) }
                        runCatching { environment.thumbnails.thumbnail(entry, root.backend) }
                    }
                }.awaitAll()
                _state.update { it.reduce(BrowserEvent.CoverWarmed) }
                delay(CoverWarmupPolicy.GAP_BETWEEN_BATCHES_MS)
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
        // 超时也单独一类：公网/弱网下"一直转圈"变成"明确超时 + 重试"（2026-10-24）
        is StorageException.Timeout -> BrowserErrorKind.TIMEOUT
        else -> BrowserErrorKind.UNKNOWN
    }

    /** 列目录结果（成功 / 失败二选一，避免在协程里做异常控制流）。 */
    private sealed interface LoadOutcome {
        data class Success(val entries: List<RemoteEntry>) : LoadOutcome
        data class Failure(val kind: BrowserErrorKind, val detail: String?) : LoadOutcome
    }
}

/** 自愈重建连接的硬上限：超过就当自愈失败，交给界面报错。 */
private const val RECOVER_TIMEOUT_MS: Long = 10_000L
