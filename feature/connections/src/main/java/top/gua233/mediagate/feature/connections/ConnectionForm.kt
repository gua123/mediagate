package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.NetworkCapability
import io.github.gua123.mediagate.core.network.NetworkRule
import io.github.gua123.mediagate.core.network.ProtocolKind

/**
 * 表单里可以出错的位置（**R8**）。界面按它把红字放到对应输入框下面。
 */
enum class FormField {
    NAME,
    PROTOCOL,
    BASE_PATH,
    ADDRESSES,
    HOST,
    PORT,
    SCHEME,
    PASSWORD,
}

/** 编辑器里的一个地址草稿（**R7** 多地址；端口用字符串以便校验"不是数字"）。 */
data class AddressDraft(
    val id: Long = 0L,
    val label: AddressLabel = AddressLabel.LAN,
    val scheme: String = "http",
    val host: String = "",
    val port: String = "80",
    val priority: String = "0",
) {

    /** 端口 → Int（空串按 0 处理，校验会拦住需要端口的协议）。 */
    val portOrZero: Int get() = port.trim().toIntOrNull() ?: 0

    /** 优先级 → Int（乱写按 0）。 */
    val priorityOrZero: Int get() = priority.trim().toIntOrNull() ?: 0
}

/** 编辑器里的一条选路规则草稿（**R7**）。 */
data class RuleDraft(
    val id: Long = 0L,
    val transport: NetworkCapability? = null,
    val ssidPattern: String = "",
    val localSubnet: String = "",
    val prefer: AddressLabel = AddressLabel.LAN,
) {

    /** 展示/选路用的核心模型（空串归一成 null = 不限制）。 */
    fun toNetworkRule(): NetworkRule = NetworkRule(
        id = id,
        transport = transport,
        ssidPattern = ssidPattern.trim().ifEmpty { null },
        localSubnet = localSubnet.trim().ifEmpty { null },
        prefer = prefer,
    )
}

/**
 * 连接编辑草稿（**R8**）。
 *
 * @param password 用户**新输入**的明文；空串表示"不修改已保存的密码"（[hasStoredSecret] 为真时界面提示"已保存"）。
 * @param basePath 连接内的根路径（plan 第 7 章 `connection.basePath`）；LOCAL 协议时它是本地目录的绝对路径。
 */
data class ConnectionDraft(
    val id: Long? = null,
    val name: String = "",
    val protocol: ProtocolKind = ProtocolKind.WEBDAV,
    val basePath: String = "/",
    val username: String = "",
    val password: String = "",
    val hasStoredSecret: Boolean = false,
    val allowInsecureHttp: Boolean = true,
    val addresses: List<AddressDraft> = listOf(AddressDraft()),
    val rules: List<RuleDraft> = emptyList(),
) {

    /** 协议选项写回 `connection.options`（R8）。 */
    fun options(): ConnectionOptions = ConnectionOptions(allowInsecureHttp = allowInsecureHttp)

    /** 已保存过密码且本次没重输 → 保存时保留原密文。 */
    val keepsStoredSecret: Boolean get() = password.isEmpty() && hasStoredSecret

    /**
     * 切换协议（纯函数）：把地址的传输方案与端口换成该协议的默认值，主机名保留
     * （用户多半是"地址填错了协议"，不该连地址一起清掉）。
     */
    fun withProtocol(next: ProtocolKind): ConnectionDraft {
        if (next == protocol) return this
        val defaults = ProtocolDefaults.of(next)
        return copy(
            protocol = next,
            basePath = if (next == ProtocolKind.LOCAL) basePath.ifBlank { "/" } else basePath,
            addresses = addresses.map { it.copy(scheme = defaults.scheme, port = defaults.port) },
        )
    }
}

// ------------------------------------------------------------------ 草稿的纯函数操作
// 界面上的每一次增删改都走这几个函数（都是 copy，不改原对象），因此可以直接 JVM 单测。

/** 替换第 [index] 个地址（下标越界时原样返回）。 */
fun ConnectionDraft.withAddress(index: Int, address: AddressDraft): ConnectionDraft {
    if (index !in addresses.indices) return this
    return copy(addresses = addresses.toMutableList().also { it[index] = address })
}

/** 追加一个地址（沿用当前协议的默认方案/端口）。 */
fun ConnectionDraft.plusAddress(): ConnectionDraft {
    val defaults = ProtocolDefaults.of(protocol)
    return copy(
        addresses = addresses + AddressDraft(
            label = io.github.gua123.mediagate.core.network.AddressLabel.WAN,
            scheme = defaults.scheme,
            port = defaults.port,
        ),
    )
}

/** 删除第 [index] 个地址（至少保留一个，否则表单没地方填地址）。 */
fun ConnectionDraft.minusAddress(index: Int): ConnectionDraft {
    if (index !in addresses.indices || addresses.size <= 1) return this
    return copy(addresses = addresses.toMutableList().also { it.removeAt(index) })
}

/** 追加一条选路规则（R7）。 */
fun ConnectionDraft.plusRule(rule: RuleDraft): ConnectionDraft = copy(rules = rules + rule)

/** 删除第 [index] 条选路规则。 */
fun ConnectionDraft.minusRule(index: Int): ConnectionDraft {
    if (index !in rules.indices) return this
    return copy(rules = rules.toMutableList().also { it.removeAt(index) })
}

/** 各协议的默认传输方案与端口（R2/R8）。 */
object ProtocolDefaults {

    /** 默认方案与端口。 */
    data class Defaults(val scheme: String, val port: String)

    fun of(protocol: ProtocolKind): Defaults = when (protocol) {
        ProtocolKind.LOCAL -> Defaults("file", "0")
        ProtocolKind.WEBDAV -> Defaults("http", "80")
        ProtocolKind.SFTP -> Defaults("sftp", "22")
        ProtocolKind.FTP -> Defaults("ftp", "21")
    }

