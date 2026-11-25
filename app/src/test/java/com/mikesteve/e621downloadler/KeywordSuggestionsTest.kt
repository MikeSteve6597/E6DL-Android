package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.net.KEYWORD_SUGGESTION_LIMIT
import com.mikesteve.e621downloadler.net.SearchKeyword
import com.mikesteve.e621downloadler.net.containsChineseChar
import com.mikesteve.e621downloadler.net.firstKeyword
import com.mikesteve.e621downloadler.net.keywordSuggestions
import com.mikesteve.e621downloadler.net.resolveKeyword
import com.mikesteve.e621downloadler.net.splitKeywords
import com.mikesteve.e621downloadler.net.tagLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.1「实时中英对照推荐 + 只取第一个关键词」的纯逻辑测试。
 *
 * 覆盖：
 *  - [firstKeyword]：逗号/中文逗号/顿号/分号/空白/多空格/空输入；
 *  - [keywordSuggestions]：中文按释义包含匹配、英文按标签包含匹配、完全 > 前缀 > 包含排序、
 *    多关键词只取第一个、大小写不敏感、limit 截断、空词典/无命中安全。
 */
class KeywordSuggestionsTest {

    private val entries = listOf(
        "dog" to "狗",
        "doggy_style" to "后入式",
        "hotdog" to "热狗",
        "hyena" to "鬣狗",
        "wolf" to "狼",
        "cat" to "猫",
        "feline" to "猫科",
        "dragon" to "龙",
    )

    // ------------------------------------------------------------ 第一个关键词

    @Test
    fun `first keyword splits on commas spaces and cjk punctuation`() {
        assertEquals("狗", firstKeyword("狗"))
        assertEquals("狗", firstKeyword("狗 猫"))
        assertEquals("狗", firstKeyword("狗,猫"))
        assertEquals("狗", firstKeyword("狗，猫"))
        assertEquals("狗", firstKeyword("狗、猫"))
        assertEquals("狗", firstKeyword("狗；猫"))
        assertEquals("dog", firstKeyword("dog,cat"))
        assertEquals("dog", firstKeyword("  dog   cat  "))
        assertEquals("狼", firstKeyword("  狼  "))
    }

    @Test
    fun `first keyword is empty for blank input`() {
        assertEquals("", firstKeyword(""))
        assertEquals("", firstKeyword("   "))
        assertEquals("", firstKeyword(",，、;；  "))
    }

    // ------------------------------------------------------------ 中文匹配

    @Test
    fun `chinese query matches exact first then contains`() {
        val result = keywordSuggestions(entries, "狗")
        // 完全命中的「狗 - dog」排最前，其后按英文标签长度排序：hyena(5) → hotdog(6)。
        assertEquals(listOf("dog", "hyena", "hotdog"), result.map { it.english })
        assertEquals(listOf("狗", "鬣狗", "热狗"), result.map { it.chinese })
    }

    @Test
    fun `chinese query matches chinese substring in the middle`() {
        val result = keywordSuggestions(entries, "鬣")
        assertEquals(listOf("hyena"), result.map { it.english })
        assertEquals("鬣狗", result.single().chinese)
    }

    @Test
    fun `chinese query only uses the first keyword`() {
        val result = keywordSuggestions(entries, "狗 猫")
        assertEquals(listOf("dog", "hyena", "hotdog"), result.map { it.english })
        assertTrue(result.none { it.english == "cat" })
    }

    @Test
    fun `longer chinese query can match a shorter dictionary word`() {
        val dict = listOf("blue_eyes" to "蓝眼", "dog" to "狗")
        // 用户输入更长的中文短语时，词典里的「蓝眼」也应命中。
        assertEquals(listOf("blue_eyes"), keywordSuggestions(dict, "蓝眼睛").map { it.english })
        // 单字词典词不参与反向包含，避免「鬣狗」把「狗」也拉进来。
        assertTrue(keywordSuggestions(dict, "鬣狗").isEmpty())
    }

    // ------------------------------------------------------------ 英文匹配

    @Test
    fun `english query ranks exact then prefix then contains`() {
        val result = keywordSuggestions(entries, "dog")
        assertEquals(listOf("dog", "doggy_style", "hotdog"), result.map { it.english })
    }

