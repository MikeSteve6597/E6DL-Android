package com.mikesteve.e621downloadler.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.TranslationKind
import com.mikesteve.e621downloadler.net.TranslationRepository
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import kotlinx.coroutines.CancellationException

/** 标签分组的展示顺序（与桌面版详情页的分类顺序一致）。 */
private val GROUP_ORDER = listOf("artist", "copyright", "character", "species", "general", "meta", "lore")

/** 单次最多展示的标签数（避免一次组合出上千个 chip）。 */
private const val MAX_DISPLAY_TAGS = 120

/** 首屏最多翻译的标签数；其余标签保持原文并可点击搜索。 */
private const val MAX_TRANSLATE_TAGS = 80

/** 每次向翻译仓库提交的批大小（内部并发 4，进度按批推进）。 */
private const val TRANSLATE_CHUNK = 8

/**
 * 作品标签区块（v1.4.0）。
 *
 *  - 按 e621 的分类分组展示：作者 / 版权 / 角色 / 物种 / 通用 / 元信息 / 设定，
 *    组内用 FlowRow + AssistChip 排列，点击 chip 回调**原 tag** 用于搜索。
 *  - 标题右侧的翻译开关直接读写配置 `cfg.translateTags`（持久化经 AppViewModel）。
 *  - 开启翻译后分批调用 [TranslationRepository.translateBatch]（kind = TAG），
 *    命中的标签在原文下方追加一行译文，并显示“翻译中 N/M”进度。
 *  - 在线引擎失败（单机模式 / debug 网络锁 / 服务异常）时显示可读错误，
 *    并自动改用内置词典兜底——无网络环境下标签翻译仍然可见（演示可用）。
 *  - 空标签不渲染整个区块。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsSection(
    vm: AppViewModel,
    post: Post,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(post.id, post.tags) { visibleGroups(post, MAX_DISPLAY_TAGS) }
    val totalTags = remember(post.id, post.tags) {
        post.tags.values.sumOf { list -> list.count { it.isNotBlank() } }
    }

    val cfg by vm.config.collectAsState()
    val context = LocalContext.current

    var translations by remember(post.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var translating by remember(post.id) { mutableStateOf(false) }
    var doneCount by remember(post.id) { mutableIntStateOf(0) }
    var totalCount by remember(post.id) { mutableIntStateOf(0) }
    var notice by remember(post.id) { mutableStateOf<String?>(null) }

    // 关闭翻译 / 切换引擎 / 切换运行模式时清空旧译文并重新翻译。
    LaunchedEffect(post.id, cfg.translateTags, cfg.translationEngine, cfg.mode) {
        translations = emptyMap()
        translating = false
        doneCount = 0
        totalCount = 0
        notice = null
        if (!cfg.translateTags) return@LaunchedEffect

        val tags = groups.flatMap { it.second }.distinct().take(MAX_TRANSLATE_TAGS)
        totalCount = tags.size
        if (tags.isEmpty()) return@LaunchedEffect

        translating = true
        try {
            translations = translateInChunks(context, cfg, tags) { done ->
                doneCount = done
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val readable = friendlyMessage(e)
            if (cfg.translationEngine == Config.TRANSLATION_GOOGLE) {
                // 在线引擎不可用（单机 / debug 网络锁 / 服务异常）→ 内置词典兜底，
                // 保证离线演示与弱网环境下标签翻译仍然有效。
                try {
                    translations = translateInChunks(
                        context = context,
                        cfg = cfg.copy(translationEngine = Config.TRANSLATION_LOCAL),
                        tags = tags,
                    ) { done -> doneCount = done }
                    notice = context.getString(R.string.e6dl_tags_fallback_local, readable)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    notice = context.getString(
                        R.string.e6dl_tags_fallback_failed,
                        readable,
                        friendlyMessage(e2),
                    )
                }
            } else {
                notice = readable
            }
        } finally {
            translating = false
        }
    }

    // 空标签不渲染整个区块（所有 remember / LaunchedEffect 已提前声明，避免条件调用 Compose 钩子）。
    if (groups.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.e6dl_tags_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.setTranslateTags(!cfg.translateTags) }) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = if (cfg.translateTags) {
                        stringResource(R.string.e6dl_tags_translate_off)
                    } else {
                        stringResource(R.string.e6dl_tags_translate_on)
                    },
                    tint = if (cfg.translateTags) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (cfg.translateTags) {
            Text(
                text = if (cfg.translationEngine == Config.TRANSLATION_LOCAL) {
                    stringResource(R.string.e6dl_tags_engine_local)
                } else {
                    stringResource(R.string.e6dl_tags_engine_google)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (translating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.e6dl_tags_progress, doneCount, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InfoBar(message = notice, type = InfoType.Warning)
            if (!translating && totalCount > 0 && totalTags > MAX_TRANSLATE_TAGS) {
                Text(
                    text = stringResource(
                        R.string.e6dl_tags_translated_limit,
                        MAX_TRANSLATE_TAGS,
                        totalTags,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        groups.forEach { (category, tags) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = groupLabelRes(category)
                Text(
                    text = if (label != null) stringResource(label) else category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            translated = translations[tag],
                            onSearchTag = onSearchTag,
                        )
                    }
                }
            }
        }

        if (totalTags > MAX_DISPLAY_TAGS) {
            Text(
                text = stringResource(
                    R.string.e6dl_tags_display_limit,
                    totalTags,
                    MAX_DISPLAY_TAGS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单个标签 chip：译文命中时显示两行（原 tag + 中文）。 */
