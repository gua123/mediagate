package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.ConnectivityError
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
     *
     * @param source 本次测试用的是哪份密码。**必须标明**：真机反馈过"账号密码都是对的却报认证失败"，
     *   而"密码框留空 = 沿用已保存的旧密码"这条规则让用户很难意识到测的是旧密码——
     *   标明来源之后，空着测一次、重输一遍再测一次，两次结果一比就知道问题在哪。
     */
    fun summarize(results: List<AddressTestResult>, source: PasswordSource = PasswordSource.NONE): String {
        if (results.isEmpty()) return "没有可测试的地址：先填主机名"
        val used = "〔使用：" + source.zhText + "〕"
        val ok = results.firstOrNull { it.ok }
        if (ok != null) {
            return used + "测试通过：" + ok.address.display + "（" + ok.totalMs + " ms）"
        }
        val first = results.first()
        val detail = first.error?.display ?: first.message ?: first.notice ?: "原因未知"
        val base = used + "测试失败：" + first.address.display + " — " + detail + "（" + first.timingLine + "）"
        // 认证失败时的自检提示：换一份密码再测，结论立刻分化
        return if (first.error == ConnectivityError.AUTH_FAILED && source != PasswordSource.NEW_INPUT) {
            base + "。试试在密码框里重新输入一遍密码再点「测试一下」：如果这次通过，说明存着的那份密码不对（留空=沿用旧密码）。"
        } else {
            base
        }
    }
}

/** 测试结果的三种结局（**2026-10-03 真机**：截图里「测试通过」被染成了红色）。 */
enum class TestOutcome(val mark: String) {

    /** 至少一个地址通了。 */
    PASSED("✓"),

    /** 全都没通。 */
    FAILED("✗"),

    /** 连地址都没填，谈不上通不通。 */
    NOT_APPLICABLE("•"),
}

/**
 * 把 [DraftTestSupport.summarize] 产出的那句话分类（**纯函数**）。
 *
 * 为什么要有它：界面原来用 `result.startsWith("测试通过")` 判断颜色，而真实文案是
 * `〔使用：已保存的密码〕测试通过：sftp://…（1209 ms）`——**开头是"〔使用：…〕"**，
 * 所以 `startsWith` 永远为假，**"测试通过"被染成了错误的红色**（用户截图里看得一清二楚）。
 * 现在只认"文案里出现「测试通过」/「测试失败」"，并且**有单测把真机那串原文钉住**。
 */
fun testOutcomeOf(result: String): TestOutcome = when {
    result.contains("测试通过") -> TestOutcome.PASSED
    result.contains("测试失败") -> TestOutcome.FAILED
    else -> TestOutcome.NOT_APPLICABLE
}

/** 本次测试用的是哪份密码（真机自查的关键提示，见 [DraftTestSupport.summarize]）。 */
enum class PasswordSource(val zhText: String) {

    /** 用户这次在密码框里新输入的明文。 */
    NEW_INPUT("本次新输入的密码"),

    /** 沿用已保存（Keystore 密文）的密码。 */
    STORED("已保存的密码"),

    /** 没有密码（公钥认证 / 匿名）。 */
    NONE("没有密码"),
}
