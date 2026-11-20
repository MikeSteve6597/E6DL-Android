package com.mikesteve.e621downloadler.data

import org.json.JSONObject

/** 与桌面版 `e6core.get_image_url` 一致的画质取值。 */
object Quality {
    const val ORIGINAL = "1"
    const val SAMPLE = "2"
    const val PREVIEW = "3"

    val labels: List<Pair<String, String>> = listOf(
        ORIGINAL to "原图",
        SAMPLE to "采样图",
        PREVIEW to "预览图",
    )
}

/** 视频扩展名：与桌面版一致，界面里只显示预览帧。 */
private val VIDEO_EXTS = setOf("webm", "mp4", "swf")

data class Post(
    val id: Long,
    val md5: String,
    val ext: String,
    val fileUrl: String?,
    val sampleUrl: String?,
    val previewUrl: String?,
    val width: Int,
    val height: Int,
    val score: Int,
    val rating: String,
    val tags: Map<String, List<String>>,
    val isFavorited: Boolean = false,
) {
    val isVideo: Boolean get() = ext.lowercase() in VIDEO_EXTS

    val tagsFlat: List<String> get() = tags.values.flatten()

    val ratingLabel: String
        get() = when (rating) {
            "s" -> "安全"
            "q" -> "待定"
            "e" -> "明确"
            else -> "未知"
        }

    /** 逐级回退：原图 → 采样 → 预览（与桌面版一致）。 */
    fun imageUrl(quality: String): String? = when (quality) {
        Quality.ORIGINAL -> fileUrl ?: sampleUrl ?: previewUrl
        Quality.SAMPLE -> sampleUrl ?: fileUrl ?: previewUrl
        else -> previewUrl ?: sampleUrl ?: fileUrl
    }

    /** 文件名规则与桌面版一致：`<id>_<md5前8>.<ext>`。 */
    fun targetFilename(): String = "${id}_${md5.take(8)}.$ext"

    companion object {
        /**
         * 兼容 e621 的两代返回结构（2026-11 实测线上已切换为新结构）：
         *
         * 新：`{ "files": { "meta": {md5, ext, size, duration}, "original": {width, height, url},
         *        "sample": {width, height, jpg, webp}, "preview": {width, height, jpg, webp} } }`
         * 旧：`{ "file": {url, ext, width, height}, "sample": {...}, "preview": {...}, "md5": "..." }`
         *
         * 两个都读，缺哪个用哪个，避免站点再改一次就整页报错。
         */
        fun from(json: JSONObject): Post {
            val files = json.optJSONObject("files")
            val meta = files?.optJSONObject("meta")
            val original = files?.optJSONObject("original")
            val newSample = files?.optJSONObject("sample")
            val newPreview = files?.optJSONObject("preview")

            val oldFile = json.optJSONObject("file")
            val oldSample = json.optJSONObject("sample")
            val oldPreview = json.optJSONObject("preview")

            // 地址：新结构里可能是 url，也可能是 jpg/webp 两个备选
            fun urlOf(obj: JSONObject?): String? {
                if (obj == null) return null
                obj.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("jpg").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("webp").takeIf { it.isNotBlank() }?.let { return it }
                return null
            }

            val md5 = json.optString("md5").takeIf { it.isNotBlank() }
                ?: meta?.optString("md5")?.takeIf { it.isNotBlank() }
                ?: ""

            val ext = (meta?.optString("ext")
                ?: oldFile?.optString("ext")
                ?: "jpg").lowercase()

            val scoreObj = json.optJSONObject("score")
            val score = scoreObj?.optInt("total", 0) ?: json.optInt("score", 0)

            val tags = linkedMapOf<String, List<String>>()
            json.optJSONObject("tags")?.let { t ->
                t.keys().forEach { key ->
                    val arr = t.optJSONArray(key) ?: return@forEach
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    tags[key] = list
                }
            }

            val width = original?.optInt("width", 0)?.takeIf { it > 0 }
                ?: oldFile?.optInt("width", 0) ?: 0
            val height = original?.optInt("height", 0)?.takeIf { it > 0 }
                ?: oldFile?.optInt("height", 0) ?: 0

            return Post(
                id = json.optLong("id"),
                md5 = md5,
                ext = ext,
                fileUrl = urlOf(original) ?: oldFile?.optString("url")?.takeIf { it.isNotBlank() },
                sampleUrl = urlOf(newSample) ?: oldSample?.optString("url")?.takeIf { it.isNotBlank() },
                previewUrl = urlOf(newPreview) ?: oldPreview?.optString("url")?.takeIf { it.isNotBlank() },
                width = width,
                height = height,
                score = score,
                rating = json.optString("rating"),
                tags = tags,
                isFavorited = json.optBoolean("is_favorited", false),
            )
        }
    }
}

/** 搜索历史条目，字段与桌面版 `config["history"]` 对齐。 */
data class HistoryEntry(
    val time: String,
    val tags: List<String>,
    val blacklist: List<String>,
)

/**
 * 本地配置。键名尽量与桌面版 `userdata.json` 保持一致，便于将来做导入导出。
 */
data class Config(
    val mode: String = MODE_OFFLINE,
    val theme: String = "system",
    val quality: String = Quality.SAMPLE,
    val maxPages: Int = 5,
    val limit: Int = 50,
    val minScore: Int = 10,
    val rememberHistory: Boolean = true,
    val keywordMemory: Int = 10,
    val username: String = "",
    val apiKey: String = "",
    val blacklist: List<String> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val recentKeywords: List<String> = emptyList(),
) {
    val loggedIn: Boolean get() = username.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val MODE_OFFLINE = "offline"
        const val MODE_ONLINE = "online"
        const val MAX_HISTORY = 10
        const val MAX_KEYWORD_MEMORY = 50
        const val DEFAULT_KEYWORD_MEMORY = 10

        fun keywordsLimit(config: Config): Int =
            config.keywordMemory.coerceIn(0, MAX_KEYWORD_MEMORY)
    }
}
