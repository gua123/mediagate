package io.github.gua123.mediagate.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「另存为文件」的默认文件名（2026-10-03 用户要求：错误报告要能复制/另存，否则只能截图）。
 *
 * 只钉格式（时区会让具体时刻不同，不做逐字断言）：mediagate-崩溃报告-yyyyMMdd-HHmmss.txt
 */
class DefaultReportFileNameTest {

    @Test
    fun `文件名带时间戳且是 txt`() {
        val name = defaultReportFileName(1_700_000_000_000L)
        assertTrue("格式不对：" + name, Regex("^mediagate-崩溃报告-\\d{8}-\\d{6}\\.txt$").matches(name))
    }

    @Test
    fun `没拿到崩溃时间时退回到当前时间（不崩）`() {
        val name = defaultReportFileName(null)
        assertTrue("格式不对：" + name, name.startsWith("mediagate-崩溃报告-") && name.endsWith(".txt"))
    }
}
