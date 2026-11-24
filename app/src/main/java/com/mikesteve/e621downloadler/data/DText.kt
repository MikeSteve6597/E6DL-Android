package com.mikesteve.e621downloadler.data

/**
 * e621 评论正文（DText）的节点树。
 *
 * DText 是 e621 自己的一套轻量标记语法（由 `dtext` 库渲染成 HTML），评论里最常见的是
 * `[quote]…[/quote]` 引用块与 `"显示名":/users/123` 用户链接。这里只实现界面真正需要的子集：
 *
 *  - 块级：[quote]（可嵌套）、[code]
 *  - 行内：[b]、[i]、[u]、[s]（删除线）、[spoiler]
 *  - 链接：`"显示名":/users/123`（站内）、`"显示名":https://…`（站外）、裸 URL
 *  - 文本：保留换行
 *
 * 未知标签不报错、也不把源码漏到界面上——直接去掉标签保留内部文本（见 [DText.parse]）。
 * 本文件是**纯 Kotlin**（不依赖 Android），因此可以直接在 JVM 单元测试里跑（DTextTest）。
 */
sealed class DTextNode {

    /** 纯文本片段（可含 `\n`）。 */
    data class Text(val text: String) : DTextNode()

    /**
     * 链接。[label] 是显示名，[target] 是原始目标（站内是 `/users/123` 这类路径，
     * 站外是完整 URL）。界面只显示 [label]，不把 URL 源码露出来。
     */
    data class Link(val label: String, val target: String) : DTextNode()

    /** `[code]…[/code]`：内容按原样保留（不再解析其中的标签）。 */
    data class Code(val text: String) : DTextNode()

    /**
     * `[quote]…[/quote]` 引用块。
     *
     * 如果引用开头是 e621 站点生成的 `"名字":/users/<id> said:`，解析时会把它提取成
     * [authorName] / [authorTarget]，[children] 里只剩真正的引用正文；没有作者时为 null。
     */
    data class Quote(
        val children: List<DTextNode>,
        val authorName: String? = null,
        val authorTarget: String? = null,
    ) : DTextNode()

    /** 带行内样式的文本容器（[DTextStyle] 可嵌套，例如粗体里套斜体）。 */
    data class Styled(val style: DTextStyle, val children: List<DTextNode>) : DTextNode()
}

/** DText 行内样式。 */
enum class DTextStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, SPOILER }

/**
 * DText 子集解析器。无状态、纯函数，可反复调用。
 *
 * 解析失败从不抛异常：不认识的标签、没闭合的标签都退化成"去掉标签保留文本"，
 * 保证评论正文永远能显示，不会因为某个用户输入了奇怪的标记就整段空白。
 */
object DText {

    /** 把 DText 源码解析成节点树。空串返回空列表。 */
    fun parse(input: String): List<DTextNode> {
        if (input.isEmpty()) return emptyList()
        // 统一换行符：Windows 粘贴的 \r\n 在 Text 里也能正常换行
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
        return Parser(normalized).parseNodes(emptyList())
    }

    /**
     * 节点树 → 纯文本（引用块内容展开、链接取显示名、代码取原文）。
     *
     * 供调试/预览与单元测试使用：可以验证界面上不会出现 `[quote]`、`[/b]` 这类源码。
     */
    fun plainText(nodes: List<DTextNode>): String = buildString { appendPlain(nodes, this) }

    private fun appendPlain(nodes: List<DTextNode>, out: StringBuilder) {
        for (node in nodes) {
            when (node) {
                is DTextNode.Text -> out.append(node.text)
                is DTextNode.Link -> out.append(node.label)
                is DTextNode.Code -> out.append(node.text)
                is DTextNode.Quote -> appendPlain(node.children, out)
                is DTextNode.Styled -> appendPlain(node.children, out)
            }
        }
    }
}

/**
 * 生成 e621 站点同款的回复引用文本，供"回复"按钮填进输入框。
 *
 * 站点生成的格式是：
 * ```
 * [quote]"richard noggin99":/users/1003100 said: 被引用的正文[/quote]
 * ```
 * 没有 creatorId（老数据/演示数据）时按站点降级写法只写名字，不产生无效链接。
 * 末尾固定两个换行，用户的光标/后续输入自然落在引用块下方。
 */
