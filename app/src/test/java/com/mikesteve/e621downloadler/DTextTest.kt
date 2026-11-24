package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Comment
import com.mikesteve.e621downloadler.data.DText
import com.mikesteve.e621downloadler.data.DTextNode
import com.mikesteve.e621downloadler.data.DTextStyle
import com.mikesteve.e621downloadler.data.buildReplyQuote
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DText 解析 / 渲染节点树 / 回复引用生成的单元测试。
 *
 * 背景：v1.4.0 起评论正文不再原样显示，`[quote]…[/quote]` 必须渲染成引用对话块。
 * 这里把"什么样的源码 → 什么样的节点树"钉死，避免以后改解析器时又把 `[quote]` 源码
 * 漏回界面上（用户目标：评论里不能看到方括号标签）。
 */
class DTextTest {

    // ------------------------------------------------------------ 纯文本 / 换行

    @Test
    fun `plain text keeps newlines`() {
        val nodes = DText.parse("第一行\n第二行")
        assertEquals(listOf(DTextNode.Text("第一行\n第二行")), nodes)
    }

    @Test
    fun `crlf is normalized to lf`() {
        val nodes = DText.parse("a\r\nb\rc")
        assertEquals(listOf(DTextNode.Text("a\nb\nc")), nodes)
    }

    @Test
    fun `plain text is never wrapped in extra nodes`() {
        val nodes = DText.parse("只是一段普通评论，没有标记。")
        assertEquals(listOf(DTextNode.Text("只是一段普通评论，没有标记。")), nodes)
    }

    // ------------------------------------------------------------ 引用块

    @Test
    fun `quote block is parsed`() {
        val nodes = DText.parse("""[quote]你好[/quote]""")
        assertEquals(
            listOf(DTextNode.Quote(children = listOf(DTextNode.Text("你好")))),
            nodes,
        )
    }

    @Test
    fun `quote with user link attribution extracts author header`() {
        val nodes = DText.parse(
            """[quote]"richard noggin99":/users/1003100 said: 你号[/quote]""",
        )
        assertEquals(
            listOf(
                DTextNode.Quote(
                    children = listOf(DTextNode.Text("你号")),
                    authorName = "richard noggin99",
                    authorTarget = "/users/1003100",
                ),
            ),
            nodes,
        )
    }

    @Test
    fun `quote attribution survives a leading newline`() {
        val nodes = DText.parse("""[quote]
            "foo":/users/7 said: hi[/quote]""")
        val quote = nodes.single() as DTextNode.Quote
        assertEquals("foo", quote.authorName)
        assertEquals("/users/7", quote.authorTarget)
        assertEquals(listOf(DTextNode.Text("hi")), quote.children)
    }

    @Test
    fun `quote without said keeps link as content`() {
        val nodes = DText.parse("""[quote]"foo":/users/1 只是引用[/quote]""")
        val quote = nodes.single() as DTextNode.Quote
        assertNull(quote.authorName)
        assertEquals(2, quote.children.size)
        assertEquals(
            DTextNode.Link("foo", "/users/1"),
            quote.children[0],
        )
    }

    @Test
    fun `nested quotes parse recursively`() {
        val nodes = DText.parse("""[quote]outer [quote]inner[/quote] tail[/quote]""")
        val quote = nodes.single() as DTextNode.Quote
        assertEquals(3, quote.children.size)
        assertEquals(DTextNode.Text("outer "), quote.children[0])
        assertEquals(
            DTextNode.Quote(children = listOf(DTextNode.Text("inner"))),
            quote.children[1],
        )
        assertEquals(DTextNode.Text(" tail"), quote.children[2])
    }

    @Test
    fun `unclosed quote degrades to text instead of crashing`() {
        val nodes = DText.parse("""[quote]没有闭合""")
        assertEquals(
            listOf(DTextNode.Quote(children = listOf(DTextNode.Text("没有闭合")))),
            nodes,
        )
        assertEquals("没有闭合", DText.plainText(nodes))
    }

    @Test
    fun `quote attribution with inline style body`() {
        val nodes = DText.parse("""[quote]"n":/users/1 said: [b]hi[/b][/quote]""")
        val quote = nodes.single() as DTextNode.Quote
        assertEquals("n", quote.authorName)
        assertEquals(
            listOf(DTextNode.Styled(DTextStyle.BOLD, listOf(DTextNode.Text("hi")))),
            quote.children,
        )
    }

    // ------------------------------------------------------------ 链接

    @Test
    fun `user link is parsed outside quotes`() {
        val nodes = DText.parse("""感谢 "foo":/users/42 的回复""")
        assertEquals(
            listOf(
                DTextNode.Text("感谢 "),
                DTextNode.Link("foo", "/users/42"),
                DTextNode.Text(" 的回复"),
            ),
            nodes,
        )
    }

