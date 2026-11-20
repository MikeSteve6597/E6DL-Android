package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.SectionCard

/**
 * 搜索下载页（对齐桌面版 `SearchPage`）：
 * 关键词 / 本次黑名单 / 画质 / 页数 / 每页数量 / 最低评分 / 保存历史 / 进度 / 取消 / 历史 / 最近关键词。
 */
@Composable
fun SearchPage(vm: AppViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsState()
    val progress by vm.progress.collectAsState()

    var tags by remember { mutableStateOf("") }
    var tempBlacklist by remember { mutableStateOf("") }
    var quality by remember(cfg.quality) { mutableStateOf(cfg.quality) }
    var maxPages by remember(cfg.maxPages) { mutableStateOf(cfg.maxPages.toString()) }
    var limit by remember(cfg.limit) { mutableStateOf(cfg.limit.toString()) }
    var minScore by remember(cfg.minScore) { mutableStateOf(cfg.minScore.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }

    fun start() {
        val tagList = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tagList.isEmpty()) {
            message = "请先填写至少一个关键词"
            messageType = InfoType.Warning
            return
        }
        val blacklist = tempBlacklist.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { cfg.blacklist }
        val pages = maxPages.toIntOrNull()?.coerceIn(1, 50) ?: 5
        val perPage = limit.toIntOrNull()?.coerceIn(1, 320) ?: 50
        val score = minScore.toIntOrNull() ?: 10

        vm.setSearchDefaults(pages, perPage, score)
        vm.rememberKeyword(tagList.first())
        vm.rememberHistory(tagList, blacklist)
        message = "开始下载…"
        messageType = InfoType.Info
        vm.startDownload(
            tags = tagList,
            blacklist = blacklist,
            quality = quality,
            maxPages = pages,
            limit = perPage,
            minScore = score,
        ) { result ->
            message = result
            messageType = if (result.contains("失败") || result.contains("已取消")) InfoType.Warning else InfoType.Success
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBar(message = message, type = messageType)

        SectionCard(title = "搜索条件") {
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("关键词（多个用英文逗号分隔）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tempBlacklist,
                onValueChange = { tempBlacklist = it },
                label = { Text("本次黑名单（留空则用黑名单页设置）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.labels.forEach { (key, label) ->
                    FilterChip(
                        selected = quality == key,
                        onClick = { quality = key },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxPages,
                    onValueChange = { maxPages = it.filter(Char::isDigit) },
                    label = { Text("页数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit) },
                    label = { Text("每页数量") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minScore,
                    onValueChange = { minScore = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("最低评分") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("登录后保存搜索历史与下载记录", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = cfg.rememberHistory, onCheckedChange = { vm.setRememberHistory(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { start() }, enabled = progress == null) { Text("开始下载") }
                OutlinedButton(
                    onClick = { vm.cancelDownload(); message = "正在取消…" },
                    enabled = progress != null,
                ) { Text("取消") }
            }
            progress?.let { p ->
                LinearProgressIndicator(
                    progress = {
                        if (p.total > 0) (p.done + p.skipped + p.failed).toFloat() / p.total.toFloat() else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "已下载 ${p.done} · 跳过 ${p.skipped} · 失败 ${p.failed}${if (p.currentName.isNotEmpty()) " · ${p.currentName}" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.message.isNotEmpty()) {
                    Text(p.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionCard(title = "搜索历史") {
            if (cfg.history.isEmpty()) {
                Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                cfg.history.forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = h.tags.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(h.time, style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { vm.clearHistory() }) { Text("清空") }
            }
        }

        if (cfg.recentKeywords.isNotEmpty() && cfg.keywordMemory > 0) {
            SectionCard(title = "最近关键词") {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cfg.recentKeywords.forEach { kw ->
                        AssistChip(onClick = { tags = kw }, label = { Text(kw) })
                    }
                }
            }
        }
    }
}
