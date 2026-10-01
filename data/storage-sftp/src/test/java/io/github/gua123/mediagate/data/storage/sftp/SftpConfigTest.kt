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
        assertThrows(IllegalArgumentException::class.java) { config(maxChannels = 99) }
        assertEquals(2, config(maxChannels = 2).maxChannels)
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
