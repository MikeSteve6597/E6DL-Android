package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Comment
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.DText
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.buildReplyQuote
import com.mikesteve.e621downloadler.net.TranslationKind
import com.mikesteve.e621downloadler.net.TranslationRepository
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
 * v1.4.0 起评论正文不再用 `Text` 原样显示源码，而是交给 [DText] 解析 + [DTextContent] 渲染：
 *  - `[quote]…[/quote]` → 引用对话块（左侧主色竖条 + 浅色底 + `名字 说：` 头部，支持嵌套）；
 *  - `[b]/[i]/[u]/[s]/[spoiler]/[code]`、`"名字":/users/id` 用户链接与裸 URL → 富文本；
 *  - 界面上不会再出现 `[quote]`、`[/b]` 这类标记。
 *
 * 另外两条交互：
 *  - **回复**：每条评论右下角"回复"把站点同款 `[quote]"名字":/users/id said: 正文[/quote]`
 *    插到输入框最前面（已有正文保留在后面），并显示"正在回复 @名字"小条；发送成功/取消后清空引用状态。
 *  - **长按翻译**：长按评论卡片弹出 [DropdownMenu]（不是全屏弹窗），项为"翻译评论 / 显示原文"；
 *    译文显示在原文下方（浅色容器 + "译文"标签），按 comment.id 缓存，重复长按不会重复请求。
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
    // 翻译需要 Context（本地机翻模型 / 在线请求都从 TextTranslation 层拿），在组合期取一次
    val context = LocalContext.current

    var comments by remember(post.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember(post.id) { mutableStateOf(online) }
    var loaded by remember(post.id) { mutableStateOf(false) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var sending by remember(post.id) { mutableStateOf(false) }
    var sendNotice by remember(post.id) { mutableStateOf<String?>(null) }
    var sendNoticeType by remember(post.id) { mutableStateOf(InfoType.Info) }

    // ---------------------------------------------------------------- 回复状态
    /** 当前正在回复的评论；null 表示普通发表。 */
    var replyingTo by remember(post.id) { mutableStateOf<Comment?>(null) }

    // ---------------------------------------------------------------- 翻译状态
    /** comment.id → 译文：按 id 缓存，避免同一评论反复请求。 */
    val translations = remember(post.id) { mutableStateMapOf<Long, String>() }
    /** comment.id → 是否翻译中（防止重复点击）。 */
    val translatingIds = remember(post.id) { mutableStateMapOf<Long, Boolean>() }
    /** 当前正在"显示译文"的评论 id 集合（"显示原文"只是隐藏，不丢掉缓存）。 */
    var visibleTranslationIds by remember(post.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var translateNotice by remember(post.id) { mutableStateOf<String?>(null) }

    // 在组合期取文本：load / onClick 这类非 @Composable 回调里不能直接调 stringResource。
    val offlineMessage = stringResource(R.string.e6dl_comments_offline)
    val postedMessage = stringResource(R.string.e6dl_comments_posted)
    val translateEmptyMessage = stringResource(R.string.e6dl_comments_translate_empty)

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
            // 仅 debug 演示数据：注入本地示例评论，用于离线截图验证评论列表/引用块布局。
            comments = demoSampleComments(post.id)
            loading = false
            loaded = true
            error = null
            return@LaunchedEffect
        }
        load()
    }

    // 长按菜单「翻译评论」：失败走 InfoBar + friendlyMessage；成功写入缓存并立即展示。
    val requestTranslation: (Comment) -> Unit = { target ->
        when {
            translations.containsKey(target.id) -> {
                // 已翻译过：只重新显示，不重复请求（"显示原文"后再次长按走这里）
                visibleTranslationIds = visibleTranslationIds + target.id
            }

            translatingIds[target.id] == true -> Unit // 正在翻译，忽略重复触发

            else -> scope.launch {
                translatingIds[target.id] = true
                translateNotice = null
                try {
                    // 用纯文本翻译：避免译文里出现 [quote]/[b] 等 DText 源码。
                    val sourceText = DText.plainText(DText.parse(target.body)).trim()
                    val translated = TranslationRepository.translate(
                        context,
                        cfg,
                        sourceText,
                        TranslationKind.COMMENT,
                    )
                    if (translated.isBlank()) {
                        translateNotice = translateEmptyMessage
                    } else {
                        translations[target.id] = translated
                        visibleTranslationIds = visibleTranslationIds + target.id
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络锁 / 单机 / 翻译服务异常都由 TranslationRepository 抛标准异常
                    translateNotice = friendlyMessage(e)
                } finally {
                    translatingIds.remove(target.id)
                }
            }
        }
    }

    // 取消回复：尽量把之前插进输入框的引用删掉，保留用户自己写的内容。
    val cancelReply: () -> Unit = {
        val target = replyingTo
        if (target != null) {
            draft = stripReplyQuote(draft, target)
            replyingTo = null
        }
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

        // 翻译失败提示（成功不弹条，避免打断阅读）
        InfoBar(message = translateNotice, type = InfoType.Warning)

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
            CommentCard(
                comment = comment,
                translation = translations[comment.id],
                showTranslation = comment.id in visibleTranslationIds,
                translating = translatingIds[comment.id] == true,
                replyEnabled = (online && cfg.loggedIn && !sending) || DebugFlags.demo,
                onReply = {
                    if ((online && cfg.loggedIn) || DebugFlags.demo) {
                        // 已有回复目标时先撤掉旧引用，避免连续点两条评论堆两份引用
                        val previous = replyingTo
                        val base = if (previous != null) stripReplyQuote(draft, previous) else draft
                        val quote = buildReplyQuote(comment)
                        draft = if (base.isBlank()) quote else quote + base
                        replyingTo = comment
                        sendNotice = null
                    }
                },
                onTranslate = { requestTranslation(comment) },
                onHideTranslation = {
                    visibleTranslationIds = visibleTranslationIds - comment.id
                },
            )
        }

        // ---------------------------------------------------------- 发表评论
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "正在回复 @名字" 小条 + 取消按钮
            val replyTarget = replyingTo
            if (replyTarget != null && ((online && cfg.loggedIn) || DebugFlags.demo)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.e6dl_comments_replying_to, replyTarget.authorLabel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = cancelReply) {
                            Text(stringResource(R.string.e6dl_action_cancel))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // 一旦继续输入，清掉上一次的成功/失败提示，避免用户误以为这次也失败了
                    sendNotice = null
                },
                label = { Text(stringResource(R.string.e6dl_comments_input_label)) },
                placeholder = { Text(stringResource(R.string.e6dl_comments_input_placeholder)) },
                enabled = (online && cfg.loggedIn && !sending) || DebugFlags.demo,
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
                                    // 发送成功：清空引用状态（"正在回复 @xxx"小条随之消失）
                                    replyingTo = null
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

/**
 * 单条评论卡片：
 * `作者 · 时间` / DText 正文（引用块、格式、链接）/ 可选译文 / `★评分 + 状态 + 回复`。
 *
 * 长按卡片弹出 [DropdownMenu]（翻译评论 / 显示原文），单击不做事——
 * 卡片没有详情可进，保留长按入口即可，也避免误触。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentCard(
    comment: Comment,
    translation: String?,
    showTranslation: Boolean,
    translating: Boolean,
    replyEnabled: Boolean,
    onReply: () -> Unit,
    onTranslate: () -> Unit,
    onHideTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val timeLabel = formatCommentTime(comment.createdAt)
    val header = if (timeLabel.isBlank()) comment.authorLabel else "${comment.authorLabel} · $timeLabel"
    // 正文按 body 缓存节点树：组合重组（比如输入评论）不必反复解析
    val nodes = remember(comment.body) { DText.parse(comment.body) }

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = { /* 评论卡片没有详情页；单击保留空实现 */ },
                    onLongClick = { menuExpanded = true },
                ),
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

                // DText 正文：引用块 / 代码块 / 富文本，不再显示 [quote] 等源码
                DTextContent(nodes = nodes)

                // 译文：原文下方浅色容器 + "译文"标签
                if (showTranslation && !translation.isNullOrBlank()) {
                    TranslationBox(text = translation)
                }
                if (translating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.e6dl_comments_translating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

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
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onReply,
                        enabled = replyEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.e6dl_comments_reply),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        // 长按弹出的小菜单（不是全屏 Dialog）
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.e6dl_comments_translate_comment)) },
                enabled = !translating,
                onClick = {
                    menuExpanded = false
                    onTranslate()
                },
            )
            if (showTranslation && translation != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.e6dl_comments_show_original)) },
                    onClick = {
                        menuExpanded = false
                        onHideTranslation()
                    },
                )
            }
        }
    }
}

