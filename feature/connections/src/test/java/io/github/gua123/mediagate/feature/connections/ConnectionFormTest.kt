package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.NetworkCapability
import io.github.gua123.mediagate.core.network.ProtocolKind

/**
 * 连接表单校验的 JVM 单测（**R8**：新建/编辑时在本地就把错误拦下来）。
 *
 * 覆盖：名称、根路径、多地址、主机名、端口、传输方案、WebDAV 明文 http 开关，
 * 以及草稿的纯函数增删改（协议切换、加/删地址、加/删规则）。
 */
class ConnectionFormTest {

    private fun draft(
        protocol: ProtocolKind = ProtocolKind.WEBDAV,
        name: String = "家里的网盘",
        basePath: String = "/",
        host: String = "192.168.1.10",
        port: String = "8080",
        scheme: String = "http",
        allowInsecureHttp: Boolean = true,
        addresses: List<AddressDraft>? = null,
    ): ConnectionDraft = ConnectionDraft(
        name = name,
        protocol = protocol,
        basePath = basePath,
        allowInsecureHttp = allowInsecureHttp,
        addresses = addresses ?: listOf(AddressDraft(scheme = scheme, host = host, port = port)),
    )

    @Test
    fun `合法草稿通过校验`() {
        val result = ConnectionFormValidator.validate(draft())
        assertTrue(result.valid)
        assertTrue(result.globalErrors.isEmpty())
        assertTrue(result.addressErrors.single().isEmpty())
    }

    @Test
    fun `名称为空或过长都报错`() {
        val empty = ConnectionFormValidator.validate(draft(name = "   "))
        assertFalse(empty.valid)
        assertEquals("请填写连接名称", empty.errorOf(FormField.NAME))

        val tooLong = ConnectionFormValidator.validate(draft(name = "长".repeat(ConnectionFormValidator.MAX_NAME_LENGTH + 1)))
        assertTrue(tooLong.errorOf(FormField.NAME)!!.contains("最多"))
    }

    @Test
    fun `至少需要一个地址`() {
        val result = ConnectionFormValidator.validate(draft(addresses = emptyList()))
        assertFalse(result.valid)
        assertEquals("至少需要一个地址", result.errorOf(FormField.ADDRESSES))
    }

    @Test
    fun `根路径必须以斜杠开头且不能有双点`() {
        assertEquals(
            "根路径必须以 / 开头",
            ConnectionFormValidator.validate(draft(basePath = "media")).errorOf(FormField.BASE_PATH),
        )
        assertEquals(
            "根路径不能包含 ..",
            ConnectionFormValidator.validate(draft(basePath = "/../etc")).errorOf(FormField.BASE_PATH),
        )
    }

    @Test
    fun `本地连接的目录必须是绝对路径`() {
        val ok = ConnectionFormValidator.validate(
            draft(protocol = ProtocolKind.LOCAL, name = "本机", basePath = "/", host = "/storage/emulated/0", addresses = listOf(AddressDraft(scheme = "file", host = "/storage/emulated/0", port = "0"))),
        )
        assertTrue(ok.valid)
        val bad = ConnectionFormValidator.validate(
            draft(protocol = ProtocolKind.LOCAL, basePath = "/", addresses = listOf(AddressDraft(scheme = "file", host = "storage/emulated/0", port = "0"))),
        )
        assertTrue(bad.addressErrors.single()[FormField.HOST]!!.contains("绝对路径"))
    }

    @Test
    fun `主机名不能带协议前缀或路径`() {
        val withScheme = ConnectionFormValidator.validate(draft(host = "http://192.168.1.10"))
        assertTrue(withScheme.addressErrors.single()[FormField.HOST]!!.contains("不要带"))

        val withPath = ConnectionFormValidator.validate(draft(host = "192.168.1.10/dav"))
        assertTrue(withPath.addressErrors.single()[FormField.HOST]!!.contains("路径"))
    }

    @Test
    fun `端口必须是 1 到 65535 的数字`() {
        assertEquals("端口必须是数字", ConnectionFormValidator.validate(draft(port = "abc")).addressErrors.single()[FormField.PORT])
        assertTrue(ConnectionFormValidator.validate(draft(port = "0")).addressErrors.single()[FormField.PORT]!!.contains("1-65535"))
        assertTrue(ConnectionFormValidator.validate(draft(port = "70000")).addressErrors.single()[FormField.PORT]!!.contains("1-65535"))
        assertTrue(ConnectionFormValidator.validate(draft(port = "")).addressErrors.single().containsKey(FormField.PORT))
    }

    @Test
    fun `传输方案必须匹配协议`() {
        val bad = ConnectionFormValidator.validate(draft(scheme = "ftp"))
        assertTrue(bad.addressErrors.single()[FormField.SCHEME]!!.contains("http"))
        val sftpOk = ConnectionFormValidator.validate(
            draft(protocol = ProtocolKind.SFTP, name = "SFTP", scheme = "sftp", port = "2222"),
        )
        assertTrue(sftpOk.valid)
    }

