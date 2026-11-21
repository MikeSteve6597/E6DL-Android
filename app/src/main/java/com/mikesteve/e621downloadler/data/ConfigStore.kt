package com.mikesteve.e621downloadler.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configDataStore by preferencesDataStore(name = "e6dl_config")

/**
 * 配置持久化（DataStore）。
 *
 * 存储布局（同一个 DataStore，两个键）：
 *  - `config_json`：与桌面版 `userdata.json` 对齐的配置 JSON，**不含 API Key**；
 *  - `api_key_enc`：API Key 的 Keystore 加密密文（见 [SecretStore]）。
 *
 * 从 v1.0.x 升级时，旧配置 JSON 里的明文 `api_key` 会被 [migrateLegacyPlaintextKey]
 * 一次性迁移到加密存储并删除明文字段。
 */
class ConfigStore(
    private val context: Context,
    private val secretStore: SecretStore = SecretStore(),
) {

    private val configKey = stringPreferencesKey("config_json")
    private val apiKeyKey = stringPreferencesKey("api_key_enc")

    val config: Flow<Config> = context.configDataStore.data.map { prefs -> decode(prefs) }

    suspend fun read(): Config = config.first()

    suspend fun save(cfg: Config) {
        context.configDataStore.edit { prefs ->
            val current = decode(prefs)
            prefs[configKey] = ConfigJson.encode(cfg)
            writeApiKey(prefs, current.apiKey, cfg.apiKey)
        }
    }

    suspend fun update(transform: (Config) -> Config): Config {
        var result: Config? = null
        context.configDataStore.edit { prefs ->
            val current = decode(prefs)
            val next = transform(current)
            result = next
            prefs[configKey] = ConfigJson.encode(next)
            writeApiKey(prefs, current.apiKey, next.apiKey)
        }
        return result ?: read()
    }

    /**
     * 把 v1.0.x 的明文 API Key 迁移到 Keystore 加密存储。
     * 幂等：没有明文字段时什么都不做；加密失败则删除明文（宁可让用户重登，也不留明文）。
     */
    suspend fun migrateLegacyPlaintextKey() {
        context.configDataStore.edit { prefs ->
            val raw = prefs[configKey]
            val plain = ConfigJson.legacyApiKey(raw) ?: return@edit
            val encrypted = secretStore.encrypt(plain)
            if (encrypted != null) {
                prefs[apiKeyKey] = encrypted
            } else {
                Log.w("ConfigStore", "旧明文 API Key 无法加密，已从配置中移除，需用户重新登录")
            }
            prefs[configKey] = ConfigJson.withoutLegacyApiKey(raw!!)
        }
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

    // ---------------------------------------------------------------- 内部

    private fun decode(prefs: androidx.datastore.preferences.core.Preferences): Config {
        val raw = prefs[configKey]
        // 优先用加密存储；若尚未迁移，则临时用旧明文值（迁移由 App 启动时执行）
        val apiKey = secretStore.decrypt(prefs[apiKeyKey])
            ?: ConfigJson.legacyApiKey(raw)
            ?: ""
        return ConfigJson.decode(raw, apiKey)
    }

    private fun writeApiKey(
        prefs: MutablePreferences,
        previousKey: String,
        newKey: String,
    ) {
        when {
            newKey.isBlank() -> prefs.remove(apiKeyKey)
            newKey == previousKey && prefs[apiKeyKey] != null -> Unit
            else -> {
                val encrypted = secretStore.encrypt(newKey)
                if (encrypted != null) {
                    prefs[apiKeyKey] = encrypted
                } else {
                    Log.w("ConfigStore", "API Key 加密失败，保留原有密文（若存在）")
                }
            }
        }
    }
}
