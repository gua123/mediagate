package io.github.gua123.mediagate.data.storage.sftp

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 主机密钥指纹（**R2** / plan 4.9「SFTP 主机密钥 TOFU + 变更告警」）。
 *
 * 指纹算的是 SSH 线上那把密钥的原始 blob（`K_S`），与 OpenSSH 的 `ssh-keyscan` 口径一致：
 * - [sha256]：SHA-256 的 Base64（OpenSSH 默认展示形式，展示时加 `SHA256:` 前缀）；
 * - [md5]：MD5 的冒号分隔十六进制（老工具链习惯，展示时加 `MD5:` 前缀）。
 *
 * @param host 主机名（原样，不含端口）。
 * @param port SSH 端口。
 * @param keyType 密钥算法名（如 `ssh-ed25519` / `ecdsa-sha2-nistp256`）。
 * @param sha256 SHA-256 指纹的 Base64（无前缀）。
 * @param md5 MD5 指纹（冒号分隔小写十六进制）。
 */
data class HostKeyFingerprint(
    val host: String,
    val port: Int,
    val keyType: String,
    val sha256: String,
    val md5: String,
) {

    /** 展示用：`SHA256:xxxx`。 */
    val sha256Display: String get() = "SHA256:" + sha256

    /** 展示用：`MD5:aa:bb:...`。 */
    val md5Display: String get() = "MD5:" + md5

    /** 存储键：`host:port`（同一主机换端口就是另一台，必须分开记）。 */
    val storeKey: String get() = key(host, port)

    /** 写成 OpenSSH known_hosts 风格的一行（到指纹级别），用于交给用户确认/落库展示。 */
    val knownHostsLine: String get() = host + ":" + port + " " + keyType + " " + sha256Display

    companion object {

        /** 存储键：`host:port`。 */
        fun key(host: String, port: Int): String = host + ":" + port

        /** 从 SSH 密钥 blob（JSch 的 K_S）算出指纹。 */
        fun of(host: String, port: Int, keyType: String, keyBlob: ByteArray): HostKeyFingerprint =
            HostKeyFingerprint(
                host = host,
                port = port,
                keyType = keyType,
                sha256 = Base64.getEncoder().withoutPadding().encodeToString(digest("SHA-256", keyBlob)),
                md5 = digest("MD5", keyBlob).joinToString(":") { b -> "%02x".format(b) },
            )

        /** 从密钥 blob 的 SSH 线格式里取出算法名（4 字节长度 + 名字）。 */
        fun keyTypeOf(keyBlob: ByteArray): String = runCatching {
            if (keyBlob.size < 4) return@runCatching "unknown"
            val length = ((keyBlob[0].toInt() and 0xff) shl 24) or ((keyBlob[1].toInt() and 0xff) shl 16) or
                ((keyBlob[2].toInt() and 0xff) shl 8) or (keyBlob[3].toInt() and 0xff)
            if (length <= 0 || length > keyBlob.size - 4) return@runCatching "unknown"
            String(keyBlob, 4, length, Charsets.UTF_8)
        }.getOrDefault("unknown")

        private fun digest(algorithm: String, data: ByteArray): ByteArray =
            MessageDigest.getInstance(algorithm).digest(data)
    }
}

/** 主机密钥被拒绝的原因（**R8** 错误分类：界面据此提示，而不是一句「连接失败」）。 */
enum class HostKeyRejectReason {

    /** 指纹与已记录的不一致（疑似中间人，plan 4.9 要求红色告警）。 */
    CHANGED,

    /** [SftpHostKeyPolicy.STRICT] 下遇到未知主机。 */
    UNKNOWN_HOST,
}

/** 主机密钥校验结论（[HostKeyVerifier.verify] 的返回值）。 */
sealed interface HostKeyCheck {

    /** 本次校验用到的指纹。 */
    val fingerprint: HostKeyFingerprint

