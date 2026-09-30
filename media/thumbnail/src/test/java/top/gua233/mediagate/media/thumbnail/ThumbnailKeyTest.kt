package io.github.gua123.mediagate.media.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.File

/**
 * [ThumbnailKey] 的 JVM 单测（R5）：key 稳定性、size/mtime 敏感性与磁盘路径策略。
 *
 * 只用纯 Kotlin 逻辑，不依赖任何 Android API。
 */
class ThumbnailKeyTest {

    private fun entry(
        name: String = "video.mp4",
        path: String = "/movies/video.mp4",
        size: Long = 1_000_000L,
        mtime: Long = 1_700_000_000_000L,
    ) = RemoteEntry(name = name, path = path, size = size, mtime = mtime)

    @Test
    fun `同 key 稳定且可复现`() {
        val a = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        val b = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.hash, b.hash)
        assertEquals(a.relativePath, b.relativePath)
        // 哈希是 32 位小写十六进制（SHA-256 前 16 字节）
        assertEquals(32, a.hash.length)
        assertTrue("hash=${a.hash}", a.hash.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `size 变更后 key 必变`() {
        val base = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        val changed = ThumbnailKey.of("local-file:/sdcard", entry(size = 1_000_001L), targetWidth = 256)
        assertNotEquals(base, changed)
        assertNotEquals(base.hash, changed.hash)
        assertNotEquals(base.relativePath, changed.relativePath)
    }

    @Test
    fun `mtime 变更后 key 必变`() {
        val base = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        val changed = ThumbnailKey.of("local-file:/sdcard", entry(mtime = 1_700_000_001_000L), targetWidth = 256)
        assertNotEquals(base, changed)
        assertNotEquals(base.hash, changed.hash)
    }

    @Test
    fun `后端或路径变更后 key 必变`() {
        val base = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        assertNotEquals(base, ThumbnailKey.of("webdav:https://host", entry(), targetWidth = 256))
        assertNotEquals(base, ThumbnailKey.of("local-file:/sdcard", entry(path = "/movies/other.mp4"), targetWidth = 256))
        // size/mtime 未知（-1 / 0）同样是合法输入，且与已知值区分
        val unknown = ThumbnailKey.of("local-file:/sdcard", entry(size = -1L, mtime = 0L), targetWidth = 256)
        assertNotEquals(base, unknown)
    }

    @Test
    fun `磁盘路径策略为 kind 哈希前2位 与 webp`() {
        val key = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        val expected = "video/${key.hash.substring(0, 2)}/${key.hash}.webp"
        assertEquals(expected, key.relativePath)
        assertEquals(key.hash.substring(0, 2), key.shard)

        val root = File("/tmp/thumb-cache")
        val file = File(root, key.relativePath)
        // <cacheDir>/<kind>/<hash前2位>/<hash>.webp
        assertEquals("video", file.parentFile!!.parentFile!!.name)
        assertEquals(key.shard, file.parentFile!!.name)
        assertEquals("${key.hash}.webp", file.name)
    }

    @Test
    fun `kind 按文件名判定并决定一级目录`() {
        assertEquals(MediaKind.VIDEO, ThumbnailKey.of("b", entry(name = "a.ts", path = "/a.ts")).kind)
        assertEquals(MediaKind.AUDIO, ThumbnailKey.of("b", entry(name = "a.flac", path = "/a.flac")).kind)
        assertEquals(MediaKind.IMAGE, ThumbnailKey.of("b", entry(name = "a.jpg", path = "/a.jpg")).kind)
        assertTrue(ThumbnailKey.of("b", entry(name = "a.jpg", path = "/a.jpg")).relativePath.startsWith("image/"))
        // 显式指定类型（例如 HLS 播放列表按视频处理）
        assertEquals(MediaKind.VIDEO, ThumbnailKey.of("b", entry(name = "a.m3u8"), kind = MediaKind.VIDEO).kind)
    }

    @Test
    fun `目标宽度进入 key 避免不同尺寸互相覆盖`() {
        val small = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        val large = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 512)
        assertNotEquals(small, large)
        assertNotEquals(small.hash, large.hash)
        // 同一宽度仍稳定
        assertEquals(small.hash, ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256).hash)
    }

    @Test
    fun `缓存 fileFor 落在 rootDir 之下`() {
        val root = File(System.getProperty("java.io.tmpdir"), "mg-thumb-path-check")
        val cache = ThumbnailCache(root)
        val key = ThumbnailKey.of("local-file:/sdcard", entry(), targetWidth = 256)
        assertEquals(File(root, key.relativePath), cache.fileFor(key))
        assertTrue(cache.fileFor(key).path.startsWith(root.path))
    }
}
