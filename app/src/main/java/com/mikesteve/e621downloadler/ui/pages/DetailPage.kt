package com.mikesteve.e621downloadler.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import kotlinx.coroutines.launch

/**
 * 作品详情（对齐桌面版 `DetailPage`）：
 * 大图、评分/分级/标签、在浏览器中打开、收藏/取消收藏、单张下载。
 * 视频作品只显示预览帧并提示去浏览器（与桌面版一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPage(
    vm: AppViewModel,
    post: Post,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var favorited by remember(post.id) { mutableStateOf(post.isFavorited) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("#${post.id}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoBar(message = message, type = messageType)

            AsyncImage(
                model = post.imageUrl(cfg.quality),
                contentDescription = "作品 #${post.id}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 560.dp),
            )

            if (post.isVideo) {
                Text(
                    text = "这是视频作品，界面中只显示预览帧；完整内容请在浏览器中打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "评分 ${post.score} · 分级 ${post.ratingLabel} · ${post.width}×${post.height} · ${post.ext.uppercase()}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                if (favorited) vm.api.removeFavorite(cfg, post.id) else vm.api.addFavorite(cfg, post.id)
                                favorited = !favorited
                                message = if (favorited) "已添加到收藏" else "已取消收藏"
                                messageType = InfoType.Success
                            } catch (e: Exception) {
                                message = friendlyMessage(e)
                                messageType = InfoType.Warning
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (favorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                    )
                    Text(if (favorited) "  取消收藏" else "  收藏")
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://e621.net/posts/${post.id}"))
                        runCatching { context.startActivity(intent) }
                    },
                ) { Text("在浏览器中打开") }

                OutlinedButton(
                    onClick = {
                        vm.downloadSingle(post, cfg.quality) { result ->
                            message = result
                            messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                        }
                    },
                ) { Text("下载这张") }
            }

            if (post.tagsFlat.isNotEmpty()) {
                Text(
                    text = post.tagsFlat.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
