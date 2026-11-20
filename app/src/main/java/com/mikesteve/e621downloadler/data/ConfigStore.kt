package com.mikesteve.e621downloadler.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.configDataStore by preferencesDataStore(name = "e6dl_config")

/**
 * 配置持久化（DataStore）。
 *
 * 整份配置以一段 JSON 存在单个键里，结构对齐桌面版 `userdata.json`，
 * 这样将来做"桌面 → Android 导入导出"时不用再写一遍映射。
 */
class ConfigStore(private val context: Context) {

    private val key = stringPreferencesKey("config_json")

    val config: Flow<Config> = context.configDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun read(): Config = config.first()

    suspend fun save(cfg: Config) {
        context.configDataStore.edit { prefs -> prefs[key] = encode(cfg) }
    }

    suspend fun update(transform: (Config) -> Config): Config {
        var result: Config? = null
        context.configDataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = transform(current)
            result = next
            prefs[key] = encode(next)
        }
        return result ?: read()
    }

    // ---------------------------------------------------------------- 语义辅助

    /** 搜索历史：去重、插到最前、最多 10 条（与桌面版 `add_to_history` 一致）。 */
    suspend fun addHistory(tags: List<String>, blacklist: List<String>) {
        update { cfg ->
            if (!cfg.loggedIn || !cfg.rememberHistory) return@update cfg
            val entry = HistoryEntry(
                time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date()),
                tags = tags,
                blacklist = blacklist,
            )
            val rest = cfg.history.filterNot { it.tags == tags && it.blacklist == blacklist }
            cfg.copy(history = (listOf(entry) + rest).take(Config.MAX_HISTORY))
        }
    }

    /** 关键词记忆：去重、插到最前、按 `keywordMemory` 裁剪；0 表示不记忆。 */
    suspend fun addRecentKeyword(keyword: String) {
        val text = keyword.trim()
        update { cfg ->
            val limit = Config.keywordsLimit(cfg)
            if (limit == 0) return@update cfg.copy(recentKeywords = emptyList())
            if (text.isEmpty()) return@update cfg
            val rest = cfg.recentKeywords.filterNot { it.equals(text, ignoreCase = true) }
            cfg.copy(recentKeywords = (listOf(text) + rest).take(limit))
        }
    }

    // ---------------------------------------------------------------- 编解码

    private fun encode(cfg: Config): String = JSONObject().apply {
        put("mode", cfg.mode)
        put("theme", cfg.theme)
        put("quality", cfg.quality)
        put("max_pages", cfg.maxPages)
        put("limit", cfg.limit)
        put("min_score", cfg.minScore)
        put("remember_history", cfg.rememberHistory)
        put("username", cfg.username)
        put("api_key", cfg.apiKey)
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

    private fun decode(raw: String?): Config {
        if (raw.isNullOrBlank()) return Config()
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
                apiKey = o.optString("api_key", ""),
                blacklist = o.optJSONArray("blacklist").toStringList(),
                history = o.optJSONArray("history").toHistory(),
                recentKeywords = o.optJSONArray("recent_keywords").toStringList(),
            )
        } catch (e: Exception) {
            Log.w("ConfigStore", "配置解析失败，回到默认值：${e.message}")
            Config()
        }
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