    @Test
    fun `english partial matches prefix and is case insensitive`() {
        assertEquals(
            listOf("cat"),
            keywordSuggestions(entries, "ca").map { it.english },
        )
        assertEquals(
            listOf("dog", "doggy_style", "hotdog"),
            keywordSuggestions(entries, "DOG").map { it.english },
        )
        assertEquals(
            listOf("dog", "doggy_style", "hotdog"),
            keywordSuggestions(entries, "dog").map { it.english },
        )
    }

    @Test
    fun `english query shows chinese translation of matched tags`() {
        val dog = keywordSuggestions(entries, "dog").first()
        assertEquals("狗", dog.chinese)
        assertEquals("狗 - dog", "${dog.chinese} - ${dog.english}")
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `limit is respected and zero disables suggestions`() {
        assertEquals(1, keywordSuggestions(entries, "do", limit = 1).size)
        assertEquals(2, keywordSuggestions(entries, "do", limit = 2).size)
        assertEquals(0, keywordSuggestions(entries, "do", limit = 0).size)

        // 默认条数上限：构造 10 条前缀命中，默认只返回 KEYWORD_SUGGESTION_LIMIT 条。
        val many = (1..10).map { index -> "dog$index" to "狗$index" }
        assertEquals(KEYWORD_SUGGESTION_LIMIT, keywordSuggestions(many, "dog").size)
    }

    @Test
    fun `no match or empty dictionary returns empty list`() {
        assertTrue(keywordSuggestions(entries, "zzz").isEmpty())
        assertTrue(keywordSuggestions(emptyList(), "狗").isEmpty())
        assertTrue(keywordSuggestions(listOf("" to "狗", "dog" to ""), "狗").isEmpty())
        assertTrue(keywordSuggestions(entries, "   ").isEmpty())
    }

    // ------------------------------------------------------------ v1.5.2：空格分隔 + 中文气泡

    @Test
    fun `split keywords uses spaces only`() {
        assertEquals(listOf("狗", "猫"), splitKeywords("狗 猫"))
        assertEquals(listOf("dog", "cat"), splitKeywords("  dog   cat  "))
        assertEquals(listOf("dog"), splitKeywords("dog"))
        assertEquals(listOf("狼", "solo"), splitKeywords("狼\tsolo"))
        assertTrue(splitKeywords("   ").isEmpty())
        assertTrue(splitKeywords("").isEmpty())
    }

    @Test
    fun `resolve keyword prefers dictionary chinese label`() {
        assertEquals(SearchKeyword("dog", "狗"), resolveKeyword(entries, "狗"))
        assertEquals(SearchKeyword("dog", "狗"), resolveKeyword(entries, "DOG"))
        assertEquals(SearchKeyword("wolf", "狼"), resolveKeyword(entries, "狼"))
        assertEquals(SearchKeyword("dog", "狗"), resolveKeyword(entries, " 狗 "))
    }

    @Test
    fun `resolve keyword falls back to suggestion for longer chinese phrase`() {
        val dict = listOf("blue_eyes" to "蓝眼", "dog" to "狗")
        assertEquals(SearchKeyword("blue_eyes", "蓝眼"), resolveKeyword(dict, "蓝眼睛"))
    }

    @Test
    fun `resolve keyword keeps unknown chinese pending for online translation`() {
        val keyword = resolveKeyword(entries, "完全没收录的词")
        assertEquals("完全没收录的词", keyword.tag)
        assertEquals("完全没收录的词", keyword.label)
    }

    @Test
    fun `resolve keyword keeps unknown english as tag`() {
        assertEquals(SearchKeyword("xyz_tag", "xyz_tag"), resolveKeyword(entries, "xyz_tag"))
        // 大小写按原样展示，提交用的小写 tag 由 tag 字段保证。
        assertEquals(SearchKeyword("xyz_tag", "XYZ_TAG"), resolveKeyword(entries, "XYZ_TAG"))
    }

    @Test
    fun `tag label falls back to tag when dictionary misses`() {
        assertEquals("狗", tagLabel(entries, "dog"))
        assertEquals("狗", tagLabel(entries, "DOG"))
        assertEquals("unknown_tag", tagLabel(entries, "unknown_tag"))
    }

    @Test
    fun `contains chinese char detects cjk`() {
        assertTrue(containsChineseChar("狗"))
        assertTrue(containsChineseChar("dog 猫"))
        assertFalse(containsChineseChar("dog"))
        assertFalse(containsChineseChar("blue_eyes"))
    }
}
