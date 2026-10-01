package io.github.gua123.mediagate.media.tsext

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.local.FileStorageBackend
import java.io.File

/**
 * 数据层适配 [openRandomAccessSource] 的 JVM 单测（R3/R4）：走真实 [FileStorageBackend]，
 * 验证「StorageBackend.openRead → RandomAccessSource → TsIndexer」这条链路端到端可用。
 */
class TsSourcesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `通过存储后端打开的数据源与文件内容一致`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 2)
        val root = temporary.newFolder("root-a")
        File(root, "sample.ts").writeBytes(bytes)
        val backend = FileStorageBackend(root)

        val source = backend.openRandomAccessSource("sample.ts")
        try {
            assertEquals(bytes.size.toLong(), source.size)
            assertArrayEquals(bytes, source.readFully(0, bytes.size))
            assertTrue(source.readFully(bytes.size.toLong(), 32).isEmpty())
            assertEquals(-1, source.readAt(bytes.size.toLong(), ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    @Test
    fun `不存在的路径抛出 NotFound`() = runTest {
        val backend = FileStorageBackend(temporary.newFolder("root-b"))
        try {
            backend.openRandomAccessSource("missing.ts")
            fail("应当抛 StorageException.NotFound")
        } catch (expected: StorageException.NotFound) {
            assertTrue(expected.message!!.contains("missing.ts"))
        }
    }

    @Test
    fun `目录抛出 NotSupported`() = runTest {
        val root = temporary.newFolder("root-c")
        File(root, "dir").mkdirs()
        val backend = FileStorageBackend(root)
        try {
            backend.openRandomAccessSource("dir")
            fail("应当抛 StorageException.NotSupported")
        } catch (expected: StorageException.NotSupported) {
            assertTrue(expected.message!!.contains("dir"))
        }
    }

    @Test
    fun `端到端索引与内存扫描完全一致`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 5, payloadBytes = 600)
        val root = temporary.newFolder("root-d")
        File(root, "movie.ts").writeBytes(bytes)
        val backend = FileStorageBackend(root)

        val direct = IndexerSupport.completed(TsIndexer(ByteSource(bytes)).scan().toList()).index
        val viaBackend = backend.openRandomAccessSource("movie.ts").use { source ->
            IndexerSupport.completed(TsIndexer(source, chunkBytes = TS_PACKET_SIZE * 3).scan().toList()).index
        }

        assertEquals(direct.points, viaBackend.points)
        assertEquals(direct.videoPid, viaBackend.videoPid)
        assertEquals(direct.videoCodec, viaBackend.videoCodec)
        assertEquals(direct.scannedBytes, viaBackend.scannedBytes)
        assertEquals(5, viaBackend.keyframeCount)
    }

    @Test
    fun `索引 key 由后端标识与目录项的 size 和 mtime 决定`() {
        val entry = RemoteEntry(name = "a.ts", path = "/movies/a.ts", size = 2_048L, mtime = 1_700_000_000_000L)
        val key = TsIndexKey.of("local-file:/sdcard", entry)
        assertEquals(TsIndexKey("local-file:/sdcard", "/movies/a.ts", 2_048L, 1_700_000_000_000L), key)
        assertEquals(key.hash, TsIndexKey.of("local-file:/sdcard", entry).hash)
        assertTrue(key.hash != TsIndexKey.of("webdav:https://host", entry).hash)
    }
}

/** 小工具：取扫描结果里唯一的 Completed。 */
internal object IndexerSupport {
    fun completed(updates: List<TsScanUpdate>): TsScanUpdate.Completed =
        updates.filterIsInstance<TsScanUpdate.Completed>().single()
}