    /** 是否放行。 */
    val accepted: Boolean

    /**
     * 放行。
     *
     * @param firstSeen true = 第一次见到并已按 TOFU 记住（界面提示「已信任新主机」）。
     */
    data class Trusted(
        override val fingerprint: HostKeyFingerprint,
        val firstSeen: Boolean,
    ) : HostKeyCheck {
        override val accepted: Boolean get() = true
    }

    /**
     * 拒绝。
     *
     * @param expected 已记录的指纹；未知主机（STRICT）为 null。
     * @param message 中文原因（可直接展示，含两个指纹便于用户核对）。
     */
    data class Rejected(
        override val fingerprint: HostKeyFingerprint,
        val expected: HostKeyFingerprint?,
        val reason: HostKeyRejectReason,
        val message: String,
    ) : HostKeyCheck {
        override val accepted: Boolean get() = false
    }
}

/**
 * 主机密钥变更事件（plan 4.9：「变更要能检测出来」「不要静默接受变更」）。
 *
 * 只要检测到指纹与已记录的不一致就**一定会**产生本事件（哪怕策略是
 * [SftpHostKeyPolicy.ACCEPT_ANY]），上层可据此弹红色告警、落诊断日志、通知用户核对。
 */
data class HostKeyChangeEvent(
    val host: String,
    val port: Int,
    val keyType: String,
    val expectedSha256: String,
    val actualSha256: String,
    val policy: SftpHostKeyPolicy,
) {
    /** 中文告警文案（R16 单语）。 */
    val message: String
        get() = "SFTP 主机密钥已变更（" + host + ":" + port + "）：期望 SHA256:" + expectedSha256 +
            "，实际 SHA256:" + actualSha256 + "。可能是服务器换钥，也可能是中间人攻击"
}

/**
 * 已知主机指纹存储（plan 4.9 的 TOFU 落点）。
 *
 * 后端只依赖这个抽象：默认给内存实现，上层可以换成落 Room / 落文件 / 走 Keystore 的实现，
 * 换实现不影响后端逻辑。
 */
interface KnownHostsStore {

    /** 列出 [host]:[port] 已记录的指纹（可能多把：换过钥但用户手工信任过）。 */
    fun find(host: String, port: Int): List<HostKeyFingerprint>

    /** 记住一把指纹（TOFU 首次信任）。 */
    fun save(fingerprint: HostKeyFingerprint)

    /** 清掉 [host]:[port] 的记录（用户确认换钥后手工重新 TOFU）。 */
    fun remove(host: String, port: Int)

    /** 当前记录条数（诊断用）。 */
    val size: Int
}

/** 内存实现：[KnownHostsStore] 的默认实现，进程内有效（单测与首次接线够用）。 */
class InMemoryKnownHostsStore : KnownHostsStore {

    private val entries = ConcurrentHashMap<String, MutableList<HostKeyFingerprint>>()

    override fun find(host: String, port: Int): List<HostKeyFingerprint> =
        entries[HostKeyFingerprint.key(host, port)]?.toList().orEmpty()

    override fun save(fingerprint: HostKeyFingerprint) {
        val key = fingerprint.storeKey
        entries.compute(key) { _, existing ->
            val list = existing ?: mutableListOf()
            if (list.none { it.sha256 == fingerprint.sha256 }) list.add(fingerprint)
            list
        }
    }

    override fun remove(host: String, port: Int) {
        entries.remove(HostKeyFingerprint.key(host, port))
    }

    override val size: Int get() = entries.values.sumOf { it.size }
}

/**
 * 主机密钥校验器（plan 4.9）。
 *
 * 实现必须保证「首次 TOFU 记住」与「变更不静默」两件事：
 * 未知主机按策略决定收不收，已知主机只在指纹一致时放行。
 */
fun interface HostKeyVerifier {

    /** 校验 [fingerprint]；实现负责在首次通过时记住它。 */
    fun verify(fingerprint: HostKeyFingerprint): HostKeyCheck
}