fun buildReplyQuote(comment: Comment): String {
    val name = sanitizeAuthorName(comment.creatorName.ifBlank { "未知用户" })
    val author = if (comment.creatorId > 0L) {
        "\"$name\":/users/${comment.creatorId}"
    } else {
        name
    }
    return "[quote]$author said: ${comment.body}[/quote]\n\n"
}

/** 作者名里的 `"` / 方括号会破坏 DText 链接语法，退化成正文字符而不是生成坏标记。 */
private fun sanitizeAuthorName(name: String): String = name
    .replace("\"", "'")
    .replace('[', '(')
    .replace(']', ')')
    .trim()

// ---------------------------------------------------------------------------
// 解析实现
// ---------------------------------------------------------------------------

/** 开标签：`[quote]`、`[b]`、`[color=red]`（属性部分会被忽略）。 */
private val OPEN_TAG = Regex("""\[\s*([A-Za-z][A-Za-z0-9_]*)(?:\s*=\s*[^\]\s]*)?\s*\]""")

/** 闭标签：`[/quote]`、`[/ b ]`（DText 允许闭合标签里带空格）。 */
private val CLOSE_TAG = Regex("""\[\s*/\s*([A-Za-z][A-Za-z0-9_]*)\s*\]""")

/** `[code]` 的闭合标签：只按固定写法查找，内容不参与嵌套解析。 */
private val CODE_CLOSE = Regex("""\[\s*/\s*code\s*\]""", RegexOption.IGNORE_CASE)

/**
 * DText 链接语法：`"显示名":/users/123` 或 `"显示名":https://…`。
 * 只把 `/…` 与 `http(s)://…` 当作链接目标，避免把正文里普通的 `"xx": yy` 误判成链接。
 */
private val LINK = Regex(
    """"([^"\n]+)"\s*:\s*(/\S+|https?://\S+)""",
    RegexOption.IGNORE_CASE,
)

/** 裸 URL（http/https），遇到空白或 DText 标签边界即结束。 */
private val BARE_URL = Regex("""https?://[^\s\[\]"<>]+""", RegexOption.IGNORE_CASE)

/** URL 末尾紧跟的标点不属于链接。 */
private const val URL_TRAILING_PUNCTUATION = ".,;:!?)"

private class Parser(private val src: String) {

    private var pos = 0

