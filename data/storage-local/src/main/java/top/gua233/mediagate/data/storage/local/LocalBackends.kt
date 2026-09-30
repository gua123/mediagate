package io.github.gua123.mediagate.data.storage.local

import android.content.Context
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import java.io.File

/**
 * 本地双模式工厂（R12）：File（全盘访问）与 SAF（持久化树授权）二选一。
 *
 * 之所以只做两个极小工厂方法，是为了让上层（连接管理 / 浏览器）拿到的是统一的
 * [StorageBackend]，模式差异不外泄。
 */
object LocalBackends {

    /** 「所有文件访问」模式：直接以 [root] 为根，随机读走 RandomAccessFile。 */
    fun file(root: File): StorageBackend = FileStorageBackend(root)

    /**
     * SAF 模式：以 [treeUri]（形如 content://…/tree/primary%3AMovies，需已 takePersistableUriPermission）
     * 为根；随机读走 ContentResolver，能力受提供方限制。
     */
    fun saf(context: Context, treeUri: String): StorageBackend = SafStorageBackend(context, treeUri)
}
