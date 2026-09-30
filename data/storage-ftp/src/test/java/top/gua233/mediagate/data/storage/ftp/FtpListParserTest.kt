package io.github.gua123.mediagate.data.storage.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * LIST / MLSD 解析（**R2** / plan 4.1「FTP：MLSD→LIST」）。
 *
 * 纯函数单测：不碰网络，覆盖 Unix `ls -l` 与 DOS 两种 LIST 方言、MLSD facts、
 * 名字含空格、`total` 行与 `.`/`..` 过滤、无法解析的行跳过。
 */
class FtpListParserTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `MLSD 解析文件与目录`() {
        val entries = parseMlsd(
            listOf(
                "type=file;size=1234;modify=20240102030405;UNIX.mode=0644; movie.mkv",
                "type=dir;modify=20240102030405;UNIX.mode=0755; movies",
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("movie.mkv", entries[0].name)
        assertFalse(entries[0].isDirectory)
        assertEquals(1234L, entries[0].size)
        assertEquals(utc(2024, 1, 2, 3, 4) + 5_000L, entries[0].mtimeMs)
        assertTrue(entries[1].isDirectory)
        assertEquals(-1L, entries[1].size)
    }

    @Test
    fun `MLSD 过滤 cdir 与 pdir`() {
        val entries = parseMlsd(
            listOf(
                "type=cdir;modify=20240102030405; .",
                "type=pdir;modify=20240102030405; ..",
                "type=file;size=1; a.txt",
            ),
        )
        assertEquals(listOf("a.txt"), entries.map { it.name })
    }

    @Test
    fun `MLSD 名字含空格与中文`() {
        val entries = parseMlsd(listOf("type=file;size=42;modify=20240102030405; 我的 电影 01.mkv"))
        assertEquals("我的 电影 01.mkv", entries.single().name)
    }

    @Test
    fun `MLSD 跳过无法解析的行`() {
        val entries = parseMlsd(listOf("", "garbage without facts", "type=file;size=1; ok.txt", "size=9; no-type"))
        assertEquals(listOf("ok.txt"), entries.map { it.name })
    }

    @Test
    fun `MLSD 缺字段时用兜底值`() {
        val entries = parseMlsd(listOf("type=file; name.txt"))
        assertEquals(-1L, entries.single().size)
        assertEquals(0L, entries.single().mtimeMs)
    }

    @Test
    fun `Unix LIST 解析文件与目录`() {
        val entries = parseList(
            listOf(
                "total 12",
                "-rw-r--r--   1 owner group     1234 Jan  2 03:04 movie.mkv",
                "drwxr-xr-x   2 owner group     4096 Jan  2  2020 movies",
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("movie.mkv", entries[0].name)
        assertFalse(entries[0].isDirectory)
        assertEquals(1234L, entries[0].size)
        assertTrue(entries[1].isDirectory)
        assertEquals(-1L, entries[1].size)
        assertEquals(-1L, entries[1].size)
        // 只有年份的写法（超过 6 个月）算到那一年
        assertEquals(utc(2020, 1, 2, 0, 0), entries[1].mtimeMs)
    }

    @Test
    fun `Unix LIST 名字含空格`() {
        val entries = parseList(listOf("-rw-r--r--   1 owner group     10 Jan  2 03:04 my movie file.mkv"))
        assertEquals("my movie file.mkv", entries.single().name)
    }

    @Test
    fun `Unix LIST 软链接去掉箭头目标`() {
        val entries = parseList(listOf("lrwxrwxrwx   1 owner group        7 Jan  2 03:04 link.mkv -> real.mkv"))
        assertEquals("link.mkv", entries.single().name)
        assertFalse(entries.single().isDirectory)
    }

    @Test
    fun `Unix LIST 过滤点目录与坏行`() {
        val entries = parseList(
            listOf(
                "drwxr-xr-x   2 owner group     4096 Jan  2 03:04 .",
                "drwxr-xr-x   3 owner group     4096 Jan  2 03:04 ..",
                "this is not a listing line",
                "-rw-r--r--   1 owner group        1 Jan  2 03:04 ok.txt",
            ),
        )
        assertEquals(listOf("ok.txt"), entries.map { it.name })
    }

    @Test
    fun `DOS LIST 解析目录与文件`() {
        val entries = parseList(
            listOf(
                "01-02-24  03:04PM       <DIR>          movies",
                "01-02-24  03:04PM                 1234 my movie.mkv",
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("movies", entries[0].name)
        assertTrue(entries[0].isDirectory)
        assertEquals(-1L, entries[0].size)
        assertEquals("my movie.mkv", entries[1].name)
        assertFalse(entries[1].isDirectory)
        assertEquals(1234L, entries[1].size)
        assertEquals(utc(2024, 1, 2, 15, 4), entries[1].mtimeMs)
    }

    @Test
    fun `DOS LIST 上午时间不打转`() {
        val entries = parseList(listOf("01-02-24  12:00AM                 7 midnight.txt"))
        assertEquals(utc(2024, 1, 2, 0, 0), entries.single().mtimeMs)
    }

    @Test
    fun `路径归一化与拼接`() {
        assertEquals("", normalizeFtpPath("/"))
        assertEquals("a/b", normalizeFtpPath("/a//b/"))
        assertEquals("a/b", normalizeFtpPath("a\\b"))
        assertEquals("a/b", joinFtpPath("a", "b"))
        assertEquals("b", joinFtpPath("", "b"))
        assertEquals("/media", absoluteFtpRoot("/media/"))
        assertEquals("/media/a.mkv", absoluteFtpPath("/media", "a.mkv"))
        assertEquals("/a.mkv", absoluteFtpPath("/", "a.mkv"))
        assertEquals("a", ftpParentOf("a/b"))
        assertEquals("b", ftpNameOf("a/b"))
    }

    @Test
    fun `请求长度计算考虑越界`() {
        assertEquals(50L, effectiveFtpLength(100L, 50L, -1L))
        assertEquals(0L, effectiveFtpLength(100L, 200L, -1L))
        // 已知总长时按剩余字节夹住（与 storage-local 的 effectiveLength 同口径）
        assertEquals(5L, effectiveFtpLength(100L, 95L, 10L))
        assertEquals(10L, effectiveFtpLength(-1L, 95L, 10L))
    }

    @Test
    fun `路径越界抛 AccessDenied`() {
        org.junit.Assert.assertThrows(io.github.gua123.mediagate.data.storage.api.StorageException.AccessDenied::class.java) {
            normalizeFtpPath("../etc/passwd")
        }
    }
}
