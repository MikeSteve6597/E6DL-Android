package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** D7 下载页缩略图预览：一次最多展示前 24 张（与发现页每页数量一致）。 */
private const val PREVIEW_LIMIT = 24

/**
 * 关键词拆分：逗号、中文逗号与空白都当分隔符。
 *
 * 仅用于预览；「开始下载」仍保持原来的「只按逗号拆分」行为不变。
 */
private fun splitKeywords(raw: String): List<String> =
    raw.split(Regex("[,\\s，]+")).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 缩略图地址：`previewUrl → sampleUrl → fileUrl`。
 * 与 [Post.hasVisibleCard] 的 v1.5.0 规则一致：三者都没有的作品整张预览不显示。
 */
private fun thumbnailUrl(post: Post): String? {
    post.previewUrl?.takeIf { it.isNotBlank() }?.let { return it }
    post.sampleUrl?.takeIf { it.isNotBlank() }?.let { return it }
    return post.fileUrl?.takeIf { it.isNotBlank() }
}

/**
 * 搜索下载页（对齐桌面版 `SearchPage`）：
 * 关键词 / 本次黑名单 / 画质 / 页数 / 每页数量 / 最低评分 / 保存历史 / 进度 / 取消 / 历史 / 最近关键词。
 *
 * @param initialTags 从详情页点标签跳过来时携带的标签；只用于「预填关键词输入框」，
 *   不会自动触发下载。预填后立即通过 [onPrefillConsumed] 清空，避免下次进页面覆盖用户输入。
 */
