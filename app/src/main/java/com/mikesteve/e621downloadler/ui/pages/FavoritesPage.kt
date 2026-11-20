package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.PostCard
import kotlinx.coroutines.launch

/**
 * 我的收藏（对齐桌面版 `FavoritesPage`）：登录后逐页拉取、网格展示、支持批量下载。
 */
@Composable
fun FavoritesPage(
    vm: AppViewModel,
    onOpenPost: (Post) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        if (!cfg.loggedIn) return
        loading = true
        message = null
        try {
            posts = vm.api.fetchAllFavorites(cfg) { count ->
                message = "已加载 $count 项…"
            }
            if (posts.isEmpty()) message = "还没有收藏"
        } catch (e: Exception) {
            message = friendlyMessage(e)
            messageType = InfoType.Warning
        } finally {
            loading = false
        }
    }

    LaunchedEffect(cfg.loggedIn) { if (cfg.loggedIn) reload() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !loading && cfg.loggedIn) {
                Text("刷新")
            }
            Button(
                onClick = {
                    vm.downloadPosts(posts, cfg.quality) { result ->
                        message = result
                        messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                    }
                },
                enabled = posts.isNotEmpty(),
            ) { Text("下载已加载") }
        }

        InfoBar(message = message, type = messageType, modifier = Modifier.padding(horizontal = 16.dp))

        when {
            !cfg.loggedIn -> EmptyState(
                title = "尚未登录",
                description = "登录 e621 账号后即可查看并管理你的收藏。",
            )
            loading && posts.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            posts.isEmpty() -> EmptyState(
                title = "还没有收藏",
                description = "在作品详情页点击「收藏」即可加入这里。",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(posts, key = { it.id }) { post ->
                    PostCard(post = post, quality = cfg.quality, onClick = { onOpenPost(post) })
                }
            }
        }
    }
}
