package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.net.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索标签构建规则（与桌面版 `e6core.build_search_tags` 对齐）：
 * 关键词原样、黑名单拼成 `-tag`、最低评分拼 `score:>=N`。
 */
class ApiTagsTest {

    private val api = ApiClient()

    @Test
    fun `builds tags with blacklist and min score`() {
        val tags = api.buildSearchTags(
            tags = listOf("cat", "solo"),
            blacklist = listOf("gore", "scat"),
            minScore = 10,
        )
        assertEquals("cat solo -gore -scat score:>=10", tags)
    }

    @Test
    fun `trims and drops blanks`() {
        val tags = api.buildSearchTags(
            tags = listOf("  cat ", "", "  "),
            blacklist = listOf(" gore "),
            minScore = null,
        )
        assertEquals("cat -gore", tags)
    }

    @Test
    fun `empty input yields empty query`() {
        assertEquals("", api.buildSearchTags(emptyList(), emptyList(), null))
    }

    @Test
    fun `only blacklist still yields exclusion terms`() {
        assertEquals("-gore -scat", api.buildSearchTags(emptyList(), listOf("gore", "scat"), null))
        // 空白项被过滤，不会生成孤零零的 "-"。
        assertEquals("-gore", api.buildSearchTags(listOf(""), listOf(" gore "), null))
    }

    @Test
    fun `min score accepts zero and negative values`() {
        assertEquals("cat score:>=0", api.buildSearchTags(listOf("cat"), emptyList(), 0))
        assertEquals("cat score:>=-5", api.buildSearchTags(listOf("cat"), emptyList(), -5))
        assertEquals("score:>=10", api.buildSearchTags(emptyList(), emptyList(), 10))
    }

    @Test
    fun `inner spaces and duplicate tags are preserved`() {
        // 只 trim 首尾，标签内部空格原样保留。
        assertEquals("cock vore -gore", api.buildSearchTags(listOf(" cock vore "), listOf("gore"), null))
        assertEquals("cat  solo", api.buildSearchTags(listOf("cat  solo"), emptyList(), null))
        // 重复标签不去重。
        assertEquals(
            "cat cat -gore -gore",
            api.buildSearchTags(listOf("cat", "cat"), listOf("gore", "gore"), null),
        )
    }
}
