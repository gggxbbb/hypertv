package icu.gxb.hypertv.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 获取本机局域网 IPv4 地址。
 *
 * 优先方案：枚举 [NetworkInterface] 找非回环、已启用的 IPv4，优先返回 site-local
 * 网段（192.168.x.x / 10.x.x.x / 172.16~31.x.x），避免依赖 WifiManager 权限。
 *
 * 返回 null 表示未找到（未联网 / 无可用接口 / 权限受限）。
 */
fun getLocalIpv4(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<String>()
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addresses = nif.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { candidates.add(it) }
                }
            }
        }
        selectPreferredIpv4(candidates)
    } catch (_: Exception) {
        // 网络接口枚举在某些受限环境会抛异常，统一按“无 IP”处理
        null
    }
}

/**
 * 从候选 IPv4 列表中选择首选地址：优先 site-local 网段，且 192.168.x.x > 10.x.x.x >
 * 172.16~31.x.x > 其他；同优先级保持原始顺序。纯函数，便于单测。
 */
fun selectPreferredIpv4(candidates: List<String>): String? {
    if (candidates.isEmpty()) return null
    return candidates.maxByOrNull { siteLocalRank(it) }
}

/** site-local 网段优先级，数值越大越优先；0 表示非私网地址。 */
private fun siteLocalRank(ip: String): Int = when {
    ip.startsWith("192.168.") -> 3
    ip.startsWith("10.") -> 2
    PRIVATE_172_RE.matches(ip) -> 1
    else -> 0
}

/** 判断是否为私网 site-local 网段（RFC1918）。 */
fun isSiteLocal(ip: String): Boolean {
    return ip.startsWith("192.168.") ||
        ip.startsWith("10.") ||
        PRIVATE_172_RE.matches(ip)
}

private val PRIVATE_172_RE = Regex("^172\\.(?:1[6-9]|2[0-9]|3[01])\\..*")
