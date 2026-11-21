package com.mikesteve.e621downloadler.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.CommentsSection
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.PostVideoPlayer

/**
 * 作品详情（对齐桌面版 `DetailPage`）：
 * 大图、评分/分级/收藏数/作者/标签、在浏览器中打开、收藏/取消收藏、单张下载、系统分享、评论。
 * 视频作品可在应用内用 Media3 播放器播放（编码不支持时提示改用浏览器）。
 *
 * 自适应：单栏时上下排布；详情栏较宽（≥600dp，如平板双栏）时改为左右排布。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPage(
    vm: AppViewModel,
    post: Post,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var busy by remember { mutableStateOf(false) }

    // 分享作品链接：标题 + 系统分享面板；没有可处理的应用时在页内提示，不崩溃。
    // 分享面板标题与失败提示要在非 Composable 的 lambda 里使用，先在这里取出字符串资源。
    val shareChooserTitle = stringResource(R.string.e6dl_detail_share_chooser)
    val shareFailedMessage = stringResource(R.string.e6dl_detail_share_failed)
    val sharePost: () -> Unit = {
        val link = "https://e621.net/posts/${post.id}"
        val ok = runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }
            context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
        }.isSuccess
        if (!ok) {
            message = shareFailedMessage
            messageType = InfoType.Warning
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("#${post.id}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.e6dl_action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    DetailImage(post = post, cfg = cfg, modifier = Modifier.weight(1.4f))
                    DetailInfo(
                        vm = vm,
                        post = post,
                        cfg = cfg,
                        message = message,
                        messageType = messageType,
                        busy = busy,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        onFavorite = {
                            if (!cfg.loggedIn) {
                                message = "收藏需要先登录账号。"
                                messageType = InfoType.Warning
                                onLoginRequired()
                            } else if (!busy) {
                                busy = true
                                vm.setFavorite(post, !post.isFavorited) { ok, msg ->
                                    busy = false
                                    message = msg
                                    messageType = if (ok) InfoType.Success else InfoType.Warning
                                }
                            }
                        },
                        onOpenBrowser = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://e621.net/posts/${post.id}")),
                                )
                            }
                        },
                        onDownload = {
                            if (!busy) {
                                busy = true
                                vm.downloadSingle(post, cfg.quality) { result ->
                                    busy = false
                                    message = result
                                    messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                                }
                            }
                        },
                        onShare = sharePost,
                        onSearchTag = onSearchTag,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailImage(post = post, cfg = cfg)
                    DetailInfo(
                        vm = vm,
                        post = post,
                        cfg = cfg,
                        message = message,
                        messageType = messageType,
                        busy = busy,
                        onFavorite = {
                            if (!cfg.loggedIn) {
                                message = "收藏需要先登录账号。"
                                messageType = InfoType.Warning
                                onLoginRequired()
                            } else if (!busy) {
                                busy = true
                                vm.setFavorite(post, !post.isFavorited) { ok, msg ->
                                    busy = false
                                    message = msg
                                    messageType = if (ok) InfoType.Success else InfoType.Warning
                                }
                            }
                        },
                        onOpenBrowser = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://e621.net/posts/${post.id}")),
                                )
                            }
                        },
                        onDownload = {
                            if (!busy) {
                                busy = true
                                vm.downloadSingle(post, cfg.quality) { result ->
                                    busy = false
                                    message = result
                                    messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
                                }
                            }
                        },
                        onShare = sharePost,
                        onSearchTag = onSearchTag,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailImage(
    post: Post,
    cfg: com.mikesteve.e621downloadler.data.Config,
    modifier: Modifier = Modifier,
) {
    // 视频作品优先用应用内播放器；SWF 等不支持的编码会落到播放器错误提示，可改用浏览器打开。
    if (post.isVideo && !post.videoUrl.isNullOrBlank()) {
        PostVideoPlayer(post = post, modifier = modifier)
        return
    }
    val url = post.imageUrl(cfg.quality)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 560.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        // 占位层：无地址/加载失败时也能看出这是图片区域
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = if (url == null) {
                    stringResource(R.string.e6dl_detail_no_image)
                } else {
                    stringResource(R.string.e6dl_detail_image_loading)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.e6dl_detail_image_desc, post.id),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailInfo(
    vm: AppViewModel,
    post: Post,
    cfg: com.mikesteve.e621downloadler.data.Config,
    message: String?,
    messageType: InfoType,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onFavorite: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onSearchTag: (String) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoBar(message = message, type = messageType)

        val qualityText = stringResource(qualityLabelRes(cfg.quality))
        Text(
            text = stringResource(R.string.e6dl_detail_stats, post.score, post.favCount, post.ratingLabel),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.e6dl_detail_specs,
                post.width,
                post.height,
                post.ext.uppercase(),
                qualityText,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 作者：优先 artist 标签，没有时用上传者名字
        val author = post.artist.ifBlank { post.uploaderName }
        if (author.isNotBlank()) {
            Text(
                text = stringResource(R.string.e6dl_detail_author, author),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (post.commentCount > 0) {
            Text(
                text = stringResource(R.string.e6dl_detail_comment_count, post.commentCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (post.isVideo) {
            Text(
                text = stringResource(R.string.e6dl_detail_video_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 用 FlowRow 而不是 Row：360dp 窄屏放不下时按钮整体换到下一行，不会被挤压变形。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFavorite, enabled = !busy) {
                Icon(
                    imageVector = if (post.isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                )
                Text(
                    if (post.isFavorited) {
                        stringResource(R.string.e6dl_detail_unfavorite)
                    } else {
                        stringResource(R.string.e6dl_detail_favorite)
                    },
                )
            }
            OutlinedButton(onClick = onDownload, enabled = !busy) {
                Text(
                    if (post.isVideo) {
                        stringResource(R.string.e6dl_detail_download_preview)
                    } else {
                        stringResource(R.string.e6dl_detail_download_this)
                    },
                )
            }
            OutlinedButton(onClick = onShare) { Text(stringResource(R.string.e6dl_detail_share)) }
        }
        OutlinedButton(
            onClick = onOpenBrowser,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.e6dl_detail_open_browser)) }

        if (post.tagsFlat.isNotEmpty()) {
            Text(
                stringResource(R.string.e6dl_detail_tags_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // 标签多时截断到 120 个，避免一次组合出上千个 chip；FlowRow 保证 360dp 下正常换行。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                post.tagsFlat.take(120).forEach { tag ->
                    AssistChip(
                        onClick = { onSearchTag(tag) },
                        label = {
                            Text(
                                text = tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        // 评论：查看（公开）与发表（需登录）。组件自包含，失败时只会显示提示，不影响详情页。
        CommentsSection(vm = vm, post = post)
    }
}

private fun qualityLabelRes(quality: String): Int = when (quality) {
    "1" -> R.string.e6dl_quality_original
    "2" -> R.string.e6dl_quality_sample
    else -> R.string.e6dl_quality_preview
}
