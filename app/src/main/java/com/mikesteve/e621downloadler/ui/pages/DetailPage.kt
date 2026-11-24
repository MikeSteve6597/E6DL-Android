package com.mikesteve.e621downloadler.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.VideoFullscreenActivity
import com.mikesteve.e621downloadler.ui.components.CommentsSection
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.PostVideoPlayer
import com.mikesteve.e621downloadler.ui.components.TagsSection

/**
 * 作品详情（对齐桌面版 `DetailPage`，v1.4.0 起支持左右滑动切换作品）：
 * 大图、评分/分级/收藏数/作者/标签、在浏览器中打开、收藏/取消收藏、下载（下拉选画质）、分享、评论。
 * 视频作品可在应用内用 Media3 播放器播放（支持全屏；编码不支持时提示改用浏览器）。
 *
 * 左右滑动：页面列表来自 `vm.detailPosts`（打开详情时由发现页/收藏页传入的当前列表），
 * 用 `HorizontalPager` 在「上一个/下一个作品」之间切换；列表为空或只有一个作品时退化为普通单页。
 * 滑动切换只更新 VM 里的当前作品（`showDetailPost`），不重建列表，返回行为保持不变。
 *
 * 自适应：单栏时上下排布；详情栏较宽（≥600dp，如平板双栏）时改为左右排布；每个 page 独立套用。
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
    val detailPosts by vm.detailPosts.collectAsState()
    val context = LocalContext.current

    // 可滑动页面列表：优先用 VM 里的上下文列表；为空时至少显示当前作品；
    // 当前作品不在列表里（列表刚好刷新过）时补到末尾，保证不会出现空白页。
    val pages = remember(detailPosts, post) {
        when {
            detailPosts.isEmpty() -> listOf(post)
            detailPosts.any { it.id == post.id } -> detailPosts
            else -> detailPosts + post
        }
    }
    // 初始页 = 传入作品在列表中的位置；之后由 pagerState 维护当前页。
    val initialPage = remember(pages) { pages.indexOfFirst { it.id == post.id }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }

    // 顶部标题用「当前页」作品；翻页后标题跟着变。
    val currentPost = pages.getOrNull(pagerState.currentPage) ?: post

    // 左右滑动落页 → 同步 VM 当前作品（分享/收藏等操作都以它为准）。
    // 只在 id 真的变化时写，避免收藏状态更新（同一 id）时反复覆盖。
    LaunchedEffect(pagerState.currentPage) {
        pages.getOrNull(pagerState.currentPage)?.let { current ->
            if (vm.selectedPost.value?.id != current.id) vm.showDetailPost(current)
        }
    }
    // 外部切换作品（双栏里点列表、恢复选中）时把 pager 滚到对应页；
    // 滑动自身落页时 currentPage 已经正确，这里不会重复滚动。
    LaunchedEffect(post.id) {
        val index = pages.indexOfFirst { it.id == post.id }
        if (index >= 0 && pagerState.currentPage != index) pagerState.scrollToPage(index)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("#${currentPost.id}")
                        if (pages.size > 1) {
                            Text(
                                text = "${pagerState.currentPage + 1}/${pages.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.e6dl_action_back),
                        )
                    }
                },
                actions = {
                    // 视频作品：顶部直接提供全屏入口（独立 Activity，横屏沉浸式）。
                    if (currentPost.isVideo && !currentPost.videoUrl.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, VideoFullscreenActivity::class.java)
                                        .putExtra(VideoFullscreenActivity.EXTRA_URL, currentPost.videoUrl)
                                        .putExtra(VideoFullscreenActivity.EXTRA_TITLE, "#${currentPost.id}"),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.Fullscreen,
                                contentDescription = stringResource(R.string.e6dl_video_fullscreen_play),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            if (pages.size <= 1) {
                // 单个作品（或没有上下文列表）：退化为普通单页，不创建 pager。
                DetailPageBody(
                    vm = vm,
                    post = pages.firstOrNull() ?: post,
                    wide = wide,
                    onLoginRequired = onLoginRequired,
                    onSearchTag = onSearchTag,
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 用作品 id 做 key：收藏状态更新导致列表内容变化时，页面状态不会串页。
                    key = { index -> pages[index].id },
                ) { pageIndex ->
                    DetailPageBody(
                        vm = vm,
                        post = pages[pageIndex],
                        wide = wide,
                        onLoginRequired = onLoginRequired,
                        onSearchTag = onSearchTag,
                    )
                }
            }
        }
    }
}

/**
 * 单个作品页的内容（滑动时每个 page 各一份）。
 *
 * 提示条 / busy 状态按 `post.id` 记忆：翻到别的作品再翻回来，各页的状态互不影响；
 * 评论、视频播放器等子组件本身也按作品 id 记忆状态。
 */
