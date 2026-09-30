package io.github.gua123.mediagate.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 协议类型（见 docs/plan.md 第 7 章数据模型）。 */
object Protocols {
    const val LOCAL = "LOCAL"
    const val WEBDAV = "WEBDAV"
    const val SFTP = "SFTP"
    const val FTP = "FTP"
}

/**
 * 连接记录（R8）。密码/私钥口令等敏感信息只以 Keystore 密文落在 [secretRef] 指向的位置，
 * 本表不存明文。
 */
@Entity(tableName = "connection")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,
    val basePath: String = "/",
    val username: String? = null,
    val secretRef: String? = null,
    /** JSON：协议特有选项（编码、超时、并发数、私钥路径等）。 */
    val options: String? = null,
    /** NONE / IMPLICIT / STARTTLS。 */
    val tls: String? = null,
    val lastWorkingAddressId: Long? = null,
    val lastCheckedAt: Long? = null,
)

/** 连接下的多地址（LAN / 公网域名），R7 选路的基础。 */
@Entity(
    tableName = "address",
    indices = [Index("connectionId")],
)
data class AddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    /** LAN / WAN。 */
    val label: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val priority: Int = 0,
)

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection ORDER BY id")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Query("DELETE FROM connection WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM address WHERE connectionId = :connectionId ORDER BY priority DESC, id")
    suspend fun addressesOf(connectionId: Long): List<AddressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddress(address: AddressEntity): Long
}

@Database(
    entities = [ConnectionEntity::class, AddressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MediaGateDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    companion object {
        const val NAME = "mediagate.db"
    }
}
