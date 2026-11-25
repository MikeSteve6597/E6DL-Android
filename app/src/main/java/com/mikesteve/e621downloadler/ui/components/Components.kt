package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.KeywordSuggestion
import com.mikesteve.e621downloadler.net.SearchKeyword

/** 信息条：替代桌面版的 InfoBar（图标 + 文案，颜色随类型）。 */
@Composable
fun InfoBar(message: String?, type: InfoType = InfoType.Info, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    val container = when (type) {
        InfoType.Info -> MaterialTheme.colorScheme.secondaryContainer
        InfoType.Success -> MaterialTheme.colorScheme.tertiaryContainer
        InfoType.Warning -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (type) {
        InfoType.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        InfoType.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        InfoType.Warning -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (type == InfoType.Warning) Icons.Outlined.Warning else Icons.Outlined.Info,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

enum class InfoType { Info, Success, Warning }

/**
 * Pixiv 式关键词搜索栏（v1.5.2）：
 *
 * - 已选关键词以**中文气泡**显示在搜索栏内部，每个气泡右侧有 x 可删除；
 * - 下方是输入区，输入内容只用于出推荐 / 拼气泡，不直接参与展示；
 * - 提交（键盘搜索键或右侧放大镜）时由调用方把气泡的英文 tag 交给后端。
 *
 * 候选列表（[KeywordSuggestionList]）由调用方渲染在搜索栏下方。
 */
@Composable
fun KeywordSearchBar(
    keywords: List<SearchKeyword>,
    onRemoveKeyword: (SearchKeyword) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    // v1.5.2.1：**整个气泡都是删除热区**（不再只有右侧 22dp 的小 x）。
    // 真机上手指稍偏就会点空、误以为"气泡删不掉"，所以 x 只作为视觉提示，点击气泡任意位置都删除。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (keywords.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    keywords.forEach { keyword ->
                        SearchKeywordChip(
                            label = keyword.label,
                            onDelete = { onRemoveKeyword(keyword) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = onSubmit) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.e6dl_action_search),
                    )
                }
            }
        }
    }
}

/**
 * 搜索栏内的中文关键词气泡（v1.5.2）。
 *
 * 整颗气泡可点击删除（v1.5.2.1 修复：真机上只有小 x 可点，容易点空导致"删不掉"）；
 * 右侧 x 保留为视觉提示。气泡高度约 40dp，方便手指点击。
 */
@Composable
private fun SearchKeywordChip(label: String, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onDelete),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.e6dl_keyword_remove, label),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 关键词实时推荐列表（v1.5.1）：输入框下方展示「中文 - 英文」对照，点击把英文标签写回输入框。
 *
 * 只负责展示与点击回调；匹配逻辑在 `net/KeywordSuggestions` 的纯函数里，便于单测。
 * 条数由调用方限制（默认 6 条），因此这里用普通 Column 即可，放进可滚动页面也不会嵌套滚动。
 */
@Composable
fun KeywordSuggestionList(
    suggestions: List<KeywordSuggestion>,
    onPick: (KeywordSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column {
            suggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.e6dl_keyword_suggestion_pair,
                            suggestion.chinese,
                            suggestion.english,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index != suggestions.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 空状态：图标 + 标题 + 说明 + 可选操作。 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** 分区卡片：设置页/搜索页复用。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/**
 * 作品卡片：图片按原图宽高比展示，右下角标评分与分级，下方一行作者。
 * 网格列数由外层 `GridCells.Adaptive` 决定，天然适配手机/平板/折叠屏。
 *
 * [selected] 为 null 表示当前不可多选；非 null 时显示选择描边。长按进入选择模式。
 * [showCheckbox] 为 true 时常驻左上角复选框：点击只切换选中，不会打开详情；
 * 由调用方保证 `checked` 与多选状态一致（发现页/收藏页批量操作入口）。
 * 图片为空（演示数据/离线）时显示占位块，保证布局仍可验证。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(
    post: Post,
    quality: String,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showCheckbox: Boolean = false,
    checked: Boolean = false,
    onCheckedChange: (() -> Unit)? = null,
) {
    val ratio = if (post.width > 0 && post.height > 0) {
        (post.width.toFloat() / post.height.toFloat()).coerceIn(0.5f, 2.0f)
    } else {
        1f
    }
    val shape = MaterialTheme.shapes.medium
    val imageUrl = post.imageUrl(quality)
    val borderModifier = if (selected == true) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .then(borderModifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 占位层：无图/加载失败时保持卡片尺寸可读
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "#${post.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = post.id.toString(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (post.isVideo) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.e6dl_post_video),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (showCheckbox) {
                // 常驻复选框：位于左上角，点击只切换选中（Checkbox 自己消费点击事件，
                // 不会冒泡到卡片打开详情）。
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onCheckedChange?.invoke() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            } else if (selected != null) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (selected) {
                            stringResource(R.string.e6dl_post_selected)
                        } else {
                            stringResource(R.string.e6dl_post_unselected)
                        },
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(2.dp).size(20.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "#${post.id}", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "★${post.score} · ${post.ratingLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 作者行：优先 artist 标签，其次上传者；都为空时整行不显示（保持卡片紧凑）。
        val author = post.artist.ifBlank { post.uploaderName }
        if (author.isNotBlank()) {
            Text(
                text = stringResource(R.string.e6dl_detail_author, author),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }
    }
}
