package io.github.gua123.mediagate.data.storage.ftp

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.command.CommandFactory
import org.apache.ftpserver.command.CommandFactoryFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.PropertiesUserManager
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.ftplet.UserManager
import java.io.Closeable
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 嵌入式 FTP 服务器（**只存在于测试源集**，plan 2 章选型：org.apache.ftpserver:ftpserver-core）。
 *
 * 真实协议、随机端口、临时目录做根、内存/临时用户的属性文件：
 * 这样单测跑的是真 LIST / MLSD / REST / RETR / STOR，而不是假实现。
 *
 * 两个可注入的开关用来测降级链与权限：
 * - [disabledCommands]：把某些命令从 [CommandFactory] 里摘掉（返回 null），
 *   服务端就会回 500 —— 用来模拟「不支持 REST」「不支持 MLSD」的老服务器；
 * - [writable]：用户是否带 [WritePermission]（不带则 STOR 被拒 → AccessDenied）。
 *
 * @param rootDir 服务端根目录（客户端看到的 `/`）。
 * @param username / [password] 登录凭据（测试内固定，不是真凭据）。
 */
internal class FtpTestServer(
    val rootDir: Path,
    val username: String = DEFAULT_USER,
    val password: String = DEFAULT_PASSWORD,
    private val writable: Boolean = true,
    disabledCommands: Set<String> = emptySet(),
    val port: Int = freePort(),
) : Closeable {

    private val server: FtpServer

    init {
        val factory = FtpServerFactory()
        val listenerFactory = ListenerFactory()
        listenerFactory.setPort(port)
        factory.addListener("default", listenerFactory.createListener())
        factory.setUserManager(userManager())
        if (disabledCommands.isNotEmpty()) {
            // DefaultCommandFactory 的默认构造是空表，标准命令要由 CommandFactoryFactory 装配
            val delegate = CommandFactoryFactory().createCommandFactory()
            val disabled = disabledCommands.map { it.uppercase() }.toSet()
            factory.setCommandFactory(CommandFactory { name -> if (disabled.contains(name.uppercase())) null else delegate.getCommand(name) })
        }
        server = factory.createServer()
        server.start()
    }

    override fun close() {
        runCatching { server.stop() }
    }

    /** 本服务器的后端配置。 */
    fun config(
        basePath: String = "/",
        ftpsMode: FtpsMode = FtpsMode.NONE,
        passive: Boolean = true,
        maxConnections: Int = 2,
        password: String = this.password,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): FtpConfig = FtpConfig(
        host = "127.0.0.1",
        port = port,
        username = username,
        password = password,
        basePath = basePath,
        ftpsMode = ftpsMode,
        passive = passive,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        maxConnections = maxConnections,
    )

    /**
     * 用户表放在临时 properties 文件里（[PropertiesUserManager] 的常规用法）。
     *
     * 直接写标准键值而不是走 `save(User)`：`save` 依赖 BaseUser 的密码字段回读，
     * 行为不够直观；直接写文件所见即所得，也便于断言权限差异（[writable]）。
     */
    private fun userManager(): UserManager {
        val file = Files.createTempFile("mediagate-ftp-users", ".properties").toFile()
        file.deleteOnExit()
        val prefix = "ftpserver.user." + username + "."
        file.writeText(
            listOf(
                prefix + "userpassword=" + password,
                // 默认的 NativeFileSystemFactory 就以用户的 homeDirectory 为根（客户端看到的 /）
                prefix + "homedirectory=" + rootDir.toAbsolutePath(),
                prefix + "enable=true",
                prefix + "writepermission=" + writable,
                prefix + "maxloginnumber=0",
                prefix + "maxloginperip=0",
                prefix + "idletime=0",
                prefix + "uploadrate=0",
                prefix + "downloadrate=0",
            ).joinToString("\n"),
        )
        return PropertiesUserManager(ClearTextPasswordEncryptor(), file, ADMIN)
    }

    companion object {
        const val DEFAULT_USER: String = "tester"
        const val DEFAULT_PASSWORD: String = "ftp-test-secret"
        const val ADMIN: String = "admin"
        const val CONNECT_TIMEOUT_MS: Long = 8_000L
        const val READ_TIMEOUT_MS: Long = 15_000L

        /** 拿一个「当前没人监听」的端口。 */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

/** 临时目录。 */
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

/** 造一段可验证的数据：第 i 个字节 = (i * 31 + 7) mod 251（区间读能一眼比对出偏移错位）。 */
internal fun sampleBytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) % 251).toByte() }

internal fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

internal fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

/** 把一段流读到 EOF。 */
internal suspend fun readAll(stream: io.github.gua123.mediagate.data.storage.api.RangeStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val n = stream.read(buffer, 0, buffer.size)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** 精确读 [length] 字节（少读就算失败：区间读语义必须精确，R4 靠它）。 */
internal suspend fun readExactly(
    stream: io.github.gua123.mediagate.data.storage.api.RangeStream,
    length: Int,
): ByteArray {
    val out = java.io.ByteArrayOutputStream(length)
    val buffer = ByteArray(minOf(length, 4096).coerceAtLeast(1))
    while (out.size() < length) {
        val n = stream.read(buffer, 0, minOf(buffer.size, length - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
