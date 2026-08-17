package icu.gxb.hypertv.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val parser = M3uParser()

    @Test
    fun `parses standard m3u with full extinf attributes`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="http://logo/cctv1.png" group-title="新闻" catchup="append" catchup-days="7" catchup-source="http://catchup/example",CCTV-1 高清
            http://stream.example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        assertEquals(1, result.channels.size)
        val ch = result.channels[0]
        assertEquals("CCTV-1 高清", ch.name)
        assertEquals("http://stream.example.com/cctv1.m3u8", ch.url)
        assertEquals("新闻", ch.groupName)
        assertEquals("http://logo/cctv1.png", ch.logoUrl)
        assertEquals("cctv1", ch.epgId)
        assertEquals("append", ch.catchup)
        assertEquals(7, ch.catchupDays)
        assertEquals("http://catchup/example", ch.catchupSource)
        assertEquals(listOf("新闻"), result.groups)
    }

    @Test
    fun `extgrp line overrides group-title`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" group-title="A",频道甲
            http://s.example/1.m3u8
            #EXTGRP:体育
            #EXTINF:-1 tvg-id="y",频道乙
            http://s.example/2.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        assertEquals("A", result.channels[0].groupName)
        assertEquals("体育", result.channels[1].groupName)
    }

    @Test
    fun `tolerates malformed and garbage lines without crashing`() {
        val content = """
            #EXTM3U
            这行是乱码没有意义
            #EXTINF:-1 this is malformed no equals
            http://s.example/good.m3u8
            #EXTINF:-1 tvg-id="broken
            http://s.example/unquoted.m3u8
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #
            http://s.example/bare.m3u8
            garbage line without hash
        """.trimIndent()

        val result = parser.parse(content)

        // 只有三个合法 URL 行生成了频道，异常行全部被跳过
        assertEquals(3, result.channels.size)
        assertEquals("http://s.example/good.m3u8", result.channels[0].url)
        assertEquals("http://s.example/unquoted.m3u8", result.channels[1].url)
        assertEquals("http://s.example/bare.m3u8", result.channels[2].url)
    }

    @Test
    fun `bare url without extinf gets a fallback name`() {
        val result = parser.parse("#EXTM3U\nhttp://s.example/1.m3u8\n")

        assertEquals(1, result.channels.size)
        assertTrue(result.channels[0].name.startsWith("频道"))
        assertEquals("http://s.example/1.m3u8", result.channels[0].url)
    }

    @Test
    fun `empty source returns empty result`() {
        val empty = parser.parse("")
        assertTrue(empty.channels.isEmpty())
        assertTrue(empty.groups.isEmpty())

        val commentsOnly = parser.parse("#EXTM3U\n# comment\n#EXTINF:-1 no url here\n")
        assertTrue(commentsOnly.channels.isEmpty())
        assertTrue(commentsOnly.groups.isEmpty())
    }

    @Test
    fun `groups are distinct and ordered by first appearance`() {
        val content = """
            #EXTINF:-1 group-title="体育",A
            http://s.example/a.m3u8
            #EXTINF:-1 group-title="新闻",B
            http://s.example/b.m3u8
            #EXTINF:-1 group-title="体育",C
            http://s.example/c.m3u8
        """.trimIndent()

        assertEquals(listOf("体育", "新闻"), parser.parse(content).groups)
    }

    @Test
    fun `unquoted attribute values are supported`() {
        val content = """
            #EXTINF:-1 tvg-id=cctv1 group-title=新闻 tvg-logo=http://logo/1.png,CCTV-1
            http://s.example/1.m3u8
        """.trimIndent()

        val ch = parser.parse(content).channels[0]
        assertEquals("cctv1", ch.epgId)
        assertEquals("新闻", ch.groupName)
        assertEquals("http://logo/1.png", ch.logoUrl)
    }

    @Test
    fun `title containing comma is kept whole`() {
        val content = """
            #EXTINF:-1 group-title="测试",CCTV-1 高清, 测试频道
            http://s.example/1.m3u8
        """.trimIndent()

        assertEquals("CCTV-1 高清, 测试频道", parser.parse(content).channels[0].name)
    }

    @Test
    fun `crlf line endings are handled`() {
        val content = "#EXTM3U\r\n#EXTINF:-1 group-title=\"A\",频道1\r\nhttp://s.example/1.m3u8\r\n"

        val result = parser.parse(content)
        assertEquals(1, result.channels.size)
        assertEquals("频道1", result.channels[0].name)
    }

    @Test
    fun `missing optional fields default to null or empty`() {
        val content = """
            #EXTINF:-1,CCTV-1
            http://s.example/1.m3u8
        """.trimIndent()

        val ch = parser.parse(content).channels[0]
        assertEquals("CCTV-1", ch.name)
        assertEquals("", ch.groupName)
        assertNull(ch.logoUrl)
        assertNull(ch.epgId)
        assertNull(ch.catchup)
        assertNull(ch.catchupDays)
        assertNull(ch.catchupSource)
    }

    // ---- txt 直播源格式 ----

    @Test
    fun `txt genre line groups following channels`() {
        val content = """
            央视频道,#genre#
            CCTV-1,http://223.110.255.208:6610/jsyd/live1/G_CCTV-1-MD/G_CCTV-1-MD/1.m3u8
            CCTV-2,http://223.110.255.209:6610/jsyd/live1/G_CCTV-2-MD/G_CCTV-2-MD/1.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        assertEquals(2, result.channels.size)
        val first = result.channels[0]
        assertEquals("CCTV-1", first.name)
        assertEquals("http://223.110.255.208:6610/jsyd/live1/G_CCTV-1-MD/G_CCTV-1-MD/1.m3u8", first.url)
        assertEquals("央视频道", first.groupName)
        assertNull(first.logoUrl)
        assertNull(first.epgId)
        assertNull(first.catchup)
        assertNull(first.catchupDays)
        assertNull(first.catchupSource)

        assertEquals("CCTV-2", result.channels[1].name)
        assertEquals("央视频道", result.channels[1].groupName)
        assertEquals(listOf("央视频道"), result.groups)
    }

    @Test
    fun `txt channel without genre line has empty group name`() {
        val content = """
            CCTV-1,http://s.example/1.m3u8
            凤凰卫视 HD,https://s.example/2.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        assertEquals(2, result.channels.size)
        assertEquals("CCTV-1", result.channels[0].name)
        assertEquals("", result.channels[0].groupName)
        // 频道名允许含空格，trim 仅去除首尾空白
        assertEquals("凤凰卫视 HD", result.channels[1].name)
        assertEquals("https://s.example/2.m3u8", result.channels[1].url)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `txt multiple genre groups assign channels correctly`() {
        val content = """
            体育,#genre#
            CCTV-5,http://s.example/cctv5.m3u8
            新闻,#genre#
            CCTV-13,http://s.example/cctv13.m3u8
            CCTV-1,http://s.example/cctv1.m3u8
            少儿,#genre#
        """.trimIndent()

        val result = parser.parse(content)

        assertEquals(3, result.channels.size)
        assertEquals("体育", result.channels[0].groupName)
        assertEquals("新闻", result.channels[1].groupName)
        assertEquals("新闻", result.channels[2].groupName)
        assertEquals(listOf("体育", "新闻"), result.groups)
    }

    @Test
    fun `txt tolerates blank garbage and non-url lines`() {
        val content = """
            更新时间 2024-01-01
            卫视,这不是一个URL
            CCTV-1,http://s.example/1.m3u8
            凤凰卫视,https://s.example/2.m3u8
            卫视,

            新闻,无scheme的文本行
            ,http://s.example/no-name.m3u8
            abc,def,http://s.example/multi-comma.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        // 只有两个合法 频道,URL 行生成了频道，其余全部静默跳过
        assertEquals(2, result.channels.size)
        assertEquals("CCTV-1", result.channels[0].name)
        assertEquals("http://s.example/1.m3u8", result.channels[0].url)
        assertEquals("凤凰卫视", result.channels[1].name)
        assertEquals("https://s.example/2.m3u8", result.channels[1].url)
    }

    @Test
    fun `txt-like content containing extinf is parsed as m3u`() {
        val content = """
            央视频道,#genre#
            CCTV-1,http://s.example/1.m3u8
            #EXTINF:-1 group-title="新闻",CCTV-1 高清
            http://s.example/2.m3u8
        """.trimIndent()

        val result = parser.parse(content)

        // 有 #EXTINF 行 → 走 M3U 路径：txt 行被忽略，只解析出 1 个频道
        assertEquals(1, result.channels.size)
        assertEquals("CCTV-1 高清", result.channels[0].name)
        assertEquals("http://s.example/2.m3u8", result.channels[0].url)
        assertEquals("新闻", result.channels[0].groupName)
    }
}
