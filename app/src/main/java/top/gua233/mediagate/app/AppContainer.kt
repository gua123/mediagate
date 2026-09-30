package io.github.gua123.mediagate.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.SurfaceView
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
import io.github.gua123.mediagate.core.crypto.CredentialCipher
import io.github.gua123.mediagate.core.crypto.KeystoreCredentialCipher
import io.github.gua123.mediagate.core.database.MediaGateDatabase
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.core.network.AddressSelector
import io.github.gua123.mediagate.core.network.AndroidNetworkMonitor
import io.github.gua123.mediagate.core.network.NetworkContext
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.local.LocalBackends
import io.github.gua123.mediagate.data.storage.webdav.WebDavConfig
import io.github.gua123.mediagate.data.storage.webdav.WebDavStorageBackend
import io.github.gua123.mediagate.feature.browser.BrowserEnvironment
import io.github.gua123.mediagate.feature.browser.BrowserRootState
import io.github.gua123.mediagate.feature.browser.RootModeKind
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
import io.github.gua123.mediagate.feature.player.video.VideoPlayerEnvironment
import io.github.gua123.mediagate.feature.player.video.VideoPlayerMath
import io.github.gua123.mediagate.feature.player.video.VideoPlayerPreferences
import io.github.gua123.mediagate.feature.settings.SettingsConnectionUi
import io.github.gua123.mediagate.feature.viewer.image.ViewerMath
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
import java.io.ByteArrayOutputStream
import java.io.File

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
    AudioPlayerEnvironment {

    private val appContext: Context = context.applicationContext

    /** 应用级协程作用域：只做「订阅配置 → 换后端」这类长生命周期的小任务。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings = RootSettings(appContext)

    /** 「当前连接」（R8）的持久化：只存一个 id，连接本体在三张表里。 */
    private val connectionSettings = ConnectionSettings(appContext)

    // ------------------------------------------------------------ 连接管理（M4，R6/R7/R8）

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

    /** 当前远端连接的后端（M4 只有 WEBDAV）。 */
    private var remoteRoot: BrowserRootState? = null

    /** 远端后端的构造指纹：选路结果、根路径、账号、密码有无变化时才重建。 */
    private var remoteSignature: String? = null

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
        AudioSessionController(appContext, audioBackend)
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

    /** 视频播放宿主能力（直接复用容器里的后端流、偏好、断点存储与回环代理）。 */
    private inner class VideoPlayerHost : VideoPlayerEnvironment {

        override val backend: StateFlow<StorageBackend?> get() = audioBackend

        override val preferences: VideoPlayerPreferences get() = videoPreferences

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
            runCatching { state.backend.close() }
        }
        // 没初始化过就别为了 stop 去初始化（lazy 的 isInitialized）
        if (networkMonitorLazy.isInitialized()) runCatching { networkMonitor.stop() }
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
     * - **LOCAL**：不建远端后端，改为把连接里的目录写回 [RootSettings]（SAF 树 URI 或绝对路径），
     *   于是浏览器拿到的仍是 [LocalBackends] 的双模式后端（R12）；
     * - **SFTP / FTP**：M5 才有后端，本轮保持本地根目录，界面已明确提示"只能测连通性"；
     * - 连接被删除 / 未选中：关掉远端后端，回落到本地根目录。
     */
    private suspend fun applyCurrentConnection(record: ConnectionRecord?, network: NetworkContext) {
        if (record == null) {
            closeRemoteRoot()
            return
        }
        when (record.protocol) {
            ProtocolKind.WEBDAV -> applyWebDavConnection(record, network)

            ProtocolKind.LOCAL -> {
                closeRemoteRoot()
                applyLocalConnectionRoot(record)
            }

            else -> {
                closeRemoteRoot()
                AppLog.i(TAG, "连接 " + record.name + " 的协议 " + record.protocolId + " 尚未接入（M5），继续使用本地根目录")
            }
        }
    }

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
        val selection = AddressSelector.select(record.selectableAddresses(), network, record.networkRules())
        val address = selection.primary
        if (address == null) {
            AppLog.w(TAG, "连接 " + record.name + " 当前没有可用地址（" + selection.explanation + "）")
            closeRemoteRoot()
            return
        }
        val signature = record.id.toString() + "|" + address.id + "|" + record.basePath + "|" +
            record.username.orEmpty() + "|" + record.hasSecret + "|" + address.scheme + address.host + address.port
        if (remoteSignature == signature && remoteRoot != null) return

        val previous = remoteRoot
        val password = runCatching { connectionRepository.revealSecret(record.id) }.getOrNull()
        val next = runCatching {
            val config = WebDavConfig(
                baseUrl = ConnectionEndpoints.baseUrl(address.scheme, address.host, address.port),
                username = record.username,
                password = password,
                rootPath = record.basePath,
                allowInsecureHttp = record.options.allowInsecureHttp,
                connectTimeoutMs = record.options.connectTimeoutMs ?: WebDavConfig.DEFAULT_CONNECT_TIMEOUT_MS,
            )
            BrowserRootState(
                // 远端连接不是"本地根目录模式"：NONE 只影响首页那个本地卡片，浏览器只读 label/displayPath/backend
                mode = RootModeKind.NONE,
                label = record.name + " · " + address.label.zhText,
                displayPath = config.requestBaseUrl,
                backend = WebDavStorageBackend(config),
            )
        }.onFailure { t ->
            AppLog.w(TAG, "构造 WebDAV 后端失败：" + record.name + " → " + address.display, t)
        }.getOrNull()

        remoteRoot = next
        remoteSignature = if (next == null) null else signature
        if (previous?.backend !== next?.backend) runCatching { previous?.backend?.close() }
        publishRoot()
        next?.let { ioScope.launch { logProbe(it) } }
    }

    /** 关掉远端后端（清除当前连接 / 连接被删 / 换协议时调用）。 */
    private fun closeRemoteRoot() {
        val previous = remoteRoot ?: return
        remoteRoot = null
        remoteSignature = null
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
    }
}
