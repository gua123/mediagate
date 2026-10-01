package io.github.gua123.mediagate.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.download.DownloadException
import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.core.download.FileDownloader
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
 * 设置页「应用更新」的状态机（**R20**）。
 *
 * 流程：检查（拉公开清单比 versionCode）→ 下载（断点续传 + 大小/SHA-256 校验）→
 * **签名证书比对**（防中间人换包）→ 调起系统安装器。
 *
 * 所有 IO 走注入的 [io] 调度器；[scope] 只给单测注入，真机用 viewModelScope。
 */
class UpdateViewModel(
    private val environment: UpdateEnvironment,
    private val checker: UpdateChecker,
    private val downloader: FileDownloader,
    private val source: UpdateSource = UpdateSource(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersionName = environment.currentVersionName,
            currentVersionCode = environment.currentVersionCode,
        ),
    )

    /** 页面唯一状态源。 */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /** 检查更新（拉清单 → 解析 → 比版本号）。 */
    fun check() {
        if (_state.value.busy) return
        _state.update { it.copy(status = UpdateStatus.Checking, notice = null) }
        workScope.launch {
            val result = checker.check(source, environment.currentVersionCode)
            _state.update { current ->
                when (result) {
                    is UpdateCheckResult.Available -> current.copy(status = UpdateStatus.Available(result.manifest))
                    is UpdateCheckResult.UpToDate -> current.copy(
                        status = UpdateStatus.UpToDate(environment.currentVersionName),
                    )
                    is UpdateCheckResult.Failed -> current.copy(status = UpdateStatus.Failed(result.display))
                }
            }
        }
    }

    /** 下载新版本（断点续传；已有 .part 会自动接着下）。 */
    fun download() {
        val manifest = (_state.value.status as? UpdateStatus.Available)?.manifest ?: return
        if (_state.value.busy) return
        val target = environment.downloadTarget(manifest)
        val initial = DownloadProgress(receivedBytes = 0L, totalBytes = manifest.sizeBytes)
        _state.update { it.copy(status = UpdateStatus.Downloading(manifest, initial), notice = null) }

        downloadJob = workScope.launch {
            try {
                val file = withContext(io) {
                    downloader.download(
                        url = manifest.apkUrl,
                        target = target,
                        expectedBytes = manifest.sizeBytes,
                        expectedSha256 = manifest.sha256,
                        headers = source.apkHeaders,
                        onProgress = { progress ->
                            _state.update { it.copy(status = UpdateStatus.Downloading(manifest, progress)) }
                        },
                    )
                }
                verifyAndFinish(manifest, file)
            } catch (e: CancellationException) {
                // 取消不清 .part：下次点下载会从断点续传
                _state.update {
                    it.copy(
                        status = UpdateStatus.Available(manifest),
                        notice = "已取消下载（已下载的部分已保留，可继续）",
                    )
                }
                throw e
            } catch (e: DownloadException) {
                _state.update { it.copy(status = UpdateStatus.Failed(e.message ?: "下载失败")) }
            } catch (e: Exception) {
                _state.update { it.copy(status = UpdateStatus.Failed(ErrorText.of(e, "下载失败"))) }
            }
        }
    }

    /** 取消下载（保留断点）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /** 安装已下载的包（会先比签名证书；Android 最后一步一定是系统安装页）。 */
    fun install() {
        val downloaded = _state.value.status as? UpdateStatus.Downloaded ?: return
        val opened = environment.installApk(downloaded.file)
        _state.update {
            it.copy(
                notice = if (opened) {
                    "已打开系统安装界面，请点「安装」完成更新"
                } else {
                    "没有权限安装应用：请在系统设置里允许本应用「安装未知应用」"
                },
            )
        }
    }

    /** 打开发布页（手动下载出口）。 */
    fun openReleasesPage() {
        environment.openReleasesPage()
    }

    /** 消费一次性提示。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 下载完成后的签名校验：不一致就删包并如实提示（绝不提示"可以安装"）。 */
    private fun verifyAndFinish(manifest: UpdateManifest, file: java.io.File) {
        when (val check = environment.verifySignature(file)) {
            SignatureCheck.Match -> _state.update { it.copy(status = UpdateStatus.Downloaded(manifest, file)) }
            is SignatureCheck.Mismatch -> {
                file.delete()
                _state.update {
                    it.copy(
                        status = UpdateStatus.Failed(
                            "下载的安装包签名与当前应用不一致，已删除（如反复出现请到发布页手动下载）",
                        ),
                    )
                }
            }
            SignatureCheck.Unknown -> {
                file.delete()
                _state.update {
                    it.copy(status = UpdateStatus.Failed("读不出下载包的签名，已删除；请到发布页手动下载"))
                }
            }
        }
    }
}
