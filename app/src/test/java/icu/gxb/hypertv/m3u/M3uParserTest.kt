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
}