/** 译文容器：浅色底 + "译文"标签 + 正文（与原文明确区分，长按"显示原文"可隐藏）。 */
@Composable
private fun TranslationBox(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.e6dl_comments_translation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 取消回复时把之前插入的引用前缀去掉；用户已经改过引用内容（前缀不再匹配）时保持原文，
 * 只清掉"正在回复"状态，不误删用户输入。
 */
private fun stripReplyQuote(draft: String, comment: Comment): String {
    val prefix = buildReplyQuote(comment)
    return if (draft.startsWith(prefix)) draft.removePrefix(prefix) else draft
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

/**
 * 仅 debug 演示数据：本地示例评论（不联网），用于离线截图验证：
 *  - 引用对话块（含 `"richard noggin99":/users/1003100 said:` 站点同款格式）；
 *  - 嵌套引用、粗体/斜体/删除线/代码块、用户链接与裸链接；
 *  - 长按翻译菜单与"回复"按钮。
 */
private fun demoSampleComments(postId: Long): List<Comment> = listOf(
    Comment(
        id = 1L,
        postId = postId,
        creatorId = 1003100L,
        creatorName = "richard noggin99",
        body = "演示评论：这条用于验证 DText 引用块与长按翻译菜单（发布版会显示真实评论）。",
        score = 12,
        createdAt = "2026-11-21T10:20:00.000+08:00",
        isSticky = true,
        isHidden = false,
    ),
    Comment(
        id = 2L,
        postId = postId,
        creatorId = 2002L,
        creatorName = "another_user",
        body = "[quote]\"richard noggin99\":/users/1003100 said: 演示评论：这条用于验证 DText 引用块与长按翻译菜单（发布版会显示真实评论）。[/quote]\n" +
            "[quote]\"demo_commenter\":/users/42 said: 引用还能再套一层：[quote]\"another_user\":/users/2002 said: 嵌套引用。[/quote] 就这样。[/quote]\n" +
            "另外支持 [b]粗体[/b]、[i]斜体[/i]、[u]下划线[/u]、[s]删除线[/s]、[code]code_block()[/code]，" +
            "以及用户链接 \"richard noggin99\":/users/1003100 和裸链接 https://e621.net/posts/123 。",
        score = 3,
        createdAt = "2026-11-21T11:05:00.000+08:00",
        isSticky = false,
        isHidden = false,
    ),
    Comment(
        id = 3L,
        postId = postId,
        creatorId = 42L,
        creatorName = "demo_commenter",
        body = "示例第三条评论：支持多行文本与较长内容换行显示。\n第二行：用于离线截图验证布局。",
        score = 0,
        createdAt = "2026-11-21T11:30:00.000+08:00",
        isSticky = false,
        isHidden = false,
    ),
)
