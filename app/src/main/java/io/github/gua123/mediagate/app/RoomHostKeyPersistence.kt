package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.core.database.HostKeyDao
import io.github.gua123.mediagate.core.database.SftpHostKeyEntity
import io.github.gua123.mediagate.data.storage.sftp.HostKeyFingerprint
import io.github.gua123.mediagate.data.storage.sftp.HostKeyPersistence

/**
 * 主机密钥指纹落 Room（**R2 / plan 4.9**）。
 *
 * 全部是**同步**方法：Room 只禁止主线程访问，而这个实现只会被
 * [io.github.gua123.mediagate.data.storage.sftp.PersistentKnownHostsStore] 在 SFTP 连接的 IO 线程上调用。
 */
class RoomHostKeyPersistence(private val dao: HostKeyDao) : HostKeyPersistence {

    override fun loadAll(): List<HostKeyFingerprint> = dao.loadAll().map { entity ->
        HostKeyFingerprint(
            host = entity.host,
            port = entity.port,
            keyType = entity.keyType,
            sha256 = entity.sha256,
            md5 = entity.md5,
        )
    }

    override fun insert(fingerprint: HostKeyFingerprint) {
        dao.insert(
            SftpHostKeyEntity(
                host = fingerprint.host,
                port = fingerprint.port,
                keyType = fingerprint.keyType,
                sha256 = fingerprint.sha256,
                md5 = fingerprint.md5,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun delete(host: String, port: Int) {
        dao.delete(host, port)
    }
}
