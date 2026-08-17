package icu.gxb.hypertv.server

import kotlinx.serialization.Serializable

/** GET /api/status 的响应结构。 */
@Serializable
data class ServerStatus(
    /** App 版本名（BuildConfig.VERSION_NAME）。 */
    val version: String,
    /** 局域网 IPv4 地址；未取到时为 null。 */
    val ip: String?,
    /** 内嵌服务实际监听端口；服务未启动/启动失败时为 null。 */
    val port: Int?,
)
