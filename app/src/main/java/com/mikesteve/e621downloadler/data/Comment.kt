package com.mikesteve.e621downloadler.data

import org.json.JSONObject

/**
 * 作品评论（对齐 e621 官方 OpenAPI 的 `Comment` 对象）。
 *
 * 只保留界面真正会用的字段；所有字段都"能读就读"，缺失时回退默认值，
 * 避免 e621 再改一次字段名就整页解析失败。
 *
 * 注意：`body` 是原始 DText 文本（含换行与 `[quote]`/`[b]` 等标记），数据层不做解析；
 * 界面侧的展示由 `data/DText.kt` 解析成节点树后渲染（见 `ui/components/DTextContent.kt`）。
 *
 * [creatorId] 用于生成"回复"引用里的用户链接（`"名字":/users/<id>`）；老数据缺失时为 0。
 * 放在最后一个参数并给默认值，保证所有既有具名/位置构造调用都不受影响。
 */
data class Comment(
    val id: Long,
    val postId: Long,
    val creatorName: String,
    val body: String,
    val score: Int,
    val createdAt: String,
    val isSticky: Boolean,
    val isHidden: Boolean,
    val creatorId: Long = 0L,
) {
    /** 作者名可能缺失/为 JSON null，界面上统一回退为"未知用户"。 */
    val authorLabel: String get() = creatorName.ifBlank { "未知用户" }

    companion object {
        fun from(json: JSONObject): Comment = Comment(
            id = json.optLong("id", 0L),
            postId = json.optLong("post_id", 0L),
            creatorName = json.textOrEmpty("creator_name"),
            body = json.textOrEmpty("body"),
            score = json.optInt("score", 0),
            createdAt = json.textOrEmpty("created_at"),
            isSticky = json.optBoolean("is_sticky", false),
            isHidden = json.optBoolean("is_hidden", false),
            creatorId = json.optLong("creator_id", 0L),
        )
    }
}

/**
 * 读取字符串字段。`JSONObject.optString` 在字段为 JSON null 时可能返回
 * 字面量 "null"（Android 自带 org.json 的实现细节），这里统一归一成空串。
 */
private fun JSONObject.textOrEmpty(name: String): String {
    val raw = optString(name, "")
    return if (raw == "null") "" else raw
}
