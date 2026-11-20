package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikesteve.e621downloadler.data.Post

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
 * 作品卡片：图片按原图宽高比展示，右下角标评分与分级。
 * 网格列数由外层 `GridCells.Adaptive` 决定，天然适配手机/平板/折叠屏。
 */
@Composable
fun PostCard(
    post: Post,
    quality: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ratio = if (post.width > 0 && post.height > 0) {
        (post.width.toFloat() / post.height.toFloat()).coerceIn(0.5f, 2.0f)
    } else {
        1f
    }
    Card(
        modifier = modifier.fillMaxSize(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box {
            AsyncImage(
                model = post.imageUrl(quality),
                contentDescription = post.id.toString(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
            )
            if (post.isVideo) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "视频",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
    }
}
