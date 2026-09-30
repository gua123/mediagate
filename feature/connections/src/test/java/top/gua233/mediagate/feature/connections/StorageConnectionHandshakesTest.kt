package io.github.gua123.mediagate.feature.connections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.data.storage.ftp.FtpsMode
import java.net.ServerSocket

/**
 * **M5 新增**：SFTP / FTP 的第三段协议握手装配与失败分类（**R8** / plan 4.5）。
 *
 * 只测纯逻辑与「连不上时的分类」：不依赖任何真服务器（真服务器由
 * `:data:storage-sftp` 的 opt-in 真网用例覆盖）。既有 M4 用例（LOCAL/WEBDAV）
 * 一个字都没改，仍与 [ConnectionHandshakes] 的语义一致。
 */
class StorageConnectionHandshakesTest {

    private fun record(
        protocol: ProtocolKind,
        username: String? = "u",
        basePath: String = "/",
        tls: String? = null,
        connectTimeoutMs: Long? = null,
    ) = ConnectionRecord(
        id = 1L,
        name = protocol.id,
        protocolId = protocol.id,
        protocol = protocol,
        basePath = basePath,
        username = username,
        hasSecret = false,
        options = ConnectionOptions.Default.copy(connectTimeoutMs = connectTimeoutMs),
        tls = tls,
        lastWorkingAddressId = null,
        lastCheckedAt = null,
        addresses = emptyList(),
        rules = emptyList(),
    )

    private fun address(port: Int) = SelectableAddress(1L, AddressLabel.LAN, "sftp", "127.0.0.1", port)

    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `SFTP 连接装配出 SFTP 握手`() {
        val keys = StorageConnectionHandshakes.forConnection(record(ProtocolKind.SFTP), "pw").keys
        assertEquals(setOf(ProtocolKind.SFTP), keys)
    }

    @Test
    fun `FTP 连接装配出 FTP 握手`() {
        val keys = StorageConnectionHandshakes.forConnection(record(ProtocolKind.FTP), "pw").keys
        assertEquals(setOf(ProtocolKind.FTP), keys)
    }

    @Test
    fun `LOCAL 与 WEBDAV 仍走 M4 的实现`() {
        assertEquals(
            ConnectionHandshakes.forConnection(record(ProtocolKind.LOCAL), null).keys,
            StorageConnectionHandshakes.forConnection(record(ProtocolKind.LOCAL), null).keys,
        )
        assertEquals(
            ConnectionHandshakes.forConnection(record(ProtocolKind.WEBDAV), "pw").keys,
            StorageConnectionHandshakes.forConnection(record(ProtocolKind.WEBDAV), "pw").keys,
        )
    }

    @Test
    fun `未知协议仍返回空表`() {
        val unknown = record(ProtocolKind.LOCAL).copy(protocol = null)
        assertTrue(StorageConnectionHandshakes.forConnection(unknown, null).isEmpty())
    }

    @Test
    fun `SFTP 端口不可达给出连接失败分类`() = runBlocking {
        val handshake = StorageConnectionHandshakes.sftp(
            record(ProtocolKind.SFTP, connectTimeoutMs = 800L),
            "pw",
        )
        val outcome = handshake.handshake(address(deadPort()))
        println("[单测] SFTP 握手（端口不可达）ok=" + outcome.ok + " error=" + outcome.error + " message=" + outcome.message)
        assertFalse(outcome.ok)
        assertEquals(ConnectivityError.CONNECTION_REFUSED, outcome.error)
        assertTrue(outcome.message.orEmpty(), outcome.message.orEmpty().contains("拒绝"))
    }

    @Test
    fun `FTP 端口不可达给出连接失败分类`() = runBlocking {
        val handshake = StorageConnectionHandshakes.ftp(
            record(ProtocolKind.FTP, connectTimeoutMs = 800L),
            "pw",
        )
        val outcome = handshake.handshake(address(deadPort()))
        println("[单测] FTP 握手（端口不可达）ok=" + outcome.ok + " error=" + outcome.error + " message=" + outcome.message)
        assertFalse(outcome.ok)
        assertEquals(ConnectivityError.CONNECTION_REFUSED, outcome.error)
        assertTrue(outcome.message.orEmpty(), outcome.message.orEmpty().contains("拒绝"))
    }

    @Test
    fun `SFTP 缺用户名给出配置不合法而不是崩掉`() = runBlocking {
        val outcome = StorageConnectionHandshakes.sftp(record(ProtocolKind.SFTP, username = null), null)
            .handshake(address(deadPort()))
        assertFalse(outcome.ok)
        assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, outcome.error)
        assertTrue(outcome.message.orEmpty(), outcome.message.orEmpty().contains("配置不合法"))
    }

    @Test
    fun `FTP 缺用户名给出配置不合法而不是崩掉`() = runBlocking {
        val outcome = StorageConnectionHandshakes.ftp(record(ProtocolKind.FTP, username = "  "), "pw")
            .handshake(address(deadPort()))
        assertFalse(outcome.ok)
        assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, outcome.error)
    }

    @Test
    fun `tls 列映射到 FTPS 模式`() {
        assertEquals(FtpsMode.NONE, StorageConnectionHandshakes.ftpsModeOf(null))
        assertEquals(FtpsMode.NONE, StorageConnectionHandshakes.ftpsModeOf(""))
        assertEquals(FtpsMode.NONE, StorageConnectionHandshakes.ftpsModeOf("明文"))
        assertEquals(FtpsMode.EXPLICIT, StorageConnectionHandshakes.ftpsModeOf("explicit"))
        assertEquals(FtpsMode.EXPLICIT, StorageConnectionHandshakes.ftpsModeOf("FTPS"))
        assertEquals(FtpsMode.EXPLICIT, StorageConnectionHandshakes.ftpsModeOf(" TLS "))
        assertEquals(FtpsMode.IMPLICIT, StorageConnectionHandshakes.ftpsModeOf("implicit"))
    }
}
