package icu.gxb.hypertv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页状态单测（ticket 11）：
 * - AboutInfo.webUiUrl 由 IP + 端口派生（ip 为 null 时为 null，页面显示"无法获取局域网 IP"）
 * - AboutScreenState 开合（只读页，返回键关闭）
 */
class AboutScreenStateTest {

    @Test
    fun `web ui url derived from ip and port`() {
        val info = AboutInfo(versionName = "1.0", ip = "192.168.1.5", port = 8080)

        assertEquals("http://192.168.1.5:8080", info.webUiUrl)
    }

    @Test
    fun `web ui url is null when no ip`() {
        val info = AboutInfo(versionName = "1.0", ip = null, port = 8080)

        assertNull(info.webUiUrl)
    }

    @Test
    fun `open and close toggle about page`() {
        val about = AboutScreenState()

        assertFalse(about.isOpen)
        about.open()
        assertTrue(about.isOpen)
        about.close()
        assertFalse(about.isOpen)
    }
}
