package com.mikesteve.e621downloadler.net

/**
 * 关键词输入框的「中英对照」候选（v1.5.1）。
 *
 * [chinese] 是内置词典里的中文释义，[english] 是可直接用于 e621 搜索/黑名单的英文标签。
 * 展示格式由 UI 决定（推荐列表为「狗 - dog」）。
 */
data class KeywordSuggestion(val english: String, val chinese: String)

/**
 * 搜索栏里的一个关键词气泡（v1.5.2，Pixiv 式交互）。
 *
 * - [tag] 是提交给 e621 后端的英文标签（唯一参与搜索的东西）；
 * - [label] 是展示给用户看的中文名（词典命中时是中文释义，未命中时退回原文）。
 */
data class SearchKeyword(val tag: String, val label: String)

/** 推荐列表默认条数：够用又不至于把搜索栏撑太开。 */
const val KEYWORD_SUGGESTION_LIMIT = 6

/** 关键词分隔符：英文/中文逗号、顿号、分号、空白都当分隔符（黑名单「只取第一个」沿用）。 */
private val KEYWORD_SEPARATORS = Regex("[,\\s，、;；]+")

/** v1.5.2：搜索栏多关键词分隔符改为**空格**（连续空白视作一个分隔符）。 */
private val KEYWORD_BLANK = Regex("\\s+")

/**
 * 取用户输入里的**第一个关键词**（v1.5.1 规则，黑名单添加仍在用）。
 *
 * - 先 trim，再按 [KEYWORD_SEPARATORS] 拆分，取第一个非空片段；
 * - 空输入、只有分隔符时返回空串。
 */
fun firstKeyword(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.split(KEYWORD_SEPARATORS).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/**
 * 按**空格**拆分一段关键词输入（v1.5.2 用户需求：分隔改为空格）。
 *
 * 连续空格/制表符/换行都算分隔；空输入返回空列表。
 */
fun splitKeywords(input: String): List<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(KEYWORD_BLANK).filter { it.isNotBlank() }
}

/** 文本里是否含中日韩统一表意文字。 */
fun containsChineseChar(text: String): Boolean = text.any { isCjkChar(it) }

/**
 * 英文标签 → 展示用中文名（v1.5.2）。
 *
 * 词典命中返回中文；未命中返回标签本身（无法离线翻译的英文标签只能原样显示）。
 */
fun tagLabel(entries: List<Pair<String, String>>, tag: String): String {
    val key = tag.trim()
    if (key.isEmpty()) return tag
    val lowered = key.lowercase()
    val hit = entries.firstOrNull { it.first.equals(lowered, ignoreCase = true) }?.second
        ?: entries.firstOrNull { it.first.equals(lowered.replace(' ', '_'), ignoreCase = true) }?.second
    return hit?.takeIf { it.isNotBlank() } ?: key
}

/**
 * 把一段用户输入解析成搜索气泡（v1.5.2）。
 *
 * 解析顺序：
 * 1. 英文标签**精确**命中词典（大小写不敏感）→ `tag` 用词典里的英文、`label` 用中文释义；
 * 2. 输入是中文且**精确**命中某个中文释义 → 用对应英文标签；
 * 3. 否则用实时推荐（[keywordSuggestions]）的第一条兜底：
 *    例如「蓝眼睛」→ `blue_eyes / 蓝眼`、`dragon` → `dragon / 龙`；
 * 4. 仍未命中：
 *    - 含中文 → `tag` 先保留原文（提交前由 ViewModel 在线翻译），`label` 用原文；
 *    - 纯英文 → `tag` 用原文小写，`label` 也用原文。
 *
 * 纯函数、不联网，便于 JVM 单测。
 */