    @Test
    fun `bare url becomes a link with display label equal to url`() {
        val nodes = DText.parse("见 https://e621.net/posts/123 谢谢")
        assertEquals(
            listOf(
                DTextNode.Text("见 "),
                DTextNode.Link("https://e621.net/posts/123", "https://e621.net/posts/123"),
                DTextNode.Text(" 谢谢"),
            ),
            nodes,
        )
    }

    @Test
    fun `dtext link label never exposes raw path in plain text`() {
        val nodes = DText.parse("""看看 "richard noggin99":/users/1003100 的作品""")
        val text = DText.plainText(nodes)
        assertEquals("看看 richard noggin99 的作品", text)
        assertFalse(text.contains("/users/1003100"))
    }

    // ------------------------------------------------------------ 样式 / 代码 / 未知标签

    @Test
    fun `inline styles are parsed`() {
        val nodes = DText.parse(
            "[b]粗[/b][i]斜[/i][u]下[/u][s]删[/s][spoiler]透[/spoiler]",
        )
        assertEquals(
            listOf(
                DTextNode.Styled(DTextStyle.BOLD, listOf(DTextNode.Text("粗"))),
                DTextNode.Styled(DTextStyle.ITALIC, listOf(DTextNode.Text("斜"))),
                DTextNode.Styled(DTextStyle.UNDERLINE, listOf(DTextNode.Text("下"))),
                DTextNode.Styled(DTextStyle.STRIKETHROUGH, listOf(DTextNode.Text("删"))),
                DTextNode.Styled(DTextStyle.SPOILER, listOf(DTextNode.Text("透"))),
            ),
            nodes,
        )
    }

    @Test
    fun `code block keeps raw content untouched`() {
        val nodes = DText.parse("""[code]a [b]not bold[/b][/code]""")
        assertEquals(listOf(DTextNode.Code("a [b]not bold[/b]")), nodes)
    }

    @Test
    fun `unknown tags are stripped but text is kept`() {
        val nodes = DText.parse("""[color=red]红色[/color]""")
        assertEquals(listOf(DTextNode.Text("红色")), nodes)
    }

    @Test
    fun `stray closing tag is stripped`() {
        val nodes = DText.parse("hello[/b]world")
        assertEquals(listOf(DTextNode.Text("helloworld")), nodes)
    }

    @Test
    fun `plainText never leaks dtext source`() {
        val source = "look [b]bold[/b] [quote]\"a\":/users/1 said: quoted[/quote] " +
            "\"l\":/users/2 [code]x[y][/code]"
        val text = DText.plainText(DText.parse(source))
        assertFalse(text.contains("[quote]"))
        assertFalse(text.contains("[/quote]"))
        assertFalse(text.contains("[b]"))
        assertFalse(text.contains("[/b]"))
        assertFalse(text.contains("[/code]"))
        assertTrue(text.contains("bold"))
        assertTrue(text.contains("quoted"))
        assertTrue(text.contains("l"))
    }

    // ------------------------------------------------------------ 回复引用文本

    private fun comment(
        creatorName: String = "richard noggin99",
        creatorId: Long = 1003100L,
        body: String = "hello",
    ): Comment = Comment(
        id = 1L,
        postId = 2L,
        creatorName = creatorName,
        body = body,
        score = 0,
        createdAt = "",
        isSticky = false,
        isHidden = false,
        creatorId = creatorId,
    )

    @Test
    fun `buildReplyQuote generates site style quote with user link`() {
        assertEquals(
            "[quote]\"richard noggin99\":/users/1003100 said: hello[/quote]\n\n",
            buildReplyQuote(comment()),
        )
    }

    @Test
    fun `buildReplyQuote omits link when creator id is missing`() {
        assertEquals(
            "[quote]someone said: hi[/quote]\n\n",
            buildReplyQuote(comment(creatorName = "someone", creatorId = 0L, body = "hi")),
        )
    }

    @Test
    fun `buildReplyQuote falls back to placeholder name for blank creator`() {
        val text = buildReplyQuote(comment(creatorName = "", creatorId = 0L, body = "x"))
        assertTrue(text.startsWith("[quote]未知用户 said: x[/quote]"))
    }

    @Test
    fun `buildReplyQuote sanitizes quotes in creator name`() {
        val text = buildReplyQuote(comment(creatorName = "bad\"name", creatorId = 0L))
        assertTrue(text.startsWith("[quote]bad'name said: hello[/quote]"))
    }

    // ------------------------------------------------------------ Comment 解析

    @Test
    fun `Comment from parses creator_id`() {
        val json = JSONObject(
            """{"id": 5, "post_id": 9, "creator_name": "u", "body": "b",
                "score": 1, "created_at": "", "creator_id": 12345}""",
        )
        val parsed = Comment.from(json)
        assertEquals(12345L, parsed.creatorId)
        assertEquals("u", parsed.creatorName)
    }

    @Test
    fun `Comment from defaults creator_id to zero when missing`() {
        val json = JSONObject("""{"id": 5, "post_id": 9, "creator_name": "u", "body": "b"}""")
        assertEquals(0L, Comment.from(json).creatorId)
    }
}