/**
 * TOFU 校验器（plan 4.9 的默认实现）。
 *
 * 规则：
 * 1. **未知主机**：按 [policy] 决定——[SftpHostKeyPolicy.STRICT] 拒绝、
 *    [SftpHostKeyPolicy.TOFU] 记住并放行、[SftpHostKeyPolicy.ACCEPT_ANY] 放行；
 * 2. **指纹一致**：放行；
 * 3. **指纹不一致**：触发 [onChange] 事件；[SftpHostKeyPolicy.ACCEPT_ANY] 放行（但仍然告警），
 *    其余策略一律拒绝，**并且不会覆盖已记录的旧指纹**（否则下次就再也发现不了变更了）。
 *
 * 本类线程安全（[store] 自身保证），可被多个连接/多个通道并发调用。
 *
 * @param store 指纹存储。
 * @param policy 信任策略。
 * @param onChange 变更事件回调（默认什么也不做，只把结论返回给调用方）。
 */
class TofuHostKeyVerifier(
    private val store: KnownHostsStore = InMemoryKnownHostsStore(),
    private val policy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU,
    private val onChange: (HostKeyChangeEvent) -> Unit = {},
) : HostKeyVerifier {

    /** 当前策略（诊断/展示用）。 */
    val hostKeyPolicy: SftpHostKeyPolicy get() = policy

    /** 底层存储（上层要做「清掉记录重新 TOFU」时用）。 */
    val knownHosts: KnownHostsStore get() = store

    override fun verify(fingerprint: HostKeyFingerprint): HostKeyCheck {
        val known = store.find(fingerprint.host, fingerprint.port)
        if (known.isEmpty()) {
            return when (policy) {
                SftpHostKeyPolicy.STRICT -> HostKeyCheck.Rejected(
                    fingerprint = fingerprint,
                    expected = null,
                    reason = HostKeyRejectReason.UNKNOWN_HOST,
                    message = unknownHostMessage(fingerprint),
                )
                // TOFU 与 ACCEPT_ANY 都要**记住**首见指纹：不记的话下一次真变更就检测不出来，
                // 也就变成了「静默接受」（plan 4.9 明确不允许）
                SftpHostKeyPolicy.TOFU, SftpHostKeyPolicy.ACCEPT_ANY -> {
                    store.save(fingerprint)
                    HostKeyCheck.Trusted(fingerprint, firstSeen = true)
                }
            }
        }
        if (known.any { it.sha256 == fingerprint.sha256 }) {
            return HostKeyCheck.Trusted(fingerprint, firstSeen = false)
        }
        val expected = known.first()
        onChange(
            HostKeyChangeEvent(
                host = fingerprint.host,
                port = fingerprint.port,
                keyType = fingerprint.keyType,
                expectedSha256 = expected.sha256,
                actualSha256 = fingerprint.sha256,
                policy = policy,
            ),
        )
        if (policy == SftpHostKeyPolicy.ACCEPT_ANY) {
            return HostKeyCheck.Trusted(fingerprint, firstSeen = false)
        }
        return HostKeyCheck.Rejected(
            fingerprint = fingerprint,
            expected = expected,
            reason = HostKeyRejectReason.CHANGED,
            message = changedMessage(fingerprint, expected),
        )
    }

    /**
     * 变更告警文案。
     *
     * 措辞刻意包含「证书不受信任」而不是「拒绝连接」：上层 `:core:network` 的
     * `ConnectivityError.fromMessage` 会把前者归到 TLS_FAILED（「安全连接失败」），
     * 后者会被误判成 CONNECTION_REFUSED（端口没开）。
     */
    private fun changedMessage(actual: HostKeyFingerprint, expected: HostKeyFingerprint): String =
        "SFTP 主机密钥已变更，证书不受信任（安全连接已中止）：期望 " + expected.sha256Display +
            "（" + expected.keyType + "），实际 " + actual.sha256Display + "（" + actual.keyType + "）"

    /** 未知主机（STRICT）文案，同样归到「安全连接失败」一类。 */
    private fun unknownHostMessage(actual: HostKeyFingerprint): String =
        "SFTP 主机密钥未在已知主机中，证书不受信任（安全连接已中止）：" +
            actual.host + ":" + actual.port + " " + actual.keyType + " " + actual.sha256Display
}

