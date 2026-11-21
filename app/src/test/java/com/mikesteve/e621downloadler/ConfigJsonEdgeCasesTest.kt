package com.mikesteve.e621downloadler

import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.ConfigJson
import com.mikesteve.e621downloadler.data.HistoryEntry
import com.mikesteve.e621downloadler.data.Quality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ConfigJson` 的边界测试（纯 JVM，不碰 DataStore / Keystore）。
 *
 * 重点：
 *  - theme 的 `ui.theme` 优先与旧版顶层 `theme` 回退；
 *  - history 多项解析、非法项跳过、空串过滤；
 *  - 数值裁剪边界；
 *  - `encode` 绝不写入 `api_key`（密钥单独加密存储）。
 */
class ConfigJsonEdgeCasesTest {

    @Test
    fun `ui theme wins and legacy top level theme is the fallback`() {
        assertEquals("dark", ConfigJson.decode("""{"theme":"light","ui":{"theme":"dark"}}""").theme)
        // 没有 ui 时才读旧版顶层 theme。
        assertEquals("dark", ConfigJson.decode("""{"theme":"dark"}""").theme)
        assertEquals("system", ConfigJson.decode("""{}""").theme)
        // 实际行为：ui 存在但没 theme 时 optString 返回空串，不会回退顶层 theme。
        assertEquals("", ConfigJson.decode("""{"theme":"dark","ui":{}}""").theme)
        assertEquals("", ConfigJson.decode("""{"ui":{"theme":""}}""").theme)
    }

    @Test
    fun `history decodes multiple entries and skips invalid ones`() {
        val raw = """
            {
              "history": [
                {"time": "2026-11-21 10:00", "tags": ["cat", "solo"], "blacklist": ["gore", ""]},
                {"time": "2026-11-21 09:00", "tags": ["dog"], "blacklist": []},
                {"tags": ["fox"]},
                "not-an-object",
                {"time": "bad"}
              ],
              "blacklist": ["scat", "  ", ""],
              "recent_keywords": ["cat", ""]
            }
        """.trimIndent()

        val cfg = ConfigJson.decode(raw)
        assertEquals(4, cfg.history.size)
        assertEquals(
            HistoryEntry("2026-11-21 10:00", listOf("cat", "solo"), listOf("gore")),
            cfg.history[0],
        )
        assertEquals(HistoryEntry("2026-11-21 09:00", listOf("dog"), emptyList()), cfg.history[1])
        assertEquals(HistoryEntry("", listOf("fox"), emptyList()), cfg.history[2])
        assertEquals(HistoryEntry("bad", emptyList(), emptyList()), cfg.history[3])

        assertEquals(listOf("scat"), cfg.blacklist)
        assertEquals(listOf("cat"), cfg.recentKeywords)
    }

    @Test
    fun `keyword memory is clamped and read from ui only`() {
        assertEquals(0, ConfigJson.decode("""{"ui":{"keyword_memory":-5}}""").keywordMemory)
        assertEquals(0, ConfigJson.decode("""{"ui":{"keyword_memory":0}}""").keywordMemory)
        assertEquals(7, ConfigJson.decode("""{"ui":{"keyword_memory":7}}""").keywordMemory)
        assertEquals(
            Config.MAX_KEYWORD_MEMORY,
            ConfigJson.decode("""{"ui":{"keyword_memory":51}}""").keywordMemory,
        )
        assertEquals(
            Config.DEFAULT_KEYWORD_MEMORY,
            ConfigJson.decode("""{"ui":{}}""").keywordMemory,
        )
        // 实际行为：顶层 keyword_memory 被忽略（只有 ui.keyword_memory 生效）。
        assertEquals(
            Config.DEFAULT_KEYWORD_MEMORY,
            ConfigJson.decode("""{"keyword_memory":3}""").keywordMemory,
        )
    }

    @Test
    fun `mode only accepts exact lowercase online and falls back to offline`() {
        assertEquals(Config.MODE_ONLINE, ConfigJson.decode("""{"mode":"online"}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("""{"mode":"ONLINE"}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("""{"mode":"Online"}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("""{"mode":"unknown"}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("""{"mode":null}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("""{}""").mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode(null).mode)
        assertEquals(Config.MODE_OFFLINE, ConfigJson.decode("   ").mode)
    }

    @Test
    fun `encode never writes api key and decode keeps only the passed secret`() {
        val cfg = Config(
            mode = Config.MODE_ONLINE,
            username = "someone",
            apiKey = "top-secret-key",
        )

        val json = ConfigJson.encode(cfg)
        val obj = JSONObject(json)
        assertFalse("配置 JSON 不应包含 api_key 键", obj.has("api_key"))
        assertFalse(json.contains("top-secret-key"))
        assertFalse(json.contains(cfg.apiKey))
        assertNull(ConfigJson.legacyApiKey(json))

        // decode 只使用显式传入的 apiKey；不传就是空串（真正的密钥在 Keystore 里）。
        assertEquals("", ConfigJson.decode(json).apiKey)
        assertEquals(cfg.apiKey, ConfigJson.decode(json, apiKey = cfg.apiKey).apiKey)
        assertEquals("someone", ConfigJson.decode(json).username)
    }

    @Test
    fun `numeric config values are clamped`() {
        assertEquals(320, ConfigJson.decode("""{"limit":9999}""").limit)
        assertEquals(1, ConfigJson.decode("""{"limit":-5}""").limit)
        assertEquals(1, ConfigJson.decode("""{"limit":0}""").limit)
        assertEquals(1, ConfigJson.decode("""{"max_pages":0}""").maxPages)
        assertEquals(1, ConfigJson.decode("""{"max_pages":-3}""").maxPages)
        assertEquals(50, ConfigJson.decode("""{"max_pages":999}""").maxPages)
        // min_score 不裁剪，允许负数。
        assertEquals(-100, ConfigJson.decode("""{"min_score":-100}""").minScore)
    }

    @Test
    fun `invalid or blank raw json falls back to defaults but keeps api key`() {
        for (raw in listOf("{not-json", "", "   ")) {
            val cfg = ConfigJson.decode(raw, apiKey = "k")
            assertEquals(Config.MODE_OFFLINE, cfg.mode)
            assertEquals("system", cfg.theme)
            assertEquals(Quality.SAMPLE, cfg.quality)
            assertEquals(5, cfg.maxPages)
            assertEquals(50, cfg.limit)
            assertEquals(10, cfg.minScore)
            assertTrue(cfg.rememberHistory)
            assertEquals(Config.DEFAULT_KEYWORD_MEMORY, cfg.keywordMemory)
            assertEquals("", cfg.username)
            assertEquals("k", cfg.apiKey)
            assertTrue(cfg.blacklist.isEmpty())
            assertTrue(cfg.history.isEmpty())
            assertTrue(cfg.recentKeywords.isEmpty())
        }
        assertEquals("", ConfigJson.decode(null).apiKey)
    }

    @Test
    fun `legacy api key helpers tolerate missing and malformed input`() {
        assertNull(ConfigJson.legacyApiKey(null))
        assertNull(ConfigJson.legacyApiKey("   "))
        assertNull(ConfigJson.legacyApiKey("{not-json"))
        assertNull(ConfigJson.legacyApiKey("""{"mode":"online"}"""))
        assertNull(ConfigJson.legacyApiKey("""{"api_key":""}"""))

        // 非法 JSON 原样返回，不破坏已有内容。
        assertEquals("{not-json", ConfigJson.withoutLegacyApiKey("{not-json"))

        val cleaned = ConfigJson.withoutLegacyApiKey(
            """{"mode":"online","username":"u","api_key":"legacy"}""",
        )
        assertFalse(cleaned.contains("api_key"))
        assertEquals("u", ConfigJson.decode(cleaned).username)
        assertEquals(Config.MODE_ONLINE, ConfigJson.decode(cleaned).mode)
    }
}
