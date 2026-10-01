package io.github.gua123.mediagate.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.SurfaceView
import androidx.core.content.FileProvider
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.crypto.CredentialCipher
import io.github.gua123.mediagate.core.crypto.KeystoreCredentialCipher
import io.github.gua123.mediagate.core.database.MediaGateDatabase
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.core.network.AddressSelector
import io.github.gua123.mediagate.core.network.AndroidNetworkMonitor
import io.github.gua123.mediagate.core.network.NetworkContext
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.ftp.FtpStorageBackend
import io.github.gua123.mediagate.data.storage.local.LocalBackends
import io.github.gua123.mediagate.data.storage.sftp.HostKeyVerifier
import io.github.gua123.mediagate.data.storage.sftp.PersistentKnownHostsStore
import io.github.gua123.mediagate.data.storage.sftp.KnownHostsStore
import io.github.gua123.mediagate.data.storage.sftp.SftpHostKeyPolicy
import io.github.gua123.mediagate.data.storage.sftp.SftpStorageBackend
import io.github.gua123.mediagate.data.storage.sftp.TofuHostKeyVerifier
import io.github.gua123.mediagate.data.storage.webdav.WebDavConfig
import io.github.gua123.mediagate.data.storage.webdav.WebDavStorageBackend
import io.github.gua123.mediagate.feature.browser.BrowserEnvironment
import io.github.gua123.mediagate.feature.browser.BrowserRootState
import io.github.gua123.mediagate.feature.browser.RootModeKind
import io.github.gua123.mediagate.feature.connections.ConnectionBackends
import io.github.gua123.mediagate.feature.connections.ConnectionEndpoints
import io.github.gua123.mediagate.feature.connections.ConnectionRecord
import io.github.gua123.mediagate.feature.connections.ConnectionRepository
import io.github.gua123.mediagate.feature.connections.ConnectionsEnvironment
import io.github.gua123.mediagate.feature.player.audio.AudioPlaybackSnapshot
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerEnvironment
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerMath
import io.github.gua123.mediagate.feature.player.audio.AudioRepeatMode
import io.github.gua123.mediagate.feature.viewer.image.ImageTooLargeException
import io.github.gua123.mediagate.feature.viewer.image.ImageViewerEnvironment
import io.github.gua123.mediagate.feature.viewer.image.MAX_VIEWER_IMAGE_BYTES
import io.github.gua123.mediagate.feature.player.video.SubtitleHost
import io.github.gua123.mediagate.feature.player.video.VideoPipActionSink
import io.github.gua123.mediagate.feature.player.video.VideoPipHost
import io.github.gua123.mediagate.feature.player.video.VideoPlaybackHost
import io.github.gua123.mediagate.feature.player.video.VideoPlayerEnvironment
import io.github.gua123.mediagate.feature.player.video.VideoPlayerMath
import io.github.gua123.mediagate.feature.player.video.VideoPlayerPreferences
import io.github.gua123.mediagate.feature.settings.SettingsConnectionUi
import io.github.gua123.mediagate.feature.viewer.image.ViewerMath
import io.github.gua123.mediagate.feature.tasks.TasksEnvironment
import io.github.gua123.mediagate.feature.tasks.TasksRoot
import io.github.gua123.mediagate.feature.asrmodel.AsrModelEnvironment
import io.github.gua123.mediagate.feature.browser.EntrySort
import io.github.gua123.mediagate.feature.player.video.TsIndexInfo
import io.github.gua123.mediagate.feature.player.video.TimestampRepairOutcome
import io.github.gua123.mediagate.feature.update.SignatureCheck
import io.github.gua123.mediagate.feature.update.UpdateEnvironment
import io.github.gua123.mediagate.feature.update.UpdateManifest
import io.github.gua123.mediagate.feature.update.UpdateSource
import io.github.gua123.mediagate.media.asr.AsrEngine
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.AsrYieldSettings
import io.github.gua123.mediagate.media.asr.FileModelStore
import io.github.gua123.mediagate.media.asr.FfmpegPcmProvider
import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.core.download.HttpTransport
import io.github.gua123.mediagate.core.download.HttpUrlConnectionTransport
import io.github.gua123.mediagate.media.asr.ModelDownloader
import io.github.gua123.mediagate.media.asr.ModelManager
import io.github.gua123.mediagate.media.asr.ModelStore
import io.github.gua123.mediagate.media.asr.PlaybackYieldGate
import io.github.gua123.mediagate.media.asr.WhisperAsrEngine
import io.github.gua123.mediagate.media.asr.WhisperModel
import io.github.gua123.mediagate.media.ffmpeg.FfmpegKitRunner
import io.github.gua123.mediagate.media.ffmpeg.FfmpegProgress
import io.github.gua123.mediagate.media.ffmpeg.TimestampRepair
import io.github.gua123.mediagate.media.ffmpeg.TimestampRepairResult
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.ExoPlayerEngine
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.VlcEngine
import io.github.gua123.mediagate.media.playback.BackendDataSourceFactory
import io.github.gua123.mediagate.media.proxy.LoopbackHttpProxy
import io.github.gua123.mediagate.media.thumbnail.EmbeddedArtworkExtractor
import io.github.gua123.mediagate.media.thumbnail.FfmpegFrameExtractor
import io.github.gua123.mediagate.media.thumbnail.ImagePreviewPipeline
import io.github.gua123.mediagate.media.thumbnail.ImageThumbnailExtractor
import io.github.gua123.mediagate.media.thumbnail.MediaMetadataRetrieverFrameExtractor
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
import io.github.gua123.mediagate.media.thumbnail.ThumbnailCache
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository
import io.github.gua123.mediagate.media.tsext.openRandomAccessSource
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 手写 DI 容器（不引 Hilt）——`:app` 的应用级单例，由 [io.github.gua123.mediagate.MediaGateApplication]
 * 在 `onCreate` 创建并持有。
 *
 * 它负责四件事（R12 / R5 / R2 / R1）：
 * 1. **根目录设置**：[RootSettings] 用 DataStore Preferences 持久化「模式 + 路径/树 URI」，
 *    并对外暴露 [rootConfig]（首页展示用）；
 * 2. **当前根目录的后端**：配置一变就创建新的 [StorageBackend]
 *    （SAF → `SafStorageBackend`；全盘 → `FileStorageBackend`），并关闭旧实例，
 *    以 [BrowserEnvironment.root] 的形式提供给 :feature:browser；
 * 3. **缩略图仓库**：懒加载 [ThumbnailRepository]（两级缓存 + MMR 主策略 + FFmpeg 兜底 +
 *    M1-F 的音频内嵌封面与图片缩略图两条分支）；
 * 4. **图片查看器**（M1-F，R1）：实现 [ImageViewerEnvironment]，复用同一个当前后端
 *    给 :feature:viewer-image 列同目录图片、按上限读取整张图片；
 * 5. **音频播放**（M1-G，R1/R18）：实现 [AudioPlayerEnvironment]，用 [AudioSessionController]
 *    连上 :media:playback 的 MediaSessionService（MediaController），给 :feature:player-audio
 *    提供「当前后端 + 队列 + 播放状态 + 命令」，并持有断点续播存储（R18）。
 *
 * 权限动作（拉起 SAF 选择器 / 跳「所有文件访问」设置页）也收在这里，页面只调方法，
 * 不各自拼 Intent。所有耗时动作都跑在 [ioScope] 或 `Dispatchers.IO` 上。
 */
