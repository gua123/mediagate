package io.github.gua123.mediagate.media.tsext

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [TsIndexStore] 的 JVM 单测（R3/R4）：命中、失效（size/mtime 变）、损坏兜底、容量淘汰。
 */
class TsIndexStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(capacity: Int = TsIndexStore.DEFAULT_CAPACITY): TsIndexStore =
        TsIndexStore(File(temporary.root, "tsidx"), capacity = capacity)

    private fun key(name: String = "a", size: Long = 2_000L, mtime: Long = 1_700_000_000_000L): TsIndexKey =
        TsIndexKey(backendId = "local-file:/sdcard", path = "/movies/$name.ts", size = size, mtime = mtime)

    private fun index(offsetBase: Long = 0L, complete: Boolean = true): TsIndex = TsIndex(
        points = listOf(
            KeyframePoint(0L, offsetBase),
            KeyframePoint(2_000L, offsetBase + 1_000L),
            KeyframePoint(4_000L, offsetBase + 2_000L),
        ),
        videoPid = TsFixtures.VIDEO_PID,
        videoCodec = TsVideoCodec.H264,
        durationMs = 4_000L,
        scannedBytes = 9_000L,
        complete = complete,
    )

    @Test
    fun `保存后同一 key 命中且内容一致`() = runTest {
        val store = store()
        val key = key()
        val saved = index()
        assertNull(store.load(key))
        assertEquals(store.fileFor(key), store.save(key, saved))
        val loaded = store.load(key)!!
        assertEquals(saved, loaded)
        assertEquals(saved.points, loaded.points)
        assertEquals(1, store.count())
    }

    @Test
    fun `size 变化即失效`() = runTest {
        val store = store()
        val original = key(size = 2_000L)
        store.save(original, index())
        assertNotNull(store.load(original))
        assertNull(store.load(original.copy(size = 2_001L)))
        // 旧 key 仍然命中（新 key 只是另一条缓存）
        assertNotNull(store.load(original))
    }

    @Test
    fun `mtime 变化即失效`() = runTest {
        val store = store()
        val original = key(mtime = 1_700_000_000_000L)
        store.save(original, index())
        assertNull(store.load(original.copy(mtime = 1_700_000_001_000L)))
    }

    @Test
    fun `后端与路径不同则互不影响`() = runTest {
        val store = store()
        val a = key("a")
        val b = key("b")
        val other = a.copy(backendId = "webdav:https://host")
        store.save(a, index(offsetBase = 10L))
        store.save(b, index(offsetBase = 20L))
        store.save(other, index(offsetBase = 30L))
        assertEquals(10L, store.load(a)!!.points.first().byteOffset)
        assertEquals(20L, store.load(b)!!.points.first().byteOffset)
        assertEquals(30L, store.load(other)!!.points.first().byteOffset)
        assertEquals(3, store.count())
    }

    @Test
    fun `invalidate 删除缓存`() = runTest {
        val store = store()
        val key = key()
        store.save(key, index())
        assertTrue(store.invalidate(key))
        assertNull(store.load(key))
        assertFalse(store.invalidate(key))
    }

    @Test
    fun `超出容量按最后修改时间淘汰最旧的`() = runTest {
        val store = store(capacity = 2)
        val a = key("a")
        val b = key("b")
        val c = key("c")
        store.save(a, index())
        store.fileFor(a).setLastModified(1_000L)
        store.save(b, index())
        store.fileFor(b).setLastModified(2_000L)
        store.save(c, index())
        assertEquals(2, store.count())
        assertNull("最旧的 a 应被淘汰", store.load(a))
        assertNotNull(store.load(b))
        assertNotNull(store.load(c))
    }

    @Test
    fun `损坏文件按未命中处理并删除`() = runTest {
        val store = store()
        val key = key()
        store.save(key, index())
        val file = store.fileFor(key)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(store.load(key))
        assertFalse("损坏缓存应被顺手删掉", file.exists())
    }

    @Test
    fun `清单里的 key 与请求不符时按未命中处理`() = runTest {
        val store = store()
        val saved = key("a")
        val requested = key("b")
        store.save(saved, index())
        // 把 a 的缓存文件改名冒充 b 的缓存：文件里的清单仍是 a → 必须判为未命中并删除
        assertTrue(store.fileFor(saved).renameTo(store.fileFor(requested)))
        assertNull(store.load(requested))
        assertFalse(store.fileFor(requested).exists())
    }

    @Test
    fun `clear 清空全部并返回数量`() = runTest {
        val store = store()
        store.save(key("a"), index())
        store.save(key("b"), index())
        assertEquals(2, store.clear())
        assertEquals(0, store.count())
        assertNull(store.load(key("a")))
    }

    @Test
    fun `命中会刷新最后修改时间用于 LRU`() = runTest {
        val store = store()
        val key = key()
        store.save(key, index())
        val file = store.fileFor(key)
        file.setLastModified(1_000L)
        assertNotNull(store.load(key))
        assertTrue("命中后 mtime 应被刷新：${file.lastModified()}", file.lastModified() > 1_000L)
    }

    @Test
    fun `缓存文件名是稳定哈希且不含路径字符`() {
        val store = store()
        val key = key()
        assertEquals(store.fileFor(key), store.fileFor(key.copy()))
        val name = store.fileFor(key).name
        assertTrue(name.endsWith(TsIndexStore.FILE_SUFFIX))
        assertEquals(32 + TsIndexStore.FILE_SUFFIX.length, name.length)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
        assertEquals(
            TsIndexKey.hashOf(key.backendId, key.path, key.size, key.mtime),
            name.removeSuffix(TsIndexStore.FILE_SUFFIX),
        )
    }

    @Test
    fun `可列出已缓存的 key`() = runTest {
        val store = store()
        store.save(key("a"), index())
        store.save(key("b"), index())
        assertEquals(setOf(key("a"), key("b")), store.cachedKeys().toSet())
    }

    @Test
    fun `续扫的中间产物也能落盘再读回`() = runTest {
        val store = store()
        val key = key()
        val partial = index(offsetBase = 100L, complete = false)
        store.save(key, partial)
        val loaded = store.load(key)!!
        assertFalse(loaded.complete)
        assertEquals(partial.points, loaded.points)
    }
}