/**
 * 把 [HostKeyVerifier] 接到 JSch 的 [HostKeyRepository]（plan 4.9 的落地方式）。
 *
 * JSch 在 Session.connect() 的密钥交换阶段调用 [check]，返回值决定它是否继续：
 * - 返回 [HostKeyRepository.OK] → 继续握手；
 * - 返回 [HostKeyRepository.CHANGED] / [HostKeyRepository.NOT_INCLUDED] → 在
 *   StrictHostKeyChecking=yes 下 JSch 抛 JSchChangedHostKeyException /
 *   JSchUnknownHostKeyException，由 [SftpChannelPool] 翻译成中文 StorageException。
 *
 * 最近一次校验结论留在 [lastCheck] 里，供异常翻译时取指纹（JSch 的异常消息里没有 SHA-256）。
 *
 * @param host 本仓库绑定的主机（JSch 回传的 host 可能带 :port，这里以配置为准）。
 * @param port 本仓库绑定的端口。
 * @param verifier 上层校验器。
 */
internal class JschHostKeyRepository(
    private val host: String,
    private val port: Int,
    private val verifier: HostKeyVerifier,
) : HostKeyRepository {

    /** 最近一次 [check] 的结论；还没握过手时为 null。 */
    val lastCheck: AtomicReference<HostKeyCheck?> = AtomicReference(null)

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null || key.isEmpty()) return HostKeyRepository.NOT_INCLUDED
        val keyType = HostKeyFingerprint.keyTypeOf(key)
        val fingerprint = HostKeyFingerprint.of(this.host, port, keyType, key)
        val check = try {
            verifier.verify(fingerprint)
        } catch (t: Throwable) {
            // 校验器自己坏了也不能放行：证书不受信任
            HostKeyCheck.Rejected(
                fingerprint = fingerprint,
                expected = null,
                reason = HostKeyRejectReason.UNKNOWN_HOST,
                message = "SFTP 主机密钥校验失败，证书不受信任（安全连接已中止）：" +
                    t.javaClass.simpleName + " " + t.message.orEmpty(),
            )
        }
        lastCheck.set(check)
        if (check.accepted) return HostKeyRepository.OK
        val rejected = check as? HostKeyCheck.Rejected
        return if (rejected?.reason == HostKeyRejectReason.CHANGED) {
            HostKeyRepository.CHANGED
        } else {
            HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostKey: HostKey?, ui: UserInfo?) {
        // JSch 只在用户手工确认后调用它；后端不做交互式确认，这里按同一套校验器记一笔即可
        val key = hostKey?.key ?: return
        val blob = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return
        verifier.verify(HostKeyFingerprint.of(this.host, port, HostKeyFingerprint.keyTypeOf(blob), blob))
    }

    override fun remove(host: String?, type: String?) {
        // 主动删记录请走 KnownHostsStore#remove（用户确认换钥后用）；这里刻意不做任何事，
        // 避免 JSch 在「变更」流程里把旧指纹删掉，导致下一次变更检测不出来
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        // 同上：不静默换钥
    }

    override fun getKnownHostsRepositoryID(): String = "mediagate-known-hosts:" + this.host + ":" + port

    override fun getHostKey(): Array<HostKey> = emptyArray()

    /** JSch 会用返回的数组判定 @revoked；我们不维护 revoked 列表，返回空数组即可（不能返回 null）。 */
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
