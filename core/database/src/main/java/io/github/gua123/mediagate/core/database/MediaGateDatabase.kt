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

/**
 * 批量字幕批次（**M7-B / R19**，plan 第 7 章 asr_batch 表）。
 *
 * 一次「选一批文件 / 选一个文件夹」的入队就是一个批次；批次本身只记录来源与选项，
 * 具体任务在 [AsrTaskEntity] 里。纯加法：v2 的 connection / address / network_rule 一字未动。
 */
@Entity(
    tableName = "asr_batch",
    indices = [Index("state")],
)
data class AsrBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 批次名（界面上那一行标题，一般取目录名）。 */
    val name: String,
    /** 来源方式：FILES（多选文件）/ FOLDER（整个文件夹）。 */
    val sourceKind: String,
    /** 批次根路径（后端内路径）。 */
    val rootPath: String,
    /** 是否递归子目录。 */
    val recursive: Boolean = false,
    /** 是否跳过已有字幕的文件。 */
    val skipExisting: Boolean = true,
    /** 批次状态：IDLE / RUNNING / PAUSED / STOPPED（:media:asr 的 AsrQueueState）。 */
    val state: String = "IDLE",
    val createdAt: Long = 0L,
)

/**
 * 单文件音转字幕任务（**M7-B / R19**，plan 第 7 章 asr_task 表）。
 *
 * 落库的唯一目的是「**进程被杀后可续跑**」：重启时把 RUNNING/WRITING 的读成 INTERRUPTED
 * （见 [AsrDao.markInterrupted]），带着已识别的进度回到队列里，用户点「继续」就接着跑。
 *
 * 比 plan 表格多出的几列都是「恢复时必须知道」的信息：文件名（展示）、失败分类（R19 要求给原因）、
 * 跳过原因、已识别毫秒与总毫秒（进度）、排序号（队列顺序）。
 */
@Entity(
    tableName = "asr_task",
    indices = [Index("batchId"), Index("state"), Index("queueOrder")],
)
data class AsrTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属批次；null = 散装任务。 */
    val batchId: Long? = null,
    /** 所属连接（远端任务用）；null = 本地/当前连接。 */
    val connectionId: Long? = null,
    /** 视频在后端内的路径。 */
    val path: String,
    /** 文件名（界面展示）。 */
    val name: String,
    /** 模型档位 id（tiny/base/small）。 */
    val model: String,
    /** 任务状态：QUEUED / RUNNING / WRITING / SUCCEEDED / FAILED / SKIPPED / INTERRUPTED / CANCELLED。 */
    val state: String = "QUEUED",
    /** 已识别毫秒（进度）。 */
    val progressMs: Long = 0L,
    /** 音轨总毫秒；0 = 未知。 */
    val durationMs: Long = 0L,
    /** 产出的字幕落点（写回路径或 App 私有目录的绝对路径）。 */
    val outputPath: String? = null,
    /** 原始错误文本（诊断用）。 */
    val error: String? = null,
    /** 失败分类（R19「失败项给原因」，:media:asr 的 AsrFailureKind.name）。 */
    val failure: String? = null,
    /** 跳过原因（已有字幕等）。 */
    val skipReason: String? = null,
    /** 重试次数。 */
    val retryCount: Int = 0,
    /** 队列顺序。 */
    val queueOrder: Int = 0,
    val createdAt: Long = 0L,
)

/**
 * 已知的 SFTP 主机密钥指纹（**R2 / plan 4.9** 的 TOFU 落库）。
 *
 * 为什么必须落库：指纹只存在内存里的话，App 一重启"变更检测"就等于没做——
 * 一个被换掉的密钥会被当成"第一次见到的主机"按 TOFU 放行，中间人攻击照样成立。
 *
 * @param sha256 SHA-256 指纹的 **Base64**（与 OpenSSH 展示口径一致；不是十六进制）。
 * @param md5 老工具链习惯的 MD5 指纹（冒号分隔小写十六进制），一并存下来便于界面核对。
 * @param addedAt 首次记录的时间（毫秒）。
 */
@Entity(
    tableName = "sftp_host_key",
    primaryKeys = ["host", "port", "keyType", "sha256"],
)
data class SftpHostKeyEntity(
    val host: String,
    val port: Int,
    val keyType: String,
    val sha256: String,
    val md5: String = "",
    val addedAt: Long = 0L,
)

/**
 * 主机密钥指纹的读写（**R2**）。
 *
 * 刻意**不用 suspend**：调用方是 JSch 的主机密钥校验回调（连接的 IO 线程），
 * 那里是同步上下文；Room 禁止的是主线程访问，这里不在主线程。
 */
@Dao
interface HostKeyDao {

    @Query("SELECT * FROM sftp_host_key")
    fun loadAll(): List<SftpHostKeyEntity>

    /** 界面用：已信任的指纹列表（设置页展示，用户可逐条忘掉）。 */
    @Query("SELECT * FROM sftp_host_key ORDER BY host, port, keyType")
    fun observeAll(): Flow<List<SftpHostKeyEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: SftpHostKeyEntity)

    @Query("DELETE FROM sftp_host_key WHERE host = :host AND port = :port")
    fun delete(host: String, port: Int)

    @Query("SELECT COUNT(*) FROM sftp_host_key")
    fun count(): Int
}

@Dao
interface AsrDao {

    /** 队列快照（按队列顺序）。 */
    @Query("SELECT * FROM asr_task ORDER BY queueOrder, id")
    fun observeTasks(): Flow<List<AsrTaskEntity>>

    @Query("SELECT * FROM asr_task ORDER BY queueOrder, id")
    suspend fun tasks(): List<AsrTaskEntity>

