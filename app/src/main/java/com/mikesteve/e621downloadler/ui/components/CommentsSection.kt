package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Comment
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.friendlyMessage
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.DebugFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 作品评论区块（自包含，可直接插进详情页任意滚动容器里）。
 *
 * 数据层：`vm.api.fetchComments` / `vm.api.postComment`；两者都会先过 [NetworkGate]，
 * 所以单机模式、开发版网络锁都会以 [friendlyMessage] 的中文提示显示，绝不绕过门禁。
 *
 * 设计取舍：
 *  - **不用 LazyColumn**：详情页（单栏与双栏的右侧）本身是 `verticalScroll` 的 Column，
 *    嵌套 LazyColumn 会因"无限高度"直接崩溃；评论量（≤100 条）用普通 Column 完全够用。
 *  - 只在在线模式发起请求；单机模式显示一条警告，不刷屏、不崩溃。
 *  - 未登录时输入框与发送按钮禁用并提示"登录后可发表评论"；发送中禁用防重复提交。
 */
@Composable
fun CommentsSection(
    vm: AppViewModel,
    post: Post,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val online = cfg.mode == Config.MODE_ONLINE
    val scope = rememberCoroutineScope()

    var comments by remember(post.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember(post.id) { mutableStateOf(online) }
    var loaded by remember(post.id) { mutableStateOf(false) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var sending by remember(post.id) { mutableStateOf(false) }
    var sendNotice by remember(post.id) { mutableStateOf<String?>(null) }
    var sendNoticeType by remember(post.id) { mutableStateOf(InfoType.Info) }

    // 在组合期取文本：load / onClick 这类非 @Composable 回调里不能直接调 stringResource。
    val offlineMessage = stringResource(R.string.e6dl_comments_offline)
    val postedMessage = stringResource(R.string.e6dl_comments_posted)

    val load: suspend () -> Unit = {
        if (!online) {
            loading = false
            loaded = true
            comments = emptyList()
            error = offlineMessage
        } else {
            loading = true
            error = null
            try {
                comments = vm.api.fetchComments(cfg, post.id)
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 开发版网络锁 / 单机 / HTTP 错误统一由 friendlyMessage 翻译
                error = friendlyMessage(e)
            } finally {
                loading = false
            }
        }
    }

    // 首帧自动拉取；切换运行模式（离线 ⇄ 在线）后也会重新加载。
    LaunchedEffect(post.id, online) {
        if (DebugFlags.demo) {
            // 仅 debug 演示数据：注入本地示例评论，用于离线截图验证评论列表布局。
            comments = demoSampleComments(post.id)
            loading = false
            loaded = true
            error = null
            return@LaunchedEffect
        }
        load()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (loaded) {
                    stringResource(R.string.e6dl_comments_title_count, comments.size)
                } else {
                    stringResource(R.string.e6dl_comments_title)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { scope.launch { load() } },
                enabled = online && !loading,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.e6dl_comments_refresh),
                )
            }
        }

        // 单机模式 / 网络锁 / HTTP 错误都走这一条 InfoBar
        InfoBar(message = error, type = InfoType.Warning)

        if (loading && comments.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (loaded && !loading && error == null && comments.isEmpty()) {
            Text(
                text = stringResource(R.string.e6dl_comments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        comments.forEach { comment ->
            CommentCard(comment = comment)
        }

        // ---------------------------------------------------------- 发表评论
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // 一旦继续输入，清掉上一次的成功/失败提示，避免用户误以为这次也失败了
                    sendNotice = null
                },
                label = { Text(stringResource(R.string.e6dl_comments_input_label)) },
                placeholder = { Text(stringResource(R.string.e6dl_comments_input_placeholder)) },
                enabled = online && cfg.loggedIn && !sending,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            val hint = when {
                !online -> stringResource(R.string.e6dl_comments_offline_hint)
                !cfg.loggedIn -> stringResource(R.string.e6dl_comments_login_hint)
                sending -> stringResource(R.string.e6dl_comments_sending_hint)
                else -> null
            }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    enabled = online && cfg.loggedIn && !sending && draft.isNotBlank(),
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty() && !sending) {
                            scope.launch {
                                sending = true
                                sendNotice = null
                                try {
                                    val created = vm.api.postComment(cfg, post.id, text)
                                    // 官方返回 201 + Comment：直接插到最前，省一次列表请求
                                    comments = (listOf(created) + comments).distinctBy { it.id }
                                    loaded = true
                                    draft = ""
                                    sendNotice = postedMessage
                                    sendNoticeType = InfoType.Success
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    sendNotice = friendlyMessage(e)
                                    sendNoticeType = InfoType.Warning
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        if (sending) {
                            stringResource(R.string.e6dl_comments_sending)
                        } else {
                            stringResource(R.string.e6dl_comments_send)
                        },
                    )
                }
            }

            InfoBar(message = sendNotice, type = sendNoticeType)
        }
    }
}

/** 单条评论卡片：`作者 · 时间` / 正文（保留换行）/ `★评分`，隐藏评论额外标注。 */
@Composable
private fun CommentCard(comment: Comment, modifier: Modifier = Modifier) {
    val timeLabel = formatCommentTime(comment.createdAt)
    val header = if (timeLabel.isBlank()) comment.authorLabel else "${comment.authorLabel} · $timeLabel"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = comment.body,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "★${comment.score}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comment.isSticky) {
                    Text(
                        text = stringResource(R.string.e6dl_comments_sticky),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (comment.isHidden) {
                    Text(
                        text = stringResource(R.string.e6dl_comments_hidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * e621 的 `created_at` 是带时区的 ISO 字符串（如 `2026-11-20T12:34:56.789-05:00`）。
 * 为兼容 minSdk 24（java.time 需 26+，工程未开 desugaring）这里只做字符串裁剪：
 * `2026-11-20 12:34`。解析不了就原样显示。
 */
private fun formatCommentTime(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || text == "null") return ""
    return if (text.length >= 16 && text[4] == '-' && text[7] == '-') {
        text.replace('T', ' ').take(16)
    } else {
        text
    }
}

/** 仅 debug 演示数据：本地示例评论（不联网），用于离线截图验证评论列表布局。 */
private fun demoSampleComments(postId: Long): List<Comment> = listOf(
    Comment(
        id = 1L,
        postId = postId,
        creatorName = "demo_commenter",
        body = "演示评论：布局验证用，发布版会显示真实评论。",
        score = 12,
        createdAt = "2026-11-21T10:20:00.000+08:00",
        isSticky = true,
        isHidden = false,
    ),
    Comment(
        id = 2L,
        postId = postId,
        creatorName = "another_user",
        body = "示例第二条评论，支持多行文本与较长内容换行显示。",
        score = 3,
        createdAt = "2026-11-21T11:05:00.000+08:00",
        isSticky = false,
        isHidden = false,
    ),
)