@Composable
private fun DetailPageBody(
    vm: AppViewModel,
    post: Post,
    wide: Boolean,
    onLoginRequired: () -> Unit,
    onSearchTag: (String) -> Unit,
) {
    val cfg by vm.config.collectAsState()
    val context = LocalContext.current
    var message by remember(post.id) { mutableStateOf<String?>(null) }
    var messageType by remember(post.id) { mutableStateOf(InfoType.Info) }
    var busy by remember(post.id) { mutableStateOf(false) }

    // 分享作品链接：标题 + 系统分享面板；没有可处理的应用时在页内提示，不崩溃。
    // 分享面板标题与失败提示要在非 Composable 的 lambda 里使用，先在这里取出字符串资源。
    val shareChooserTitle = stringResource(R.string.e6dl_detail_share_chooser)
    val shareFailedMessage = stringResource(R.string.e6dl_detail_share_failed)
    val favoriteLoginRequiredMessage = stringResource(R.string.e6dl_detail_favorite_login_required)
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

    val onFavorite: () -> Unit = {
        if (!cfg.loggedIn) {
            message = favoriteLoginRequiredMessage
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
    }
    val onOpenBrowser: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://e621.net/posts/${post.id}")),
            )
        }
    }
    // 下载：由下拉菜单选择画质后回调具体 quality（Quality.ORIGINAL/SAMPLE/PREVIEW）。
    val onDownload: (String) -> Unit = { quality ->
        if (!busy) {
            busy = true
            vm.downloadSingle(post, quality) { result ->
                busy = false
                message = result
                messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
            }
        }
    }

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
                onFavorite = onFavorite,
                onOpenBrowser = onOpenBrowser,
                onDownload = onDownload,
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
                onFavorite = onFavorite,
                onOpenBrowser = onOpenBrowser,
                onDownload = onDownload,
                onShare = sharePost,
                onSearchTag = onSearchTag,
            )
        }
    }
}

@Composable
private fun DetailImage(
    post: Post,
    cfg: Config,
    modifier: Modifier = Modifier,
) {
    // 视频作品优先用应用内播放器；SWF 等不支持的编码会落到播放器错误提示，可改用浏览器打开。
    if (post.isVideo && !post.videoUrl.isNullOrBlank()) {
        PostVideoPlayer(post = post, modifier = modifier)
        return
    }
    val url = post.imageUrl(cfg.quality)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 560.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 占位层：无地址/加载失败时也能看出这是图片区域
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Image,
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
    cfg: Config,
    message: String?,
    messageType: InfoType,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onFavorite: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDownload: (String) -> Unit,
    onShare: () -> Unit,
    onSearchTag: (String) -> Unit,
) {
    var downloadMenuOpen by remember(post.id) { mutableStateOf(false) }

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

        // 作者：优先 artist 标签，没有时用上传者名字。
        // 圆形头像（首字母）+ 名字，整行可点 → 以作者名搜索，即「查看该作者更多作品」。
        val author = post.artist.ifBlank { post.uploaderName }
        if (author.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSearchTag(author) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = author.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.e6dl_detail_author_more_works),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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

            // 下载：小型下拉列表（不是弹窗），选原图/采样图/预览图。
            // 视频作品只有预览帧可下，原图/采样图项禁用并标注原因。
            Box {
                OutlinedButton(onClick = { downloadMenuOpen = true }, enabled = !busy) {
                    Text(stringResource(R.string.e6dl_detail_download))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = downloadMenuOpen,
                    onDismissRequest = { downloadMenuOpen = false },
                ) {
                    Quality.labels.forEach { (quality, _) ->
                        val videoUnsupported = post.isVideo && quality != Quality.PREVIEW
                        DropdownMenuItem(
                            text = {
                                // 画质文案统一走资源（数据层 Quality.labels 只提供 key 顺序）
                                val label = stringResource(qualityLabelRes(quality))
                                val text = if (videoUnsupported) {
                                    stringResource(
                                        R.string.e6dl_detail_download_video_unsupported,
                                        label,
                                    )
                                } else {
                                    label
                                }
                                Text(text = text)
                            },
                            enabled = !videoUnsupported && !busy,
                            onClick = {
                                downloadMenuOpen = false
                                onDownload(quality)
                            },
                        )
                    }
                }
            }

            OutlinedButton(onClick = onShare) { Text(stringResource(R.string.e6dl_detail_share)) }
        }
        OutlinedButton(
            onClick = onOpenBrowser,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.e6dl_detail_open_browser)) }

        // 标签：分组展示 + 点标签搜索 + 标签翻译（组件由标签/翻译任务提供，这里只调用）。
        TagsSection(vm = vm, post = post, onSearchTag = onSearchTag)

        // 评论：查看（公开）与发表（需登录）。组件自包含，失败时只会显示提示，不影响详情页。
        CommentsSection(vm = vm, post = post)
    }
}

private fun qualityLabelRes(quality: String): Int = when (quality) {
    Quality.ORIGINAL -> R.string.e6dl_quality_original
    Quality.SAMPLE -> R.string.e6dl_quality_sample
    else -> R.string.e6dl_quality_preview
}