    @Query("SELECT * FROM asr_task WHERE id = :id")
    suspend fun task(id: Long): AsrTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AsrTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<AsrTaskEntity>): List<Long>

    @Update
    suspend fun updateTask(task: AsrTaskEntity)

    /**
     * 从库里**删掉**这些任务（**2026-10-03 真机**：用户问"已取消能不能去掉"）。
     *
     * 之前"清空已结束"只清了内存快照，行还留在库里——重启后它们会**原样回来**，
     * 用户看到的就是"去不掉"。
     */
    @Query("DELETE FROM asr_task WHERE id IN (:ids)")
    suspend fun deleteTasks(ids: List<Long>)

    /** 只更新会变的那几列（状态 / 进度 / 结果 / 顺序）。 */
    @Query(
        "UPDATE asr_task SET state = :state, progressMs = :progressMs, durationMs = :durationMs, " +
            "outputPath = :outputPath, error = :error, failure = :failure, skipReason = :skipReason, " +
            "retryCount = :retryCount, queueOrder = :queueOrder WHERE id = :id",
    )
    suspend fun updateTaskState(
        id: Long,
        state: String,
        progressMs: Long,
        durationMs: Long,
        outputPath: String?,
        error: String?,
        failure: String?,
        skipReason: String?,
        retryCount: Int,
        queueOrder: Int,
    )

    @Query("DELETE FROM asr_task WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("DELETE FROM asr_task")
    suspend fun clearTasks()

    /** 清空已经落定的任务（界面「清空已结束」）。 */
    @Query("DELETE FROM asr_task WHERE state IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')")
    suspend fun deleteFinishedTasks()

    /**
     * 进程被杀后重启时的恢复（**R19：未完成任务标为「已中断、可续跑」**）。
     *
     * @return 改动行数（0 说明上次是干净退出）。
     */
    @Query("UPDATE asr_task SET state = 'INTERRUPTED' WHERE state IN ('RUNNING', 'WRITING')")
    suspend fun markInterrupted(): Int

    @Insert
    suspend fun insertBatch(batch: AsrBatchEntity): Long

    @Query("SELECT * FROM asr_batch ORDER BY createdAt DESC, id DESC")
    fun observeBatches(): Flow<List<AsrBatchEntity>>

    @Query("SELECT * FROM asr_batch ORDER BY createdAt DESC, id DESC")
    suspend fun batches(): List<AsrBatchEntity>

    @Query("UPDATE asr_batch SET state = :state WHERE id = :id")
    suspend fun updateBatchState(id: Long, state: String)

    @Query("DELETE FROM asr_batch WHERE id = :id")
    suspend fun deleteBatch(id: Long)
}

@Database(
    entities = [
        ConnectionEntity::class,
        AddressEntity::class,
        NetworkRuleEntity::class,
        AsrBatchEntity::class,
        AsrTaskEntity::class,
        SftpHostKeyEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MediaGateDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    /** 批量字幕任务中心（M7-B / R19）。 */
    abstract fun asrDao(): AsrDao

    /** SFTP 主机密钥指纹（R2：TOFU 要真的落盘）。 */
    abstract fun hostKeyDao(): HostKeyDao

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

        /**
         * v2 → v3：新增 `asr_batch` 与 `asr_task` 两张表（**M7-B / R19** 批量字幕任务中心）。
         *
         * 纯新增：既有三张表与列语义一字未动，旧数据原样保留。
         *
         * [MIGRATION_2_3_SQL] 把同样的 DDL 以字符串形式暴露出来，供离线校验单测
         * （core/database 的 MigrationSqlTest）与 Room 生成的最新 schema 逐条比对：
         * 手写的迁移 SQL 一旦与实体定义漂移，单测立刻红，不用等到真机升级时才炸。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        /** v2 → v3 的全部 DDL（顺序即执行顺序；与 Room 导出的 3.json 必须一字不差）。 */
        val MIGRATION_2_3_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `asr_batch` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sourceKind` TEXT NOT NULL, " +
                "`rootPath` TEXT NOT NULL, " +
                "`recursive` INTEGER NOT NULL, " +
                "`skipExisting` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_asr_batch_state` ON `asr_batch` (`state`)",
            "CREATE TABLE IF NOT EXISTS `asr_task` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`batchId` INTEGER, " +
                "`connectionId` INTEGER, " +
                "`path` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`progressMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`outputPath` TEXT, " +
                "`error` TEXT, " +
                "`failure` TEXT, " +
                "`skipReason` TEXT, " +
                "`retryCount` INTEGER NOT NULL, " +
                "`queueOrder` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_asr_task_batchId` ON `asr_task` (`batchId`)",
            "CREATE INDEX IF NOT EXISTS `index_asr_task_state` ON `asr_task` (`state`)",
            "CREATE INDEX IF NOT EXISTS `index_asr_task_queueOrder` ON `asr_task` (`queueOrder`)",
        )

        /**
         * v3 → v4：新增 SFTP 主机密钥指纹表（**R2**：TOFU 落库）。
         *
         * 纯加法迁移，不碰既有表；DDL 必须与 Room 导出的 4.json 一字不差
         * （离线校验见 MigrationSqlTest）。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        /** v3 → v4 的全部 DDL。 */
        val MIGRATION_3_4_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `sftp_host_key` (" +
                "`host` TEXT NOT NULL, " +
                "`port` INTEGER NOT NULL, " +
                "`keyType` TEXT NOT NULL, " +
                "`sha256` TEXT NOT NULL, " +
                "`md5` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`host`, `port`, `keyType`, `sha256`))",
        )

        /** 全部迁移（:app 建库时统一 addMigrations）。 */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
