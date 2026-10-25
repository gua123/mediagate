package io.github.gua123.mediagate.feature.connections

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.crypto.CredentialCipher
import io.github.gua123.mediagate.core.crypto.CredentialEnvelope
import io.github.gua123.mediagate.core.database.AddressEntity
import io.github.gua123.mediagate.core.database.ConnectionEntity
import io.github.gua123.mediagate.core.database.MediaGateDatabase
import io.github.gua123.mediagate.core.database.NetworkRuleEntity
import io.github.gua123.mediagate.core.network.AddressLabel

/**
 * 连接的持久化（**R8** 多连接记录 / **R7** 多地址与优先网络 / **R6** 凭据加密）。
 *
 * 只做四件事，且全部在 `Dispatchers.IO` 上：
 * - [connections]：把 connection / address / network_rule 三张表拼成 [ConnectionRecord] 流（UI 直接 collect）；
 * - [save]：把编辑草稿写回（**密码立即加密**，明文不落库、不回显）；
 * - [delete]：删连接并清掉它的地址与规则（同一个事务里做，不留孤儿行）；
 * - [revealSecret]：解密出明文，仅供 :app 构造 WebDAV 后端用，**界面永远不显示它**。
 *
 * **选路口径（2026-10-25 用户要求：先内网后外网）**：`network_rule` 表不再承载
 * "Wi-Fi / 蜂窝 / SSID / 本机网段"这些条件，每条连接只存一条"优先网络"记录
 * （复用 `prefer` 列）；旧版留下的多条规则读出来时只取第一条的 `prefer`，行为不丢。
 *
 * 事务用 `room-ktx` 的 [withTransaction]，保证"删连接 + 删地址 + 删规则"要么全做要么全不做。
 *
 * @param database 三张表所在的数据库（R7 的 network_rule 表为 M4 新增）。
 * @param cipher 凭据加解密（R6）。
 * @param io 调度器（单测注入）。
 */
class ConnectionRepository(
    private val database: MediaGateDatabase,
    private val cipher: CredentialCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val dao = database.connectionDao()

    /** 连接列表（含地址与优先网络），数据库一变就发新值。 */
    val connections: Flow<List<ConnectionRecord>> =
        combine(
            dao.observeAll(),
            dao.observeAllAddresses(),
            dao.observeAllRules(),
        ) { connections, addresses, rules ->
            connections.map { entity ->
                val base = entity.toRecordBase()
                base.copy(
                    addresses = addresses.filter { it.connectionId == entity.id }.map { it.toRecord() },
                    prefer = preferredLabelOf(rules.filter { it.connectionId == entity.id }),
                )
            }
        }.flowOn(io)

    /** 取单条（测试/选路时用；已带地址与优先网络）。 */
    suspend fun byId(id: Long): ConnectionRecord? = withContext(io) {
        val entity = dao.byId(id) ?: return@withContext null
        entity.toRecordBase().copy(
            addresses = dao.addressesOf(id).map { it.toRecord() },
            prefer = preferredLabelOf(dao.rulesOf(id)),
        )
    }

    /** 旧库可能有多条规则：只认第一条的 `prefer`；没有规则就是默认「先内网后外网」。 */
    private fun preferredLabelOf(rules: List<NetworkRuleEntity>): AddressLabel =
        rules.firstOrNull()?.prefer?.let { AddressLabel.fromId(it) } ?: AddressLabel.LAN

    /**
     * 保存草稿（新建或更新）。
     *
     * 密码规则（R6/R8）：草稿里 [ConnectionDraft.password] 非空 → 立刻 [CredentialCipher.encrypt] 后写进
     * `secretRef`；为空 → 保留原密文（用户没改密码）。**任何情况下都不写明文**。
     *
     * @return 连接 id。
     */
    suspend fun save(draft: ConnectionDraft): Long = withContext(io) {
        database.withTransaction {
            val existing = draft.id?.let { dao.byId(it) }
            val secretRef = when {
                draft.password.isNotEmpty() -> cipher.encrypt(draft.password)
                else -> existing?.secretRef
            }
            val entity = ConnectionEntity(
                id = draft.id ?: 0L,
                name = draft.name.trim(),
                protocol = draft.protocol.id,
                basePath = draft.basePath.trim().ifEmpty { "/" },
                username = draft.username.trim().ifEmpty { null },
                secretRef = secretRef,
                options = draft.options().format(),
                tls = existing?.tls,
                lastWorkingAddressId = existing?.lastWorkingAddressId,
                lastCheckedAt = existing?.lastCheckedAt,
            )
            val connectionId = dao.upsert(entity)

            // 地址：按 id 增量更新，删掉草稿里已经没有的（保住 lastWorkingAddressId 指向的行）
            val keepAddressIds = draft.addresses.mapNotNull { it.id.takeIf { id -> id > 0L } }.toSet()
            dao.addressesOf(connectionId)
                .filter { it.id !in keepAddressIds }
                .forEach { dao.deleteAddress(it.id) }
            draft.addresses.forEach { address ->
                dao.upsertAddress(
                    AddressEntity(
                        id = address.id,
                        connectionId = connectionId,
                        label = address.label.id,
                        scheme = address.scheme.trim().lowercase(),
                        host = address.host.trim(),
                        port = address.portOrZero,
                        priority = address.priorityOrZero,
                    ),
                )
            }

            // 选路：只保留一条"优先网络"记录（旧的 Wi-Fi/蜂窝/SSID/网段条件在保存时被替换掉）。
            // 表结构不动，仍写 network_rule.prefer，老版本升级不会碰到迁移。
            dao.deleteRulesOf(connectionId)
            dao.upsertRule(
                NetworkRuleEntity(
                    connectionId = connectionId,
                    prefer = draft.prefer.id,
                ),
            )
            connectionId
        }
    }

    /** 删除连接（连带地址与规则，同一事务）。 */
    suspend fun delete(id: Long) = withContext(io) {
        database.withTransaction {
            dao.deleteAddressesOf(id)
            dao.deleteRulesOf(id)
            dao.delete(id)
        }
    }

    /** 记录"最近一次检测"（R8 列表里的最近检测时间与可用地址）。 */
    suspend fun markChecked(connectionId: Long, addressId: Long?, atMs: Long) = withContext(io) {
        dao.updateLastChecked(connectionId, addressId, atMs)
    }

    /**
     * 解密 `secretRef` 得到明文（**只给 :app 构造后端用**，界面不展示）。
     *
     * 解密失败（换机/清数据后密钥丢失、密文被改）返回 null，由调用方给出中文提示让用户重新录入。
     */
    suspend fun revealSecret(connectionId: Long): String? = withContext(io) {
        val entity = dao.byId(connectionId) ?: return@withContext null
        val envelope = entity.secretRef ?: return@withContext null
        try {
            cipher.decrypt(envelope)
        } catch (e: Exception) {
            null
        }
    }

    /** 密文判定（给界面与测试用）：`secretRef` 里必须是本程序的密文信封，不能是明文。 */
    fun isEncrypted(secretRef: String?): Boolean = CredentialEnvelope.isEnvelope(secretRef)
}