class AppContainer(context: Context) :
    BrowserEnvironment,
    ImageViewerEnvironment,
    AudioPlayerEnvironment,
    AsrRuntimeHost {

    private val appContext: Context = context.applicationContext

    /** 应用级协程作用域：只做「订阅配置 → 换后端」这类长生命周期的小任务。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings = RootSettings(appContext)

    /** 「当前连接」（R8）的持久化：只存一个 id，连接本体在三张表里。 */
    private val connectionSettings = ConnectionSettings(appContext)

    // ------------------------------------------------------------ 连接管理（R2/R6/R7/R8）
    // 四协议都真正接进 App：本地双模式（R12）、WebDAV、SFTP、FTP —— 见 applyCurrentConnection

    /**
     * 连接/地址/规则三张表的数据库（plan 第 7 章）。
     *
     * 懒加载：冷启动不做任何数据库 IO，只有进连接页（或选中了某个连接）才建库。
     * 建库时带上 [MediaGateDatabase.MIGRATIONS]，v1 → v2 只是新增 `network_rule` 表，旧数据原样保留。
     */
    private val database: MediaGateDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(appContext, MediaGateDatabase::class.java, MediaGateDatabase.NAME)
            .addMigrations(*MediaGateDatabase.MIGRATIONS)
            .build()
    }

    /** 凭据加解密（R6）：Android Keystore + AES-GCM，密码只以密文进 `secretRef`。 */
    private val credentialCipher: CredentialCipher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeystoreCredentialCipher(appContext)
    }

    /** 连接仓储（R8）：三张表的读写 + 密码加解密。 */
    private val connectionRepository: ConnectionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConnectionRepository(database, credentialCipher)
    }

    /**
     * 网络现场监听（R7，plan 4.5 的 `NetworkCallback`）。
     *
     * 懒加载 + [AndroidNetworkMonitor.start]：只有真的要看连接/选路时才注册回调，
     * 冷启动不占用系统回调名额。
     */
    private val networkMonitorLazy: Lazy<AndroidNetworkMonitor> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AndroidNetworkMonitor(appContext).also { it.start() }
        }

    private val networkMonitor: AndroidNetworkMonitor by networkMonitorLazy

    /** 当前网络现场（R7：网络能力是判定顺序的第一环）。 */
    val networkContext: StateFlow<NetworkContext> get() = networkMonitor.context

    /** 当前连接 id（R8）；null = 用首页选择的本地根目录。 */
    private val currentConnectionId: StateFlow<Long?> =
        connectionSettings.currentId.stateIn(ioScope, SharingStarted.Eagerly, null)

    /**
     * 设置页展示用的「当前连接」（R7/R8）：连接名 + 协议 + 当前网络下的首选地址与判定依据。
     *
     * 与浏览器真正用的后端同源（同一份记录、同一个 [AddressSelector]），所以设置页写的就是
     * 播放器实际会连的地址。
     */
    val settingsConnection: StateFlow<SettingsConnectionUi> =
        combine(
            currentConnectionId,
            connectionRepository.connections,
            networkMonitor.context,
        ) { id, records, network ->
            val record = records.firstOrNull { it.id == id }
            if (record == null) {
                SettingsConnectionUi()
            } else {
                val selection = AddressSelector.select(record.selectableAddresses(), network, record.networkRules())
                SettingsConnectionUi(
                    name = record.name,
                    protocolText = record.protocolText,
                    primaryAddress = selection.primary?.display,
                    selectionReason = selection.explanation,
                    browsable = record.browsable,
                )
            }
        }.stateIn(ioScope, SharingStarted.Eagerly, SettingsConnectionUi())

    /**
     * 连接管理页的宿主能力（R6/R7/R8）——由 :app 实现，页面只认接口。
     *
     * 用内部类而不是让 [AppContainer] 直接实现：容器里已经有同名的私有属性
     * （database / currentConnectionId / networkContext），直接实现会让"对外能力"和"内部字段"搅在一起。
     */
    val connectionsEnvironment: ConnectionsEnvironment = ConnectionsHost()

    private inner class ConnectionsHost : ConnectionsEnvironment {

        override val database: MediaGateDatabase get() = this@AppContainer.database

        override val cipher: CredentialCipher get() = this@AppContainer.credentialCipher

        override val currentConnectionId: StateFlow<Long?> get() = this@AppContainer.currentConnectionId

        override suspend fun setCurrentConnection(id: Long?) {
            connectionSettings.setCurrent(id)
        }

        override val networkContext: StateFlow<NetworkContext> get() = this@AppContainer.networkContext
    }

    /**
     * 当前根目录配置（首页展示模式 + 路径；null = 未选择）。
     *
     * 选中本地连接（LOCAL）时会把连接里的目录写回 [RootSettings]（见
     * [applyLocalConnectionRoot]），所以这里始终是最新的本地根目录，首页不会显示过期信息。
     */
    val rootConfig: StateFlow<RootConfig?> =
        settings.config.stateIn(ioScope, SharingStarted.Eagerly, null)

    private val _root = MutableStateFlow<BrowserRootState?>(null)

    /** 浏览器看到的当前根目录（R12 本地双模式 + R8 远端连接）；配置或连接变化后自动换成新的后端实例。 */
    override val root: StateFlow<BrowserRootState?> = _root.asStateFlow()

    /** 本地根目录后端（R12，来自 [RootSettings]）；远端连接生效时它作为备胎保留。 */
    private var localRoot: BrowserRootState? = null

    /** 当前远端连接的后端（**R2**：WebDAV / SFTP / FTP 三种连接共用这一个槽位）。 */
    private var remoteRoot: BrowserRootState? = null

    /** 远端后端的构造指纹：选路结果、根路径、账号、密码有无变化时才重建。 */
    private var remoteSignature: String? = null

    private val _remoteNotice = MutableStateFlow<String?>(null)

    /**
     * 远端连接切不过去时的中文提示（缺密码 / 配置不合法 / 当前网络没有可用地址）。
     *
     * 界面弹一条 Snackbar 后调 [dismissRemoteNotice] 消费掉；成功切过去会自动清空。
     * 有它才不会出现"点过设为当前连接、目录还是本地那套"的哑巴状态。
     */
    val remoteNotice: StateFlow<String?> = _remoteNotice.asStateFlow()

    /** 关掉一次性提示（Snackbar 消费完调用）。 */
    fun dismissRemoteNotice() {
        _remoteNotice.value = null
    }

    // ------------------------------------------------------------ 崩溃诊断（2026-10-03）

    /**
     * 最近一次崩溃的报告全文；没有崩溃过返回 null。
     *
     * 设置页「诊断」卡片直接显示它——自用 sideload 拿不到 logcat，用户把这段粘给我就能定位。
     */
    fun readLatestCrash(): String? = runCatching {
        CrashReporter.latest(appContext)?.readText(Charsets.UTF_8)
    }.getOrNull()

    /** 最近一次崩溃的时间（毫秒）；没有返回 null。 */
    fun latestCrashTimeMs(): Long? = runCatching { CrashReporter.latest(appContext)?.lastModified() }.getOrNull()

    /** 崩溃报告份数。 */
    fun crashReportCount(): Int = runCatching { CrashReporter.reports(appContext).size }.getOrDefault(0)

    /** 清空崩溃报告。 */
    fun clearCrashes() {
        CrashReporter.clear(appContext)
    }

    private val _remoteRootLabel = MutableStateFlow<String?>(null)

    /**
     * 当前远端连接的展示名（如「homedev · 局域网」）；null = 没有远端连接（用本地根目录）。
     *
     * 首页据此把话说清楚：**远端优先**是既有口径（[publishRoot] 里远端后端优先），
     * 但界面原先不体现，用户会以为自己在看本地目录（验收记录里的"文案未统一"）。
     */
    val remoteRootLabel: StateFlow<String?> = _remoteRootLabel.asStateFlow()

    /**
     * SFTP 已知主机指纹（plan 4.9 的 TOFU 落点）。
     *
     * **落库**（2026-10-03 补上，R2）：写穿到 Room 的 `sftp_host_key` 表，
     * 于是"重启后仍认识旧指纹"成立——否则一个被换掉的密钥在冷启动后会被当成新主机按 TOFU 放行，
     * 变更检测等于没做（[TofuHostKeyVerifier] 的规则：变更绝不静默接受）。
     * 换地址 / 重建后端也共享同一份，指纹变了照样拒绝并告警。
     */
    private val knownHosts: KnownHostsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PersistentKnownHostsStore(RoomHostKeyPersistence(database.hostKeyDao()))
    }

    private val sftpHostKeys: HostKeyVerifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TofuHostKeyVerifier(
            store = knownHosts,
            policy = SftpHostKeyPolicy.TOFU,
            onChange = { event -> AppLog.w(TAG, event.message) },
        )
    }

    /**
     * 已信任的 SFTP 主机指纹（设置页「安全」区块）。
     *
     * 为什么必须有这个出口：TOFU 一旦落库，**服务器真的换了密钥时用户会被永久拒之门外**
     * （拒绝是正确行为，但总得给一条"我看过新指纹，确认是服务器换钥"的路）。
     */
    val trustedHostKeys: StateFlow<List<TrustedHostKey>> =
        database.hostKeyDao().observeAll()
            .map { rows -> rows.map { TrustedHostKey(it.host, it.port, it.keyType, it.sha256, it.md5) } }
            .stateIn(ioScope, SharingStarted.Eagerly, emptyList())

    /** 忘掉某台主机的指纹（下次连接按 TOFU 重新记）。 */
    fun forgetHostKey(host: String, port: Int) {
        knownHosts.remove(host, port)
    }

    /** 忘掉全部（换机 / 大批服务器换钥时用）。 */
    fun forgetAllHostKeys() {
        trustedHostKeys.value.forEach { knownHosts.remove(it.host, it.port) }
    }

    private val _allFilesGranted = MutableStateFlow(hasAllFilesAccess())

    /** 是否已获得「所有文件访问」权限（首页按钮据此决定「去开启」还是「使用」）。 */
    val allFilesGranted: StateFlow<Boolean> = _allFilesGranted.asStateFlow()

    /**
     * 缩略图仓库（R5，plan 4.4）。
     *
     * 懒加载：只有真正进浏览页才建缓存目录与抽帧器；缓存目录落在 App 缓存内（系统可回收）。
     *
     * M1-F 补齐两条分支：音频走 MMR 内嵌封面（[EmbeddedArtworkExtractor]），
     * 图片走「解码 + 按目标宽度重编码」（[ImageThumbnailExtractor]）；
     * [ImagePreviewPipeline] 把编码格式一起带上，缓存文件扩展名才与实际内容一致。
     */
    /**
     * 列表排序（2026-10-03 用户要求）：DataStore 持久化，冷启动即生效。
     *
     * 用 stateIn + Eagerly：浏览页可能在设置读出来之前就组合了，先给默认值（名称升序）不会闪空。
     */
    override val sort: StateFlow<EntrySort> =
        settings.sort.stateIn(ioScope, SharingStarted.Eagerly, EntrySort())

    /** 改排序并落盘。 */
    override suspend fun setSort(sort: EntrySort) {
        settings.setSort(sort)
    }

    override val thumbnails: ThumbnailRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val imagePreview = ImageThumbnailExtractor()
        ThumbnailRepository(
            cache = ThumbnailCache(rootDir = File(appContext.cacheDir, THUMBNAIL_CACHE_DIR)),
            primary = MediaMetadataRetrieverFrameExtractor(),
            fallback = FfmpegFrameExtractor(workDir = File(appContext.cacheDir, THUMBNAIL_WORK_DIR)),
            audioArtwork = EmbeddedArtworkExtractor(),
            imagePreview = ImagePreviewPipeline(extractor = imagePreview, format = imagePreview.format),
        )
    }

    // ------------------------------------------------------------ 音频播放（M1-G，R1/R18）

    /** 当前后端流：音频页取封面、把路径编成 MediaItem 都用它（换根目录自动跟随）。 */
    private val audioBackend: StateFlow<StorageBackend?> =
        _root.map { it?.backend }.stateIn(ioScope, SharingStarted.Eagerly, null)

    /**
     * MediaController 连接与状态映射。
     *
     * 懒加载：只有真的进音频播放页（或起播）才去 bind 后台服务，冷启动不碰它。
     */
    private val audioSession: AudioSessionController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioSessionController(appContext, audioBackend, thumbnails)
    }

    /**
     * 断点续播存储（R18）：DataStore 实现，由 [io.github.gua123.mediagate.MediaGateApplication]
     * 通过 PlaybackHost 交给 :media:playback 的后台服务读写。
     */
    val playbackProgress: PlaybackProgressStore = PlaybackProgressSettings(appContext)

    /** 播放状态（来自 MediaController，见 [AudioSessionController]）。 */
    override val state: StateFlow<AudioPlaybackSnapshot> get() = audioSession.state

    /**
     * 当前根目录的后端；尚未选择时为 null。
     *
     * 直接给 [audioBackend] 这条流（与 AudioSessionController 内部那份是同一个实例），
     * 而不是走 audioSession：视频播放页也要这个后端，但不该顺手把音频后台服务绑起来。
     */
    override val backend: StateFlow<StorageBackend?> get() = audioBackend

    /**
     * 列出 [path] 所在目录里的音频（同目录队列，R1）。
     *
     * 复用当前后端与 [AudioPlayerMath.audioEntries] 的过滤/排序口径，与浏览页看到的顺序一致。
     *
     * @throws StorageException 列目录失败（无权限 / 不存在 / 网络…），播放页按分类给中文提示。
     */
    override suspend fun audioSiblings(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        AudioPlayerMath.audioEntries(currentBackend().list(parentOf(path), null))
    }

    /** 用给定队列起播（R18）：交给后台服务，多首连播由服务的播放列表承担。 */
    override suspend fun play(paths: List<String>, startIndex: Int) {
        audioSession.play(paths, startIndex)
    }

    override fun playAt(index: Int) {
        audioSession.playAt(index)
    }

    override fun togglePlayPause() {
        audioSession.togglePlayPause()
    }

    override fun seekTo(positionMs: Long) {
        audioSession.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        audioSession.setSpeed(speed)
    }

    override fun setRepeatMode(mode: AudioRepeatMode) {
        audioSession.setRepeatMode(mode)
    }

    // ------------------------------------------------------------ 视频播放（M2-B，R4/R9/R10/R18）

    /**
     * 视频播放页偏好（上次用的内核 + 解码档位，R9/R10）。
     *
     * **在容器构造时就建**（不是懒加载）：DataStore 的第一次读是异步的，早一点开始读，
     * 用户点开视频时拿到的才是上次的选择，而不是"还没读完"的默认值。
     */
    private val videoPreferences: VideoPlayerPreferences =
        VideoPlayerPreferencesSettings(appContext, ioScope)

    /**
     * 字幕宿主（M7-A，R14）：定位器 / 读取解析 / 写回都在 :media:subtitle 里，这里只把它接到
     * **当前后端**上（懒加载：只有真的进播放页选字幕才建）。
     *
     * 后端用 lambda 传（换根目录 / 换连接自动跟随）；无写权限时字幕落到 filesDir/subtitles
     * （App 私有目录，系统不会像 cache 那样清理），播放页提示「已保存到本地，可分享/稍后重试」。
     */
    private val subtitleHost: SubtitleHost by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppSubtitleHost(
            backend = { _root.value?.backend },
            fallbackDir = File(appContext.filesDir, SUBTITLE_FALLBACK_DIR),
        )
    }

    /** 回环代理（plan 4.6）；懒建 + 关闭后可按需重建，见 [requireVideoProxy]。 */
    @Volatile
    private var videoProxy: LoopbackHttpProxy? = null

    /**
     * 视频播放页的宿主能力（M2-B）：由 MediaGateApp 注入 LocalVideoPlayerEnvironment。
     *
     * **为什么不让 AppContainer 直接实现它**：:feature:viewer-image 的 ImageViewerEnvironment
     * 已经有一个同名同签名的 siblings(path)（列同目录图片），而视频侧要列的是同目录视频/音频。
     * JVM 上一个类只能有一份同签名实现，两个接口的语义又不同（viewer-image 属别的模块，不能改），
     * 所以视频这几个能力收进一个内部实现类——:app 依旧是唯一实现方，页面只认接口。
     */
    val videoPlayerEnvironment: VideoPlayerEnvironment = VideoPlayerHost()

    // ------------------------------------------------------------ 时间戳重建（R3/R11）

    /** 修复产物目录（cacheDir 下，系统可回收）；只在播放修复版期间被视频后端指向。 */
    private val timestampRepairDir: File get() = File(appContext.cacheDir, TS_REPAIR_DIR)

    /**
     * 视频专用的"修复根目录"后端（**只影响视频播放页**）。
     *
     * 时间戳重建的产物落在 App 缓存里，不属于任何用户根目录；如果把全局 [publishRoot] 切过去，
     * 浏览页/音频/图片都会跟着看到缓存目录。所以这里单独给视频一条覆盖流：置位时播放页读到的是
     * 修复目录，退出播放页（[VideoPlayerHost.exitRepairRoot]）立刻恢复。
     */
    private val _videoRepairRoot = MutableStateFlow<BrowserRootState?>(null)

    /** TS 索引（R3/R4）：限量扫描 + 落盘缓存 + 摘要（PCR 标记）。 */
    private val tsIndexPreparer: TsIndexPreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TsIndexPreparer(File(appContext.cacheDir, TS_INDEX_DIR))
    }

    private val videoBackend: StateFlow<StorageBackend?> =
        combine(audioBackend, _videoRepairRoot) { normal, repaired -> repaired?.backend ?: normal }
            .stateIn(ioScope, SharingStarted.Eagerly, null)

    /**
     * 视频会话控制器（R18 视频侧 / R19 让路）：把播放页借出的内核包成 MediaSession 的会话源，
     * 并在起播时连上 :media:playback 的 `VideoPlaybackService`（通知栏 / 锁屏可控）。
     */
    val videoSession: VideoSessionController = VideoSessionController(appContext, thumbnails)

    /** 视频播放宿主能力（直接复用容器里的后端流、偏好、断点存储与回环代理）。 */
    private inner class VideoPlayerHost : VideoPlayerEnvironment {

        /** 画中画宿主（R13）：由当前 Activity 的桥实现，页面只认接口。 */
        override val pip: VideoPipHost get() = pipHost

        /** 视频后台播放宿主（R18/R19）：会话 + 让路。 */
        override val playback: VideoPlaybackHost get() = videoSession

        override val backend: StateFlow<StorageBackend?> get() = videoBackend

        override val preferences: VideoPlayerPreferences get() = videoPreferences

        /**
         * 时间戳重建（R3/R11）：当前后端随机读 → FFmpeg `-fflags +genpts -c copy` 重写 → 落缓存，
         * 然后把**视频后端**切到修复目录，播放页 reload 文件名即可续播修复版。
         */
        override suspend fun repairTimestamps(path: String, onProgress: (Int) -> Unit): TimestampRepairOutcome {
            val backend = videoBackend.value ?: return TimestampRepairOutcome.Failed("还没有选择媒体根目录")
            val dir = timestampRepairDir
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, repairedFileNameOf(path))
            return try {
                val repair = TimestampRepair(FfmpegKitRunner(), File(appContext.cacheDir, TS_REPAIR_WORK_DIR))
                val source = backend.openRandomAccessSource(path)
                val result = source.use {
                    repair.repairToFile(it, outFile, onProgress = { progress -> onProgress(percentOf(progress)) })
                }
                when (result) {
                    is TimestampRepairResult.Success -> {
                        _videoRepairRoot.value = BrowserRootState(
                            mode = RootModeKind.NONE,
                            label = appContext.getString(R.string.root_label_timestamp_repair),
                            displayPath = dir.absolutePath,
                            backend = LocalBackends.file(dir),
                        )
                        AppLog.i(TAG, "时间戳重建完成：" + outFile.name)
                        TimestampRepairOutcome.Repaired(outFile.name)
                    }

                    is TimestampRepairResult.Failure -> {
                        runCatching { outFile.delete() }
                        TimestampRepairOutcome.Failed(result.message)
                    }
                }
            } catch (e: CancellationException) {
                runCatching { outFile.delete() }
                throw e
            } catch (t: Throwable) {
                runCatching { outFile.delete() }
                AppLog.w(TAG, "时间戳重建失败：" + path, t)
                TimestampRepairOutcome.Failed(ErrorText.of(t, "时间戳重建失败"))
            }
        }

        /**
         * 准备 TS 索引（R3/R4）：后台限量扫，用于"无 PCR 提示"与拖拽落点预取。
         */
        override suspend fun prepareTsIndex(path: String): TsIndexInfo? {
            val backend = videoBackend.value ?: return null
            val prepared = tsIndexPreparer.prepare(backend, path) ?: return null
            return TsIndexInfo(
                hasPcr = prepared.hasPcr,
                keyframeCount = prepared.index.keyframeCount,
                complete = prepared.complete,
            )
        }

        /**
         * 按索引预取拖拽落点（R4）：索引给出关键帧字节偏移，分段缓存负责把它拉下来。
         *
         * 只有"当前视频后端是分段缓存"时才预取——本地根目录本来就不需要预取。
         */
        override suspend fun prefetchSeek(path: String, positionMs: Long) {
            val index = tsIndexPreparer.indexOf(path) ?: return
            val offset = index.seekTarget(positionMs)
            if (offset < 0L) return
            val cache = videoBackend.value as? SegmentedCacheBackend ?: return
            cache.prefetch(path, offset, PREFETCH_BYTES)
        }

        /** 退出修复根目录（播放页销毁时调用）：视频后端回到正常的当前根目录。 */
        override fun exitRepairRoot() {
            val previous = _videoRepairRoot.value ?: return
            _videoRepairRoot.value = null
            // 修复产物留在缓存里由系统回收；这里只把后端收掉，避免文件句柄悬着
            runCatching { previous.backend.close() }
        }

        /** 字幕能力（M7-A，R14）：同目录匹配 / 读取解析 / 写回与本地兜底。 */
        override val subtitles: SubtitleHost get() = subtitleHost

        /** 断点续播存储（R18）：与音频后台播放共用同一份 DataStore 实现。 */
        override val progress: PlaybackProgressStore get() = playbackProgress

        /**
         * 回环代理基址（给 LibVLC 与将来的"分享播放地址"复用）；起不来时返回 null。
         *
         * 播放页据此判断能不能给出"一键切 LibVLC 续播"（LibVLC 只会吃 URL）。
         */
        override val proxyBaseUrl: String?
            get() = runCatching { requireVideoProxy().baseUrl }.getOrNull()

        /**
         * 按内核种类造内核（R9）：Media3 注入 [BackendDataSourceFactory]（本地/远端同一套数据源，
         * R4 拖拽 seek 靠它做 Range 重开）；LibVLC 注入回环代理与视频输出视图（VLC 只认 URL，
         * 画面挂在外部传进去的 SurfaceView 上，见 VlcEngine.attachVideoOutput）。
         *
         * 调用方（播放页 ViewModel）在主线程上调用，所以这里是真的在主线程构造播放器——两个内核都要求如此。
         */
        override suspend fun createEngine(kind: EngineKind): PlayerEngine = when (kind) {
            EngineKind.MEDIA3 -> ExoPlayerEngine(appContext, BackendDataSourceFactory { _root.value?.backend })

            EngineKind.VLC -> {
                // 起代理要绑监听端口（阻塞）；先在 IO 上起好，再回主线程构造播放器
                val proxy = withContext(Dispatchers.IO) { requireVideoProxy() }
                VlcEngine(
                    context = appContext,
                    proxy = proxy,
                    // 解码档位是 LibVLC 的实例级选项（改档位要重建实例），建实例时就带上持久化的那一档
                    initialDecoderMode = preferences.decoderMode.value,
                    videoView = SurfaceView(appContext),
                )
            }
        }

        /**
         * 列出 [path] 所在目录里的视频与音频（R1 上下集队列）。
         *
         * 过滤与排序交给 [VideoPlayerMath.playableEntries]，与浏览页/音频页同一口径。
         *
         * @throws StorageException 列目录失败（无权限 / 不存在 / 网络…），播放页按分类给中文提示。
         */
        override suspend fun siblings(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
            VideoPlayerMath.playableEntries(currentBackend().list(parentOf(path), null))
        }
    }

    // ------------------------------------------------------------ 后台与保活（M8-A，R18）

    /**
     * 后台与保活引导（R18，澎湃 OS）：设置页读它显示四项状态、点它跳系统页/记手动确认。
     *
     * 与 [videoPreferences] 同一考虑：**容器构造时就开始查**（权限查询很便宜），
     * 用户进设置页时看到的不是占位状态。
     */
    val keepAlive: KeepAliveController = KeepAliveController(appContext)

    // ------------------------------------------------------------ 画中画（M8-A，R13）

    /**
     * 是否处于画中画（R13）。
     *
     * 状态放在容器而不是 Activity：Activity 会因 PIP 的配置变化重建，而"现在是不是小窗"是
     * 页面与容器都要读的事实（页面据此收控制层、提示"画中画中"）。
     */
    private val _pipVisible = MutableStateFlow(false)

    /** 画中画状态（R13）。 */
    val pipVisible: StateFlow<Boolean> = _pipVisible.asStateFlow()

    /** 当前 Activity 的 PIP 桥（R13）；没有 Activity 时为 null（PIP 调用全部变成空操作）。 */
    private var pipBridge: VideoPipBridge? = null

    /** 页面注册的 PIP 动作落点：Activity 重建后要重新补给它（否则小窗里的按钮会失灵）。 */
    private var pipSink: VideoPipActionSink? = null

    /** 页面最后一次说的"是否在播"与"是否允许自动进 PIP"：Activity 重建后要重新补给它。 */
    private var pipPlaying: Boolean = false
    private var pipAutoEnter: Boolean = false

    /**
     * 画中画宿主能力（R13）——交给 :feature:player-video 的稳定对象。
     *
     * 为什么是代理而不是直接把 Activity 的桥给页面：页面（ViewModel）活在导航栈里，
     * 比 Activity 长寿；用代理可以让"Activity 重建"对页面完全透明。
     */
    val pipHost: VideoPipHost = PipHost()

    private inner class PipHost : VideoPipHost {

        override val isInPip: StateFlow<Boolean> get() = _pipVisible

        override fun enterPip(aspectRatio: Float): Boolean = pipBridge?.enterPip(aspectRatio) ?: false

        override fun updateActions(isPlaying: Boolean) {
            pipPlaying = isPlaying
            pipBridge?.updateActions(isPlaying)
        }

        override fun setAutoEnterEnabled(enabled: Boolean) {
            pipAutoEnter = enabled
            pipBridge?.setAutoEnterEnabled(enabled)
        }

        override fun setActionSink(sink: VideoPipActionSink?) {
            pipSink = sink
            pipBridge?.setActionSink(sink)
        }
    }

    /**
     * MainActivity 附着 PIP 桥（R13）：把页面已经说过的三件事补给它。
     *
     * 为什么必须补：页面（ViewModel）比 Activity 长寿，Activity 重建后新的桥对这些一无所知——
     * 不补的话"按 Home 自动进 PIP"会失效、小窗里的动作按钮会点不动。
     */
    fun attachPipBridge(bridge: VideoPipBridge) {
        pipBridge = bridge
        bridge.setActionSink(pipSink)
        bridge.updateActions(pipPlaying)
        bridge.setAutoEnterEnabled(pipAutoEnter)
    }

    /** MainActivity 销毁时摘下 PIP 桥（R13）；幂等。 */
    fun detachPipBridge(bridge: VideoPipBridge) {
        if (pipBridge === bridge) pipBridge = null
        _pipVisible.value = false
    }

    /** MainActivity 回调：PIP 状态变化（R13）。 */
    fun setPipVisible(visible: Boolean) {
        _pipVisible.value = visible
    }

    /**
     * 取回环代理（懒建）。
     *
     * 代理已经在 [close] 里关掉、进程又被系统拉回前台时重建一个：
     * 否则手里会拿着一个已经死掉的端口，LibVLC 会一直打不开。
     */
    private fun requireVideoProxy(): LoopbackHttpProxy {
        val existing = videoProxy
        if (existing != null && existing.isRunning) return existing
        return synchronized(this) {
            val current = videoProxy
            if (current != null && current.isRunning) {
                current
            } else {
                LoopbackHttpProxy { _root.value?.backend }.also { videoProxy = it }
            }
        }
    }

    /** 关闭回环代理、远端后端与网络监听（应用退出时由 MainActivity 调用）；幂等。 */
    fun close() {
        videoProxy?.let { proxy ->
            videoProxy = null
            runCatching { proxy.close() }
        }
        remoteRoot?.let { state ->
            remoteRoot = null
            remoteSignature = null
            _remoteRootLabel.value = null
            runCatching { state.backend.close() }
        }
        // 没初始化过就别为了 stop 去初始化（lazy 的 isInitialized）
        if (networkMonitorLazy.isInitialized()) runCatching { networkMonitor.stop() }
    }


    // ------------------------------------------------------------ 音转字幕（M7-B，R14/R19）

    /**
     * 音转字幕的用户设置（模型档位 / 线程数 / 并发 / 播放让路开关）。
     *
     * 与 [videoPreferences] 同一考虑：**在容器构造时就开始读**（不是懒加载），
     * 用户点开任务中心时拿到的才是上次的选择而不是默认值。
     */
    private val asrSettings = AsrSettings(appContext)

    /** 设置的快照（DataStore 的冷流转热流，默认值先顶上）。 */
    private val asrPreferences: StateFlow<AsrPreferences> =
        asrSettings.preferences.stateIn(ioScope, SharingStarted.Eagerly, AsrPreferences())

    /** 当前选中的模型档位 id（任务中心与模型管理都读它）。 */
    val selectedAsrModelId: StateFlow<String> = asrPreferences
        .map { it.modelId }
        .stateIn(ioScope, SharingStarted.Eagerly, WhisperModel.DEFAULT.id)

    /** 播放让路设置（喂给 [asrYieldGate]）。 */
    private val asrYieldSettings: StateFlow<AsrYieldSettings> = asrPreferences
        .map { it.yieldSettings }
        .stateIn(ioScope, SharingStarted.Eagerly, AsrYieldSettings())

    /**
     * 模型目录（plan 4.7 B：filesDir/models，不内置进 APK）。
     *
     * 懒加载：只有真的下载/查询模型时才建目录。
     */
    private val modelStore: ModelStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FileModelStore(File(appContext.filesDir, WhisperModel.DIRECTORY_NAME))
    }

    /**
     * 模型管理（R14 的默认获取方式：App 内下载 + 断点续传 + 校验；备用：从本地文件导入）。
     *
     * 下载用 java.net.HttpURLConnection（[HttpUrlConnectionTransport]），不引 OkHttp：
     * 这里只需要「带 Range 的顺序 GET」，而且关在一个单方法接口后面，JVM 单测灌假实现即可。
     */
    val modelManager: ModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ModelManager(modelStore, ModelDownloader(modelStore, HttpUrlConnectionTransport()))
    }

    /** 已安装的模型档位 id（任务中心据此提示「还没下载模型」）。 */
    private val _installedAsrModels = MutableStateFlow<List<String>>(emptyList())

    /** 已安装模型档位流。 */
    val installedAsrModels: StateFlow<List<String>> = _installedAsrModels.asStateFlow()

    /** 重新扫一遍模型目录（进任务中心 / 下载完成后调用）。 */
    fun refreshAsrModels() {
        ioScope.launch { _installedAsrModels.value = modelManager.installedIds() }
    }

    /**
     * 视频播放页（或其它模块）告知「我正在前台播放」，用于 R19 的播放让路。
     *
     * 这是容器对外的显式入口（R19 的让路闸门吃它）；播放页走的是
     * [io.github.gua123.mediagate.feature.player.video.VideoPlaybackHost.setActive]，两者落在同一处状态上。
     */
    fun setVideoPlaybackActive(active: Boolean) {
        videoSession.setActive(active)
    }

    /**
     * 前台是否正在播放（音频 ∨ 视频）。
     *
     * 懒加载：音频那条要 bind MediaController，只有真的用到让路（进任务中心/跑字幕）才建。
     * 视频侧不再需要页面额外交互：M8-A 起播放页在每个状态变化时把"是否真的在播"喂进来
     * （READY + 在播 + 没播完），所以让路判定与用户看到的状态一致。
     */
    private val playbackActive: StateFlow<Boolean> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        combine(audioSession.state.map { it.playing }, videoSession.active) { audio, video -> audio || video }
            .stateIn(ioScope, SharingStarted.Eagerly, false)
    }

    /** 播放让路闸门（R19：播放中降 1 线程，勾了开关就暂停等结束）。 */
    override val asrYieldGate: PlaybackYieldGate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackYieldGate(playbackActive, asrYieldSettings)
    }

    /** 队列持有者（唯一真相；界面读它、前台服务跑它、状态落 asr_task 表）。 */
    override val asrController: AsrQueueController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AsrQueueController(database.asrDao(), ioScope)
    }

    /**
     * 识别执行器：FFmpeg 解 16 kHz 单声道 PCM → 30 s 窗口（5 s 重叠）→ whisper.cpp JNI → 合并。
     *
     * 线程数取用户设置；实际每窗的线程数由 [asrYieldGate] 决定（播放中降到 1）。
     */
    override val asrEngine: AsrEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WhisperAsrEngine(
            pcmProvider = FfmpegPcmProvider(FfmpegKitRunner()),
            workDir = File(appContext.cacheDir, ASR_WORK_DIR),
            yieldGate = asrYieldGate,
            requestedThreads = asrPreferences.value.threads,
        )
    }

    /** 任务中心的宿主能力（:feature:tasks 只认接口）。 */
    val tasksEnvironment: TasksEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        refreshAsrModels()
        asrController.currentModelId = selectedAsrModelId.value
        // 进程被杀后第一次进任务中心：把库里「上次跑着」的任务读成「已中断，可续跑」（R19）
        ioScope.launch { asrController.restore() }
        AsrTasksHost(
            appContext = appContext,
            backendProvider = { _root.value?.backend },
            controller = asrController,
            browseRoot = tasksBrowseRoot,
            installedModelIds = installedAsrModels,
            selectedModelId = selectedAsrModelId,
            yielding = asrYieldGate.yielding.stateIn(ioScope, SharingStarted.Eagerly, false),
            onEnqueue = { candidates, batchName ->
                asrController.enqueue(candidates, batchName, selectedAsrModelId.value)
            },
        )
    }

    /**
     * 任务中心的「选择来源」起点（R19）：当前连接优先，其次首页选的本地根目录。
     *
     * 懒加载：它要读 connection 表，冷启动不碰。
     */
    private val tasksBrowseRoot: StateFlow<TasksRoot?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        combine(rootConfig, currentConnectionId, connectionRepository.connections) { config, id, records ->
            val record = records.firstOrNull { it.id == id }
            when {
                record != null -> TasksRoot(label = record.name, path = record.basePath.ifEmpty { "/" })
                config != null -> TasksRoot(label = config.display, path = "")
                else -> null
            }
        }.stateIn(ioScope, SharingStarted.Eagerly, null)
    }

    // ------------------------------------------------------------ 语音识别模型管理（R14，设置页）

    /**
     * 模型管理区块要的宿主能力（**R14**）：档位、已装列表、占用、下载（断点续传）、删除、选中。
     *
     * :app 只是把 [modelManager] 与 [asrSettings] 包一层；真正的下载/校验在 :media:asr。
     */
    val asrModelEnvironment: AsrModelEnvironment = AsrModelHost()

    private inner class AsrModelHost : AsrModelEnvironment {

        override fun models(): List<WhisperModel> = WhisperModel.ALL

        override fun installedIds(): List<String> = modelManager.installedIds()

        override fun usedBytes(): Long = modelManager.usedBytes()

        override val selectedId: StateFlow<String> get() = selectedAsrModelId

        override suspend fun select(id: String) {
            asrSettings.setModelId(id)
        }

        override suspend fun delete(model: WhisperModel): Boolean {
            val deleted = modelManager.delete(model)
            refreshAsrModels()
            return deleted
        }

        override suspend fun download(
            model: WhisperModel,
            mirror: String?,
            onProgress: (DownloadProgress) -> Unit,
        ) {
            modelManager.download(model, mirror, onProgress)
            refreshAsrModels()
        }

        override val defaultMirror: String? get() = WhisperModel.DEFAULT_MIRROR
    }

    // ------------------------------------------------------------ 应用内更新（R20）

    /**
     * 更新功能要的宿主能力（**R20**）：版本号、下载目录、签名校验、系统安装器、发布页。
     *
     * 为什么收在 :app：只有它拿得到 PackageManager 与 FileProvider；:feature:update 只认接口，
     * 于是状态机能在 JVM 单测里跑全（检查 → 下载 → 签名校验 → 待安装）。
     */
    val updateEnvironment: UpdateEnvironment = UpdateHost()

    /** 更新下载用的 HTTP 传输（与 ASR 模型下载同一套 HttpURLConnection 实现）。 */
    val updateTransport: HttpTransport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HttpUrlConnectionTransport(userAgent = "mediagate-android-update")
    }

    private inner class UpdateHost : UpdateEnvironment {

        override val currentVersionName: String
            get() = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull().orEmpty()

        override val currentVersionCode: Long
            get() = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
            }.getOrDefault(0L)

        override fun downloadTarget(manifest: UpdateManifest): File {
            val dir = File(appContext.filesDir, UPDATE_DIR)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "mediagate-" + manifest.versionName + ".apk")
        }

        override fun verifySignature(apk: File): SignatureCheck {
            val expected = signerSha256(installedPackageInfo()) ?: return SignatureCheck.Unknown
            val actual = signerSha256(archivePackageInfo(apk))
            return when {
                actual == null -> SignatureCheck.Mismatch(expected, null)
                actual.equals(expected, ignoreCase = true) -> SignatureCheck.Match
                else -> SignatureCheck.Mismatch(expected, actual)
            }
        }

        override fun installApk(apk: File): Boolean = runCatching {
            // content:// + 临时读权限：Android 7 起 file:// 不允许外传给安装器
            val uri = FileProvider.getUriForFile(appContext, appContext.packageName + UPDATE_AUTHORITY_SUFFIX, apk)
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrElse { t ->
            // 常见原因：没给「安装未知应用」权限、或系统没有安装器 Activity
            AppLog.w(TAG, "调起系统安装器失败", t)
            false
        }

        override fun openReleasesPage(): Boolean = runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateSource.RELEASES_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse { t ->
            AppLog.w(TAG, "打开发布页失败", t)
            false
        }
    }

    /** 安装包签名证书的 SHA-256（小写十六进制）；读不出返回 null。 */
    private fun signerSha256(info: PackageInfo?): String? {
        val signing = info?.signingInfo ?: return null
        val signers = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory
        }
        val cert = signers?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(cert.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun installedPackageInfo(): PackageInfo? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }.getOrNull()

    private fun archivePackageInfo(apk: File): PackageInfo? = runCatching {
        appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }.getOrNull()

    // ---- AsrRuntimeHost：前台服务与队列要的东西 ----

    override val asrFallbackDir: File get() = File(appContext.filesDir, SUBTITLE_FALLBACK_DIR)

    override val asrBackend: StorageBackend? get() = _root.value?.backend

    override fun asrModel(): WhisperModel = WhisperModel.of(selectedAsrModelId.value)

    override fun asrModelPath(model: WhisperModel): String? = modelManager.fileOf(model)?.absolutePath

    /**
     * 任务音源（plan 4.12：远端取音复用同一 StorageBackend 的低优先级连接）。
     *
     * 本地文件直接给绝对路径；远端走 [LoopbackHttpProxy]（与播放共用同一条数据层，
     * 不额外开一套连接，也就不会和播放抢带宽）。
     */
    override fun asrSource(item: AsrItem): String? {
        val local = File(item.path)
        if (local.isFile) return local.absolutePath
        val backend = _root.value?.backend ?: return null
        return runCatching { requireVideoProxy().playUrl(backend.id, item.path) }.getOrNull()
    }

    /** 音轨时长（毫秒）：本地与回环 URL 都用 MediaMetadataRetriever 探一次；读不到给 0。 */
    override suspend fun asrDurationMs(item: AsrItem): Long = withContext(Dispatchers.IO) {
        val source = asrSource(item) ?: return@withContext 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "探测音轨时长失败：" + source, e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------ 图片查看器（M1-F，R1）

    /** 当前根目录的展示名（查看器顶栏用）；尚未选择根目录时是空串。 */
    override val rootLabel: StateFlow<String> =
        _root.map { it?.label.orEmpty() }.stateIn(ioScope, SharingStarted.Eagerly, "")

    /**
     * 列出 [path] 所在目录的**图片**（M1-F，R1 左右翻页的数据源）。
     *
     * 复用当前根目录的后端；过滤与排序口径交给 [ViewerMath.imageEntries]（与查看器同一份纯逻辑），
     * 保证「后端给什么顺序」都不会影响「第 i / n 张」。
     *
     * @throws StorageException 列目录失败（无权限 / 不存在 / 网络…），查看器按分类给中文提示。
     */
    override suspend fun siblings(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ViewerMath.imageEntries(currentBackend().list(parentOf(path), null))
    }

    /**
     * 读取 [path] 的原始字节（M1-F，R1）。
     *
     * 边读边计数：一旦超过 [MAX_VIEWER_IMAGE_BYTES] 立刻中止并抛 [ImageTooLargeException]，
     * 不会为了报错先把几百 MB 读进内存（目录项没给 size 时的唯一保护）。
     *
     * @throws StorageException 读取失败。
     * @throws ImageTooLargeException 超过体积上限。
     */
    override suspend fun open(path: String): ByteArray = withContext(Dispatchers.IO) {
        currentBackend().openRead(path).use { stream ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val out = ByteArrayOutputStream(READ_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = stream.read(buffer, 0, buffer.size)
                if (read <= 0) break
                total += read
                if (total > MAX_VIEWER_IMAGE_BYTES) throw ImageTooLargeException(total)
                out.write(buffer, 0, read)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) throw StorageException.NotFound("图片内容为空：" + path)
            bytes
        }
    }

    /**
     * 当前根目录的后端。
     *
     * 尚未选择根目录（R12）时按「无权限」抛：查看器会提示「没有访问权限 + 尚未选择媒体根目录」，
     * 比笼统的「图片不存在」更贴近用户需要做的动作（去首页选目录）。
     */
    private fun currentBackend(): StorageBackend =
        _root.value?.backend ?: throw StorageException.AccessDenied("尚未选择媒体根目录")

    /** 目录路径（相对根目录，空串 = 根）：最后一个 `/` 之前的部分。 */
    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    init {
        ioScope.launch {
            rootConfig.collect { config -> applyConfig(config) }
        }
        // R7/R8：当前连接 + 连接记录 + 网络现场，任一变化就重算"用哪个后端"
        ioScope.launch {
            combine(
                currentConnectionId,
                connectionRepository.connections,
                networkMonitor.context,
            ) { id, records, network -> Triple(id, records, network) }
                .collect { (id, records, network) ->
                    applyCurrentConnection(records.firstOrNull { it.id == id }, network)
                }
        }
    }

    // ------------------------------------------------------------ 权限与根目录

    /**
     * SAF 目录选择器回调（R12 SAF 模式）。
     *
     * 必须立刻 `takePersistableUriPermission`，否则重启后授权失效；部分提供方不支持写权限，
     * 所以先试「读 + 写」，失败再退化成「只读」，两次都失败才记日志（不抛给 UI）。
     *
     * @param uri `ActivityResultContracts.OpenDocumentTree` 返回的树 URI。
     */
    fun onSafTreePicked(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, readWrite)
        }.isSuccess || runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!persisted) {
            AppLog.w(TAG, "SAF 持久化授权失败，重启后可能需要重新选择目录：$uri")
        }
        val display = safDisplayName(uri)
        ioScope.launch { settings.setSaf(uri.toString(), display) }
        AppLog.i(TAG, "SAF 根目录已设置：$display")
    }

    /** 切到「所有文件访问」模式（已授权时才调用；未授权请先走 [requestAllFilesAccess]）。 */
    fun useAllFilesRoot() {
        refreshAllFilesAccess()
        if (!_allFilesGranted.value) {
            AppLog.w(TAG, "尚未获得「所有文件访问」权限，忽略切换请求")
            return
        }
        ioScope.launch { settings.setAllFiles(allFilesRootPath()) }
    }

    /**
     * 跳系统「所有文件访问」设置页（R12 全盘模式引导）。
     *
     * 先用带包名的 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（直达本应用）；
     * 个别 ROM 没有该 Activity，退回 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 列表页。
     */
    fun requestAllFilesAccess() {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "本机没有应用级「所有文件访问」设置页，改用列表页", e)
            val listIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(listIntent) }
                .onFailure { AppLog.w(TAG, "无法打开「所有文件访问」设置页", it) }
        }
    }

    /** 清除根目录（回到「未选择」，首页重新显示引导卡片）。 */
    fun clearRoot() {
        ioScope.launch { settings.clear() }
    }

    /** 从系统重新读一次「所有文件访问」授权状态（页面 ON_RESUME 时调用）。 */
    fun refreshAllFilesAccess() {
        _allFilesGranted.value = hasAllFilesAccess()
    }

    // ------------------------------------------------------------ 内部实现

    /**
     * 本地根目录配置变化（R12）。
     *
     * 换根时关闭旧后端，避免 File 句柄 / SAF fd 泄漏；若当前有远端连接（WEBDAV），
     * 它优先于本地根目录（[publishRoot] 里决定谁生效），本地后端只做"备胎"保留。
     */
    private fun applyConfig(config: RootConfig?) {
        val previous = localRoot
        val next = config?.let { buildRoot(it) }
        localRoot = next
        if (previous?.backend !== next?.backend) {
            runCatching { previous?.backend?.close() }
        }
        publishRoot()
        if (next != null) {
            ioScope.launch { logProbe(next) }
        }
    }

    /**
     * 把「当前连接」落成实际后端（**R8** + **R7**）。
     *
     * - **WEBDAV**：用 [AddressSelector] 在当前网络下选地址（命中规则就用规则偏好的，
     *   否则按优先级取首选），解密密码后构造 [WebDavStorageBackend]；
     * - **SFTP**：同一条选路 + Keystore 解密密码 + TOFU 主机密钥校验（[sftpHostKeys]）；
     * - **FTP**：同一条选路，`connection.tls` 决定明文 / 显式 FTPS / 隐式 FTPS；
     * - **LOCAL**：不建远端后端，改为把连接里的目录写回 [RootSettings]（SAF 树 URI 或绝对路径），
     *   于是浏览器拿到的仍是 [LocalBackends] 的双模式后端（R12）；
     * - 连接被删除 / 未选中 / 协议标识认不出：关掉远端后端，回落到本地根目录（认不出时给一句提示）。
     */
    private suspend fun applyCurrentConnection(record: ConnectionRecord?, network: NetworkContext) {
        if (record == null) {
            _remoteNotice.value = null
            closeRemoteRoot()
            return
        }
        when (record.protocol) {
            ProtocolKind.WEBDAV -> applyWebDavConnection(record, network)
            ProtocolKind.SFTP -> applySftpConnection(record, network)
            ProtocolKind.FTP -> applyFtpConnection(record, network)

            ProtocolKind.LOCAL -> {
                _remoteNotice.value = null
                closeRemoteRoot()
                applyLocalConnectionRoot(record)
            }

            null -> {
                AppLog.w(TAG, "连接 " + record.name + " 的协议标识 " + record.protocolId + " 认不出，继续使用本地根目录")
                _remoteNotice.value = "连接「" + record.name + "」的协议标识（" + record.protocolId +
                    "）认不出：仍在使用本地根目录，请到连接页编辑或删除它"
                closeRemoteRoot()
            }
        }
    }

    /**
     * 选路（**R7**）：当前网络下该连接的首选地址。
     *
     * 没有可用地址时给一条中文提示（界面弹 Snackbar），调用方随后回落到本地根目录——
     * 比起"悄悄什么都不做"，用户至少知道为什么目录还是本地那套。
     */
    private fun selectAddress(record: ConnectionRecord, network: NetworkContext): SelectableAddress? {
        val selection = AddressSelector.select(record.selectableAddresses(), network, record.networkRules())
        val address = selection.primary
        if (address == null) {
            AppLog.w(TAG, "连接 " + record.name + " 当前没有可用地址（" + selection.explanation + "）")
            _remoteNotice.value = "连接「" + record.name + "」当前没有可用地址：" + selection.explanation
        }
        return address
    }

    /**
     * 远端后端的统一收尾（WebDAV / SFTP / FTP 共用）：换根、关旧、发布、启动自检。
     *
     * @param signature 重建指纹（[ConnectionBackends.rebuildSignature]）：只有它变了才重建后端，
     *   否则网络抖动 / 重复回调都会把 SSH 会话或 FTP 控制连接拆掉重连。
     * @param build 真正造后端；抛异常时保持本地根目录并给一句能照做的中文提示（R16）。
     */
    private suspend fun installRemoteRoot(
        record: ConnectionRecord,
        address: SelectableAddress,
        signature: String,
        displayPath: String,
        protocolText: String,
        build: suspend () -> StorageBackend,
    ) {
        if (remoteSignature == signature && remoteRoot != null) return
        val previous = remoteRoot
        val next = runCatching {
            BrowserRootState(
                // 远端连接不是"本地根目录模式"：NONE 只影响首页那个本地卡片，浏览器只读 label/displayPath/backend
                mode = RootModeKind.NONE,
                label = record.name + " · " + address.label.zhText,
                displayPath = displayPath,
                // 降级链第二级（plan 4.1）：远端一律套一层分段缓存——不支持随机读的协议（无 Range 的
                // WebDAV、无 REST 的 FTP）因此也能被拖拽 seek；支持随机读的也省掉重复过网
                backend = SegmentedCacheBackend(
                    delegate = build(),
                    rootDir = File(appContext.cacheDir, SEGMENT_CACHE_DIR),
                ),
            )
        }.onFailure { t ->
            AppLog.w(TAG, "构造 " + protocolText + " 后端失败：" + record.name + " → " + address.display, t)
            _remoteNotice.value = "连接「" + record.name + "」切不过去：" + friendlyReason(t) + "（仍在使用本地根目录）"
        }.getOrNull()

        remoteRoot = next
        remoteSignature = if (next == null) null else signature
        _remoteRootLabel.value = next?.label
        if (previous?.backend !== next?.backend) runCatching { previous?.backend?.close() }
        if (next != null) _remoteNotice.value = null
        publishRoot()
        next?.let { ioScope.launch { logProbe(it) } }
    }

    /**
     * 后端构造失败 → 给用户看的中文原因（R16：不把异常类名或库的英文 message 甩到界面上）。
     *
     * 我们自己抛的配置类错误本来就是中文（如「还没有保存密码…」），[ErrorText] 会原样保留。
     */
    private fun friendlyReason(t: Throwable): String = ErrorText.of(t, "配置或网络有问题")

    /** 修复产物的文件名：Movie.ts → Movie.repaired.mp4（重建命令固定输出 MP4）。 */
    private fun repairedFileNameOf(path: String): String {
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name).ifEmpty { "repaired" }
        return base + ".repaired.mp4"
    }

    /** ffmpeg 进度 → 0..100（报不出总时长时给 0，界面显示不确定进度）。 */
    private fun percentOf(progress: FfmpegProgress): Int = progress.percent.coerceAtLeast(0)

    /** 本地连接 → 写回根目录配置（R12：content:// 走 SAF，绝对路径走全盘模式）。 */
    private suspend fun applyLocalConnectionRoot(record: ConnectionRecord) {
        val path = record.addresses.firstOrNull()?.host?.trim().orEmpty()
        if (path.isEmpty()) {
            AppLog.w(TAG, "本地连接 " + record.name + " 没有地址，忽略")
            return
        }
        if (path.startsWith("content://")) {
            settings.setSaf(path, record.name)
        } else {
            settings.setAllFiles(path)
        }
    }

    /** WebDAV 连接 → 选路 + 解密密码 + 建后端（R7/R6）。 */
    private suspend fun applyWebDavConnection(record: ConnectionRecord, network: NetworkContext) {
        val address = selectAddress(record, network)
        if (address == null) {
            closeRemoteRoot()
            return
        }
        installRemoteRoot(
            record = record,
            address = address,
            signature = ConnectionBackends.rebuildSignature(record, address),
            displayPath = ConnectionEndpoints.baseUrl(address.scheme, address.host, address.port),
            protocolText = "WebDAV",
        ) {
            val password = runCatching { connectionRepository.revealSecret(record.id) }.getOrNull()
            WebDavStorageBackend(
                WebDavConfig(
                    baseUrl = ConnectionEndpoints.baseUrl(address.scheme, address.host, address.port),
                    username = record.username,
                    password = password,
                    rootPath = record.basePath,
                    allowInsecureHttp = record.options.allowInsecureHttp,
                    connectTimeoutMs = record.options.connectTimeoutMs ?: WebDavConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                ),
            )
        }
    }

    /**
     * SFTP 连接 → 选路 + 解密密码 + TOFU 主机密钥 + 建后端（**R2** 四协议 / R7 / R6）。
     *
     * 没存过密码时直接给一句能照做的提示（而不是让后端抛"必须提供密码或私钥"）：
     * 自用场景最常见的失败就是"建了连接忘了填密码"。
     */
    private suspend fun applySftpConnection(record: ConnectionRecord, network: NetworkContext) {
        val address = selectAddress(record, network)
        if (address == null) {
            closeRemoteRoot()
            return
        }
        installRemoteRoot(
            record = record,
            address = address,
            signature = ConnectionBackends.rebuildSignature(record, address),
            displayPath = ConnectionBackends.remoteDisplayPath("sftp", address, record.basePath),
            protocolText = "SFTP",
        ) {
            if (record.username.isNullOrBlank()) {
                error("还没有填用户名，请在连接页编辑这条连接并填写用户名")
            }
            val secret = runCatching { connectionRepository.revealSecret(record.id) }.getOrNull()
            if (secret.isNullOrEmpty()) {
                error("还没有保存密码，请在连接页编辑这条连接并填写密码")
            }
            SftpStorageBackend(
                ConnectionBackends.sftpConfig(record, address, secret, hostKeyPolicy = SftpHostKeyPolicy.TOFU),
                sftpHostKeys,
            )
        }
    }

    /**
     * FTP 连接 → 选路 + 解密密码 + 建后端（R2 / R7）。
     *
     * tls 列决定明文 / 显式 FTPS / 隐式 FTPS（认不出的一律明文，与连通性测试同一口径）。
     */
    private suspend fun applyFtpConnection(record: ConnectionRecord, network: NetworkContext) {
        val address = selectAddress(record, network)
        if (address == null) {
            closeRemoteRoot()
            return
        }
        installRemoteRoot(
            record = record,
            address = address,
            // tls 参与指纹：改加密方式要重建后端（明文与 FTPS 是两条不同的连接路径）
            signature = ConnectionBackends.rebuildSignature(record, address, extra = record.tls.orEmpty().lowercase()),
            displayPath = ConnectionBackends.remoteDisplayPath("ftp", address, record.basePath),
            protocolText = "FTP",
        ) {
            if (record.username.isNullOrBlank()) {
                error("还没有填用户名，请在连接页编辑这条连接并填写用户名（匿名 FTP 就填 anonymous）")
            }
            val secret = runCatching { connectionRepository.revealSecret(record.id) }.getOrNull()
            FtpStorageBackend(ConnectionBackends.ftpConfig(record, address, secret))
        }
    }

    /** 关掉远端后端（清除当前连接 / 连接被删 / 换协议时调用）。 */
    private fun closeRemoteRoot() {
        val previous = remoteRoot ?: return
        remoteRoot = null
        remoteSignature = null
        _remoteRootLabel.value = null
        runCatching { previous.backend.close() }
        publishRoot()
    }

    /** 生效的后端：远端连接优先，其次本地根目录（R12 与 R8 的汇合点）。 */
    private fun publishRoot() {
        _root.value = remoteRoot ?: localRoot
    }

    private fun buildRoot(config: RootConfig): BrowserRootState? = when (config.mode) {
        RootModeKind.SAF -> BrowserRootState(
            mode = RootModeKind.SAF,
            label = appContext.getString(R.string.root_label_saf),
            displayPath = config.display,
            backend = LocalBackends.saf(appContext, config.value),
        )

        RootModeKind.ALL_FILES -> BrowserRootState(
            mode = RootModeKind.ALL_FILES,
            label = appContext.getString(R.string.root_label_all_files),
            displayPath = config.display.ifEmpty { config.value },
            backend = LocalBackends.file(File(config.value)),
        )

        // NONE 不会出现在已保存配置里；真出现也只当成「未选择」，绝不抛异常把首页炸掉（R12）
        RootModeKind.NONE -> null
    }

    /** 启动时做一次根目录自检并记日志（失败只提示，不阻塞 UI——浏览页自己会给错误卡片）。 */
    private suspend fun logProbe(root: BrowserRootState) {
        val report = runCatching { root.backend.probe() }.getOrNull() ?: return
        AppLog.i(
            TAG,
            "根目录自检 mode=${root.mode} path=${root.displayPath} ok=${report.ok} ${report.message.orEmpty()}",
        )
    }

    private fun hasAllFilesAccess(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 全盘模式的根：内部存储挂载点（`/storage/emulated/0`）。 */
    private fun allFilesRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * SAF 树 URI → 展示名。
     *
     * 形如 `content://com.android.externalstorage.documents/tree/primary%3AMovies`，
     * 末段解码后是 `primary:Movies`，取冒号后的目录名；整盘授权（`primary:`）没有名字，
     * 用「内部存储」兜底。
     */
    private fun safDisplayName(uri: Uri): String {
        val segment = uri.lastPathSegment ?: return uri.toString()
        val name = segment.substringAfterLast(':')
        return name.ifEmpty { appContext.getString(R.string.root_label_internal_storage) }
    }

    private companion object {
        const val TAG = "app-container"

        /** 缩略图两级缓存的磁盘根（App 缓存目录下）。 */
        const val THUMBNAIL_CACHE_DIR = "thumbnails"

        /** FFmpeg 兜底抽帧的临时文件目录。 */
        const val THUMBNAIL_WORK_DIR = "thumb-work"

        /** 图片查看器读取整张图片时的缓冲块（256 KiB）。 */
        private const val READ_BUFFER_BYTES = 256 * 1024

        /** 无写权限时字幕的落地目录（R14：App 私有目录，可分享/稍后重试）。 */
        const val SUBTITLE_FALLBACK_DIR = "subtitles"

        /** ASR 中间件（16 kHz 单声道 PCM 临时文件）目录；跑完即删。 */
        const val ASR_WORK_DIR = "asr"

        /** 时间戳重建（R3/R11）产物目录（cacheDir 下）与 ffmpeg 中间文件目录。 */
        /** 远端随机读的分段缓存（plan 4.1 降级链第二级）。 */
        const val SEGMENT_CACHE_DIR = "segments"

        /** 拖拽预取的字节数（R4）：4 MB = 一个段。 */
        const val PREFETCH_BYTES = 4L * 1024 * 1024

        /** TS 索引缓存目录（R4）：与 :media:tsext 的 TsIndexStore 配套。 */
        const val TS_INDEX_DIR = "ts-index"

        const val TS_REPAIR_DIR = "ts-repair"
        const val TS_REPAIR_WORK_DIR = "ts-repair-work"

        /** 应用内更新（R20）：下载好的 APK 落在 filesDir/updates（已通过 FileProvider 暴露给安装器）。 */
        const val UPDATE_DIR = "updates"

        /** FileProvider authorities 后缀（applicationId + 它，debug 包也不会冲突）。 */
        const val UPDATE_AUTHORITY_SUFFIX = ".updates"

        /** APK 的 MIME（系统安装器只认它）。 */
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
