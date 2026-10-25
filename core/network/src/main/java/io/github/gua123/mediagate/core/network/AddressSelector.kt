package io.github.gua123.mediagate.core.network

/**
 * 选路结论（**R7**）。
 *
 * **2026-10-25 用户要求：按「先内网后外网」选路**——不再看 Wi-Fi / 蜂窝这类传输类型，
 * 而是永远先试局域网地址；局域网连不上再自动改用公网（[AddressFallbackProbe]）。
 */
enum class SelectionReason(val code: String, val zhText: String) {
    /** 设备离线：任何地址都不必试。 */
    OFFLINE("OFFLINE", "当前没有网络，无法连接"),

    /** 连接还没配置地址。 */
    NO_ADDRESS("NO_ADDRESS", "该连接还没有配置地址"),

    /** 有多个地址：按「优选网络 → 同网络优先级」排好序，调用方按顺序尝试，失败的自动换下一个。 */
    PREFERRED_FIRST("PREFERRED_FIRST", "按优先网络依次尝试"),

    /** 只有一个地址：直接试，不必兜底。 */
    SINGLE_ADDRESS("SINGLE_ADDRESS", "只有一个可用地址，直接连接"),
}

/**
 * 选路结果（**R7**）。
 *
 * @param primary 首选地址；[SelectionReason.OFFLINE] / [SelectionReason.NO_ADDRESS] 时为 null。
 * @param ordered 尝试顺序（含 [primary]）：优选网络的地址在前，同网络内按人工优先级降序。
 * @param reason 判定依据（稳定枚举，便于单测与埋点）。
 * @param explanation 中文解释（界面直接展示，讲清"为什么选它、连不上会怎样"）。
 * @param hasFallback 首选失败时是否还有备选可自动尝试。
 * @param preferred 该连接偏好的网络（默认 [AddressLabel.LAN] = 先内网后外网）。
 */
data class SelectionResult(
    val primary: SelectableAddress?,
    val ordered: List<SelectableAddress>,
    val reason: SelectionReason,
    val explanation: String,
    val hasFallback: Boolean,
    val preferred: AddressLabel = AddressLabel.LAN,
) {
    /** 没有可用地址（离线或未配置）。 */
    val empty: Boolean get() = primary == null
}

/**
 * 多地址选路（**R7**）——**纯逻辑，零 IO**，JVM 单测覆盖全部判定分支。
 *
 * **口径（2026-10-25 用户要求）**：先内网后外网。
 * 1. **网络能力**：离线 → [SelectionReason.OFFLINE]，不再往下走；
 * 2. **排序**：该连接偏好的网络（默认局域网）的地址全部排在前面，另一条网络的地址排在后面；
 *    同一条网络内仍按人工 `priority` 降序 + `id` 升序，保证结果可复现；
 * 3. **兜底**：[SelectionResult.hasFallback] 为真时，调用方用 [AddressFallbackProbe]
 *    依次探测（首选不通就自动换下一个），不再按 Wi-Fi / 蜂窝 / SSID / 本机网段配置规则；
 * 4. **协议握手**：本类不做——真正的决定权仍在协议握手，失败的顺延到下一个。
 */
object AddressSelector {

    /**
     * 选路主入口。
     *
     * @param addresses 该连接的全部地址（顺序无关）。
     * @param context 当前网络现场（来自 :app 的网络监听）。
     * @param prefer 该连接偏好的网络；默认局域网（先内网后外网）。
     */
    fun select(
        addresses: List<SelectableAddress>,
        context: NetworkContext,
        prefer: AddressLabel = AddressLabel.LAN,
    ): SelectionResult {
        if (addresses.isEmpty()) {
            return SelectionResult(
                primary = null,
                ordered = emptyList(),
                reason = SelectionReason.NO_ADDRESS,
                explanation = SelectionReason.NO_ADDRESS.zhText,
                hasFallback = false,
                preferred = prefer,
            )
        }
        // ① 网络能力
        if (!context.usable) {
            return SelectionResult(
                primary = null,
                ordered = emptyList(),
                reason = SelectionReason.OFFLINE,
                explanation = SelectionReason.OFFLINE.zhText + "（" + context.capability.zhText + "）",
                hasFallback = false,
                preferred = prefer,
            )
        }
        // ② 先内网后外网（或反过来，按该连接的偏好）
        val ordered = sortByPreferredLabel(addresses, prefer)
        val first = ordered.first()
        val other = prefer.other()
        val otherCount = ordered.count { it.label == other }
        val reason = if (ordered.size == 1) SelectionReason.SINGLE_ADDRESS else SelectionReason.PREFERRED_FIRST
        val explanation = when {
            ordered.size == 1 -> "只有一个地址 " + first.label.zhText + " " + first.display
            otherCount > 0 -> orderText(prefer) + "：首选 " + first.label.zhText + " " + first.display +
                "；连不上会自动改用" + other.zhText + "（" + otherCount + " 个）"
            else -> orderText(prefer) + "：首选 " + first.label.zhText + " " + first.display +
                "（没有另一条网络的地址）"
        }
        return SelectionResult(
            primary = first,
            ordered = ordered,
            reason = reason,
            explanation = explanation,
            hasFallback = ordered.size > 1,
            preferred = prefer,
        )
    }

    /**
     * 按"优选网络优先，其次人工优先级降序，最后 id 升序"排序。
     *
     * 注意：**不是**优先级数字大就能压过网络偏好——用户的口径是"先内网后外网"，
     * 公网地址优先级再高也只排在内网之后；同一网络内部的先后才看优先级。
     */
    fun sortByPreferredLabel(
        addresses: List<SelectableAddress>,
        prefer: AddressLabel,
    ): List<SelectableAddress> = addresses.sortedWith(
        compareBy<SelectableAddress> { if (it.label == prefer) 0 else 1 }
            .thenByDescending { it.priority }
            .thenBy { it.id },
    )

    /** 「先内网后外网」/「先外网后内网」的中文说法。 */
    fun orderText(prefer: AddressLabel): String = when (prefer) {
        AddressLabel.LAN -> "先内网后外网"
        AddressLabel.WAN -> "先外网后内网"
    }

    /** 另一条网络。 */
    private fun AddressLabel.other(): AddressLabel = when (this) {
        AddressLabel.LAN -> AddressLabel.WAN
        AddressLabel.WAN -> AddressLabel.LAN
    }
}
