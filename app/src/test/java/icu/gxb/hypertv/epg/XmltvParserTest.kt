package icu.gxb.hypertv.epg

import icu.gxb.hypertv.m3u.EncodingDetector
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvParserTest {

    private val parser = XmltvParser()

    private val standardXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cctv1.example">
            <display-name lang="zh">CCTV-1 综合</display-name>
            <display-name lang="en">CCTV-1</display-name>
            <icon src="http://logo.example/cctv1.png"/>
          </channel>
          <channel id="cctv5.example"><display-name>CCTV-5 体育</display-name></channel>
          <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1.example">
            <title lang="zh">新闻联播</title>
            <desc lang="zh">今日要闻</desc>
            <category>新闻</category>
          </programme>
          <programme start="20260817003000 +0000" stop="20260817010000 +0000" channel="cctv5.example">
            <title>体育新闻</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `standard xmltv parses channels and programmes`() {
        val result = parser.parse(standardXml)

        assertEquals(2, result.channels.size)
        val cctv1 = result.channels[0]
        assertEquals("cctv1.example", cctv1.id)
        assertEquals(listOf("CCTV-1 综合", "CCTV-1"), cctv1.displayNames)
        assertEquals("http://logo.example/cctv1.png", cctv1.iconUrl)

        assertEquals(2, result.programs.size)
        val first = result.programs[0]
        assertEquals("cctv1.example", first.channelId)
        assertEquals("新闻联播", first.title)
        assertEquals("今日要闻", first.description)
        assertEquals("新闻", first.category)
        assertEquals(1786924800000L, first.startTime) // 2026-08-17T00:00:00Z
        assertEquals(1786926600000L, first.endTime) // 2026-08-17T00:30:00Z
    }

    @Test
    fun `time format with non-utc offset is parsed correctly`() {
        val xml = """
            <tv>
              <programme start="20260817080000 +0800" stop="20260817090000 +0800" channel="c">
                <title>新闻</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = parser.parse(xml)

        assertEquals(1, result.programs.size)
        // 2026-08-17T08:00:00+08:00 == 2026-08-17T00:00:00Z
        assertEquals(1786924800000L, result.programs[0].startTime)
    }

    @Test
    fun `gbk encoded content decodes then parses`() {
        val xml = """<?xml version="1.0" encoding="GBK"?><tv><channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel><programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="cctv1"><title>新闻联播</title></programme></tv>"""
        val gbkBytes = xml.toByteArray(Charset.forName("GBK"))

        val decoded = EncodingDetector.decode(gbkBytes)
        assertEquals("GBK", EncodingDetector.decodeDetected(gbkBytes).encoding)
        val result = parser.parse(decoded)

        assertEquals(1, result.channels.size)
        assertEquals("CCTV-1 综合", result.channels[0].displayNames.single())
        assertEquals("新闻联播", result.programs.single().title)
    }

    @Test
    fun `malformed rows are skipped without crash`() {
        val xml = """
            <tv>
              <channel>   <!-- 缺 id：跳过 -->
                <display-name>无名频道</display-name>
              </channel>
              <channel id="good"><display-name>好频道</display-name></channel>
              <programme start="bad-time" stop="20260817010000 +0000" channel="good">
                <title>坏时间</title>
              </programme>
              <programme channel="good" stop="20260817010000 +0000">   <!-- 缺 start -->
                <title>缺开始</title>
              </programme>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000">  <!-- 缺 channel -->
                <title>缺频道</title>
              </programme>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="good">
                <title>    </title>   <!-- 空标题：跳过 -->
              </programme>
              <programme start="20260817003000 +0000" stop="20260817010000 +0000" channel="good">
                <title>正常节目</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = parser.parse(xml)

        // 仅 id 合法、时间合法、标题非空的一条被保留
        assertEquals(1, result.channels.size)
        assertEquals("good", result.channels[0].id)
        assertEquals(1, result.programs.size)
        assertEquals("正常节目", result.programs[0].title)
    }

    @Test
    fun `truncated malformed xml keeps already parsed rows`() {
        val xml = """
            <tv>
              <channel id="c1"><display-name>频道一</display-name></channel>
              <programme start="20260817000000 +0000" stop="20260817003000 +0000" channel="c1">
                <title>节目一</title>
              </programme>
              <programme start="20260817003000 +0000" stop="20260817010000 +0000" channel="c1">
                <title>节目二</title>
        """.trimIndent() // 未闭合

        val result = parser.parse(xml)

        // 已解析部分保留（至少解析到节目二的开头），不崩溃
        assertTrue(result.channels.isNotEmpty())
    }

    @Test
    fun `empty content returns empty result`() {
        val result = parser.parse("")
        assertTrue(result.channels.isEmpty())
        assertTrue(result.programs.isEmpty())
    }

    @Test
    fun `ten thousand programmes parse under 5 seconds`() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z", java.util.Locale.ROOT)
        val base = java.time.OffsetDateTime.of(2026, 7, 18, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val xml = buildString {
            append("<tv>")
            repeat(100) { i -> append("<channel id=\"c$i\"><display-name>频道 $i</display-name></channel>") }
            repeat(10_000) { i ->
                val channel = "c${i % 100}"
                val start = base.plusSeconds(i * 30L)
                val stop = start.plusSeconds(30)
                append("<programme start=\"${fmt.format(start)}\" stop=\"${fmt.format(stop)}\" channel=\"$channel\">")
                append("<title>节目 $i</title><desc>简介 $i</desc><category>综合</category>")
                append("</programme>")
            }
            append("</tv>")
        }

        val startNanos = System.nanoTime()
        val result = parser.parse(xml)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertEquals(10_000, result.programs.size)
        assertEquals(100, result.channels.size)
        assertTrue("解析 10k 节目耗时 ${elapsedMs}ms，超出 5s 预算", elapsedMs < 5_000)
    }
}
