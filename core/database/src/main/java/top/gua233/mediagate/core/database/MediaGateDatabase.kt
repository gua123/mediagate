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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/**
 * 选路规则（**R7**，plan 第 7 章 `network_rule` 表）。
 *
 * 一条规则 = 传输类型 / SSID / 本机网段的组合条件 + 偏好标签：
 * 家里的 Wi-Fi（SSID 或 192.168.1.x 网段）走 LAN 地址，蜂窝网络走公网域名。
 * 三个条件都为 null 表示"任意网络都偏好 [prefer]"。
 *
 * @param transport WIFI / CELLULAR / ETHERNET / VPN / UNKNOWN；null = 任意。
 * @param ssidPattern Wi-Fi 名称匹配（支持 `*` 通配）；null = 任意。
 * @param localSubnet 本机网段匹配（CIDR 或前缀写法）；null = 任意。
 * @param prefer 命中后优先使用的地址标签（LAN / WAN）。
 */
@Entity(
    tableName = "network_rule",
    indices = [Index("connectionId")],
)
data class NetworkRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    val transport: String? = null,
    val ssidPattern: String? = null,
    val localSubnet: String? = null,
    val prefer: String = "LAN",
)

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection ORDER BY id")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    /** 一次性取全部连接（「测试全部」的起点，不需要 Flow）。 */
    @Query("SELECT * FROM connection ORDER BY id")
    suspend fun all(): List<ConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Query("DELETE FROM connection WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM address WHERE connectionId = :connectionId ORDER BY priority DESC, id")
    suspend fun addressesOf(connectionId: Long): List<AddressEntity>

    /** 地址变化要驱动界面刷新（R7/R8）：全量流 + 单连接流都给一条。 */
    @Query("SELECT * FROM address ORDER BY connectionId, priority DESC, id")
    fun observeAllAddresses(): Flow<List<AddressEntity>>

    @Query("SELECT * FROM address WHERE connectionId = :connectionId ORDER BY priority DESC, id")
    fun observeAddressesOf(connectionId: Long): Flow<List<AddressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddress(address: AddressEntity): Long

    @Query("DELETE FROM address WHERE id = :id")
    suspend fun deleteAddress(id: Long)

    @Query("DELETE FROM address WHERE connectionId = :connectionId")
    suspend fun deleteAddressesOf(connectionId: Long)

    /** 记录"最近一次检测"的结果（R8 列表里的最近检测时间 + 可用地址）。 */
    @Query("UPDATE connection SET lastWorkingAddressId = :addressId, lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun updateLastChecked(id: Long, addressId: Long?, checkedAt: Long)

    /** 某个连接的选路规则（R7）。 */
    @Query("SELECT * FROM network_rule WHERE connectionId = :connectionId ORDER BY id")
    suspend fun rulesOf(connectionId: Long): List<NetworkRuleEntity>

    @Query("SELECT * FROM network_rule ORDER BY connectionId, id")
    fun observeAllRules(): Flow<List<NetworkRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: NetworkRuleEntity): Long

    @Query("DELETE FROM network_rule WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("DELETE FROM network_rule WHERE connectionId = :connectionId")
    suspend fun deleteRulesOf(connectionId: Long)
}

@Database(
    entities = [ConnectionEntity::class, AddressEntity::class, NetworkRuleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MediaGateDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    companion object {
        const val NAME = "mediagate.db"

        /**
         * v1 → v2：新增 `network_rule` 表（**R7** 选路规则）。
         *
         * 纯新增：既有 connection / address 两表与列语义一字未动，旧数据原样保留。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_rule` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`connectionId` INTEGER NOT NULL, " +
                        "`transport` TEXT, " +
                        "`ssidPattern` TEXT, " +
                        "`localSubnet` TEXT, " +
                        "`prefer` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_network_rule_connectionId` " +
                        "ON `network_rule` (`connectionId`)",
                )
            }
        }

        /** 全部迁移（:app 建库时统一 addMigrations）。 */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2)
    }
}
