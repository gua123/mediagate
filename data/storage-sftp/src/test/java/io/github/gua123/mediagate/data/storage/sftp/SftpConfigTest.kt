package io.github.gua123.mediagate.data.storage.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SftpConfig] 校验与身份归一化（**R2** 协议接入 / **R6** 凭据不进日志 / plan 4.9）。
 *
 * 纯 JVM 单测，不需要服务器。
 */
class SftpConfigTest {

    private fun config(
        host: String = "nas.local",
        port: Int = 22,
        username: String = "demo",
        password: String? = "pwd",
        privateKey: SftpPrivateKey? = null,
        basePath: String = "/media",
        connectTimeoutMs: Long = 5_000L,
        readTimeoutMs: Long = 15_000L,
        policy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU,
        maxChannels: Int = 4,
        maxSessions: Int = 1,
        channelsPerSession: Int = 10,
        largeFileBytes: Long = SftpConfig.DEFAULT_LARGE_FILE_BYTES,
        smallFileSessions: Int = 2,
        acquireTimeoutMs: Long = SftpConfig.DEFAULT_ACQUIRE_TIMEOUT_MS,
    ) = SftpConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        privateKey = privateKey,
        basePath = basePath,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        hostKeyPolicy = policy,
        maxChannels = maxChannels,
        maxSessions = maxSessions,
        channelsPerSession = channelsPerSession,
        largeFileBytes = largeFileBytes,
        smallFileSessions = smallFileSessions,
        acquireTimeoutMs = acquireTimeoutMs,
    )

    @Test
    fun `默认值符合 plan 口径`() {
        val config = SftpConfig(host = "nas.local", username = "demo", password = "pwd")
        assertEquals(22, config.port)
        assertEquals("/", config.rootPath)
        assertEquals(SftpHostKeyPolicy.TOFU, config.hostKeyPolicy)
        assertEquals(SftpConfig.DEFAULT_MAX_CHANNELS, config.maxChannels)
        assertEquals(5_000L, config.connectTimeoutMs)
        assertEquals(15_000L, config.readTimeoutMs)
    }

    @Test
    fun `host 为空被拒`() {
        assertThrows(IllegalArgumentException::class.java) { config(host = "  ") }
    }

    @Test
    fun `端口越界被拒`() {
        assertThrows(IllegalArgumentException::class.java) { config(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(port = 70000) }
    }

    @Test
    fun `用户名不能为空`() {
        assertThrows(IllegalArgumentException::class.java) { config(username = "") }
    }

    @Test
    fun `必须给密码或私钥`() {
        assertThrows(IllegalArgumentException::class.java) { config(password = null) }
    }

    @Test
    fun `只给私钥也算合法`() {
        val key = SftpPrivateKey(privateKey = utf8("-----BEGIN EC PRIVATE KEY-----"))
        val config = config(password = null, privateKey = key)
        assertEquals(key, config.privateKey)
    }

    @Test
    fun `basePath 不允许越界`() {
        assertThrows(IllegalArgumentException::class.java) { config(basePath = "../../etc") }
    }

    @Test
    fun `超时必须为正数`() {
        assertThrows(IllegalArgumentException::class.java) { config(connectTimeoutMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { config(readTimeoutMs = -1L) }
    }

    @Test
    fun `通道池大小有上限`() {
        assertThrows(IllegalArgumentException::class.java) { config(maxChannels = 0) }
        // 上限从 16 提到 200（20 会话 × 10 通道，2026-11-20 多连接）：201 才越界
        assertThrows(IllegalArgumentException::class.java) { config(maxChannels = 201) }
        assertEquals(2, config(maxChannels = 2).maxChannels)
    }

    @Test
    fun `默认仍是单会话老行为`() {
        val config = config(maxChannels = 4)
        assertEquals(1, config.maxSessions)
        assertEquals(4, config.channelCapacity)
        assertEquals(4, config.effectiveChannelsPerSession)
    }

    @Test
    fun `多连接加速 20 会话 x 10 通道 = 200 通道预算`() {
        val config = config().withMultiConnectionAcceleration()
        assertEquals(SftpConfig.MULTI_CONNECTION_SESSIONS, config.maxSessions)
        assertEquals(10, config.channelsPerSession)
        assertEquals(200, config.maxChannels)
        assertEquals(200, config.channelCapacity)
        // 每条会话的都开满 10 条（否则等于白建连接）
        assertEquals(10, config.effectiveChannelsPerSession)
    }

    @Test
    fun `小文件只开 2 条、大文件开满`() {
        val config = config().withMultiConnectionAcceleration()
        assertEquals(2, config.sessionsForSize(100L * 1024 * 1024))
        assertEquals(2, config.sessionsForSize(null))
        assertEquals(20, config.sessionsForSize(SftpConfig.DEFAULT_LARGE_FILE_BYTES))
        assertEquals(20, config.sessionsForSize(10L * 1024 * 1024 * 1024))
    }

    @Test
    fun `小文件会话数被夹到总会话数以内`() {
        // 单会话配置下「小文件 2 条」自动夹成 1 条（老行为不能因为新参数变非法）
        assertEquals(1, config(maxSessions = 1, smallFileSessions = 2).sessionsForSize(1L))
        assertEquals(2, config(maxSessions = 2, smallFileSessions = 3).sessionsForSize(1L))
        assertThrows(IllegalArgumentException::class.java) { config(maxSessions = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(channelsPerSession = 33) }
    }

    @Test
    fun `id 对路径写法归一化`() {
        val a = config(basePath = "/media")
        val b = config(basePath = "media/")
        assertEquals(a.id, b.id)
        assertEquals(a.rootPath, b.rootPath)
        assertEquals("/media", a.rootPath)
        assertNotEquals(a.id, config(basePath = "/other").id)
    }

    @Test
    fun `toString 不泄漏密码与私钥`() {
        val key = SftpPrivateKey(privateKey = utf8("SUPER-SECRET-KEY-MATERIAL"))
        val text = config(password = "SUPER-SECRET-PASSWORD", privateKey = key).toString()
        assertFalse(text, text.contains("SUPER-SECRET-PASSWORD"))
        assertFalse(text, text.contains("SUPER-SECRET-KEY-MATERIAL"))
        assertTrue(text.contains("nas.local"))
    }

    @Test
    fun `私钥对象 toString 不泄漏内容`() {
        val key = SftpPrivateKey(privateKey = utf8("SUPER-SECRET-KEY-MATERIAL"), passphrase = "p")
        assertFalse(key.toString(), key.toString().contains("SUPER-SECRET"))
        assertTrue(key.toString(), key.toString().contains("encrypted=true"))
    }
}
