package com.mikesteve.e621downloadler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.DTextNode
import com.mikesteve.e621downloadler.data.DTextStyle

/**
 * 把 [DTextNode] 节点树渲染成 Compose UI（评论正文用）。
 *
 * 关键点：**评论里不再出现 `[quote]` / `[b]` 这类源码**。
 *  - `[quote]` → 引用对话块：左侧主色竖条 + 浅色背景 + 内层正文；如果引用里带了
 *    `"名字":/users/id said:` 前缀，会显示 `名字 说：` 小标题；嵌套引用自然缩进在父块内部。
 *  - `[code]` → 等宽字体 + 浅色容器。
 *  - `[b]/[i]/[u]/[s]/[spoiler]` → 富文本 SpanStyle（可嵌套）。
 *  - 链接只显示可读的显示名（主色），不把 `/users/123` 或完整 URL 源码露出来。
 *
 * 布局上刻意不用 LazyColumn / 复杂排版：评论正文通常很短，普通 Column + Text 足够，
 * 而且可以直接嵌在详情页已有的 verticalScroll 里（避免嵌套滚动容器崩溃）。
 */
@Composable
fun DTextContent(
    nodes: List<DTextNode>,
    modifier: Modifier = Modifier,
) {
    DTextBlocks(nodes = nodes, modifier = modifier, depth = 0)
}

@Composable
private fun DTextBlocks(
    nodes: List<DTextNode>,
    modifier: Modifier,
    depth: Int,
) {
    val blocks = remember(nodes) { splitIntoBlocks(nodes) }
    if (blocks.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is DTextRenderBlock.Inline -> InlineRun(block.nodes)
                is DTextRenderBlock.Quote -> QuoteBlock(block.node, depth)
                is DTextRenderBlock.Code -> CodeBlock(block.node)
            }
        }
    }
}

/** 渲染分组：连续的行内节点合并成一段 [androidx.compose.ui.text.AnnotatedString]，块级节点单独渲染。 */
private sealed interface DTextRenderBlock {
    data class Inline(val nodes: List<DTextNode>) : DTextRenderBlock
    data class Quote(val node: DTextNode.Quote) : DTextRenderBlock
    data class Code(val node: DTextNode.Code) : DTextRenderBlock
}

private fun splitIntoBlocks(nodes: List<DTextNode>): List<DTextRenderBlock> {
    val blocks = ArrayList<DTextRenderBlock>()
    val inline = ArrayList<DTextNode>()

    fun flushInline() {
        if (inline.isNotEmpty()) {
            blocks.add(DTextRenderBlock.Inline(inline.toList()))
            inline.clear()
        }
    }

    for (node in nodes) {
        when (node) {
            is DTextNode.Quote -> {
                flushInline()
                blocks.add(DTextRenderBlock.Quote(node))
            }

            is DTextNode.Code -> {
                flushInline()
                blocks.add(DTextRenderBlock.Code(node))
            }

            else -> inline.add(node)
        }
    }
    flushInline()
    return blocks
}

@Composable
private fun InlineRun(nodes: List<DTextNode>) {
    val linkColor = MaterialTheme.colorScheme.primary
    val spoilerBackground = MaterialTheme.colorScheme.surfaceVariant
    val spoilerContent = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    val text = remember(nodes, linkColor, spoilerBackground, spoilerContent, codeBackground) {
        buildAnnotatedString {
            appendDText(
                nodes = nodes,
                linkColor = linkColor,
                spoilerBackground = spoilerBackground,
                spoilerContent = spoilerContent,
                codeBackground = codeBackground,
            )
        }
    }
    if (text.isEmpty()) return
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun QuoteBlock(node: DTextNode.Quote, depth: Int) {
    val barColor = MaterialTheme.colorScheme.primary
    // 嵌套越深背景越浅，配合内层缩进形成层级感
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (depth == 0) 0.5f else 0.32f,
    )
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // 左侧 4dp 主色竖条：e621 引用对话块的视觉标识
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val author = node.authorName
                if (!author.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.e6dl_dtext_quote_said, author),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DTextBlocks(nodes = node.children, modifier = Modifier, depth = depth + 1)
            }
        }
    }
}

@Composable
private fun CodeBlock(node: DTextNode.Code) {
    val text = node.text.trim('\n')
    if (text.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 节点树 → AnnotatedString
// ---------------------------------------------------------------------------

private fun AnnotatedString.Builder.appendDText(
    nodes: List<DTextNode>,
    linkColor: Color,
    spoilerBackground: Color,
    spoilerContent: Color,
    codeBackground: Color,
) {
    for (node in nodes) {
        when (node) {
            is DTextNode.Text -> append(node.text)

            // 链接只显示显示名：不把 /users/123 或完整 URL 暴露在正文里
            is DTextNode.Link -> withStyle(SpanStyle(color = linkColor)) {
                append(node.label)
            }

            is DTextNode.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) {
                append(node.text)
            }

            is DTextNode.Quote -> appendDText(
                nodes = node.children,
                linkColor = linkColor,
                spoilerBackground = spoilerBackground,
                spoilerContent = spoilerContent,
                codeBackground = codeBackground,
            )

            is DTextNode.Styled -> {
                val style = when (node.style) {
                    DTextStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    DTextStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    DTextStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                    DTextStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    DTextStyle.SPOILER -> SpanStyle(
                        background = spoilerBackground,
                        color = spoilerContent,
                    )
                }
                withStyle(style) {
                    appendDText(
                        nodes = node.children,
                        linkColor = linkColor,
                        spoilerBackground = spoilerBackground,
                        spoilerContent = spoilerContent,
                        codeBackground = codeBackground,
                    )
                }
            }
        }
    }
}
