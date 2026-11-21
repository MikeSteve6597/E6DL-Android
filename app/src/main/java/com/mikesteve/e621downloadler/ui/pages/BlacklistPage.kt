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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
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
        SectionCard(title = stringResource(R.string.e6dl_blacklist_add_section)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.e6dl_blacklist_tag_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        input.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { vm.addBlacklist(it) }
                        input = ""
                    },
                ) { Text(stringResource(R.string.e6dl_action_add)) }
            }
            Text(
                text = stringResource(R.string.e6dl_blacklist_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_blacklist_section, cfg.blacklist.size)) {
            if (cfg.blacklist.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.e6dl_blacklist_empty_title),
                    description = stringResource(R.string.e6dl_blacklist_empty_desc),
                )
            } else {
                cfg.blacklist.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = tag, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { vm.removeBlacklist(tag) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.e6dl_blacklist_remove, tag),
                            )
                        }
                    }
                }
            }
        }
    }
}
