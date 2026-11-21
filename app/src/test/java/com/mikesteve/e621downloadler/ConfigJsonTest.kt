package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.ConfigJson
import com.mikesteve.e621downloadler.data.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置 JSON 编解码测试。
 *
 * 重点保护三件事：
 *  1. 键名与桌面版 `userdata.json` 对齐（跨端导入导出时不用重写映射）；
 *  2. API Key **不写入** JSON（v1.1 起改由 Keystore 加密单独存储）；
 *  3. 旧版本明文 `api_key` 能被识别并迁移掉。
 */
class ConfigJsonTest {

    @Test
    fun `round trip keeps all non-secret fields`() {
        val cfg = Config(
            mode = Config.MODE_ONLINE,
            theme = "dark",
            quality = Quality.ORIGINAL,
            maxPages = 12,
            limit = 80,
            minScore = -5,
            rememberHistory = false,
            keywordMemory = 7,
            username = "someone",
            apiKey = "secret-key-that-must-not-be-written",
            blacklist = listOf("gore", "scat"),
            history = listOf(
                com.mikesteve.e621downloadler.data.HistoryEntry(
                    time = "2026-11-21 13:00",
                    tags = listOf("cat"),
                    blacklist = listOf("gore"),
                ),
            ),
            recentKeywords = listOf("cat", "dog"),
        )

        val json = ConfigJson.encode(cfg)
        assertFalse("API Key 绝不能出现在配置 JSON 里", json.contains("secret-key"))
        assertNull(ConfigJson.legacyApiKey(json))

        val decoded = ConfigJson.decode(json, apiKey = cfg.apiKey)
        assertEquals(cfg.mode, decoded.mode)
        assertEquals(cfg.theme, decoded.theme)
        assertEquals(cfg.quality, decoded.quality)
        assertEquals(cfg.maxPages, decoded.maxPages)
        assertEquals(cfg.limit, decoded.limit)
        assertEquals(cfg.minScore, decoded.minScore)
        assertEquals(cfg.rememberHistory, decoded.rememberHistory)
        assertEquals(cfg.keywordMemory, decoded.keywordMemory)
        assertEquals(cfg.username, decoded.username)
        assertEquals(cfg.apiKey, decoded.apiKey)
        assertEquals(cfg.blacklist, decoded.blacklist)
        assertEquals(cfg.history, decoded.history)
        assertEquals(cfg.recentKeywords, decoded.recentKeywords)
    }

    @Test
    fun `legacy plaintext api key is detected and removable`() {
        val legacy = """{"mode":"online","username":"u","api_key":"legacy-plain","blacklist":[]}"""
        assertEquals("legacy-plain", ConfigJson.legacyApiKey(legacy))

        val cleaned = ConfigJson.withoutLegacyApiKey(legacy)
        assertNull(ConfigJson.legacyApiKey(cleaned))
        assertEquals("u", ConfigJson.decode(cleaned).username)
    }

    @Test
    fun `invalid json falls back to defaults`() {
        val cfg = ConfigJson.decode("{not-json")
        assertEquals(Config.MODE_OFFLINE, cfg.mode)
        assertEquals(Quality.SAMPLE, cfg.quality)
        assertFalse(cfg.loggedIn)
    }

    @Test
    fun `out of range values are clamped`() {
        val raw = """{"max_pages":999,"limit":0,"ui":{"keyword_memory":999}}"""
        val cfg = ConfigJson.decode(raw)
        assertEquals(50, cfg.maxPages)
        assertEquals(1, cfg.limit)
        assertEquals(Config.MAX_KEYWORD_MEMORY, cfg.keywordMemory)
    }

    @Test
    fun `unknown mode falls back to offline`() {
        val cfg = ConfigJson.decode("""{"mode":"whatever"}""")
        assertEquals(Config.MODE_OFFLINE, cfg.mode)
        assertTrue(!cfg.loggedIn)
    }
}
