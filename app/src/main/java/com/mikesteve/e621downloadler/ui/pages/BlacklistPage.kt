package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.net.TranslationRepository
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 黑名单（对齐桌面版 `BlacklistPage`）：标签持久化，发现/搜索/收藏统一过滤。
 *
 * v1.5.0 起输入框一物两用：
 *  - **搜索/添加关键词**：输入英文（或含逗号/空格的英文）直接按原逻辑拆分后加入黑名单；
 *    输入含中文时先走 [TranslationRepository.translateToEnglish] 翻译成英文 tag 再加入
 *    （翻译期间按钮禁用并显示进度小字，失败用 [friendlyMessage] 的可读文案提示）。
 *  - **列表过滤词**：输入内容实时按「包含匹配」过滤下方黑名单；如果当前输入刚被翻译过，
 *    也会用翻译出的英文 tag 参与匹配（例如输入「蓝眼睛」翻译成 `blue_eyes` 后能过滤到该标签）。
 *
 * 空态区分「黑名单为空」与「没有匹配的黑名单标签」，避免用户误以为黑名单被清空。
 * 网络锁/单机语义完全交给 TranslationRepository → NetworkGate，本页不绕过门禁。
 */
@Composable
fun BlacklistPage(vm: AppViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsState()
    val scope = rememberCoroutineScope()
    // 翻译需要 Context（在线请求/本地机翻模型都从 TranslationRepository 拿），组合期取一次。
    val context = LocalContext.current

    // 组合期取文案：submit 等普通回调里不能调用 @Composable 的 stringResource。
    val translateFailedMessage = stringResource(R.string.e6dl_blacklist_translate_failed)

    var input by remember { mutableStateOf("") }

    /** 翻译 / 添加进行中：按钮禁用、输入框暂时只读，并显示进度小字。 */
    var busy by remember { mutableStateOf(false) }

    /** 可读错误（网络锁 / 单机 / 翻译服务异常），统一由 [friendlyMessage] 生成。 */
    var errorMessage by remember { mutableStateOf<String?>(null) }

    /** 最近一次成功翻译：来源输入原文 + 归一化后的英文 tag（可能多个）。 */
    var translatedSource by remember { mutableStateOf("") }
    var translatedTags: List<String> by remember { mutableStateOf(emptyList<String>()) }

    // 输入框既做过滤又做添加：翻译成功后列表同时用「原文」与「英文 tag」做包含匹配。
    val query = input.trim()
    val filtering = query.isNotEmpty()
    val englishQuery: List<String> =
        if (filtering && query == translatedSource) translatedTags else emptyList()

    val filtered = if (!filtering) {
        cfg.blacklist
    } else {
        cfg.blacklist.filter { tag ->
            tag.contains(query, ignoreCase = true) ||
                englishQuery.any { english -> tag.contains(english, ignoreCase = true) }
        }
    }

    val clearInput: () -> Unit = {
        input = ""
        translatedSource = ""
        translatedTags = emptyList()
        errorMessage = null
    }

    // 点击「搜索 / 添加」：含中文先翻译成英文 tag，纯英文直接按原逻辑拆分添加。
    val submit: () -> Unit = submit@{
        if (busy) return@submit
        val text = input.trim()
        if (text.isEmpty()) return@submit
        errorMessage = null
        translatedSource = ""
        translatedTags = emptyList()

        if (containsChinese(text)) {
            busy = true
            scope.launch {
                try {
                    val translated = TranslationRepository.translateToEnglish(context, cfg, text)
                    // 翻译服务可能返回多词短语（"blue eyes"）或以逗号分隔的多个结果：
                    // 逐项归一化成 e621 风格的单个英文 tag，再逐个加入黑名单。
                    val tags = translated
                        .split(',', '，', ';', '；')
                        .map { normalizeEnglishTag(it) }
                        .filter { it.isNotEmpty() }
                    // 翻译服务偶发返回原文/空结果：此时宁可提示失败，也不把中文写进黑名单。
                    val englishTags = tags.filterNot { containsChinese(it) }
                    if (englishTags.isEmpty()) {
                        errorMessage = translateFailedMessage
                    } else {
                        englishTags.forEach { vm.addBlacklist(it) }
                        // 保留输入内容：既展示「将添加」提示，也继续用英文 tag 过滤列表。
                        translatedSource = text
                        translatedTags = englishTags
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络锁 / 单机 / 在线翻译服务失败都走同一条可读提示，绝不绕过门禁。
                    errorMessage = friendlyMessage(e)
                } finally {
                    busy = false
                }
            }
        } else {
            // 英文/无中文：保持原有逻辑，按逗号/空格拆分后逐个添加（去重由 addBlacklist 负责）。
            text.split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { vm.addBlacklist(it) }
            // 不输入中文时无需保留翻译提示；输入框继续作为过滤词。
            translatedSource = ""
            translatedTags = emptyList()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = stringResource(R.string.e6dl_blacklist_search_add)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        // 输入一变化，上一次的翻译结果与错误提示立即失效（避免误导）。
                        translatedSource = ""
                        translatedTags = emptyList()
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.e6dl_blacklist_input_label)) },
                    placeholder = { Text(stringResource(R.string.e6dl_blacklist_input_placeholder)) },
                    singleLine = true,
                    enabled = !busy,
                    trailingIcon = if (input.isNotEmpty()) {
                        {
                            IconButton(onClick = clearInput, enabled = !busy) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.e6dl_action_clear),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = submit,
                    enabled = !busy && input.isNotBlank(),
                ) { Text(stringResource(R.string.e6dl_blacklist_submit)) }
            }

            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.e6dl_blacklist_translating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (translatedTags.isNotEmpty() && translatedSource == query) {
                Text(
                    text = stringResource(
                        R.string.e6dl_blacklist_will_add,
                        translatedTags.joinToString("，"),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            InfoBar(message = errorMessage, type = InfoType.Warning)

            Text(
                text = stringResource(R.string.e6dl_blacklist_filter_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.e6dl_blacklist_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_blacklist_section, cfg.blacklist.size)) {
            when {
                // 黑名单本身为空：与「有标签但过滤无命中」区分开。
                cfg.blacklist.isEmpty() -> EmptyState(
                    title = stringResource(R.string.e6dl_blacklist_empty_title),
                    description = stringResource(R.string.e6dl_blacklist_empty_desc),
                )

                filtered.isEmpty() -> EmptyState(
                    title = stringResource(R.string.e6dl_blacklist_filter_empty_title),
                    description = stringResource(
                        R.string.e6dl_blacklist_filter_empty_desc,
                        query,
                        cfg.blacklist.size,
                    ),
                )

                else -> {
                    if (filtering) {
                        Text(
                            text = stringResource(
                                R.string.e6dl_blacklist_match_count,
                                filtered.size,
                                cfg.blacklist.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    filtered.forEach { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = tag, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { vm.removeBlacklist(tag) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.e6dl_blacklist_remove, tag),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 是否包含 CJK 汉字：决定「搜索 / 添加」要不要先翻译成英文 tag。 */
private fun containsChinese(text: String): Boolean =
    text.any { ch -> ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF }

/** 连续空白（翻译服务返回的短语常用空格分隔）。 */
private val TAG_WHITESPACE = Regex("\\s+")

/**
 * 把翻译结果归一化成 e621 风格的英文 tag：去首尾标点/空白、转小写，
 * 词间空白换成下划线（`"blue eyes"` → `blue_eyes`），避免加入一条永远匹配不到的黑名单项。
 */
private fun normalizeEnglishTag(raw: String): String {
    val cleaned = raw
        .trim()
        .trim('.', ',', ';', ':', '。', '，', '；', '、', '"', '\'', '“', '”', '‘', '’')
        .trim()
    if (cleaned.isEmpty()) return ""
    return cleaned.lowercase().replace(TAG_WHITESPACE, "_").trim('_')
}
