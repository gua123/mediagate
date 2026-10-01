package io.github.gua123.mediagate.feature.asrmodel

import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.media.asr.WhisperModel
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个模型档位在界面上的样子（**R14** 模型管理）。
 *
 * @param installed 文件在不在（完整性由识别前的体检查，见 ModelManager.verify）。
 * @param downloading 非 null = 正在下载（带进度与断点续传标记）。
 * @param failed 该档位最近一次失败的中文原因；成功或重新开始下载时清空。
 */
data class AsrModelItemUi(
    val id: String,
    val label: String,
    val sizeMb: Long,
    val installed: Boolean,
    val selected: Boolean,
    val downloading: DownloadProgress? = null,
    val failed: String? = null,
) {

    /** 正在下载。 */
    val busy: Boolean get() = downloading != null

    /** 0..100。 */
    val percent: Int get() = downloading?.percent ?: 0
}

/** 模型管理区块的界面状态（单一状态源）。 */
data class AsrModelUiState(
    val items: List<AsrModelItemUi> = emptyList(),
    val usedMb: Long = 0L,
    val mirrorEnabled: Boolean = true,
    val notice: String? = null,
) {

    /** 有档位正在下载（同时只允许一个，避免把带宽与磁盘 IO 打架）。 */
    val busy: Boolean get() = items.any { it.busy }

    /** 正在下载的档位 id。 */
    val downloadingId: String? get() = items.firstOrNull { it.busy }?.id

    /** 已装好的档位 id。 */
    val installedIds: List<String> get() = items.filter { it.installed }.map { it.id }
}

/**
 * 模型管理需要的宿主能力（**R14**）——由 :app 实现（它才有 ModelManager 与设置存储）。
 *
 * 挡在接口后面，:feature:asr-model 就能在 JVM 单测里用假实现把状态机跑全：
 * 下载进度 → 完成 / 失败 / 取消保留断点 → 删除 → 选中。
 */
interface AsrModelEnvironment {

    /** 可下载的档位（顺序即界面顺序）。 */
    fun models(): List<WhisperModel>

    /** 已装好的档位 id。 */
    fun installedIds(): List<String>

    /** 模型目录已占用字节数。 */
    fun usedBytes(): Long

    /** 当前选中的档位 id（跟随设置变化）。 */
    val selectedId: StateFlow<String>

    /** 选中档位（落盘）。 */
    suspend fun select(id: String)

    /** 删除档位（含断点残留），返回是否真的删掉了东西。 */
    suspend fun delete(model: WhisperModel): Boolean

    /**
     * 下载模型（挂起；取消时 .part 保留，下次接着传）。
     *
     * @param mirror 镜像前缀；null = 直连官方。
     */
    suspend fun download(model: WhisperModel, mirror: String?, onProgress: (DownloadProgress) -> Unit)

    /** 默认镜像前缀（国内加速）；null = 默认直连。 */
    val defaultMirror: String?
}
