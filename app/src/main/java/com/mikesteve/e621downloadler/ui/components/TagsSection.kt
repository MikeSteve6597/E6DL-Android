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
 * 作品标签区块（v1.5.0）。
 *
 * 显示契约（**标签永远优先显示**）：
 *  - 按 e621 的分类分组展示：作者 / 版权 / 角色 / 物种 / 通用 / 元信息 / 设定；
 *    未列出的扩展分类（未知 key）追加在后面、直接用 key 当分组名，标签照常渲染；
 *    组内用 FlowRow + AssistChip 排列，点击 chip 回调**原 tag** 用于搜索。
 *  - **原文标签的渲染与翻译状态完全解耦**：关闭翻译、翻译失败、分组为空、在线服务异常
 *    都只影响“是否显示译文/提示”，绝不会隐藏标签。整个组件只有“作品确实一个标签都没有”
 *    时才不渲染。
 *  - 标题右侧的翻译开关直接读写配置 `cfg.translateTags`（持久化经 AppViewModel）。
 *  - 开启翻译后分批调用 [TranslationRepository.translateBatch]（kind = TAG）：在线引擎为
 *    第三方公开翻译服务（MyMemory 主、LibreTranslate 备），失败时**逐批静默回退内置词典**，
 *    已成功的批次保留译文，只显示一条可读提示，不隐藏任何标签。
 *  - 在线引擎不可用（单机模式 / debug 网络锁 / 服务异常）时显示可读错误并改用内置词典，
 *    无网络环境下标签翻译仍然可见（演示可用）。
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
    // 这里只是“译文”的副作用：即使整段逻辑异常，下面的原文标签也照常渲染。
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
        var fallbackFrom: Throwable? = null
        try {
            translations = translateInChunks(
                context = context,
                cfg = cfg,
                tags = tags,
                onProgress = { done -> doneCount = done },
                onFallback = { failure -> if (fallbackFrom == null) fallbackFrom = failure },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 兜底兜出意外也只提示：标签原文照常显示，绝不因此隐藏整个区块。
            notice = friendlyMessage(e)
        } finally {
            translating = false
        }
        val fallback = fallbackFrom
        if (fallback != null && notice == null) {
            notice = context.getString(R.string.e6dl_tags_fallback_local, friendlyMessage(fallback))
        }
    }

    // 唯一允许的隐藏：作品确实没有任何标签。翻译开关 / 译文 / 翻译异常都不参与这个判断，
    // 因此“翻译关闭、翻译失败、分组为空、扩展分类（未知 key）”都不会让原文标签消失。
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
                    // 在线引擎为第三方公开翻译服务（MyMemory 主、LibreTranslate 备），不再使用 Google。
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
            // 失败/回退只提示，不影响下面标签的渲染。
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

        // 标签渲染（无条件）：译文命中时 chip 显示两行，否则只显示原文。
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
 *
 * 注意：这里只过滤**空白标签**；未知分类（e621 以后新增的 key）会保留并照常渲染。
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
 *
 * - 在线引擎失败时**逐批静默回退内置词典**：已成功批次的译文保留，未成功的批次用词典兜底，
 *   并通过 [onFallback] 把首个异常交给调用方展示提示；
 * - 结果：tag → 译文；未命中词典时仓库返回原文，渲染层会跳过重复译文；
 * - 任何情况下都返回“能显示的部分”，不会因为翻译失败让调用方拿不到原文标签。
 */
private suspend fun translateInChunks(
    context: Context,
    cfg: Config,
    tags: List<String>,
    onProgress: (Int) -> Unit,
    onFallback: ((Throwable) -> Unit)? = null,
): Map<String, String> {
    val result = LinkedHashMap<String, String>(tags.size)
    var done = 0
    for (chunk in tags.chunked(TRANSLATE_CHUNK)) {
        try {
            val batch = TranslationRepository.translateBatch(context, cfg, chunk, TranslationKind.TAG)
            mergeTranslations(chunk, batch, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 在线引擎不可用（单机 / debug 网络锁 / 第三方服务异常）→ 本批用内置词典兜底。
            onFallback?.invoke(e)
            val fallbackCfg = cfg.copy(translationEngine = Config.TRANSLATION_LOCAL)
            val batch = TranslationRepository.translateBatch(
                context = context,
                cfg = fallbackCfg,
                texts = chunk,
                kind = TranslationKind.TAG,
            )
            mergeTranslations(chunk, batch, result)
        }
        done += chunk.size
        onProgress(done)
    }
    return result
}

/** 按 tag 顺序把一批译文并入结果表；空白译文（未命中）直接跳过。 */
private fun mergeTranslations(
    chunk: List<String>,
    batch: List<String>,
    out: MutableMap<String, String>,
) {
    chunk.forEachIndexed { index, tag ->
        val translated = batch.getOrNull(index)?.trim().orEmpty()
        if (translated.isNotEmpty()) out[tag] = translated
    }
}
