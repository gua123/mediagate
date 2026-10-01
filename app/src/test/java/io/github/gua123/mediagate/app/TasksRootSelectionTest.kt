package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 任务页来源判定（2026-10-03）：远端生效就用远端的名字，否则用本地；都没有就不给来源。
 *
 * **路径一律是后端内的相对根**（`""`）：远端后端的根就是连接的 basePath，浏览器也从 `""` 开始，
 * 任务页与它同一口径（旧实现把 basePath 又拼了一遍，basePath=/media 时会去列 /media/media）。
 */
class TasksRootSelectionTest {

    @Test
    fun `远端连接生效时用远端名字且从后端根开始`() {
        val root = tasksRootOf(remoteName = "hml", localDisplay = "已授权目录")
        assertEquals("hml", root?.label)
        assertEquals("", root?.path)
    }

    @Test
    fun `没有远端时用本地展示名`() {
        val root = tasksRootOf(remoteName = null, localDisplay = "内部存储")
        assertEquals("内部存储", root?.label)
        assertEquals("", root?.path)
    }

    @Test
    fun `两边都没有时不给来源（页面提示去选目录）`() {
        assertNull(tasksRootOf(null, null))
        assertNull(tasksRootOf("", ""))
        assertNull(tasksRootOf("   ", "  "))
    }
}
