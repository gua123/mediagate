package io.github.gua123.mediagate.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [ErrorText] 的 JVM 单测（**R16**：界面简体中文单语，不出现库的英文原文）。
 *
 * 用户 2026-10-02 明确要求：「app 中界面的语言直接使用中文即可，不需要中英双语」。
 * 这里锁住三条口径：自己的中文原样保留、英文能归类就归成中文、归不了也不能把英文甩出去。
 */
class ErrorTextTest {

    @Test
    fun `自己的中文提示原样保留`() {
        val text = "还没有保存密码，请在连接页编辑这条连接并填写密码"
        assertEquals(text, ErrorText.of(text))
        assertEquals(text, ErrorText.of(IllegalStateException(text)))
    }

    @Test
    fun `明文被系统拦时给的是能照做的中文`() {
        val english = "CLEARTEXT communication to 192.168.1.10 not permitted by network security policy"
        val zh = ErrorText.of(IOException(english))
        assertTrue("必须说清是明文被拦：$zh", zh.contains("明文"))
        assertTrue("要给出口：$zh", zh.contains("允许明文 http"))
        assertEquals(zh, ErrorText.of(english))
    }

    @Test
    fun `常见网络英文都能归成中文`() {
        assertEquals("域名解析失败：检查主机名与当前网络的 DNS", ErrorText.of(UnknownHostException("nas.local")))
        assertEquals("连接超时：地址不可达或对方没有响应", ErrorText.of(SocketTimeoutException("timeout")))
        assertEquals("连接被拒绝：端口没开、服务没启动或被防火墙挡住", ErrorText.of(ConnectException("Connection refused")))
        assertTrue(ErrorText.of(IOException("No route to host")).contains("网络不可达"))
        assertTrue(ErrorText.of(IOException("Connection reset by peer")).contains("中断"))
        assertTrue(ErrorText.of(IOException("unable to find valid certification path")).contains("安全连接"))
        assertTrue(ErrorText.of(IOException("401 Unauthorized")).contains("认证失败"))
        assertTrue(ErrorText.of(IOException("550 No such file")).contains("路径不存在"))
    }

    @Test
    fun `认不出的英文与空消息都走兜底文案`() {
        assertEquals(ErrorText.FALLBACK, ErrorText.of(IOException("boom")))
        assertEquals(ErrorText.FALLBACK, ErrorText.of((null as String?)))
        assertEquals(ErrorText.FALLBACK, ErrorText.of("   "))
        assertEquals(ErrorText.FALLBACK, ErrorText.of(RuntimeException()))
        assertEquals("配置或网络有问题", ErrorText.of(IOException("boom"), "配置或网络有问题"))
        assertNull(ErrorText.classify(""))
        assertNull(ErrorText.classify("boom"))
    }

    @Test
    fun `汉字判定`() {
        assertTrue(ErrorText.hasChinese("网络不可达"))
        assertTrue(ErrorText.hasChinese("SFTP 连接失败"))
        assertTrue(!ErrorText.hasChinese("Connection refused"))
        assertTrue(!ErrorText.hasChinese(null))
    }
}