fun resolveKeyword(entries: List<Pair<String, String>>, raw: String): SearchKeyword {
    val text = raw.trim()
    if (text.isEmpty()) return SearchKeyword("", "")
    // 1. 英文标签精确命中
    entries.firstOrNull { it.first.equals(text, ignoreCase = true) }?.let { entry ->
        return SearchKeyword(entry.first, entry.second)
    }
    // 2. 中文释义精确命中
    entries.firstOrNull { it.second == text }?.let { entry ->
        return SearchKeyword(entry.first, entry.second)
    }
    // 3. 推荐兜底（排序后第一条就是最合适的）
    keywordSuggestions(entries, text, limit = 1).firstOrNull()?.let { suggestion ->
        return SearchKeyword(suggestion.english, suggestion.chinese)
    }
    // 4. 兜底：中文先保留原文待在线翻译；英文原样作为标签
    return if (containsChineseChar(text)) {
        SearchKeyword(text, text)
    } else {
        SearchKeyword(text.lowercase(), text)
    }
}

/**
 * 实时中英对照推荐（v1.5.1 用户需求）：
 *
 * - 输入含中文：按词典的**中文释义**做包含匹配（如「狗」→ 狗 - dog、鬣狗 - hyena）；
 *   查询比词典词更长时做反向包含（如「蓝眼睛」→ 蓝眼 - blue_eyes），词典词需 ≥2 字以压噪声；
 * - 输入纯英文：按**英文标签**做包含匹配（如「do」→ 狗 - dog），同时补全完整英文标签；
 * - 只拿**第一个关键词**参与匹配（搜索栏文本可能还没变成气泡，用空格分隔）；
 * - 排序：完全匹配 → 前缀匹配 → 包含匹配 →（中文）反向包含；同级按英文标签长度、字典序；
 * - 纯函数、不依赖 Android：词典由调用方传入（见 `TranslationRepository.dictionaryEntries`）。
 *
 * @param entries 词典条目（英文标签 → 中文释义），顺序无关。
 * @param input 用户当前输入（可以含多个关键词，内部只取第一个）。
 * @param limit 最多返回多少条（≤0 表示不返回）。
 */
fun keywordSuggestions(
    entries: List<Pair<String, String>>,
    input: String,
    limit: Int = KEYWORD_SUGGESTION_LIMIT,
): List<KeywordSuggestion> {
    if (limit <= 0) return emptyList()
    val query = firstKeyword(input)
    if (query.isEmpty()) return emptyList()
    val hasChinese = query.any { isCjkChar(it) }

    data class Ranked(val rank: Int, val english: String, val chinese: String)

    return entries.asSequence()
        .mapNotNull { (englishRaw, chineseRaw) ->
            val english = englishRaw.trim()
            val chinese = chineseRaw.trim()
            if (english.isEmpty() || chinese.isEmpty()) return@mapNotNull null
            // 中文输入匹配中文释义，英文输入匹配英文标签（两边都不做跨列匹配，避免噪声）。
            val rank = if (hasChinese) {
                matchChineseRank(chinese, query)
            } else {
                matchEnglishRank(english, query)
            } ?: return@mapNotNull null
            Ranked(rank, english, chinese)
        }
        .sortedWith(
            compareBy<Ranked>({ it.rank }, { it.english.length }, { it.english }),
        )
        .take(limit)
        .map { KeywordSuggestion(english = it.english, chinese = it.chinese) }
        .toList()
}

/** 中文列匹配等级：0 = 完全一致，1 = 前缀，2 = 包含，3 = 词典词被查询包含（仅 ≥2 字）。 */
private fun matchChineseRank(text: String, query: String): Int? {
    if (text == query) return 0
    if (text.startsWith(query)) return 1
    if (text.contains(query)) return 2
    // 反向包含：用户输入更长的中文短语（如「蓝眼睛」），词典里的「蓝眼」也应该命中。
    // 单字词典词不参与反向包含，否则「鬣狗」这类输入会把「狗」也拉进来，噪声过大。
    if (text.length >= 2 && query.contains(text)) return 3
    return null
}

/** 英文标签匹配等级：0 = 完全一致，1 = 前缀，2 = 包含（大小写不敏感）。 */
private fun matchEnglishRank(text: String, query: String): Int? {
    val t = text.lowercase()
    val q = query.lowercase()
    if (q.isEmpty()) return null
    return when {
        t == q -> 0
        t.startsWith(q) -> 1
        t.contains(q) -> 2
        else -> null
    }
}

/** 是否中日韩统一表意文字（判断"该查哪一列"用，与 TranslationRepository 的规则一致）。 */
private fun isCjkChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
}
