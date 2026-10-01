package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.ProtocolKind

/**
 * 「在编辑器里先测一下」的纯逻辑（**R8**）。
 *
 * 为什么要它：真机上出现过"填完密码保存 → 点开浏览 → 认证失败"，而用户无从判断
 * 到底是密码错了、还是端口/协议不对、还是服务器在拒人。
 * 有了草稿级的测试，用户**不用先保存**就能知道这一组参数行不行；
 * 密码框留空时会沿用已保存的密文（与保存口径一致），所以"改没改密码"这件事也测得出来。
 */
object DraftTestSupport {

    /**
     * 草稿 → 可测试的连接记录（**不落库**）。
     *
     * 只造测试需要的那几项：协议、第一个地址、用户名、根路径；id 用 0（表示"还没保存"）。
     * 地址用草稿里的非空项，全部为空时给一个占位地址，让测试结果给出"没有地址"这类诚实结论。
     */
    fun recordOf(draft: ConnectionDraft): ConnectionRecord {
        val addresses = draft.addresses
            .filter { it.host.isNotBlank() }
            .mapIndexed { index, address ->
                AddressRecord(
                    id = (index + 1).toLong(),
                    connectionId = 0L,
                    label = address.label,
                    scheme = address.scheme,
                    host = address.host.trim(),
                    port = address.portOrZero,
                    priority = address.priorityOrZero,
                )
            }
        return ConnectionRecord(
            id = 0L,
            name = draft.name.ifBlank { "（未命名）" },
            protocolId = draft.protocol.id,
            protocol = draft.protocol,
            basePath = draft.basePath.ifBlank { "/" },
            username = draft.username.trim().ifEmpty { null },
            hasSecret = draft.hasStoredSecret || draft.password.isNotEmpty(),
            options = draft.options(),
            tls = null,
            lastWorkingAddressId = null,
            lastCheckedAt = null,
            addresses = addresses,
            rules = emptyList(),
        )
    }

    /**
     * 测试结果 → 一句话中文（编辑器里直接显示）。
     *
     * 取**第一个成功**的地址；全失败时把第一条的失败原因摊开——用户最需要知道的是"错在哪"。
     */
    fun summarize(results: List<AddressTestResult>): String {
        if (results.isEmpty()) return "没有可测试的地址：先填主机名"
        val ok = results.firstOrNull { it.ok }
        if (ok != null) {
            return "测试通过：" + ok.address.display + "（" + ok.totalMs + " ms）"
        }
        val first = results.first()
        val detail = first.error?.display ?: first.message ?: first.notice ?: "原因未知"
        return "测试失败：" + first.address.display + " — " + detail + "（" + first.timingLine + "）"
    }
}
