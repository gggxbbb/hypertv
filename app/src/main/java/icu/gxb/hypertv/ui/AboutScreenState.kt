package icu.gxb.hypertv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 关于页展示的只读连接信息（ticket 11，ADR-0002）：
 * 版本号、局域网 IP、实际监听端口与派生的 WebUI 地址。全部本地读取，
 * 端口来自内嵌服务实际 listen 状态（动态端口改造）：null 表示服务未启动/启动失败。
 */
internal data class AboutInfo(
    /** App 版本名（BuildConfig.VERSION_NAME） */
    val versionName: String,
    /** 局域网 IPv4；未取到（未联网/无可用接口）时为 null */
    val ip: String?,
    /** 内嵌服务实际监听端口；服务未启动/启动失败时为 null */
    val port: Int?,
) {
    /** WebUI 地址；IP 或端口任一缺失时为 null（页面显示"服务未启动/无法获取局域网 IP"） */
    val webUiUrl: String?
        get() = port?.let { p -> ip?.let { "http://$it:$p" } }
}

/**
 * 关于页全屏页的 UI 状态（ticket 11）：开合。
 * 内容只读（版本/IP/端口/二维码），无任何可修改项；返回键关闭回到播放页。
 */
class AboutScreenState {

    var isOpen by mutableStateOf(false)
        private set

    /** 主菜单"关于"进入 */
    fun open() {
        isOpen = true
    }

    /** 返回键关闭（回到播放页） */
    fun close() {
        isOpen = false
    }
}
