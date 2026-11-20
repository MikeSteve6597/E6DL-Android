package com.mikesteve.e621downloadler.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.components.SectionCard

/**
 * 黑名单（对齐桌面版 `BlacklistPage`）：标签持久化，发现/搜索/收藏统一过滤。
 */
@Composable
fun BlacklistPage(vm: AppViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "添加要屏蔽的标签") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("标签，例如：gore") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        input.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { vm.addBlacklist(it) }
                        input = ""
                    },
                ) { Text("添加") }
            }
            Text(
                text = "搜索时会以 -标签 的形式拼进查询；发现页与收藏页也会过滤。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "已屏蔽（${cfg.blacklist.size}）") {
            if (cfg.blacklist.isEmpty()) {
                EmptyState(title = "黑名单为空", description = "被屏蔽的标签会从所有列表中隐藏。")
            } else {
                cfg.blacklist.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = tag, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { vm.removeBlacklist(tag) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除 $tag")
                        }
                    }
                }
            }
        }
    }
}
