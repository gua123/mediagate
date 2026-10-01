package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.core.download.DownloadException
import io.github.gua123.mediagate.core.download.DownloadProgress
import java.io.File

/** 模型体检结果（**R14**：下载前/识别前都要知道手上这份模型到底能不能用）。 */
enum class ModelVerifyResult(val zhText: String) {

    /** 文件在、字节数对、（有校验和时）校验和也对。 */
    OK("可用"),

    /** 文件不在。 */
    MISSING("模型未下载"),

    /** 字节数与官方大小不符（下载不完整）。 */
    SIZE_MISMATCH("模型文件不完整，请重新下载"),

    /** 校验和不符。 */
    CHECKSUM_MISMATCH("模型校验和不符，请重新下载"),
    ;

    /** 能不能直接拿去识别。 */
    val usable: Boolean get() = this == OK
}

/** 从本地文件导入模型的结果（**R14 的备用获取方式**）。 */
sealed interface ModelImportResult {

    /** 导入成功。 */
    data class Imported(val model: WhisperModel, val path: String) : ModelImportResult

    /** 源文件不合法（不存在/大小不符）。 */
    data class Rejected(val reason: String) : ModelImportResult
}

/**
 * 模型管理（**M7-B / R14**）：查已安装、删除、体检、导入、下载编排。
 *
 * 与 [ModelDownloader] 分开：下载是「网络 + 续传 + 校验」的一套流程，管理是「这个目录里现在
 * 有什么、能不能用」的查询与文件操作。前者要假 HTTP 才能测，后者用临时目录就能测。
 *
 * @param store 模型目录（:app 用 filesDir/models）。
 * @param downloader 下载器；只做查询/导入时可以不给（[download] 会报错提示）。
 */
class ModelManager(
    private val store: ModelStore,
    private val downloader: ModelDownloader? = null,
) {

    /** 已知档位里已经装好的（按 [WhisperModel.ALL] 的顺序）。 */
    fun installed(): List<WhisperModel> = WhisperModel.ALL.filter { isInstalled(it) }

    /** 这份档位是否已经装好（只看文件在不在，不看校验——体检用 [verify]）。 */
    fun isInstalled(model: WhisperModel): Boolean = store.exists(model.fileName)

    /** 已装好的档位 id 列表（设置页展示用）。 */
    fun installedIds(): List<String> = installed().map { it.id }

    /** 模型绝对路径；没装返回 null。 */
    fun fileOf(model: WhisperModel): File? {
        val path = store.pathOf(model.fileName) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    /** 删除模型（幂等）。同时清掉可能残留的 .part。 */
    fun delete(model: WhisperModel): Boolean {
        store.delete(model.fileName + WhisperModel.PART_SUFFIX)
        return store.delete(model.fileName)
    }

    /**
     * 体检：文件在不在、字节数对不对、（有校验和时）校验和对不对。
     *
     * 识别前调一次能省掉「跑到一半才发现模型是坏的」。
     */
    fun verify(model: WhisperModel): ModelVerifyResult {
        if (!store.exists(model.fileName)) return ModelVerifyResult.MISSING
        val size = store.size(model.fileName)
        if (size != model.sizeBytes) return ModelVerifyResult.SIZE_MISMATCH
        val expected = model.sha256?.lowercase()?.takeIf { it.isNotBlank() } ?: return ModelVerifyResult.OK
        val actual = store.sha256(model.fileName) ?: return ModelVerifyResult.MISSING
        return if (actual.equals(expected, ignoreCase = true)) ModelVerifyResult.OK else ModelVerifyResult.CHECKSUM_MISMATCH
    }

    /**
     * 从本地文件导入模型（**plan 4.7 B 的备用方式**：从连接里把模型文件拷进来）。
     *
     * 校验口径与下载完全一致：字节数必须等于官方大小（有校验和时再核一遍），
     * 不合格直接拒绝并给中文原因——绝不把一个坏模型放进目录里让后续识别莫名其妙地失败。
     */
    fun importFrom(source: File, model: WhisperModel): ModelImportResult {
        if (!source.isFile) return ModelImportResult.Rejected("源文件不存在：" + source.absolutePath)
        val length = source.length()
        if (length != model.sizeBytes) {
            return ModelImportResult.Rejected(
                "文件大小不符（" + length + " 字节，预期 " + model.sizeBytes + " 字节），可能选错了档位或文件不完整",
            )
        }
        val path = runCatching {
            store.openWrite(model.fileName, append = false).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            store.pathOf(model.fileName)
        }.getOrNull() ?: return ModelImportResult.Rejected("写入模型目录失败")

        val result = verify(model)
        if (!result.usable) {
            delete(model)
            return ModelImportResult.Rejected(result.zhText)
        }
        return ModelImportResult.Imported(model, path)
    }

    /**
     * 下载模型（转交 [ModelDownloader]）。
     *
     * @throws DownloadException 分类失败。
     * @throws IllegalStateException 没装配下载器。
     */
    suspend fun download(
        model: WhisperModel,
        mirror: String? = WhisperModel.DEFAULT_MIRROR,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        val engine = downloader ?: error("没有装配下载器，无法下载模型")
        val name = engine.download(model, mirror, onProgress)
        return store.pathOf(name)?.let { File(it) } ?: error("下载完成但拿不到模型路径")
    }

    /** 模型目录里已经占用的字节数（设置页展示「已占用 xxx MB」）。 */
    fun usedBytes(): Long = store.names().sumOf { name -> store.size(name).coerceAtLeast(0L) }
}