    /** 该协议允许的传输方案（校验用）。 */
    fun allowedSchemes(protocol: ProtocolKind): Set<String> = when (protocol) {
        ProtocolKind.LOCAL -> setOf("file")
        ProtocolKind.WEBDAV -> setOf("http", "https")
        ProtocolKind.SFTP -> setOf("sftp")
        ProtocolKind.FTP -> setOf("ftp", "ftps")
    }

    /** 该协议是否需要主机与端口（本地目录不需要）。 */
    fun needsHostAndPort(protocol: ProtocolKind): Boolean = protocol != ProtocolKind.LOCAL
}

/**
 * 表单校验结果（**R8**）。
 *
 * @param globalErrors 与具体地址无关的错误（名称 / 根路径 / 地址列表本身）。
 * @param addressErrors 与 [ConnectionDraft.addresses] 下标一一对应的错误表。
 */
data class ConnectionFormResult(
    val globalErrors: Map<FormField, String> = emptyMap(),
    val addressErrors: List<Map<FormField, String>> = emptyList(),
) {

    /** 全部通过才能保存。 */
    val valid: Boolean get() = globalErrors.isEmpty() && addressErrors.all { it.isEmpty() }

    /** 取某个字段的错误（[addressIndex] 为 null 时取全局错误）。 */
    fun errorOf(field: FormField, addressIndex: Int? = null): String? =
        if (addressIndex == null) globalErrors[field] else addressErrors.getOrNull(addressIndex)?.get(field)
}

/**
 * 连接表单校验（**R8**）——**纯逻辑，零 IO**，JVM 单测覆盖每条规则。
 *
 * 校验口径按"能少一次失败就少一次"来定：能在本地拦下的错误（空名称、坏端口、http 明文、
 * 主机名里带 `http://`）绝不留给连通性测试去发现。
 */
object ConnectionFormValidator {

    /** 连接名长度上限（界面一行放得下，也避免列表被撑破）。 */
    const val MAX_NAME_LENGTH = 40

    /** 校验入口。 */
    fun validate(draft: ConnectionDraft): ConnectionFormResult {
        val global = mutableMapOf<FormField, String>()

        val name = draft.name.trim()
        when {
            name.isEmpty() -> global[FormField.NAME] = "请填写连接名称"
            name.length > MAX_NAME_LENGTH -> global[FormField.NAME] = "名称最多 " + MAX_NAME_LENGTH + " 个字符"
        }

        val basePath = draft.basePath.trim()
        when {
            basePath.isEmpty() -> global[FormField.BASE_PATH] =
                if (draft.protocol == ProtocolKind.LOCAL) "请填写本地目录的绝对路径" else "请填写根路径（例如 /）"
            !basePath.startsWith("/") -> global[FormField.BASE_PATH] =
                if (draft.protocol == ProtocolKind.LOCAL) "本地目录必须是绝对路径（以 / 开头）" else "根路径必须以 / 开头"
            basePath.contains("..") -> global[FormField.BASE_PATH] = "根路径不能包含 .."
        }

        if (draft.addresses.isEmpty()) {
            global[FormField.ADDRESSES] = "至少需要一个地址"
        }

        val addressErrors = draft.addresses.map { validateAddress(draft, it) }
        return ConnectionFormResult(global, addressErrors)
    }

    private fun validateAddress(draft: ConnectionDraft, address: AddressDraft): Map<FormField, String> {
        val errors = mutableMapOf<FormField, String>()
        val protocol = draft.protocol
        val needsHost = ProtocolDefaults.needsHostAndPort(protocol)

        val allowed = ProtocolDefaults.allowedSchemes(protocol)
        val scheme = address.scheme.trim().lowercase()
        if (scheme !in allowed) {
            errors[FormField.SCHEME] = "该协议的传输方案只能是 " + allowed.joinToString(" / ")
        }

        val host = address.host.trim()
        if (needsHost) {
            when {
                host.isEmpty() -> errors[FormField.HOST] = "请填写主机名或 IP"
                host.contains("://") -> errors[FormField.HOST] = "主机名不要带 http:// 之类的协议前缀"
                host.contains('/') -> errors[FormField.HOST] = "主机名里不要带路径（路径填在根路径里）"
                host.contains(' ') -> errors[FormField.HOST] = "主机名不能包含空格"
            }
            val portText = address.port.trim()
            val port = portText.toIntOrNull()
            when {
                portText.isEmpty() -> errors[FormField.PORT] = "请填写端口"
                port == null -> errors[FormField.PORT] = "端口必须是数字"
                port !in 1..65535 -> errors[FormField.PORT] = "端口范围是 1-65535"
            }
        } else {
            // LOCAL：地址就是本地目录，必须绝对路径
            when {
                host.isEmpty() -> errors[FormField.HOST] = "请填写本地目录的绝对路径"
                !host.startsWith("/") -> errors[FormField.HOST] = "本地目录必须是绝对路径（以 / 开头）"
            }
        }

        // WebDAV 明文 http：局域网自用允许，但要显式打开开关
        if (protocol == ProtocolKind.WEBDAV && scheme == "http" && !draft.allowInsecureHttp) {
            errors[FormField.SCHEME] = "已禁止明文 http：请改用 https，或打开「允许明文 http」"
        }

        if (draft.password.length > MAX_PASSWORD_LENGTH) {
            errors[FormField.PASSWORD] = "密码过长（最多 " + MAX_PASSWORD_LENGTH + " 个字符）"
        }
        return errors
    }

    /** 密码长度上限（Keystore 能加密任意长度，这里只是挡住明显的误粘贴）。 */
    const val MAX_PASSWORD_LENGTH = 512
}
