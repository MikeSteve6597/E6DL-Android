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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.DebugFlags
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.PostCard

private val ORDER_OPTIONS = listOf(
    AppViewModel.ORDER_SCORE to R.string.e6dl_order_hot,
    AppViewModel.ORDER_NEWEST to R.string.e6dl_order_newest,
    AppViewModel.ORDER_RANDOM to R.string.e6dl_order_random,
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
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val state by vm.discover.collectAsState()
    val gridState = rememberLazyStaggeredGridState()

    var keywordInput by remember(state.keyword) { mutableStateOf(state.keyword) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var showBlacklistConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
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
            OutlinedTextField(
                value = keywordInput,
                onValueChange = { keywordInput = it },
                label = { Text(stringResource(R.string.e6dl_discover_keyword_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.setDiscoverKeyword(keywordInput) }),
                trailingIcon = {
                    IconButton(onClick = { vm.setDiscoverKeyword(keywordInput) }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.e6dl_action_search),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

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
