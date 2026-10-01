package io.github.gua123.mediagate.media.asr

/**
 * whisper.cpp 的模型档位（**M7-B / R14**，plan 4.7 B：tiny / base / small，默认 small）。
 *
 * **不内置进 APK**（最大的 small 有 466 MB）：App 内下载是默认方式（见 [ModelDownloader]），
 * 从本地/远端连接导入是备用方式（见 [ModelManager.importFrom]）。模型统一放
 * [DIRECTORY_NAME] 目录（:app 传 filesDir/models）。
 *
 * 关于 [sha256]：whisper.cpp 官方只发布模型文件本身、没有随包发布校验和，所以这里**不编造哈希值**——
 * [sha256] 为 null 时，下载校验退化成「字节数必须与 [sizeBytes] 完全一致」（足以挡住截断、
 * 断点续传错位、拿到 HTML 错误页这类问题）。真机第一次下载完成后，把官方值填进对应档位即可
 * 自动升级为强校验，[ModelDownloader] 的校验分支已经写好并有单测覆盖。
 *
 * @property id 稳定标识（入库/设置项用，如 "small"）。
 * @property label 界面展示名（全中文，R16）。
 * @property fileName 落盘文件名。
 * @property sizeBytes 期望字节数（**精确值**，来自官方发布的模型大小）。
 * @property url 官方下载地址（HuggingFace）。
 * @property sha256 期望的 SHA-256（小写十六进制）；null = 只校验字节数。
 */
data class WhisperModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val url: String,
    val sha256: String? = null,
) {

    /** 约等于多少 MB（界面展示）。 */
    val sizeMb: Long get() = sizeBytes / (1024L * 1024L)

    /**
     * 下载地址（可换镜像主机，国内直连 HuggingFace 常常很慢）。
     *
     * 镜像约定 = **替换主机**：给 `https://hf-mirror.com` 就得到
     * `https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/…`。
     * 2026-10-03 实测：老的前缀式镜像 `https://gh-proxy.com/<原始 URL>` 已经 404，
     * 而 hf-mirror（429 KB/s）与直连 HuggingFace（走代理 505 KB/s）都可用。
     */
    fun downloadUrl(mirror: String? = null): String {
        val base = mirror?.trim()?.trimEnd('/').orEmpty()
        if (base.isEmpty() || !url.startsWith(HF_HOST)) return url
        return base + url.removePrefix(HF_HOST)
    }

    /** 换一个校验和（第一次真机下载拿到官方 SHA-256 后回填）。 */
    fun withChecksum(sha256: String?): WhisperModel = copy(sha256 = sha256?.lowercase())

    /** 是否需要强校验。 */
    val hasChecksum: Boolean get() = !sha256.isNullOrBlank()

    companion object {

        /** 模型目录名（filesDir/ 下）。 */
        const val DIRECTORY_NAME = "models"

        /** 断点续传的临时后缀（下载完成后改名去掉它）。 */
        const val PART_SUFFIX = ".part"

        /** 国内镜像主机（替换 HuggingFace 主机；实测比直连稳）。 */
        const val DEFAULT_MIRROR = "https://hf-mirror.com"

        /** HuggingFace 主机（镜像替换的目标）。 */
        private const val HF_HOST = "https://huggingface.co"

        private const val BASE_URL = HF_HOST + "/ggerganov/whisper.cpp/resolve/main/"

        /** tiny：约 74 MB，最快、最不准（只适合试跑）。 */
        val TINY = WhisperModel(
            id = "tiny",
            label = "tiny（最快，约 74 MB）",
            fileName = "ggml-tiny.bin",
            sizeBytes = 77_691_713L,
            url = BASE_URL + "ggml-tiny.bin",
            sha256 = CHECKSUM_TINY,
        )

        /** base：约 141 MB，速度与准确率的折中（plan 4.7 B 的「慢可一键换 base」）。 */
        val BASE = WhisperModel(
            id = "base",
            label = "base（均衡，约 141 MB）",
            fileName = "ggml-base.bin",
            sizeBytes = 147_951_465L,
            url = BASE_URL + "ggml-base.bin",
            sha256 = CHECKSUM_BASE,
        )

        /** small：约 466 MB，**默认**（用户口径「要准」，plan 4.7 B）。 */
        val SMALL = WhisperModel(
            id = "small",
            label = "small（默认，最准，约 466 MB）",
            fileName = "ggml-small.bin",
            sizeBytes = 487_601_967L,
            url = BASE_URL + "ggml-small.bin",
            sha256 = CHECKSUM_SMALL,
        )

        // ---- 官方模型的 SHA-256（2026-10-03 实测：从官方 HuggingFace 仓库下载三份模型后逐份计算，
        //      字节数与上面的 sizeBytes 完全一致；有了它，下载校验从"只核大小"升级为强校验）。
        private const val CHECKSUM_TINY = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        private const val CHECKSUM_BASE = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
        private const val CHECKSUM_SMALL = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"

        /** 全部档位（顺序 = 界面展示顺序）。 */
        val ALL: List<WhisperModel> = listOf(TINY, BASE, SMALL)

        /** 默认档位：small。 */
        val DEFAULT: WhisperModel = SMALL

        /** 按 id 取档位；不认识/null 用 [DEFAULT]（界面永远有东西可用，不崩）。 */
        fun of(id: String?): WhisperModel = ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** 按文件名取档位（从库里恢复任务时用）；不是已知模型返回 null。 */
        fun ofFileName(fileName: String): WhisperModel? = ALL.firstOrNull { it.fileName == fileName }
    }
}
