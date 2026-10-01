package io.github.gua123.mediagate.feature.browser

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry

/** 列表状态机（R2）：加载中 / 空目录 / 有内容 / 出错 / 尚未选择根目录。 */
enum class BrowserStatus {
    /** 正在列目录。 */
    LOADING,

    /** 目录存在但没有子项。 */
    EMPTY,

    /** 有内容（可能被类型过滤后为空，见 [BrowserUiState.visibleEntries]）。 */
    CONTENT,

    /** 列目录失败（错误分类见 [BrowserErrorKind]）。 */
    ERROR,

    /** 尚未选择根目录（R12 引导，不是错误）。 */
    NO_ROOT,
}

/** 列目录失败的语义分类（对应 [io.github.gua123.mediagate.data.storage.api.StorageException]）。 */
enum class BrowserErrorKind {
    /** 无读权限（如 SAF 授权失效）→ 给「去授权」。 */
    ACCESS_DENIED,

    /** 目录不存在（如授权目录被删）。 */
    NOT_FOUND,

    /** 后端不支持（如把文件当目录列）。 */
    NOT_SUPPORTED,

    /**
     * 认证失败（账号 / 密码 / 密钥不对）。
     *
     * 单独一类的原因：这是**用户自己能修**的错误，界面必须说清"去哪儿改"，
     * 而不是笼统一句「加载失败，请重试」——真机反馈就是"SFTP 认证失败时不知道该重填密码"。
     */
    AUTH_FAILED,

    /** 其余错误。 */
    UNKNOWN,
}

/**
 * 浏览页状态（R2 / R5）。
 *
 * 不可变 + 纯数据：所有变更都走 [reduce]，因此「UiState 归约」可以在纯 JVM 单测里覆盖。
 * [entries] 是后端原始顺序（**后端已保证目录优先 + 名称排序，这里直接透传**），
 * [visibleEntries] 是套上类型过滤后的展示列表。
 */
data class BrowserUiState(
    val status: BrowserStatus = BrowserStatus.NO_ROOT,
    /** 根目录展示名（面包屑第一段）。 */
    val rootLabel: String = "",
    /** 当前目录的相对路径（`""` = 根目录）。 */
    val path: String = "",
    /** 类型过滤（首页入口卡片带进来的，可清除）。 */
    val filter: MediaKind? = null,
    /** 后端返回的原始列表。 */
    val entries: List<RemoteEntry> = emptyList(),
    /** 套上 [filter] 之后真正展示的列表（目录始终保留，否则进不去子目录）。 */
    val visibleEntries: List<RemoteEntry> = emptyList(),
    /** 列表排序（用户可选，持久化在 :app）。 */
    val sort: EntrySort = EntrySort(),
    /** 失败分类；非 [BrowserStatus.ERROR] 时为 null。 */
    val errorKind: BrowserErrorKind? = null,
    /** 后端给的具体错误信息（诊断用，可为 null）。 */
    val errorDetail: String? = null,
    /** 是否是下拉刷新（内容保留，只转圈）。 */
    val refreshing: Boolean = false,
) {

    /** 面包屑（含根），供路径栏渲染。 */
    val crumbs: List<BrowserCrumb> get() = BrowserPaths.crumbs(rootLabel, path)

    /** 能否返回上级。 */
    val canGoUp: Boolean get() = BrowserPaths.parentOf(path) != null

    /** 标题栏文案：当前目录名，根目录时用根目录名。 */
    val title: String get() = BrowserPaths.nameOf(path).ifEmpty { rootLabel }

    /** 目录非空但被过滤干净（此时要提示「没有匹配的项」而不是「目录为空」）。 */
    val filteredToEmpty: Boolean get() = entries.isNotEmpty() && visibleEntries.isEmpty()
}

/** 状态事件：ViewModel 只负责把外部结果翻译成事件，状态迁移全在 [reduce] 里。 */
sealed interface BrowserEvent {

