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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 我的收藏（对齐桌面版 `FavoritesPage`）：
 * 登录后逐页拉取（每页 200、最多 25 页、按 id 去重）、按黑名单过滤、网格展示、支持批量下载。
 * 数据存在 ViewModel，切换尺寸/旋转不会丢；从详情页修改收藏状态后会标记 dirty 并在回到本页时刷新。
 *
 * v1.2 起支持多选批量操作：**取消收藏 / 拉黑 / 下载选中**，与发现页的批量模式保持一致。
 */
@Composable
fun FavoritesPage(
    vm: AppViewModel,
    onOpenPost: (Post) -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val state by vm.favorites.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var showBlacklistConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // 在组合期取文本：onClick / 本地函数等非 @Composable 作用域里不能直接调 stringResource。
    val selectRemoveFirstMessage = stringResource(R.string.e6dl_fav_select_remove_first)
    val selectBlacklistFirstMessage = stringResource(R.string.e6dl_select_blacklist_first)
    val selectDownloadFirstMessage = stringResource(R.string.e6dl_select_download_first)

    LaunchedEffect(cfg.loggedIn, state.dirty) {
        if (cfg.loggedIn) vm.loadFavorites()
    }

    fun runBatchDownload(posts: List<Post>) {
        message = context.getString(R.string.e6dl_batch_download_start, posts.size)
        messageType = InfoType.Info
        vm.downloadPosts(posts, cfg.quality) { result ->
            message = result
            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
        }
        vm.setFavoritesSelectionMode(false)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { vm.loadFavorites(force = true) },
                enabled = !state.loading && !state.busy && cfg.loggedIn,
            ) { Text(stringResource(R.string.e6dl_action_refresh)) }
            Button(
                onClick = {
                    vm.downloadPosts(state.posts, cfg.quality) { result ->
                        message = result
                        messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                    }
                },
                enabled = state.posts.isNotEmpty() && !state.busy,
            ) { Text(stringResource(R.string.e6dl_fav_download_loaded)) }
            Text(
                text = if (state.posts.isEmpty()) "" else stringResource(R.string.e6dl_fav_count, state.posts.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { vm.setFavoritesSelectionMode(!state.selectionMode) },
                // 未登录时收藏页只有登录引导、没有可批量的作品，按钮自然禁用
                enabled = state.posts.isNotEmpty() && !state.busy,
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
        }

        if (state.selectionMode) {
            FavoritesSelectionBar(
                count = state.selected.size,
                loggedIn = cfg.loggedIn,
                busy = state.busy,
                onSelectAll = { vm.selectAllFavorites() },
                onRemoveFavorite = {
                    if (state.selected.isEmpty()) {
                        message = selectRemoveFirstMessage
                        messageType = InfoType.Warning
                    } else {
                        message = context.getString(R.string.e6dl_fav_removing, state.selected.size)
                        messageType = InfoType.Info
                        vm.batchRemoveFavorite(state.selectedPosts) { result ->
                            message = result
                            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                        }
                    }
                },
                onBlacklist = {
                    if (state.selected.isEmpty()) {
                        message = selectBlacklistFirstMessage
                        messageType = InfoType.Warning
                    } else {
                        showBlacklistConfirm = true
                    }
                },
                onDownload = {
                    if (state.selected.isEmpty()) {
                        message = selectDownloadFirstMessage
                        messageType = InfoType.Warning
                    } else {
                        runBatchDownload(state.selectedPosts)
                    }
                },
                onCancel = { vm.setFavoritesSelectionMode(false) },
            )
        }

        InfoBar(
            message = message ?: state.message,
            type = if (state.messageIsError) InfoType.Warning else messageType,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when {
            // 演示数据模式下即使未登录也展示（仅 debug 启动参数，不会出现在发布版）
            !cfg.loggedIn && !state.demo -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    title = stringResource(R.string.e6dl_fav_login_required_title),
                    description = stringResource(R.string.e6dl_fav_login_required_desc),
                    action = {
                        Button(onClick = onOpenLogin) { Text(stringResource(R.string.e6dl_action_login)) }
                    },
                )
            }

            state.loading && state.posts.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.posts.isEmpty() -> EmptyState(
                title = stringResource(R.string.e6dl_fav_empty_title),
                description = stringResource(R.string.e6dl_fav_empty_desc),
            )

            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
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
                            vm.setFavoritesSelectionMode(true)
                            vm.toggleFavoritesSelected(post.id)
                        },
                        onClick = { onOpenPost(post) },
                        onLongClick = {
                            if (!state.selectionMode) vm.setFavoritesSelectionMode(true)
                            vm.toggleFavoritesSelected(post.id)
                        },
                    )
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
                    stringResource(R.string.e6dl_batch_blacklist_desc_favorites, tags.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlacklistConfirm = false
                    vm.batchBlacklist(state.selectedPosts) { result ->
                        message = result
                        messageType = InfoType.Success
                    }
                    vm.setFavoritesSelectionMode(false)
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
private fun FavoritesSelectionBar(
    count: Int,
    loggedIn: Boolean,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onRemoveFavorite: () -> Unit,
    onBlacklist: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.e6dl_selection_count, count), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = loggedIn && !busy) {
                Text(stringResource(R.string.e6dl_action_select_all))
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.e6dl_action_exit)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onRemoveFavorite,
                enabled = loggedIn && !busy && count > 0,
            ) { Text(stringResource(R.string.e6dl_fav_batch_remove)) }
            TextButton(
                onClick = onBlacklist,
                enabled = loggedIn && !busy && count > 0,
            ) { Text(stringResource(R.string.e6dl_action_blacklist)) }
            TextButton(
                onClick = onDownload,
                enabled = loggedIn && count > 0,
            ) { Text(stringResource(R.string.e6dl_action_download_selected)) }
        }
    }
}
