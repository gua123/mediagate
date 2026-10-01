package io.github.gua123.mediagate.feature.asrmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.media.asr.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「语音识别模型」的状态机（**R14**）。
 *
 * 补齐了之前"后端齐备、界面全无"的缺口：档位列表、下载（进度/断点续传/取消）、删除、选中。
 * 所有 IO 走注入的 [io]；[scope] 只给单测注入，真机用 viewModelScope。
 */
class AsrModelViewModel(
    private val environment: AsrModelEnvironment,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _state = MutableStateFlow(AsrModelUiState(mirrorEnabled = environment.defaultMirror != null))

    /** 页面唯一状态源。 */
    val state: StateFlow<AsrModelUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refresh()
        // 选中的档位可能被任务中心那边改，这里跟着走
        workScope.launch {
            environment.selectedId.collect { id ->
                _state.update { current ->
                    current.copy(items = current.items.map { it.copy(selected = it.id == id) })
                }
            }
        }
    }

    /** 重新扫一遍模型目录（进页面 / 下载完成 / 删除后调用）。 */
    fun refresh() {
        workScope.launch {
            val installed = withContext(io) { environment.installedIds().toSet() }
            val usedBytes = withContext(io) { environment.usedBytes() }
            val selected = environment.selectedId.value
            _state.update { current ->
                current.copy(
                    items = environment.models().map { model ->
                        val previous = current.items.firstOrNull { it.id == model.id }
                        AsrModelItemUi(
                            id = model.id,
                            label = model.label,
                            sizeMb = model.sizeMb,
                            installed = model.id in installed,
                            selected = model.id == selected,
                            downloading = previous?.downloading,
                            failed = previous?.failed,
                        )
                    },
                    usedMb = usedBytes / (1024L * 1024L),
                )
            }
        }
    }

    /** 切换下载源（国内镜像 / 直连官方）。 */
    fun setMirrorEnabled(enabled: Boolean) {
        _state.update { it.copy(mirrorEnabled = enabled) }
    }

    /** 下载某个档位（已有 .part 会自动续传）。 */
    fun download(id: String) {
        if (_state.value.busy) return
        val model = environment.models().firstOrNull { it.id == id } ?: return
        markItem(id) { it.copy(downloading = DownloadProgress(0L, model.sizeBytes), failed = null) }
        downloadJob = workScope.launch {
            try {
                val mirror = if (_state.value.mirrorEnabled) environment.defaultMirror else null
                withContext(io) {
                    environment.download(model, mirror) { progress ->
                        markItem(id) { it.copy(downloading = progress) }
                    }
                }
                markItem(id) { it.copy(downloading = null, installed = true, failed = null) }
                _state.update { it.copy(notice = model.label + " 下载完成") }
                refresh()
            } catch (e: CancellationException) {
                markItem(id) { it.copy(downloading = null, failed = "已取消（已下载的部分保留，可继续）") }
                throw e
            } catch (e: Exception) {
                markItem(id) { it.copy(downloading = null, failed = ErrorText.of(e, "下载失败")) }
            }
        }
    }

    /** 取消下载（保留断点）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /** 删除某个档位（含断点残留）。 */
    fun delete(id: String) {
        val model = environment.models().firstOrNull { it.id == id } ?: return
        workScope.launch {
            val deleted = withContext(io) { environment.delete(model) }
            markItem(id) { it.copy(installed = false, downloading = null, failed = null) }
            _state.update { it.copy(notice = if (deleted) model.label + " 已删除" else "没有可删除的模型文件") }
            refresh()
        }
    }

    /** 把某个档位设为当前使用。 */
    fun select(id: String) {
        val model = environment.models().firstOrNull { it.id == id } ?: return
        workScope.launch {
            withContext(io) { environment.select(model.id) }
            _state.update { current ->
                current.copy(
                    items = current.items.map { it.copy(selected = it.id == model.id) },
                    notice = "已切换到 " + model.label,
                )
            }
        }
    }

    /** 消费一次性提示。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 就地改某个档位的界面项（进度回调可能很频繁，避免整表重建）。 */
    private fun markItem(id: String, transform: (AsrModelItemUi) -> AsrModelItemUi) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) transform(it) else it })
        }
    }
}
