package io.github.gua123.mediagate.core.network

/**
 * 选路结论（**R7**，plan 4.5「判定顺序：网络能力 → 规则 → 竞速 → 协议握手」）。
 *
 * [reason] 说明走到了哪一步，[explanation] 是可以直接贴到界面上的中文依据（R16）。
 */
enum class SelectionReason(val code: String, val zhText: String) {
    /** 设备离线：任何地址都不必试。 */
    OFFLINE("OFFLINE", "当前没有网络，无法连接"),

    /** 连接还没配置地址。 */
    NO_ADDRESS("NO_ADDRESS", "该连接还没有配置地址"),

    /** 命中规则：按规则偏好排序，直接用它（协议握手仍会做，失败再降级到备选）。 */
    RULE_MATCHED("RULE_MATCHED", "命中选路规则，按规则偏好直连"),

    /** 没有规则命中且有多个地址：需要并行竞速（TCP 1.5 s）。 */
    NEEDS_RACE("NEEDS_RACE", "没有命中规则，多个地址需要并行竞速"),

    /** 只有一个地址：直接试，不必竞速。 */
    SINGLE_ADDRESS("SINGLE_ADDRESS", "只有一个可用地址，直接连接"),
}

/**
 * 选路结果（**R7**）。
 *
 * @param primary 首选地址；[SelectionReason.OFFLINE] / [SelectionReason.NO_ADDRESS] 时为 null。
 * @param ordered 备选顺序（含 [primary]）。命中规则时= 规则偏好序；无规则时= 人工优先级序。
 * @param reason 判定依据（稳定枚举，便于单测与埋点）。
 * @param explanation 中文解释（界面直接展示，讲清"为什么选它"）。
 * @param needsRace 是否需要并行竞速（true = 调用方应交给 [RaceProbe] 而不是直接连 [primary]）。
 * @param matchedRule 命中的规则（null = 没命中）。
 */
data class SelectionResult(
    val primary: SelectableAddress?,
    val ordered: List<SelectableAddress>,
    val reason: SelectionReason,
    val explanation: String,
    val needsRace: Boolean,
    val matchedRule: NetworkRule? = null,
) {
    /** 没有可用地址（离线或未配置）。 */
    val empty: Boolean get() = primary == null
}

/**
 * 多地址选路（**R7**）——**纯逻辑，零 IO**，JVM 单测覆盖全部判定分支。
 *
 * 判定顺序与 plan 4.5 一致：
 * 1. **网络能力**：离线 → [SelectionReason.OFFLINE]，不再往下走；
 * 2. **规则**：按"具体度"（SSID > 网段 > 传输类型）挑出第一条匹配的 [NetworkRule]，
 *    命中后按 [NetworkRule.prefer] 排序（LAN 优先或 WAN 优先），**不竞速**
 *    （plan 4.5：有规则命中就不必竞速）；
 * 3. **无规则**：按 `priority` 降序 + `id` 升序给出候选，并置 [SelectionResult.needsRace]
 *    （多地址时）交给 [RaceProbe] 并行探测；
 * 4. **协议握手**：本类不做——真正的决定权在协议握手，调用方对 [SelectionResult.ordered]
 *    逐个（或竞速）握手，失败的顺延到下一个。
 *
 * 排序稳定性：同一优先级下按 [SelectableAddress.id] 升序，保证结果可复现（单测依赖这一点）。
 */
object AddressSelector {

    /** TCP 竞速的默认超时（plan 4.5：1.5 s）。 */
    const val DEFAULT_RACE_TIMEOUT_MS: Long = 1_500L

    /**
     * 选路主入口。
     *
     * @param addresses 该连接的全部地址（顺序无关）。
     * @param context 当前网络现场（来自 :app 的网络监听）。
     * @param rules 该连接的选路规则（顺序 = 用户录入顺序；同具体度时先录入者优先）。
     */
    fun select(
        addresses: List<SelectableAddress>,
        context: NetworkContext,
        rules: List<NetworkRule> = emptyList(),
    ): SelectionResult {
        if (addresses.isEmpty()) {
            return SelectionResult(
                primary = null,
                ordered = emptyList(),
                reason = SelectionReason.NO_ADDRESS,
                explanation = SelectionReason.NO_ADDRESS.zhText,
                needsRace = false,
            )
        }
        // ① 网络能力
        if (!context.usable) {
            return SelectionResult(
                primary = null,
                ordered = emptyList(),
                reason = SelectionReason.OFFLINE,
                explanation = SelectionReason.OFFLINE.zhText + "（" + context.capability.zhText + "）",
                needsRace = false,
            )
        }
        // ② 规则
        val rule = matchRule(context, rules)
        if (rule != null) {
            val ordered = sortByPreference(addresses, rule.prefer)
            return SelectionResult(
                primary = ordered.first(),
                ordered = ordered,
                reason = SelectionReason.RULE_MATCHED,
                explanation = "命中规则：" + rule.display + "，首选 " + ordered.first().display,
                needsRace = false,
                matchedRule = rule,
            )
        }
        // ③ 无规则 → 人工优先级排序 + 竞速信号
        val ordered = sortByPriority(addresses)
        val multi = ordered.size > 1
        return SelectionResult(
            primary = ordered.first(),
            ordered = ordered,
            reason = if (multi) SelectionReason.NEEDS_RACE else SelectionReason.SINGLE_ADDRESS,
            explanation = if (multi) {
                "未命中规则：" + ordered.size + " 个地址按优先级排序，需并行竞速（TCP " +
                    DEFAULT_RACE_TIMEOUT_MS + " ms）"
            } else {
                "未命中规则：只有一个地址 " + ordered.first().display
            },
            needsRace = multi,
        )
    }

    /**
     * 挑出命中的规则：三个条件（传输类型 / SSID / 网段）全部满足才算命中；
     * 多条命中时取 [NetworkRule.specificity] 最大者，同分取先录入者。
     */
    fun matchRule(context: NetworkContext, rules: List<NetworkRule>): NetworkRule? = rules
        .filter { matches(it, context) }
        .maxByOrNull { it.specificity }

    /** 单条规则是否命中当前网络。 */
    fun matches(rule: NetworkRule, context: NetworkContext): Boolean {
        if (rule.transport != null && rule.transport != context.capability) return false
        if (!rule.ssidPattern.isNullOrBlank() && !NetworkRuleMatch.matchesSsid(rule.ssidPattern, context.ssid)) {
            return false
        }
        if (!rule.localSubnet.isNullOrBlank() && !NetworkRuleMatch.matchesSubnet(rule.localSubnet, context.localSubnets)) {
            return false
        }
        return true
    }

    /** 按"偏好标签优先，其次人工优先级降序，最后 id 升序"排序。 */
    fun sortByPreference(addresses: List<SelectableAddress>, prefer: AddressLabel): List<SelectableAddress> =
        addresses.sortedWith(
            compareBy<SelectableAddress> { if (it.label == prefer) 0 else 1 }
                .thenByDescending { it.priority }
                .thenBy { it.id },
        )

    /** 纯人工优先级排序（无规则时用）。 */
    fun sortByPriority(addresses: List<SelectableAddress>): List<SelectableAddress> =
        addresses.sortedWith(compareByDescending<SelectableAddress> { it.priority }.thenBy { it.id })
}
