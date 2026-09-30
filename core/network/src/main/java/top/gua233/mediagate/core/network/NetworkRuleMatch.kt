package io.github.gua123.mediagate.core.network

/**
 * 规则匹配的纯函数集合（**R7**，plan 4.5：规则 = 传输类型 / SSID / 本机网段）。
 *
 * 单独成文件是为了能被 JVM 单测直接打靶：SSID 通配、网段的三种写法（CIDR / 前缀 / 精确 IP）都在这里，
 * [AddressSelector] 只负责"按具体度挑一条规则"。
 */
object NetworkRuleMatch {

    /**
     * SSID 通配匹配：`*` 匹配任意串（含空串），大小写不敏感。
     *
     * 例：pattern `Home*` 命中 `Home-5G`；pattern `*` 命中任意 SSID；
     * [ssid] 为 null（没有定位权限时 Android 不给 SSID）时**只有** pattern 为 `*` 才算命中——
     * 拿不到 SSID 就不该假装匹配上了某条 SSID 规则。
     */
    fun matchesSsid(pattern: String?, ssid: String?): Boolean {
        val clean = pattern?.trim().orEmpty()
        if (clean.isEmpty()) return false
        if (clean == "*") return true
        val name = NetworkContext.normalizeSsid(ssid) ?: return false
        return wildcard(clean, name)
    }

    /**
     * 本机网段匹配（[pattern] 来自规则，[localSubnets] 来自系统采集）。
     *
     * 支持三种写法：
     * - CIDR：`192.168.1.0/24` —— 与本地网段（`192.168.1.10/24` 或 `192.168.1.0/24`）
     *   按**较短前缀**比较网络号（`192.168.1.10/24` 属于 `192.168.1.0/24`）；
     * - 前缀：`192.168.1.` 或 `192.168.1.*` —— 字符串前缀匹配；
     * - 精确：`192.168.1.10` —— 与本机地址精确相等（或本地网段的网络号相等）。
     *
     * 非 IPv4（如 IPv6）退化为"字符串前缀 / 相等"比较，不做地址运算。
     */
    fun matchesSubnet(pattern: String?, localSubnets: Set<String>): Boolean {
        val clean = pattern?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return localSubnets.any { matchesOne(clean, it.trim()) }
    }

    private fun matchesOne(pattern: String, local: String): Boolean {
        if (local.isEmpty()) return false
        val patternIp = pattern.substringBefore('/')
        val localIp = local.substringBefore('/')

        // 前缀 / 通配写法：192.168.1. / 192.168.1.* / 192.168.*
        if (pattern.endsWith(".") || pattern.endsWith("*")) {
            val prefix = pattern.trimEnd('*').trimEnd('.')
            return localIp.startsWith(prefix + ".") || localIp == prefix
        }

        val patternNet = ipv4ToLong(patternIp)
        val localNet = ipv4ToLong(localIp)
        if (patternNet == null || localNet == null) {
            // 非 IPv4：只做字符串相等
            return patternIp == localIp
        }
        // 规则写的是精确 IP（没有 /len）：只跟本机地址本身比，不按网段放宽
        if (!pattern.contains('/')) {
            return patternNet == localNet
        }
        val patternBits = prefixBits(pattern)
        val localBits = prefixBits(local)
        val bits = minOf(patternBits, localBits)
        return (patternNet shr (32 - bits)) == (localNet shr (32 - bits))
    }

    /** 取前缀长度；无 `/len` 时按 32（精确 IP）。 */
    private fun prefixBits(cidr: String): Int {
        val raw = cidr.substringAfter('/', "32").trim().toIntOrNull() ?: 32
        return raw.coerceIn(0, 32)
    }

    /** `a.b.c.d` → 32 位无符号长；不是 IPv4 返回 null。 */
    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    /** `*` 通配（大小写不敏感），逐段回溯匹配。 */
    private fun wildcard(pattern: String, text: String): Boolean {
        val p = pattern.lowercase()
        val t = text.lowercase()
        var pi = 0
        var ti = 0
        var star = -1
        var match = 0
        while (ti < t.length) {
            when {
                pi < p.length && (p[pi] == t[ti]) -> {
                    pi++
                    ti++
                }
                pi < p.length && p[pi] == '*' -> {
                    star = pi
                    match = ti
                    pi++
                }
                star >= 0 -> {
                    pi = star + 1
                    match++
                    ti = match
                }
                else -> return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }
}
