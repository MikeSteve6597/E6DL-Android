package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Config
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配置层纯逻辑边界：`Config.keywordsLimit` 的裁剪规则与默认值。
 *
 * 规则（见 `data/Models.kt`）：`keywordMemory.coerceIn(0, MAX_KEYWORD_MEMORY)`，
 * 其中 `MAX_KEYWORD_MEMORY = 50`、`DEFAULT_KEYWORD_MEMORY = 10`。
 */
class ConfigKeywordsTest {

    private fun limitOf(keywordMemory: Int): Int =
        Config.keywordsLimit(Config(keywordMemory = keywordMemory))

    @Test
    fun `negative and zero keyword memory clamp to zero`() {
        assertEquals(0, limitOf(-1))
        assertEquals(0, limitOf(-100))
        assertEquals(0, limitOf(0))
    }

    @Test
    fun `values up to the cap are kept and larger values are clipped`() {
        assertEquals(49, limitOf(49))
        assertEquals(Config.MAX_KEYWORD_MEMORY, limitOf(50))
        assertEquals(Config.MAX_KEYWORD_MEMORY, limitOf(100))
        assertEquals(Config.MAX_KEYWORD_MEMORY, limitOf(Int.MAX_VALUE))
    }

    @Test
    fun `default config uses default keyword memory`() {
        assertEquals(10, Config().keywordMemory)
        assertEquals(Config.DEFAULT_KEYWORD_MEMORY, Config().keywordMemory)
        assertEquals(Config.DEFAULT_KEYWORD_MEMORY, Config.keywordsLimit(Config()))
    }
}