    /** 改排序方式（方式或升降序）。 */
    data class SortChanged(val sort: EntrySort) : BrowserEvent

    /** 开始列某个目录（[refreshing] = 下拉刷新，保留旧内容）。 */
    data class LoadStarted(
        val path: String,
        val rootLabel: String,
        val refreshing: Boolean = false,
    ) : BrowserEvent

    /** 列目录成功（后端已排序，直接透传）。 */
    data class LoadSucceeded(val entries: List<RemoteEntry>) : BrowserEvent

    /** 列目录失败（已分类）。 */
    data class LoadFailed(
        val kind: BrowserErrorKind,
        val detail: String? = null,
    ) : BrowserEvent

    /** 尚未选择根目录（R12）。 */
    data object RootMissing : BrowserEvent

    /** 切换 / 清除类型过滤。 */
    data class FilterChanged(val filter: MediaKind?) : BrowserEvent
}

/**
 * 状态归约（纯函数）：`新状态 = 旧状态.reduce(事件)`。
 *
 * 不抛异常、不碰 IO、不依赖 Android，方便单测覆盖所有分支。
 */
fun BrowserUiState.reduce(event: BrowserEvent): BrowserUiState = when (event) {
    is BrowserEvent.LoadStarted -> {
        val target = BrowserPaths.normalize(event.path)
        val keepContent = event.refreshing && target == path
        copy(
            status = BrowserStatus.LOADING,
            path = target,
            rootLabel = event.rootLabel,
            refreshing = event.refreshing,
            errorKind = null,
            errorDetail = null,
            entries = if (keepContent) entries else emptyList(),
            visibleEntries = if (keepContent) visibleEntries else emptyList(),
        )
    }

    is BrowserEvent.LoadSucceeded -> copy(
        status = if (event.entries.isEmpty()) BrowserStatus.EMPTY else BrowserStatus.CONTENT,
        entries = event.entries,
        visibleEntries = EntrySorter.sort(BrowserFilters.apply(event.entries, filter), sort),
        refreshing = false,
        errorKind = null,
        errorDetail = null,
    )

    is BrowserEvent.LoadFailed -> copy(
        status = BrowserStatus.ERROR,
        refreshing = false,
        errorKind = event.kind,
        errorDetail = event.detail,
        entries = emptyList(),
        visibleEntries = emptyList(),
    )

    // 排序变了：拿当前目录项重算可见列表（不再打一次远端）
    is BrowserEvent.SortChanged -> copy(
        sort = event.sort,
        visibleEntries = EntrySorter.sort(
            BrowserFilters.apply(entries, filter),
            event.sort,
        ),
    )

    BrowserEvent.RootMissing -> copy(
        status = BrowserStatus.NO_ROOT,
        refreshing = false,
        errorKind = null,
        errorDetail = null,
        entries = emptyList(),
        visibleEntries = emptyList(),
    )

    is BrowserEvent.FilterChanged -> copy(
        filter = event.filter,
        visibleEntries = EntrySorter.sort(BrowserFilters.apply(entries, event.filter), sort),
    )
}

/**
 * 类型过滤 / 分组（R1 三类媒体 + R5，纯函数）。
 *
 * 过滤规则：**目录永远保留**（否则进不去子目录），文件按 [MediaKindGuesser] 判定是否命中。
 */
object BrowserFilters {

    /** 套过滤；[kind] 为 null 时原样返回（后端顺序即展示顺序）。 */
    fun apply(entries: List<RemoteEntry>, kind: MediaKind?): List<RemoteEntry> =
        if (kind == null) entries else entries.filter { it.isDirectory || MediaKindGuesser.guess(it.name) == kind }

    /** 按媒体类型统计文件数量（目录不计入）；后续首页「最近媒体」等可直接复用。 */
    fun countByKind(entries: List<RemoteEntry>): Map<MediaKind, Int> =
        entries.asSequence()
            .filterNot { it.isDirectory }
            .groupingBy { MediaKindGuesser.guess(it.name) }
            .eachCount()
}
