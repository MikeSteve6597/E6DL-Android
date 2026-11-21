package com.mikesteve.e621downloadler.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的 JSON 编解码（纯逻辑，不依赖 Android，便于 JVM 单元测试）。
 *
 * 结构尽量与桌面版 `userdata.json` 保持一致，便于将来做跨端导入导出；
 * **唯一有意差异**：`api_key` 不再以明文写进这份 JSON，而是由 [SecretStore]
 * 加密后单独保存到 DataStore 的另一个键里（见 [ConfigStore]）。
 */
object ConfigJson {

    /** 编码为 JSON 文本；不包含 API Key（密钥单独加密存储）。 */
    fun encode(cfg: Config): String = JSONObject().apply {
        put("mode", cfg.mode)
        put("theme", cfg.theme)
        put("quality", cfg.quality)
        put("max_pages", cfg.maxPages)
        put("limit", cfg.limit)
        put("min_score", cfg.minScore)
        put("remember_history", cfg.rememberHistory)
        put("username", cfg.username)
        put("blacklist", JSONArray(cfg.blacklist))
        put("history", JSONArray().apply {
            cfg.history.forEach { h ->
                put(JSONObject().apply {
                    put("time", h.time)
                    put("tags", JSONArray(h.tags))
                    put("blacklist", JSONArray(h.blacklist))
                })
            }
        })
        put("ui", JSONObject().put("theme", cfg.theme).put("keyword_memory", cfg.keywordMemory))
        put("recent_keywords", JSONArray(cfg.recentKeywords))
    }.toString()

    /**
     * 解码。任何异常都回落到默认配置（宁可丢设置也不让 App 起不来）。
     *
     * @param apiKey 从加密存储解出的 API Key；旧版本明文配置会通过 [legacyApiKey] 迁移。
     */
    fun decode(raw: String?, apiKey: String = ""): Config {
        if (raw.isNullOrBlank()) return Config(apiKey = apiKey)
        return try {
            val o = JSONObject(raw)
            val ui = o.optJSONObject("ui")
            Config(
                mode = o.optString("mode", Config.MODE_OFFLINE).let {
                    if (it == Config.MODE_ONLINE) Config.MODE_ONLINE else Config.MODE_OFFLINE
                },
                theme = ui?.optString("theme") ?: o.optString("theme", "system"),
                quality = o.optString("quality", Quality.SAMPLE),
                maxPages = o.optInt("max_pages", 5).coerceIn(1, 50),
                limit = o.optInt("limit", 50).coerceIn(1, 320),
                minScore = o.optInt("min_score", 10),
                rememberHistory = o.optBoolean("remember_history", true),
                keywordMemory = (ui?.optInt("keyword_memory", Config.DEFAULT_KEYWORD_MEMORY)
                    ?: Config.DEFAULT_KEYWORD_MEMORY).coerceIn(0, Config.MAX_KEYWORD_MEMORY),
                username = o.optString("username", ""),
                apiKey = apiKey,
                blacklist = o.optJSONArray("blacklist").toStringList(),
                history = o.optJSONArray("history").toHistory(),
                recentKeywords = o.optJSONArray("recent_keywords").toStringList(),
            )
        } catch (e: Exception) {
            Config(apiKey = apiKey)
        }
    }

    /**
     * 旧版本（v1.0.x）把 API Key 明文写进配置 JSON。
     * 返回该明文值用于一次性迁移；没有则返回 null。
     */
    fun legacyApiKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw).optString("api_key").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 去掉旧明文 `api_key` 字段的 JSON（迁移成功后写回）。 */
    fun withoutLegacyApiKey(raw: String): String = try {
        val o = JSONObject(raw)
        o.remove("api_key")
        o.toString()
    } catch (e: Exception) {
        raw
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = optString(i)
            if (v.isNotBlank()) list.add(v)
        }
        return list
    }

    private fun JSONArray?.toHistory(): List<HistoryEntry> {
        if (this == null) return emptyList()
        val list = ArrayList<HistoryEntry>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            list.add(
                HistoryEntry(
                    time = o.optString("time"),
                    tags = o.optJSONArray("tags").toStringList(),
                    blacklist = o.optJSONArray("blacklist").toStringList(),
                ),
            )
        }
        return list
    }
}
