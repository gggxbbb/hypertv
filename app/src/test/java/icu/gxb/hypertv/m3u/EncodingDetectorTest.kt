package icu.gxb.hypertv.m3u

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncodingDetectorTest {

    @Test
    fun `detects utf8 with bom`() {
        val content = "#EXTM3U\n#EXTINF:-1,中文频道\nhttp://s.example/1.m3u8"
        val bytes = "\uFEFF$content".toByteArray(Charsets.UTF_8)

        val decoded = EncodingDetector.decodeDetected(bytes)

        assertEquals("UTF-8", decoded.encoding)
        assertEquals(content, decoded.text)
    }

    @Test
    fun `detects utf8 without bom`() {
        val content = "#EXTINF:-1,中文频道\nhttp://s.example/1.m3u8"
        val decoded = EncodingDetector.decodeDetected(content.toByteArray(Charsets.UTF_8))

        assertEquals("UTF-8", decoded.encoding)
        assertEquals(content, decoded.text)
    }

    @Test
    fun `detects gbk content`() {
        val content = "#EXTM3U\n#EXTINF:-1,中央一台\nhttp://s.example/1.m3u8"
        val bytes = content.toByteArray(Charset.forName("GBK"))

        val decoded = EncodingDetector.decodeDetected(bytes)

        assertEquals("GBK", decoded.encoding)
        assertEquals(content, decoded.text)
    }

    @Test
    fun `pure ascii decodes as utf8 identically`() {
        val content = "#EXTM3U\n#EXTINF:-1,CCTV-1\nhttp://s.example/1.m3u8"

        val decoded = EncodingDetector.decodeDetected(content.toByteArray())

        assertEquals("UTF-8", decoded.encoding)
        assertEquals(content, decoded.text)
    }

    @Test
    fun `gbk bytes misdetected as utf8 produces mojibake not crash`() {
        // GBK 字节中偶有碰巧合法 UTF-8 序列的极端情况：保证不崩溃、可解析即可
        val content = "测试"
        val gbk = content.toByteArray(Charset.forName("GBK"))

        val text = EncodingDetector.decode(gbk)

        assertNotEquals("", text)
    }

    @Test
    fun `empty bytes decode to empty string`() {
        assertEquals("", EncodingDetector.decode(ByteArray(0)))
    }
}