@Composable
private fun TagChip(
    tag: String,
    translated: String?,
    onSearchTag: (String) -> Unit,
) {
    val translatedText = translated?.takeIf { it != tag }
    AssistChip(
        onClick = { onSearchTag(tag) },
        label = {
            // 在 lambda 内先取局部副本，保证可空判断后的 smart cast 稳定成立。
            val translation = translatedText
            if (translation != null) {
                Column {
                    Text(
                        text = tag,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = translation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = tag,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** 分组中文名资源；未知分类返回 null，调用处直接显示分类键（技术串不迁移）。 */
private fun groupLabelRes(category: String): Int? = when (category) {
    "artist" -> R.string.e6dl_tags_group_artist
    "copyright" -> R.string.e6dl_tags_group_copyright
    "character" -> R.string.e6dl_tags_group_character
    "species" -> R.string.e6dl_tags_group_species
    "general" -> R.string.e6dl_tags_group_general
    "meta" -> R.string.e6dl_tags_group_meta
    "lore" -> R.string.e6dl_tags_group_lore
    "invalid" -> R.string.e6dl_tags_group_invalid
    else -> null
}

/**
 * 按分类整理标签：先按 [GROUP_ORDER] 固定顺序，未列出的分类追加在后面；
 * 总数超过 [max] 时按顺序截断（与详情页原来 take(120) 的行为一致）。
 */
private fun visibleGroups(post: Post, max: Int): List<Pair<String, List<String>>> {
    val ordered = LinkedHashSet<String>()
    ordered.addAll(GROUP_ORDER)
    ordered.addAll(post.tags.keys)
    val result = ArrayList<Pair<String, List<String>>>()
    var remaining = max
    for (category in ordered) {
        if (remaining <= 0) break
        val tags = post.tags[category]
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        if (tags.isEmpty()) continue
        val included = tags.take(remaining)
        result.add(category to included)
        remaining -= included.size
    }
    return result
}

/**
 * 分批翻译标签（每批 [TRANSLATE_CHUNK] 个），每完成一批回调一次进度。
 * 结果：tag → 译文；未命中词典时仓库返回原文，渲染层会跳过重复译文。
 */
private suspend fun translateInChunks(
    context: Context,
    cfg: Config,
    tags: List<String>,
    onProgress: (Int) -> Unit,
): Map<String, String> {
    val result = LinkedHashMap<String, String>(tags.size)
    var done = 0
    for (chunk in tags.chunked(TRANSLATE_CHUNK)) {
        val batch = TranslationRepository.translateBatch(context, cfg, chunk, TranslationKind.TAG)
        chunk.forEachIndexed { index, tag ->
            val translated = batch.getOrNull(index)?.trim().orEmpty()
            if (translated.isNotEmpty()) result[tag] = translated
        }
        done += chunk.size
        onProgress(done)
    }
    return result
}
