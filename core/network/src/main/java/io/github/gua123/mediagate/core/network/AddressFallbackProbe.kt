package io.github.gua123.mediagate.core.network

/**
 * 一次地址探测的结果（**R7**：先内网后外网的实际落点）。
 *
 * @param address 被探测的地址。
 * @param ok TCP 是否连得上。
 * @param error 失败分类；成功为 null。
 */
data class AddressProbeAttempt(
    val address: SelectableAddress,
    val ok: Boolean,
    val error: ConnectivityError? = null,
)

/**
 * 顺序探测的结论。
 *
 * @param address 最终要用的地址：第一个可连的；全都不通时是首选（让后续浏览给出真实错误）。
 * @param attempts 已经探测过的地址（按尝试顺序；没探测时为空）。
 * @param usedFallback 是否用到了备选（首选不通、改用了后面的地址）。
 */
data class AddressChoice(
    val address: SelectableAddress?,
    val attempts: List<AddressProbeAttempt> = emptyList(),
    val usedFallback: Boolean = false,
) {
    /** 是否真的发起过探测。 */
    val probed: Boolean get() = attempts.isNotEmpty()
}

/**
 * 按顺序探测候选地址，**先内网后外网**（2026-10-25 用户要求）。
 *
 * 与旧的并行竞速（RaceProbe）不同：这里**不追求"谁快选谁"**，而是严格按
 * [SelectionResult.ordered] 的顺序先试局域网；局域网 TCP 不通才轮到公网。
 * 理由：同一台服务器在内网通常更快，而且用户明确要"先内网"；并行竞速会让公网
 * 抢在局域网前面（尤其局域网握手慢一点时），正好违背这个口径。
 *
 * **为什么用 TCP 而不是协议握手**：选路要在建后端之前完成，而各协议后端的
 * `probe()` 用的是连接配置里的超时（默认 5 s），局域网不可达时会让"回公网"慢上好几秒。
 * TCP 探测有 1.5 s 硬上限，地址层面的"通不通"已经足够决定顺序；认证/协议错误仍由
 * 后续的真实请求暴露（同一台服务器换条网络不会改变凭据）。
 *
 * 全部候选都不通时返回首选而不是 null：调用方仍会建后端，浏览页会给出
 * 具体的中文错误（"网络超时 / 认证失败…"），比"没有可用地址"更可诊断。
 *
 * 纯 JVM 可测：注入 [TcpProbe] 即可（见 `AddressFallbackProbeTest`）。
 */
class AddressFallbackProbe(
    private val tcp: TcpProbe = TcpProbe(),
    private val timeoutMs: Long = TcpProbe.DEFAULT_TIMEOUT_MS,
) {

    /**
     * 依次探测 [ordered]，返回第一个连得上的地址。
     *
     * @param ordered 选路给出的尝试顺序（首选在前）。
     */
    suspend fun choose(ordered: List<SelectableAddress>): AddressChoice {
        if (ordered.isEmpty()) return AddressChoice(address = null)
        // 只有一个地址：没必要探测，直接交给协议握手（省一次往返）
        if (ordered.size == 1) return AddressChoice(address = ordered.first())

        val attempts = mutableListOf<AddressProbeAttempt>()
        ordered.forEachIndexed { index, candidate ->
            val result = tcp.connect(candidate.host, candidate.port, timeoutMs)
            attempts += AddressProbeAttempt(candidate, result.ok, result.error)
            if (result.ok) {
                return AddressChoice(
                    address = candidate,
                    attempts = attempts,
                    usedFallback = index > 0,
                )
            }
        }
        // 全都不通：仍按首选安装，错误在真正请求时如实暴露
        return AddressChoice(address = ordered.first(), attempts = attempts)
    }
}
