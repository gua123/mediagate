package io.github.gua123.mediagate.data.storage.sftp

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import io.github.gua123.mediagate.data.storage.api.RangeStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger

/**
 * 嵌入式 SFTP 服务器（**只存在于测试源集**，plan 2 章选型：org.apache.sshd:sshd-sftp）。
 *
 * 用 Apache MINA SSHD 起一个真实 SFTP 服务：随机端口、临时目录做 chroot、内存用户，
 * 这样 JVM 单测跑的是**真协议**（真 readdir / 真 SSH_FXP_READ + offset），而不是假实现。
 *
 * 两个可注入的「坏服务器」开关，用来测降级与权限路径（测试进程通常是 root，
 * chmod 拦不住 root，所以权限拒绝必须由服务端显式制造）：
 * - [readonlyPrefix]：该前缀下的**打开写句柄**一律回 SSH_FX_PERMISSION_DENIED（模拟只读目录）；
 * - [requestedPort]：指定端口（用于「停掉 A 换 B 但端口不变」的主机密钥变更测试）。
 *
 * @param rootDir 服务端根目录（客户端看到的 `/`）。
 * @param username / [password] 密码认证凭据（测试内固定，不是真凭据）。
 * @param hostKeyPair 主机密钥；默认每次新建一把 EC 密钥（所以两个实例天然指纹不同）。
 */
internal class SftpTestServer(
    val rootDir: Path,
    val username: String = DEFAULT_USER,
    val password: String = DEFAULT_PASSWORD,
    private val readonlyPrefix: String? = null,
    val hostKeyPair: KeyPair = newEcKeyPair(),
    requestedPort: Int = 0,
    private val acceptAnyPublicKey: Boolean = true,
) : Closeable {

    private val sshd: SshServer = SshServer.setUpDefaultServer()

    /** 实际监听端口（[requestedPort] 为 0 时由系统分配）。 */
    var port: Int = -1
        private set

    init {
        sshd.setHost("127.0.0.1")
        sshd.setPort(requestedPort)
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKeyPair))
        sshd.setPasswordAuthenticator(
            PasswordAuthenticator { user, pass, _ -> user == username && pass == password },
        )
        sshd.setPublickeyAuthenticator(PublickeyAuthenticator { _, _, _ -> acceptAnyPublicKey })
        sshd.setFileSystemFactory(VirtualFileSystemFactory(rootDir))
        val subsystem = SftpSubsystemFactory()
        if (readonlyPrefix != null) subsystem.addSftpEventListener(ReadOnlyEventListener(readonlyPrefix))
        sshd.setSubsystemFactories(listOf(subsystem))
        sshd.start()
        port = sshd.getPort()
    }

    override fun close() {
        runCatching { sshd.stop(true) }
    }

    /** 本服务器的后端配置（默认 TOFU 策略、小通道池以便断言复用）。 */
    fun config(
        policy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU,
        maxChannels: Int = 4,
        basePath: String = "/",
        password: String = this.password,
        privateKey: SftpPrivateKey? = null,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): SftpConfig = SftpConfig(
        host = "127.0.0.1",
        port = port,
        username = username,
        password = if (privateKey == null) password else null,
        privateKey = privateKey,
        basePath = basePath,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        hostKeyPolicy = policy,
        maxChannels = maxChannels,
    )

    /** 模拟只读目录：打开写句柄时直接回 SSH_FX_PERMISSION_DENIED。 */
    private class ReadOnlyEventListener(private val prefix: String) : SftpEventListener {

        override fun opening(session: ServerSession?, remoteHandle: String?, localHandle: Handle?) {
            val file = localHandle?.file?.toString() ?: return
            if (file.contains(prefix)) {
                throw SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "read-only (test): " + file)
            }
        }
    }

    companion object {
        const val DEFAULT_USER: String = "tester"
        const val DEFAULT_PASSWORD: String = "sftp-test-secret"
        const val CONNECT_TIMEOUT_MS: Long = 8_000L
        const val READ_TIMEOUT_MS: Long = 15_000L

        /** 新建一把 256 位 EC 密钥（比 RSA 生成快得多，且两把密钥一定不同）。 */
        fun newEcKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}

/** 临时目录 + 常用小工具（每个测试类自建自删）。 */
internal fun newTempDir(prefix: String): Path = Files.createTempDirectory(prefix)

/** 写一个测试文件（父目录自动建）。 */
internal fun Path.writeFile(relative: String, bytes: ByteArray): Path {
    val target = resolve(relative)
    target.parent?.let { Files.createDirectories(it) }
    Files.write(target, bytes)
    return target
}

/** 写一个测试文件（文本）。 */
internal fun Path.writeTextFile(relative: String, text: String): Path = writeFile(relative, utf8(text))

/** 造一段可验证的数据：第 i 个字节 = (i * 31 + 7) mod 251（保证区间读能比对出偏移错位）。 */
internal fun sampleBytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) % 251).toByte() }

internal fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

internal fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

/** 把一段流读到 EOF。 */
internal suspend fun readAll(stream: RangeStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val n = stream.read(buffer, 0, buffer.size)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** 精确读 [length] 字节（少读就算失败：区间读语义必须精确，R4 靠它）。 */
internal suspend fun readExactly(stream: RangeStream, length: Int): ByteArray {
    val out = ByteArrayOutputStream(length)
    val buffer = ByteArray(minOf(length, 4096).coerceAtLeast(1))
    while (out.size() < length) {
        val n = stream.read(buffer, 0, minOf(buffer.size, length - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** 文件系统里递归找一个小文件（真网测试挑样本用）。 */
internal fun smallFiles(root: Path, minSize: Long, maxSize: Long): List<Path> {
    val counter = AtomicInteger()
    return Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { Files.size(it) in minSize..maxSize }
            .limit(20)
            .toList()
    }.also { counter.incrementAndGet() }
}
