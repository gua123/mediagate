package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.File

/**
 * [ThumbnailCache] 的 JVM 单测（R5，plan 4.4 / 4.10）。
 *
 * 覆盖：内存 LRU 容量与命中刷新、磁盘写入→命中→按容量淘汰、负缓存 TTL、
 * 同一 key 并发写不产生半截文件。全部用真实临时目录，不 mock 文件系统、不依赖 Android API。
 */
class ThumbnailCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun key(tag: String, width: Int = 256): ThumbnailKey = ThumbnailKey.of(
        backendId = "local-file:/tmp/root",
        entry = RemoteEntry(name = "$tag.mp4", path = "/dir/$tag.mp4", size = 1_000L, mtime = 1_000L),
        targetWidth = width,
    )

    private fun bytes(value: Int, size: Int = 128): ByteArray = ByteArray(size) { value.toByte() }

    private fun tmpLeftovers(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.contains(".tmp-") }.toList()

    @Test
    fun `内存层按条数淘汰且命中刷新 LRU`() = runTest {
        val cache = ThumbnailCache(tmp.newFolder("mem"), memoryCapacity = 2)
        val a = key("a")
        val b = key("b")
        val c = key("c")

        cache.put(a, bytes(1))
        cache.put(b, bytes(2))
        assertEquals(2, cache.memorySize())

        // 命中 a → a 变成最近使用
        assertArrayEquals(bytes(1), cache.get(a)!!)
        cache.put(c, bytes(3))
        assertEquals(2, cache.memorySize())

        // 删掉磁盘文件后只剩内存层：能命中的就是没被淘汰的
        cache.fileFor(a).delete()
        cache.fileFor(b).delete()
        cache.fileFor(c).delete()
        assertArrayEquals(bytes(1), cache.get(a)!!)
        assertArrayEquals(bytes(3), cache.get(c)!!)
        assertNull(cache.get(b))
    }

    @Test
    fun `内存层容量为 0 时不缓存`() = runTest {
        val cache = ThumbnailCache(tmp.newFolder("mem0"), memoryCapacity = 0)
        val a = key("a")
        cache.put(a, bytes(1))
        assertEquals(0, cache.memorySize())
        // 仍能从磁盘命中
        assertArrayEquals(bytes(1), cache.get(a)!!)
        assertEquals(0, cache.memorySize())
    }

    @Test
    fun `磁盘写入命中与 sizeBytes`() = runTest {
        val dir = tmp.newFolder("disk")
        val cache = ThumbnailCache(dir, memoryCapacity = 0)
        val a = key("a")

        cache.put(a, bytes(42, 128))
        val file = cache.fileFor(a)
        assertTrue("缓存文件应存在：${file.path}", file.isFile)
        assertEquals(128L, cache.sizeBytes())
        // 落盘位置就是 <cacheDir>/<kind>/<hash前2位>/<hash>.webp
        assertEquals(a.relativePath, file.relativeTo(dir).path.replace(File.separatorChar, '/'))
        assertTrue(tmpLeftovers(dir).isEmpty())

        // 新实例（模拟进程重启）仍能命中磁盘缓存
        val reopened = ThumbnailCache(dir, memoryCapacity = 0)
        assertArrayEquals(bytes(42, 128), reopened.get(a)!!)
    }

    @Test
    fun `磁盘按容量做 lastModified LRU 淘汰`() = runTest {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("evict")
        val cache = ThumbnailCache(dir, memoryCapacity = 0, diskCapacityBytes = 300, clock = { now })
        val a = key("a")
        val b = key("b")
        val c = key("c")

        now += 1_000
        cache.put(a, bytes(1, 150))
        now += 1_000
        cache.put(b, bytes(2, 150))
        // 第三次写入后总量 450 > 300，最久未用的 a 应被淘汰
        now += 1_000
        cache.put(c, bytes(3, 150))

        assertEquals(300L, cache.sizeBytes())
        assertFalse("最久未用的 a 应被淘汰", cache.fileFor(a).exists())
        assertTrue(cache.fileFor(b).exists())
        assertTrue(cache.fileFor(c).exists())

        // 命中刷新 lastModified：访问 b 之后再写入 d，被淘汰的应该是 c
        now += 1_000
        assertArrayEquals(bytes(2, 150), cache.get(b)!!)
        val d = key("d")
        now += 1_000
        cache.put(d, bytes(4, 150))
        assertTrue("刚命中过的 b 不该被淘汰", cache.fileFor(b).exists())
        assertFalse("此时最久未用的是 c", cache.fileFor(c).exists())
        assertTrue(cache.sizeBytes() <= 300)
    }

    @Test
    fun `历史文件超出容量时由清理任务淘汰到上限以内`() = runTest {
        // 注入假时钟：淘汰顺序只看 lastModified，用真实时钟时三次写入可能落在同一毫秒，
        // 会退化成「按文件名（哈希）排序」而变得不确定
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("sweep")
        val big = ThumbnailCache(dir, memoryCapacity = 0, diskCapacityBytes = 10_000, clock = { now })
        now += 1_000
        big.put(key("a"), bytes(1, 150))
        now += 1_000
        big.put(key("b"), bytes(2, 150))
        now += 1_000
        big.put(key("c"), bytes(3, 150))
        assertEquals(450L, big.sizeBytes())

        // 模拟「设置里把容量改小了」：新实例按更小的上限清理存量缓存（最久未用的 a、b 先走）
        val small = ThumbnailCache(dir, memoryCapacity = 0, diskCapacityBytes = 150, clock = { now })
        small.evictToCapacity()
        assertEquals(150L, small.sizeBytes())
        assertTrue(small.fileFor(key("c")).exists())
    }

    @Test
    fun `负缓存 TTL 生效`() = runTest {
        var now = 1_000_000L
        val cache = ThumbnailCache(tmp.newFolder("neg"), negativeTtlMs = 5_000, clock = { now })
        val a = key("a")

        assertFalse(cache.isNegative(a))
        cache.markFailed(a)
        assertTrue(cache.isNegative(a))
        assertEquals(1, cache.negativeSize())

        now += 4_999
        assertTrue("TTL 内应继续判失败", cache.isNegative(a))
        now += 1
        assertFalse("TTL 到期后应放行重试", cache.isNegative(a))
        assertEquals(0, cache.negativeSize())

        // 手动重试
        cache.markFailed(a)
        cache.clearNegative(a)
        assertFalse(cache.isNegative(a))

        // 关闭负缓存（TTL <= 0）
        val disabled = ThumbnailCache(tmp.newFolder("neg0"), negativeTtlMs = 0)
        disabled.markFailed(a)
        assertFalse(disabled.isNegative(a))
    }

    @Test
    fun `同一 key 并发写不会产生半截文件`() = runBlocking {
        val dir = tmp.newFolder("concurrent")
        // 内存层关掉，强制每次都读磁盘
        val cache = ThumbnailCache(dir, memoryCapacity = 0)
        val a = key("a")
        val payloads = List(8) { index -> ByteArray(32 * 1024) { (index + 1).toByte() } }

        val writers = payloads.map { payload ->
            launch(Dispatchers.IO) {
                repeat(3) { cache.put(a, payload) }
            }
        }
        val reader = launch(Dispatchers.IO) {
            repeat(300) {
                cache.get(a)?.let { bytes ->
                    assertEquals(32 * 1024, bytes.size)
                    val first = bytes[0]
                    assertTrue("读到了半截/混合内容", bytes.all { it == first })
                }
                yield()
            }
        }
        writers.forEach { it.join() }
        reader.join()

        val finalBytes = cache.get(a)!!
        assertEquals(32 * 1024, finalBytes.size)
        assertTrue("最终文件必须是某一份完整内容", finalBytes.all { it == finalBytes[0] })
        assertTrue("不应残留 .tmp 文件：${tmpLeftovers(dir)}", tmpLeftovers(dir).isEmpty())
    }

    @Test
    fun `缓存文件被截断为空时按未命中处理`() = runTest {
        val cache = ThumbnailCache(tmp.newFolder("empty"), memoryCapacity = 0)
        val a = key("a")
        cache.put(a, bytes(1))
        cache.fileFor(a).writeBytes(ByteArray(0))
        assertNull(cache.get(a))
        assertFalse("空文件应被删除", cache.fileFor(a).exists())
    }

    @Test
    fun `clear 清空两级与负缓存`() = runTest {
        val dir = tmp.newFolder("clear")
        val cache = ThumbnailCache(dir)
        val a = key("a")
        cache.put(a, bytes(1))
        cache.markFailed(a)
        assertEquals(1, cache.memorySize())

        cache.clear()
        assertEquals(0, cache.memorySize())
        assertEquals(0, cache.negativeSize())
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.get(a))
    }

    @Test
    fun `非 webp 扩展名的条目同样参与容量统计与淘汰`() = runTest {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("mixed")
        val cache = ThumbnailCache(dir, memoryCapacity = 0, diskCapacityBytes = 300, clock = { now })
        // 图片缩略图按实际编码格式落 .jpg（M1-F），它必须和 .webp 一样被计入容量
        val jpeg = ThumbnailKey.of(
            backendId = "local-file:/tmp/root",
            entry = RemoteEntry(name = "photo.jpg", path = "/dir/photo.jpg", size = 1_000L, mtime = 1_000L),
            targetWidth = 256,
            variant = ThumbnailVariant.IMAGE_PREVIEW,
            format = ThumbnailImageFormat.JPEG,
        )

        now += 1_000
        cache.put(jpeg, bytes(1, 150))
        assertTrue("应落成 .jpg：${cache.fileFor(jpeg).name}", cache.fileFor(jpeg).name.endsWith(".jpg"))
        assertEquals(150L, cache.sizeBytes())

        now += 1_000
        cache.put(key("b"), bytes(2, 150))
        assertEquals(300L, cache.sizeBytes())

        // 第三次写入后 450 > 300：最久未用的 .jpg 条目也要被淘汰（按扩展名白名单就会漏掉它）
        now += 1_000
        cache.put(key("c"), bytes(3, 150))
        assertEquals(300L, cache.sizeBytes())
        assertFalse("非 webp 的条目必须参与淘汰", cache.fileFor(jpeg).exists())
        assertTrue(cache.fileFor(key("b")).exists())
        assertTrue(cache.fileFor(key("c")).exists())
    }
}
