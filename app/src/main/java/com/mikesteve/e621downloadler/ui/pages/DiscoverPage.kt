package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.SearchKeyword
import com.mikesteve.e621downloadler.net.TranslationRepository
import com.mikesteve.e621downloadler.net.keywordSuggestions
import com.mikesteve.e621downloadler.net.resolveKeyword
import com.mikesteve.e621downloadler.net.splitKeywords
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.DebugFlags
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.KeywordSearchBar
import com.mikesteve.e621downloadler.ui.components.KeywordSuggestionList
import com.mikesteve.e621downloadler.ui.components.PostCard

private val ORDER_OPTIONS = listOf(
    AppViewModel.ORDER_SCORE to R.string.e6dl_order_hot,
    AppViewModel.ORDER_NEWEST to R.string.e6dl_order_newest,
    AppViewModel.ORDER_VIDEO to R.string.e6dl_order_video,
)

/**
 * 发现页（对齐桌面版 `DiscoverPage`）：
 * 排序三选一 + 关键词过滤 + 瀑布流触底加载 + **多选批量收藏/拉黑/下载**。
 * 黑名单**仅登录时生效**（与桌面版一致）。
 */
@Composable
fun DiscoverPage(
    vm: AppViewModel,
    onOpenPost: (Post) -> Unit,
    onSearch: (List<SearchKeyword>) -> Unit,
    onOpenTagWiki: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val state by vm.discover.collectAsState()
    val gridState = rememberLazyStaggeredGridState()

    // v1.5.2：已选关键词以「中文气泡」形式保存在搜索栏里；输入框只负责输入新关键词/出推荐。
    // 气泡存在 ViewModel 里（打开结果页/切换 Tab 再回来不会丢）。
    val keywords by vm.searchKeywords.collectAsState()
    var keywordInput by rememberSaveable { mutableStateOf(DebugFlags.demoQuery) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var showBlacklistConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // 词典只在页面首次组合时读一次（TranslationRepository 内部还有进程级缓存），
    // 匹配/解析逻辑都是纯函数，见 net/KeywordSuggestions。
    val dictionaryEntries = remember(context) {
        TranslationRepository.dictionaryEntries(context)
    }
    val suggestions = remember(keywordInput, dictionaryEntries) {
        if (keywordInput.isBlank()) {
            emptyList()
        } else {
            keywordSuggestions(dictionaryEntries, keywordInput)
        }
    }
    // 把输入框里的文本（可能含空格分隔的多个词）解析成中文气泡。
    val addKeywordsFromText: (String) -> Unit = { text ->
        val resolved = splitKeywords(text)
            .map { resolveKeyword(dictionaryEntries, it) }
            .filter { it.tag.isNotBlank() }
        if (resolved.isNotEmpty()) {
            vm.setSearchKeywords(keywords + resolved)
        }
    }
    // v1.5.2：空格作为关键词分隔符——输入空格时把前面的内容收进气泡，输入框清空，
    // 这样紧接着输入第二个关键词时推荐仍能继续（修复 v1.5.1 点击推荐后无法继续翻译）。
    val onInputChange: (String) -> Unit = { value ->
        if (value.endsWith(' ') || value.endsWith('\n')) {
            addKeywordsFromText(value)
            keywordInput = ""
        } else {
            keywordInput = value
        }
    }
    // 提交搜索：把输入框里剩下的文本也拼成气泡，然后把**全部气泡的英文 tag** 交给后端；
    // 界面上始终只展示气泡里的中文名（v1.5.2 需求）。
    val submitSearch: () -> Unit = {
        val pending = splitKeywords(keywordInput)
        val all = (keywords + pending.map { resolveKeyword(dictionaryEntries, it) })
            .filter { it.tag.isNotBlank() }
            .distinctBy { it.tag }
        vm.setSearchKeywords(all)
        keywordInput = ""
        if (all.isNotEmpty()) {
            onSearch(all)
        }
    }
    // 在组合期取文本：onClick / 本地函数等非 @Composable 作用域里不能直接调 stringResource。
    val selectBlacklistFirstMessage = stringResource(R.string.e6dl_select_blacklist_first)

    // 首次进入（或上次失败后列表为空）时加载；演示数据模式下不联网
    LaunchedEffect(Unit) {
        if (state.posts.isEmpty() && !state.loading && !state.demo && !DebugFlags.demo) {
            vm.loadDiscover(reset = true)
        }
    }

    // 联网模式切换：从离线切到 online 且当前没有数据 / 上次报错时自动重拉，
    // 避免用户切完模式还得手动点刷新。
    LaunchedEffect(cfg.mode) {
        if (cfg.mode != Config.MODE_ONLINE) return@LaunchedEffect
        val snapshot = vm.discover.value
        if (snapshot.demo || DebugFlags.demo || snapshot.loading) return@LaunchedEffect
        if (snapshot.posts.isEmpty() || snapshot.error != null) {
            vm.loadDiscover(reset = true)
        }
    }

    // 触底加载：最后 4 个可见项时尝试下一页
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.posts.size - 4
        }
    }
    LaunchedEffect(gridState, state.posts.size) {
        snapshotFlow { nearEnd }.collect { if (it) vm.loadDiscover(reset = false) }
    }

    fun runBatchDownload(posts: List<Post>) {
        message = context.getString(R.string.e6dl_batch_download_start, posts.size)
        messageType = InfoType.Info
        vm.downloadPosts(posts, cfg.quality) { result ->
            message = result
            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
        }
        vm.setDiscoverSelectionMode(false)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // v1.5.2：Pixiv 式搜索栏——已选关键词以中文气泡显示在栏内，每个气泡右侧 x 可删除。
            KeywordSearchBar(
                keywords = keywords,
                // v1.5.2.1：按关键词对象删除（而不是下标），并让整颗气泡成为删除热区。
                onRemoveKeyword = { keyword ->
                    vm.setSearchKeywords(keywords.filterNot { it.tag == keyword.tag })
                },
                input = keywordInput,
                onInputChange = onInputChange,
                onSubmit = submitSearch,
                placeholder = stringResource(R.string.e6dl_keyword_input_placeholder),
            )

            // 搜索栏下方只在这里显示「英文原 tag」（v1.5.2 需求）；点击后变成中文气泡。
            if (suggestions.isNotEmpty()) {
                KeywordSuggestionList(
                    suggestions = suggestions,
                    onPick = { suggestion ->
                        vm.setSearchKeywords(
                            keywords + SearchKeyword(suggestion.english, suggestion.chinese),
                        )
                        keywordInput = ""
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ORDER_OPTIONS.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = state.order == value,
                        onClick = { vm.setDiscoverOrder(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                // v1.5.2：Tag Wiki 入口（展示内置词典收录的全部 tag 中英文）。
                IconButton(onClick = onOpenTagWiki) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = stringResource(R.string.e6dl_keyword_wiki),
                    )
                }
                IconButton(
                    onClick = {
                        if (state.selectionMode) {
                            vm.setDiscoverSelectionMode(false)
                        } else {
                            vm.setDiscoverSelectionMode(true)
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (state.selectionMode) Icons.Filled.Close else Icons.Filled.Checklist,
                        contentDescription = if (state.selectionMode) {
                            stringResource(R.string.e6dl_action_deselect)
                        } else {
                            stringResource(R.string.e6dl_action_select)
                        },
                    )
                }
                IconButton(
                    onClick = { vm.loadDiscover(reset = true) },
                    enabled = !state.loading,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.e6dl_action_refresh))
                }
            }

            // 批量条：只要处于选择模式或已有选中项就显示（复选框常驻后，
            // 用户不点顶栏选择按钮、直接勾选也能立刻看到批量操作入口）。
            if (state.selectionMode || state.selected.isNotEmpty()) {
                SelectionBar(
                    count = state.selected.size,
                    loggedIn = cfg.loggedIn,
                    busy = state.busy,
                    onSelectAll = { vm.selectAllDiscover() },
                    onFavorite = { vm.batchFavorite(state.selectedPosts) { result -> message = result; messageType = InfoType.Success } },
                    onBlacklist = {
                        if (state.selected.isEmpty()) {
                            message = selectBlacklistFirstMessage
                            messageType = InfoType.Warning
                        } else {
                            showBlacklistConfirm = true
                        }
                    },
                    onDownload = { runBatchDownload(state.selectedPosts) },
                    onCancel = { vm.setDiscoverSelectionMode(false) },
                )
            }

            InfoBar(
                message = message
                    ?: state.error
                    ?: if (state.demo) {
                        stringResource(R.string.e6dl_discover_demo_hint)
                    } else if (!cfg.loggedIn && cfg.blacklist.isNotEmpty()) {
                        stringResource(R.string.e6dl_discover_guest_blacklist_hint)
                    } else {
                        null
                    },
                type = if (state.error != null) InfoType.Warning else messageType,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when {
            state.posts.isEmpty() && state.loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.posts.isEmpty() -> EmptyState(
                title = stringResource(R.string.e6dl_discover_empty_title),
                description = stringResource(R.string.e6dl_discover_empty_desc),
                action = {
                    Button(onClick = { vm.loadDiscover(reset = true) }) {
                        Text(stringResource(R.string.e6dl_action_reload))
                    }
                },
            )

            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        quality = cfg.quality,
                        selected = if (state.selectionMode) post.id in state.selected else null,
                        // 复选框常驻：勾选即进入选择模式并切换选中，点击卡片仍打开详情。
                        showCheckbox = true,
                        checked = post.id in state.selected,
                        onCheckedChange = {
                            vm.setDiscoverSelectionMode(true)
                            vm.toggleDiscoverSelected(post.id)
                        },
                        onClick = { onOpenPost(post) },
                        onLongClick = {
                            if (!state.selectionMode) vm.setDiscoverSelectionMode(true)
                            vm.toggleDiscoverSelected(post.id)
                        },
                    )
                }
                if (state.loading) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                if (state.reachedEnd && state.posts.isNotEmpty() && !state.loading) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            text = stringResource(R.string.e6dl_discover_reached_end),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showBlacklistConfirm) {
        val tags = state.selectedPosts.flatMap { it.tagsFlat }.distinct()
        AlertDialog(
            onDismissRequest = { showBlacklistConfirm = false },
            title = { Text(stringResource(R.string.e6dl_batch_blacklist_title)) },
            text = {
                Text(
                    stringResource(R.string.e6dl_batch_blacklist_desc_discover, tags.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlacklistConfirm = false
                    vm.batchBlacklist(state.selectedPosts) { result ->
                        message = result
                        messageType = InfoType.Success
                    }
                    vm.setDiscoverSelectionMode(false)
                }) { Text(stringResource(R.string.e6dl_batch_blacklist_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showBlacklistConfirm = false }) {
                    Text(stringResource(R.string.e6dl_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    loggedIn: Boolean,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onBlacklist: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.e6dl_selection_count, count), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.e6dl_action_select_all)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.e6dl_action_exit)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onFavorite, enabled = loggedIn && !busy) {
                Text(stringResource(R.string.e6dl_action_favorite))
            }
            TextButton(onClick = onBlacklist) { Text(stringResource(R.string.e6dl_action_blacklist)) }
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.e6dl_action_download_selected))
            }
        }
    }
}
