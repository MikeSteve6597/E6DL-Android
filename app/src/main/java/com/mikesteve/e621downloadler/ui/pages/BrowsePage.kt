package com.mikesteve.e621downloadler.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.PostCard

/**
 * 通用作品浏览页（v1.5.0）：搜索结果、作者主页、标签作品页共用。
 *
 * 布局/交互与发现页一致：瀑布流卡片 + 复选框批量 + 触底加载 + 空/错状态。
 * 排序支持评分/热度 × 升序/降序（e621 order:*）。
 * 黑名单过滤由 ViewModel 查询时通过 -tag 排除；无缩略图的不可见作品也已在 ViewModel 过滤。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowsePage(
    vm: AppViewModel,
    onOpenPost: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val state by vm.browse.collectAsState()
    val gridState = rememberLazyStaggeredGridState()
    val context = LocalContext.current

    var selectedIds by remember(state.title, state.query) { mutableStateOf<Set<Long>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }

    BackHandler(enabled = state.active) { vm.closeBrowse() }

    // 触底加载
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.posts.size - 4
        }
    }
    LaunchedEffect(gridState, state.posts.size) {
        snapshotFlow { nearEnd }.collect { if (it) vm.loadBrowse(reset = false) }
    }

    val selectedPosts = state.posts.filter { it.id in selectedIds }

    // 组合期取文案：runDownload()/ifBlank 等普通回调里不能调用 @Composable 的 stringResource。
    val downloadStartMessage = stringResource(R.string.e6dl_batch_download_start, selectedPosts.size)
    val browseTitleFallback = stringResource(R.string.e6dl_browse_title)

    fun runDownload() {
        if (selectedPosts.isEmpty()) return
        message = downloadStartMessage
        messageType = InfoType.Info
        vm.downloadPosts(selectedPosts, cfg.quality) { result ->
            message = result
            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
        }
        selectedIds = emptySet()
    }

    fun runFavorite() {
        vm.batchFavorite(selectedPosts) { result ->
            message = result
            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
        }
        selectedIds = emptySet()
    }

    fun runBlacklist() {
        vm.batchBlacklist(selectedPosts) { result ->
            message = result
            messageType = InfoType.Success
        }
        selectedIds = emptySet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeBrowse() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.e6dl_action_back),
                )
            }
            Text(
                text = state.title.ifBlank { browseTitleFallback },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.e6dl_browse_count, state.posts.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { vm.loadBrowse(reset = true) }, enabled = !state.loading) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.e6dl_action_refresh),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.e6dl_browse_sort),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterChip(
                selected = state.sort == AppViewModel.BROWSE_SORT_SCORE,
                onClick = { vm.setBrowseSort(AppViewModel.BROWSE_SORT_SCORE, state.ascending) },
                label = { Text(stringResource(R.string.e6dl_browse_sort_score)) },
            )
            FilterChip(
                selected = state.sort == AppViewModel.BROWSE_SORT_HOT,
                onClick = { vm.setBrowseSort(AppViewModel.BROWSE_SORT_HOT, state.ascending) },
                label = { Text(stringResource(R.string.e6dl_browse_sort_hot)) },
            )
            TextButton(
                onClick = { vm.setBrowseSort(state.sort, !state.ascending) },
            ) {
                Text(
                    if (state.ascending) {
                        stringResource(R.string.e6dl_browse_ascending)
                    } else {
                        stringResource(R.string.e6dl_browse_descending)
                    },
                )
            }
        }

        InfoBar(message = message, type = messageType)
        InfoBar(message = state.error, type = InfoType.Warning)

        if (selectedIds.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.e6dl_selection_count, selectedIds.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { selectedIds = emptySet() }) {
                    Text(stringResource(R.string.e6dl_action_cancel))
                }
                Button(onClick = { runDownload() }) {
                    Text(stringResource(R.string.e6dl_action_download))
                }
                OutlinedButton(onClick = { runFavorite() }) {
                    Text(stringResource(R.string.e6dl_action_favorite))
                }
                OutlinedButton(onClick = { runBlacklist() }) {
                    Text(stringResource(R.string.e6dl_action_blacklist))
                }
            }
        }

        when {
            state.posts.isEmpty() && state.loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            state.posts.isEmpty() && !state.loading -> {
                EmptyState(
                    title = if (state.error != null) {
                        stringResource(R.string.e6dl_browse_empty_error_title)
                    } else {
                        stringResource(R.string.e6dl_browse_empty_title)
                    },
                    description = state.error
                        ?: stringResource(R.string.e6dl_browse_empty_desc),
                )
            }

            else -> {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            quality = cfg.quality,
                            selected = post.id in selectedIds,
                            showCheckbox = true,
                            checked = post.id in selectedIds,
                            onClick = { onOpenPost(post) },
                            onLongClick = {
                                selectedIds = if (post.id in selectedIds) {
                                    selectedIds + post.id
                                } else {
                                    selectedIds + post.id
                                }
                            },
                            onCheckedChange = {
                                selectedIds = if (post.id in selectedIds) {
                                    selectedIds - post.id
                                } else {
                                    selectedIds + post.id
                                }
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
                    } else if (state.reachedEnd && state.posts.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                text = stringResource(R.string.e6dl_discover_reached_end),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