    @Test
    fun `WebDAV 关掉明文开关后 http 被拦下`() {
        val blocked = ConnectionFormValidator.validate(draft(allowInsecureHttp = false, scheme = "http"))
        assertFalse(blocked.valid)
        assertTrue(blocked.addressErrors.single()[FormField.SCHEME]!!.contains("明文"))
        // 换成 https 就能过
        assertTrue(ConnectionFormValidator.validate(draft(allowInsecureHttp = false, scheme = "https", port = "443")).valid)
    }

    @Test
    fun `多个地址各自独立报错`() {
        val result = ConnectionFormValidator.validate(
            draft(
                addresses = listOf(
                    AddressDraft(scheme = "http", host = "192.168.1.10", port = "8080"),
                    AddressDraft(scheme = "http", host = "", port = "70000"),
                ),
            ),
        )
        assertFalse(result.valid)
        assertTrue(result.addressErrors[0].isEmpty())
        assertNotNull(result.addressErrors[1][FormField.HOST])
        assertNotNull(result.addressErrors[1][FormField.PORT])
    }

    @Test
    fun `密码过长被拦下`() {
        val result = ConnectionFormValidator.validate(
            draft().copy(password = "p".repeat(ConnectionFormValidator.MAX_PASSWORD_LENGTH + 1)),
        )
        assertTrue(result.addressErrors.single()[FormField.PASSWORD]!!.contains("过长"))
    }

    @Test
    fun `切换协议会换掉方案与端口但保留主机`() {
        val webdav = draft()
        val sftp = webdav.withProtocol(ProtocolKind.SFTP)
        assertEquals(ProtocolKind.SFTP, sftp.protocol)
        assertEquals("sftp", sftp.addresses.single().scheme)
        assertEquals("22", sftp.addresses.single().port)
        assertEquals("192.168.1.10", sftp.addresses.single().host)
        // 切到同一个协议是空操作
        assertEquals(sftp, sftp.withProtocol(ProtocolKind.SFTP))
    }

    @Test
    fun `地址增删改都是纯函数且至少保留一个`() {
        val original = draft()
        val added = original.plusAddress()
        assertEquals(2, added.addresses.size)
        assertEquals("原对象不变", 1, original.addresses.size)

        val edited = added.withAddress(1, added.addresses[1].copy(host = "dav.example.com"))
        assertEquals("dav.example.com", edited.addresses[1].host)

        val removed = edited.minusAddress(1)
        assertEquals(1, removed.addresses.size)
        assertEquals("只剩一个时不允许再删", 1, removed.minusAddress(0).addresses.size)
        assertEquals("越界不改", removed, removed.withAddress(5, AddressDraft()))
    }

    @Test
    fun `规则增删与空串归一`() {
        val base = draft()
        val withRule = base.plusRule(RuleDraft(transport = NetworkCapability.WIFI, ssidPattern = "Home*"))
        assertEquals(1, withRule.rules.size)
        assertTrue(base.rules.isEmpty())
        assertEquals(0, withRule.minusRule(0).rules.size)
        assertEquals(withRule, withRule.minusRule(9))

        val normalized = RuleDraft(transport = null, ssidPattern = "  ", localSubnet = " ").toNetworkRule()
        assertNull(normalized.ssidPattern)
        assertNull(normalized.localSubnet)
        assertEquals(AddressLabel.LAN, normalized.prefer)
    }

    @Test
    fun `各协议的默认值与允许方案`() {
        assertEquals("file", ProtocolDefaults.of(ProtocolKind.LOCAL).scheme)
        assertEquals("http", ProtocolDefaults.of(ProtocolKind.WEBDAV).scheme)
        assertEquals("22", ProtocolDefaults.of(ProtocolKind.SFTP).port)
        assertEquals(setOf("ftp", "ftps"), ProtocolDefaults.allowedSchemes(ProtocolKind.FTP))
        assertFalse(ProtocolDefaults.needsHostAndPort(ProtocolKind.LOCAL))
        assertTrue(ProtocolDefaults.needsHostAndPort(ProtocolKind.WEBDAV))
    }

    @Test
    fun `options 的解析与序列化`() {
        assertEquals("allowInsecureHttp=true", ConnectionOptions.Default.format())
        val off = ConnectionOptions(allowInsecureHttp = false, connectTimeoutMs = 8000L)
        val parsed = ConnectionOptions.parse(off.format())
        assertEquals(off, parsed)
        // 坏数据一律退回默认值，不抛异常
        assertEquals(ConnectionOptions.Default, ConnectionOptions.parse(null))
        assertEquals(ConnectionOptions.Default, ConnectionOptions.parse("乱写的东西"))
        assertEquals(ConnectionOptions.Default, ConnectionOptions.parse("connectTimeoutMs=abc"))
        assertEquals(false, ConnectionOptions.parse("allowInsecureHttp=false;unknown=1").allowInsecureHttp)
    }
}
