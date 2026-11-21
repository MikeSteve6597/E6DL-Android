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
    val favCount: Int = 0,
    // 上传者用户名（e621 新结构 `uploader_name`）。
    val uploaderName: String = "",
    // 评论数（e621 新结构 `stats.comment_count`）。
    val commentCount: Int = 0,
    // 视频直链，按 `files.video.*` 逐级回退；图片帖为 null。
    val videoUrl: String? = null,
) {
    val isVideo: Boolean get() = ext.lowercase() in VIDEO_EXTS

    val tagsFlat: List<String> get() = tags.values.flatten()

    /** 第一个 `artist` 分类标签（作品作者）；没有则为空串。 */
    val artist: String get() = tags["artist"]?.firstOrNull().orEmpty()

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

    /**
     * 收藏状态变化后的副本：同时维护 `isFavorited` 与收藏数，
     * 避免详情页收藏后收藏数显示与状态不一致。
     */
    fun withFavorite(favorited: Boolean): Post {
        val delta = when {
            favorited && !isFavorited -> 1
            !favorited && isFavorited -> -1
            else -> 0
        }
        return copy(isFavorited = favorited, favCount = (favCount + delta).coerceAtLeast(0))
    }

    companion object {
        /**
         * 兼容 e621 的两代返回结构（2026-11 实测线上已切换为新结构）：
         *
         * 新：`{ "files": { "meta": {md5, ext, size, duration}, "original": {width, height, url},
         *        "sample": {width, height, jpg, webp}, "preview": {width, height, jpg, webp},
         *        "video": { "original": {url}, "variants": {webm, mp4}, "samples": {720p, 480p} } },
         *        "stats": { "score": {up, down, total}, "fav_count", "is_favorited", "comment_count" },
         *        "uploader_name": "..." }`
         * 旧：`{ "file": {url, ext, width, height}, "sample": {...}, "preview": {...}, "md5": "...",
         *        "score": 9 或 {up, down, total}, "fav_count", "is_favorited", "up_score", "down_score" }`
         *
         * 两个都读，缺哪个用哪个，避免站点再改一次就整页报错。
         */
        fun from(json: JSONObject): Post {
            val files = json.optJSONObject("files")
            val meta = files?.optJSONObject("meta")
            val original = files?.optJSONObject("original")
            val newSample = files?.optJSONObject("sample")
            val newPreview = files?.optJSONObject("preview")
            val video = files?.optJSONObject("video")
            val stats = json.optJSONObject("stats")

            val oldFile = json.optJSONObject("file")
            val oldSample = json.optJSONObject("sample")
            val oldPreview = json.optJSONObject("preview")

            // 地址：新结构里可能是 url，也可能是 jpg/webp 两个备选。
            // 注意 Android 的 `optString` 遇到 JSON null 会返回字符串 "null"（不是空串），
            // 这里一并过滤，否则会把 "null" 当成合法地址。
            fun urlOf(obj: JSONObject?): String? {
                if (obj == null) return null
                val url = obj.optString("url").takeIf { it.isNotBlank() && it != "null" }
                if (url != null) return url
                val jpg = obj.optString("jpg").takeIf { it.isNotBlank() && it != "null" }
                if (jpg != null) return jpg
                return obj.optString("webp").takeIf { it.isNotBlank() && it != "null" }
            }

            val md5 = json.optString("md5").takeIf { it.isNotBlank() }
                ?: meta?.optString("md5")?.takeIf { it.isNotBlank() }
                ?: ""

            val ext = (meta?.optString("ext")
                ?: oldFile?.optString("ext")
                ?: "jpg").lowercase()

            // 评分：新结构 `stats.score.total` 优先；旧结构顶层 `score` 可能是数字或
            // `{up, down, total}` 对象；再退到 `up_score - down_score`。
            fun totalOf(obj: JSONObject?): Int? =
                if (obj != null && obj.has("total")) obj.optInt("total", 0) else null

            fun legacyScoreOrNull(): Int? {
                if (!json.has("score") || json.isNull("score")) return null
                if (json.opt("score") is JSONObject) return null
                return json.optInt("score", 0)
            }

            val legacyScoreObj = json.optJSONObject("score")
            val score: Int = totalOf(stats?.optJSONObject("score"))
                ?: totalOf(legacyScoreObj)
                ?: legacyScoreOrNull()
                ?: (json.optInt("up_score", 0) - json.optInt("down_score", 0))

            // 收藏数 / 是否已收藏 / 评论数：新结构放在 `stats` 里，旧结构在顶层。
            fun statInt(key: String): Int {
                val fromStats = stats?.let { if (it.has(key)) it.optInt(key, 0) else null }
                return fromStats ?: json.optInt(key, 0)
            }

            fun statBoolean(key: String): Boolean {
                val fromStats = stats?.let { if (it.has(key)) it.optBoolean(key, false) else null }
                return fromStats ?: json.optBoolean(key, false)
            }

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

            // 视频直链：`files.video.original.url` → `variants.webm.url` → `variants.mp4.url`
            // → `samples.720p.url` → `samples.480p.url`，逐级回退；视频帖的
            // `files.original.url` 可能为空。旧接口没有 `files.video`，视频帖的地址就
            // 在 `files.original.url` / `file.url` 里，最后按扩展名兜底，保证老数据也能播。
            fun videoUrlOf(): String? {
                val videoVariants = video?.optJSONObject("variants") ?: files?.optJSONObject("variants")
                val videoSamples = video?.optJSONObject("samples") ?: files?.optJSONObject("samples")
                return urlOf(video?.optJSONObject("original"))
                    ?: urlOf(videoVariants?.optJSONObject("webm"))
                    ?: urlOf(videoVariants?.optJSONObject("mp4"))
                    ?: urlOf(videoSamples?.optJSONObject("720p"))
                    ?: urlOf(videoSamples?.optJSONObject("480p"))
                    ?: if (ext in VIDEO_EXTS) {
                        urlOf(original)
                            ?: oldFile?.optString("url")?.takeIf { it.isNotBlank() && it != "null" }
                    } else {
                        null
                    }
            }

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
                isFavorited = statBoolean("is_favorited"),
                favCount = statInt("fav_count"),
                // 同样过滤 Android optString 对 JSON null 返回的 "null"。
                uploaderName = json.optString("uploader_name").takeIf { it.isNotBlank() && it != "null" } ?: "",
                commentCount = statInt("comment_count"),
                videoUrl = videoUrlOf(),
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
 *
 * 注意：`apiKey` 是内存中的明文；落盘时由 `ConfigStore` 用 Android Keystore 加密后
 * 单独存放（见 `SecretStore`），不再以明文写进配置 JSON。
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