@Composable
fun SearchPage(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    initialTags: String? = null,
    onPrefillConsumed: () -> Unit = {},
) {
    val cfg by vm.config.collectAsState()
    val progress by vm.progress.collectAsState()

    // 输入内容用 rememberSaveable：旋转/折叠/分屏拖动时不会丢
    var tags by rememberSaveable { mutableStateOf("") }
    var tempBlacklist by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable { mutableStateOf(cfg.quality) }
    var maxPages by rememberSaveable { mutableStateOf(cfg.maxPages.toString()) }
    var limit by rememberSaveable { mutableStateOf(cfg.limit.toString()) }
    var minScore by rememberSaveable { mutableStateOf(cfg.minScore.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }

    // D7 预览状态：只存在页面内存里，不写配置/历史，也不会触发任何下载。
    var previewPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 组合期取文案：start()/loadPreview()/onClick 等普通回调里不能调用 @Composable 的 stringResource。
    val keywordRequiredMessage = stringResource(R.string.e6dl_search_keyword_required)
    val startingMessage = stringResource(R.string.e6dl_search_starting)
    val cancellingMessage = stringResource(R.string.e6dl_search_cancelling)

    // 详情页点标签跳转：预填关键词后立刻消费掉，避免每次回到本页都覆盖用户已改的内容。
    // 注意：这里只填输入框，不调用 start()，不自动开始下载。
    LaunchedEffect(initialTags) {
        val incoming = initialTags?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        tags = incoming.replace(' ', ',')
        onPrefillConsumed()
    }

    fun start() {
        val tagList = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tagList.isEmpty()) {
            message = keywordRequiredMessage
            messageType = InfoType.Warning
            return
        }
        val blacklist = tempBlacklist.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { cfg.blacklist }
        val pages = maxPages.toIntOrNull()?.coerceIn(1, 50) ?: 5
        val perPage = limit.toIntOrNull()?.coerceIn(1, 320) ?: 50
        val score = minScore.toIntOrNull() ?: 10

        vm.setSearchDefaults(pages, perPage, score)
        vm.rememberKeyword(tagList.first())
        vm.rememberHistory(tagList, blacklist)
        message = startingMessage
        messageType = InfoType.Info
        vm.startDownload(
            tags = tagList,
            blacklist = blacklist,
            quality = quality,
            maxPages = pages,
            limit = perPage,
            minScore = score,
        ) { result ->
            message = result
            messageType = if (result.contains("失败") || result.contains("已取消")) InfoType.Warning else InfoType.Success
        }
    }

    /**
     * D7 预览：按当前关键词 / 临时黑名单拉前 [PREVIEW_LIMIT] 个作品，只显示缩略图。
     *
     * 只调 `fetchPosts` 取元数据，**不下载任何文件、不写历史/最近关键词**；
     * 再次点「预览」重新拉取即刷新。失败时用 [friendlyMessage] 给出可读错误。
     */
    fun loadPreview() {
        val tagList = splitKeywords(tags)
        if (tagList.isEmpty()) {
            message = keywordRequiredMessage
            messageType = InfoType.Warning
            return
        }
        val currentCfg = cfg
        val blacklist = splitKeywords(tempBlacklist).ifEmpty { currentCfg.blacklist }
        previewLoaded = true
        previewLoading = true
        previewError = null
        previewPosts = emptyList()
        scope.launch {
            try {
                val posts = vm.api.fetchPosts(
                    cfg = currentCfg,
                    tags = tagList,
                    blacklist = blacklist,
                    limit = PREVIEW_LIMIT,
                    page = 1,
                )
                previewPosts = posts.take(PREVIEW_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewPosts = emptyList()
                previewError = friendlyMessage(e)
            } finally {
                previewLoading = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBar(message = message, type = messageType)

        SectionCard(title = stringResource(R.string.e6dl_search_section_conditions)) {
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.e6dl_search_keyword_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tempBlacklist,
                onValueChange = { tempBlacklist = it },
                label = { Text(stringResource(R.string.e6dl_search_temp_blacklist_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.labels.forEach { (key, label) ->
                    FilterChip(
                        selected = quality == key,
                        onClick = { quality = key },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxPages,
                    onValueChange = { maxPages = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.e6dl_search_max_pages)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.e6dl_search_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minScore,
                    onValueChange = { minScore = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text(stringResource(R.string.e6dl_search_min_score)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.e6dl_search_remember_history),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = cfg.rememberHistory, onCheckedChange = { vm.setRememberHistory(it) })
            }
            // 三个按钮用 FlowRow：360dp 窄屏放不下时整体换行，不会被挤压变形。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { start() }, enabled = progress == null) {
                    Text(stringResource(R.string.e6dl_search_start))
                }
                // D7：预览只拉缩略图，不进入下载流程；加载中禁用，避免重复请求。
                OutlinedButton(onClick = { loadPreview() }, enabled = !previewLoading) {
                    Text(stringResource(R.string.e6dl_search_preview))
                }
                OutlinedButton(
                    onClick = { vm.cancelDownload(); message = cancellingMessage },
                    enabled = progress != null,
                ) { Text(stringResource(R.string.e6dl_action_cancel)) }
            }
            progress?.let { p ->
                LinearProgressIndicator(
                    progress = {
                        if (p.total > 0) (p.done + p.skipped + p.failed).toFloat() / p.total.toFloat() else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.e6dl_search_progress, p.done, p.skipped, p.failed) +
                        if (p.currentName.isNotEmpty()) " · ${p.currentName}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.message.isNotEmpty()) {
                    Text(p.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // D7 预览结果：无缩略图地址的作品不显示（v1.5.0 规则），所以 N 是「可见缩略图数」。
        if (previewLoaded) {
            val visiblePreview = previewPosts.mapNotNull { post ->
                thumbnailUrl(post)?.let { url -> post to url }
            }
            val previewErrorMessage = previewError
            SectionCard(
                title = stringResource(R.string.e6dl_search_preview_title, visiblePreview.size),
            ) {
                when {
                    previewLoading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }

                    previewErrorMessage != null -> InfoBar(
                        message = previewErrorMessage,
                        type = InfoType.Warning,
                    )

                    visiblePreview.isEmpty() -> Text(
                        text = stringResource(R.string.e6dl_search_preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        visiblePreview.forEach { (post, url) ->
                            PreviewThumbnail(post = post, url = url)
                        }
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.e6dl_search_history_title)) {
            if (cfg.history.isEmpty()) {
                Text(
                    stringResource(R.string.e6dl_search_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cfg.history.forEach { h ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tags = h.tags.joinToString(", ")
                                tempBlacklist = h.blacklist.joinToString(", ")
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = h.tags.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(h.time, style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { vm.clearHistory() }) {
                    Text(stringResource(R.string.e6dl_action_clear))
                }
            }
        }

        if (cfg.recentKeywords.isNotEmpty() && cfg.keywordMemory > 0) {
            SectionCard(title = stringResource(R.string.e6dl_search_recent_keywords)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cfg.recentKeywords.forEach { kw ->
                        AssistChip(onClick = { tags = kw }, label = { Text(kw) })
                    }
                }
            }
        }
    }
}

/**
 * D7 预览缩略图：88dp 方形圆角缩略图 + 下方小字 `#id`。
 *
 * 只展示接口返回的缩略图地址（Coil 3 [AsyncImage]），不触发文件下载、不写历史。
 */
@Composable
private fun PreviewThumbnail(post: Post, url: String) {
    Column(
        modifier = Modifier.width(88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            model = url,
            contentDescription = "#${post.id}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Text(
            text = "#${post.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

