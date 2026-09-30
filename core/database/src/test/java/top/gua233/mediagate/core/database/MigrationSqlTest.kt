package io.github.gua123.mediagate.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移的**离线校验**（**M7-B / R19**：core:database 的纯加法 2 → 3）。
 *
 * 为什么要这个单测：Room 的迁移 SQL 是手写的，实体却是注解生成的；两者一旦漂移，
 * 真机上表现为「升级安装后一进任务中心就崩」（Room 打开库时会校验实际表结构与实体定义是否一致），
 * 而那种问题只有真的覆盖安装才会暴露。这里把 Room 刚导出的 schema JSON 读回来，
 * 与 [MediaGateDatabase.MIGRATION_2_3_SQL] 逐条比对：任何字段、类型、非空、索引的差异立刻失败。
 *
 * 不引 JSON 库也不写正则：schema 是我们自己 KSP 的产物，按 key 取字符串足够，
 * 也不会被反斜杠转义坑到（对照：手写正则时 \" 与 \\s 极易写错）。
 */
class MigrationSqlTest {

    @Test
    fun migration2To3_matchesExportedSchemaExactly() {
        val expected = readStatements(version = 3, table = "asr_batch") +
            readStatements(version = 3, table = "asr_task")
        val actual = MediaGateDatabase.MIGRATION_2_3_SQL.map { normalize(it) }
        assertEquals("迁移 DDL 与 Room 导出 schema 不一致", expected.toSet(), actual.toSet())
        assertEquals(actual.size, actual.toSet().size)
        assertEquals(expected.size, actual.size)
    }

    @Test
    fun exportedSchemaHasEveryAsrTaskColumn() {
        val sql = readStatements(version = 3, table = "asr_task").first()
        listOf(
            "batchId", "connectionId", "path", "name", "model", "state",
            "progressMs", "durationMs", "outputPath", "error", "failure",
            "skipReason", "retryCount", "queueOrder", "createdAt",
        ).forEach { column ->
            assertTrue("缺少列 " + column + "：" + sql, sql.contains(BACKTICK + column + BACKTICK))
        }
    }

    @Test
    fun exportedSchemaHasAsrIndices() {
        val sql = (readStatements(version = 3, table = "asr_batch") + readStatements(version = 3, table = "asr_task"))
            .joinToString(" ")
        assertTrue(sql.contains("index_asr_batch_state"))
        assertTrue(sql.contains("index_asr_task_batchId"))
        assertTrue(sql.contains("index_asr_task_state"))
        assertTrue(sql.contains("index_asr_task_queueOrder"))
    }

    @Test
    fun migrationsCoverVersionOneToThree() {
        assertEquals(2, MediaGateDatabase.MIGRATIONS.size)
        assertTrue(MediaGateDatabase.NAME.isNotEmpty())
    }

    /** 从导出的 schema JSON 里读出某张表的 createSql（表本身 + 它的索引）。 */
    private fun readStatements(version: Int, table: String): List<String> {
        val file = schemaFile(version)
        assertTrue("找不到 schema 文件：" + file.absolutePath, file.isFile)
        val text = file.readText()

        val tables = ArrayList<JsonString>()
        var cursor = 0
        while (true) {
            val hit = nextString(text, "tableName", cursor) ?: break
            tables += hit
            cursor = hit.end
        }
        val target = tables.indexOfFirst { it.value == table }
        assertTrue("schema 里没有表 " + table + "（现有：" + tables.map { it.value } + "）", target >= 0)
        val block = text.substring(
            tables[target].keyIndex,
            if (target + 1 < tables.size) tables[target + 1].keyIndex else text.length,
        )

        val statements = ArrayList<String>()
        var inner = 0
        while (true) {
            val hit = nextString(block, "createSql", inner) ?: break
            statements += normalize(hit.value.replace(TABLE_PLACEHOLDER, table))
            inner = hit.end
        }
        assertTrue("表 " + table + " 没读到任何 createSql", statements.isNotEmpty())
        return statements
    }

    /** 取 "key": "value" 里的 value（key 与冒号之间、冒号与引号之间允许有空白）。 */
    private fun nextString(text: String, key: String, from: Int): JsonString? {
        val marker = QUOTE + key + QUOTE
        val keyIndex = text.indexOf(marker, from)
        if (keyIndex < 0) return null
        val colon = text.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        val open = text.indexOf(QUOTE, colon + 1)
        if (open < 0) return null
        val close = text.indexOf(QUOTE, open + 1)
        if (close < 0) return null
        return JsonString(text.substring(open + 1, close), close, keyIndex)
    }

    /** 沿目录向上找 schemas/（Gradle 单测的工作目录是模块目录，但多找几层更稳）。 */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/io.github.gua123.mediagate.core.database.MediaGateDatabase/" + version + ".json"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromRoot = File(dir, "core/database/" + relative)
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        return File(relative)
    }

    private fun normalize(sql: String): String =
        sql.split(' ', '\n', '\t', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    private class JsonString(val value: String, val end: Int, val keyIndex: Int)

    private companion object {
        const val QUOTE = '"'
        const val BACKTICK = '`'
        /** Room 导出的 SQL 里表名是占位符，比对前替换成真实表名。 */
        val TABLE_PLACEHOLDER: String = '$' + "{TABLE_NAME}"
    }
}
