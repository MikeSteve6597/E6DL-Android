package com.mikesteve.e621downloadler.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.net.TranslationRepository

/**
 * 标签 Wiki（v1.5.2）：展示内置词典收录的全部标签「中文 + 英文原 tag」对照，
 * 支持按中文或英文过滤；数据来自 `res/raw/local_tag_translations.tsv`（不联网）。
 *
 * 入口：发现页搜索栏右侧的书本图标、设置页「标签 Wiki」按钮。
 */
@Composable
fun TagWikiPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 词条只在首次进入时读一次；`demo_*` 是调试演示数据，不在 Wiki 里展示。
    val entries = remember {
        TranslationRepository.dictionaryEntries(context)
            .filterNot { it.first.startsWith("demo_") }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    BackHandler(onBack = onBack)

    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, entries) {
        val q = query.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { (english, chinese) ->
                english.contains(q, ignoreCase = true) || chinese.contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.e6dl_action_back),
                )
            }
            Text(
                text = stringResource(R.string.e6dl_keyword_wiki),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.e6dl_keyword_wiki_count, entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.e6dl_keyword_wiki_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.e6dl_keyword_wiki_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.first }) { (english, chinese) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Text(text = chinese, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = english,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