    /**
     * 解析到 [stopTags] 中任意闭合标签（或字符串结尾）为止。
     *
     * [stopTags] 是"当前仍处于打开状态"的标签栈（外层在前）。遇到栈里的闭合标签时**不消费**，
     * 直接返回，让打开它的那一层去消费；这样未闭合的 `[b]` 不会吞掉外层的 `[/quote]`。
     */
    fun parseNodes(stopTags: List<String>): List<DTextNode> {
        val out = ArrayList<DTextNode>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(DTextNode.Text(buffer.toString()))
                buffer.setLength(0)
            }
        }

        while (pos < src.length) {
            val ch = src[pos]

            if (ch == '[') {
                val closing = matchAt(CLOSE_TAG)
                if (closing != null) {
                    val name = closing.groupValues[1].lowercase()
                    if (name in stopTags) {
                        flush()
                        return out
                    }
                    // 未知/多余闭合标签：去掉标签，保留前后文字
                    pos = closing.range.last + 1
                    continue
                }
                val opening = matchAt(OPEN_TAG)
                if (opening != null) {
                    val name = opening.groupValues[1].lowercase()
                    pos = opening.range.last + 1
                    when {
                        name == "quote" -> {
                            flush()
                            val children = parseNodes(stopTags + name)
                            pos = consumeClosing(name)
                            out.add(buildQuote(children))
                        }

                        name == "code" -> {
                            flush()
                            out.add(DTextNode.Code(readCodeContent()))
                        }

                        styleOf(name) != null -> {
                            flush()
                            val children = parseNodes(stopTags + name)
                            pos = consumeClosing(name)
                            if (children.isNotEmpty()) {
                                out.add(DTextNode.Styled(styleOf(name)!!, children))
                            }
                        }

                        // 未知开标签：只去掉标签本身，内部文本继续按 DText 解析
                    }
                    continue
                }
                // 不是合法标签的 '['：当普通字符
                buffer.append(ch)
                pos++
                continue
            }

            if (ch == '"') {
                val link = matchAt(LINK)
                if (link != null) {
                    flush()
                    out.add(DTextNode.Link(link.groupValues[1], link.groupValues[2]))
                    pos = link.range.last + 1
                    continue
                }
                buffer.append(ch)
                pos++
                continue
            }

            if (ch == 'h' || ch == 'H') {
                val url = matchAt(BARE_URL)
                if (url != null) {
                    val trimmed = url.value.trimEnd { it in URL_TRAILING_PUNCTUATION }
                    if (trimmed.length > "https://".length) {
                        flush()
                        out.add(DTextNode.Link(trimmed, trimmed))
                        pos += trimmed.length
                        continue
                    }
                }
                buffer.append(ch)
                pos++
                continue
            }

            buffer.append(ch)
            pos++
        }

        flush()
        return mergeText(out)
    }

    private fun matchAt(regex: Regex): MatchResult? =
        regex.find(src, pos)?.takeIf { it.range.first == pos }

    /** 消费当前位置的 `[/name]`；若当前不是它（被外层抢先结束）则原地不动。 */
    private fun consumeClosing(name: String): Int {
        val match = matchAt(CLOSE_TAG) ?: return pos
        return if (match.groupValues[1].equals(name, ignoreCase = true)) {
            match.range.last + 1
        } else {
            pos
        }
    }

    /** `[code]` 内容：一直读到 `[/code]`，中间的标签一律当普通文本。 */
    private fun readCodeContent(): String {
        val close = CODE_CLOSE.find(src, pos)
        return if (close == null) {
            val rest = src.substring(pos)
            pos = src.length
            rest
        } else {
            val content = src.substring(pos, close.range.first)
            pos = close.range.last + 1
            content
        }
    }

    /**
     * 构造引用节点：识别 e621 自动生成的 `"名字":/users/<id> said:` 前缀并提取成头部，
     * 其余内容才是引用正文。不是这个格式时整段就是正文（authorName = null）。
     */
    private fun buildQuote(rawChildren: List<DTextNode>): DTextNode.Quote {
        val children = mergeText(rawChildren)
        var index = 0
        // 跳过 [quote] 后紧跟的空白文本（站点有时会带一个换行）
        while (index < children.size) {
            val node = children[index]
            if (node is DTextNode.Text && node.text.isBlank()) index++ else break
        }
        val link = children.getOrNull(index) as? DTextNode.Link
        if (link != null) {
            val after = children.getOrNull(index + 1) as? DTextNode.Text
            if (after != null) {
                val text = after.text.trimStart()
                if (text.startsWith("said:", ignoreCase = true)) {
                    val body = text.removePrefixIgnoreCase("said:").trimStart()
                    val rest = if (body.isEmpty()) {
                        children.drop(index + 2)
                    } else {
                        listOf(DTextNode.Text(body)) + children.drop(index + 2)
                    }
                    return DTextNode.Quote(rest, link.label, link.target)
                }
            }
        }
        return DTextNode.Quote(children)
    }

    private fun mergeText(nodes: List<DTextNode>): List<DTextNode> {
        if (nodes.size < 2) return nodes
        val out = ArrayList<DTextNode>(nodes.size)
        for (node in nodes) {
            val last = out.lastOrNull()
            if (node is DTextNode.Text && last is DTextNode.Text) {
                out[out.size - 1] = DTextNode.Text(last.text + node.text)
            } else {
                out.add(node)
            }
        }
        return out
    }
}

private fun styleOf(name: String): DTextStyle? = when (name) {
    "b" -> DTextStyle.BOLD
    "i" -> DTextStyle.ITALIC
    "u" -> DTextStyle.UNDERLINE
    "s" -> DTextStyle.STRIKETHROUGH
    "spoiler" -> DTextStyle.SPOILER
    else -> null
}

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
