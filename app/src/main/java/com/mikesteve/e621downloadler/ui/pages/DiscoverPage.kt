package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.PostCard
import kotlinx.coroutines.launch

private val ORDER_OPTIONS = listOf(
    "order:score" to "热门",
    "order:newest" to "最新",
    "order:random" to "随机",
)

private const val PAGE_LIMIT = 24

/**
 * 发现页（对齐桌面版 `DiscoverPage`）：
 * 排序三选一 + 关键词过滤 + 瀑布流触底加载；黑名单**仅登录时生效**（保持与桌面版一致）。
 */
@Composable
fun DiscoverPage(
    vm: AppViewModel,
    onOpenPost: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    var order by remember { mutableStateOf(ORDER_OPTIONS.first().first) }
    var keyword by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun loadPage(reset: Boolean) {
        if (loading) return
        if (reset) {
            posts = emptyList()
            reachedEnd = false
            error = null
        }
        if (reachedEnd) return
        loading = true
        try {
            val page = posts.size / PAGE_LIMIT + 1
            val tags = listOf(order, keyword.trim()).filter { it.isNotEmpty() }
            val blacklist = if (cfg.loggedIn) cfg.blacklist else emptyList()
            val batch = vm.api.fetchPosts(cfg, tags = tags, blacklist = blacklist, limit = PAGE_LIMIT, page = page)
            if (batch.isEmpty()) {
                reachedEnd = true
            } else {
                posts = posts + batch
                if (batch.size < PAGE_LIMIT) reachedEnd = true
            }
        } catch (e: Exception) {
            error = friendlyMessage(e)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(order) { loadPage(reset = true) }

    // 触底加载
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= posts.size - 4
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { nearEnd }.collect { if (it) scope.launch { loadPage(reset = false) } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("关键词过滤（可留空）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { scope.launch { loadPage(reset = true) } }),
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ORDER_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = order == value,
                        onClick = { order = value },
                        label = { Text(label) },
                    )
                }
            }
            InfoBar(
                message = error ?: if (!cfg.loggedIn && cfg.blacklist.isNotEmpty()) {
                    "游客模式下发现页不使用黑名单（与桌面版一致）"
                } else {
                    null
                },
            )
        }

        if (posts.isEmpty() && !loading && error == null) {
            EmptyState(
                title = "暂无推荐内容",
                description = "检查网络后重试，或切换推荐排序。开发构建禁止联网，属预期。",
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(posts, key = { it.id }) { post ->
                    PostCard(post = post, quality = cfg.quality, onClick = { onOpenPost(post) })
                }
                if (loading) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (reachedEnd && posts.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "已经到底了",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
