package com.mikesteve.e621downloadler.ui.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.net.NetGuard
import com.mikesteve.e621downloadler.ui.AppViewModel
import com.mikesteve.e621downloadler.ui.components.InfoBar
import com.mikesteve.e621downloadler.ui.components.InfoType
import com.mikesteve.e621downloadler.ui.components.SectionCard

/**
 * 设置页（对齐桌面版 `SettingsPage`）。
 *
 * 关键点：**单机 / 在线模式开关可以手动切换**（状态会持久化），
 * 但 debug 构建受开发期网络锁保护——切到"在线"也不会真的联网，
 * 页面会明确提示这是开发限制，避免被误当成 bug。
 */
@Suppress("DEPRECATION") // Slider 的旧重载仍是稳定 API；迁移到 SliderState 留待下个版本
@Composable
fun SettingsPage(
    vm: AppViewModel,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg by vm.config.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf(InfoType.Info) }
    var keywordMemory by remember(cfg.keywordMemory) { mutableIntStateOf(cfg.keywordMemory) }

    // 在组合期取文本：onClick 等非 @Composable 回调里不能直接调 stringResource。
    val offlineSwitchMessage = stringResource(R.string.e6dl_settings_mode_switched_offline)
    val onlineSwitchMessage = stringResource(R.string.e6dl_settings_mode_switched_online)
    val onlineSwitchLockedMessage = stringResource(R.string.e6dl_settings_mode_switched_online_locked)
    val logoutDoneMessage = stringResource(R.string.e6dl_settings_logout_done)
    val historyClearedMessage = stringResource(R.string.e6dl_settings_history_cleared)
    val cacheClearedMessage = stringResource(R.string.e6dl_settings_cache_cleared)
    val cacheClearFailedMessage = stringResource(R.string.e6dl_settings_cache_clear_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) {
            vm.exportZip(uri) { result ->
                message = result
                messageType = if (result.contains("失败")) InfoType.Warning else InfoType.Success
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBar(message = message, type = messageType)

        SectionCard(title = stringResource(R.string.e6dl_settings_section_mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cfg.mode == Config.MODE_OFFLINE,
                    onClick = {
                        vm.setMode(Config.MODE_OFFLINE)
                        message = offlineSwitchMessage
                        messageType = InfoType.Info
                    },
                    label = { Text(stringResource(R.string.e6dl_settings_mode_offline)) },
                )
                FilterChip(
                    selected = cfg.mode == Config.MODE_ONLINE,
                    onClick = {
                        vm.setMode(Config.MODE_ONLINE)
                        message = if (NetGuard.networkAllowed) {
                            onlineSwitchMessage
                        } else {
                            onlineSwitchLockedMessage
                        }
                        messageType = if (NetGuard.networkAllowed) InfoType.Success else InfoType.Warning
                    },
                    label = { Text(stringResource(R.string.e6dl_settings_mode_online)) },
                )
            }
            Text(
                text = if (cfg.mode == Config.MODE_ONLINE) {
                    stringResource(R.string.e6dl_settings_mode_current_online)
                } else {
                    stringResource(R.string.e6dl_settings_mode_current_offline)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (NetGuard.networkAllowed) {
                    stringResource(R.string.e6dl_settings_net_allowed_note)
                } else {
                    stringResource(R.string.e6dl_settings_net_locked_note)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_appearance)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to R.string.e6dl_settings_theme_system,
                    "light" to R.string.e6dl_settings_theme_light,
                    "dark" to R.string.e6dl_settings_theme_dark,
                ).forEach { (key, labelRes) ->
                    FilterChip(
                        selected = cfg.theme == key,
                        onClick = { vm.setTheme(key) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_download)) {
            Text(
                stringResource(R.string.e6dl_settings_default_quality),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.labels.forEach { (key, label) ->
                    FilterChip(
                        selected = cfg.quality == key,
                        onClick = { vm.setQuality(key) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.e6dl_settings_download_dir, vm.openDownloadDirHint()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { exportLauncher.launch("E6DL-export.zip") }) {
                Text(stringResource(R.string.e6dl_settings_export_zip))
            }
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_keyword_memory)) {
            Text(
                stringResource(R.string.e6dl_settings_keyword_memory_count, keywordMemory),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = keywordMemory.toFloat(),
                onValueChange = { v -> keywordMemory = v.toInt() },
                onValueChangeFinished = { vm.setKeywordMemory(keywordMemory) },
                valueRange = 0f..50f,
                steps = 49,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_account)) {
            if (cfg.loggedIn) {
                Text(
                    stringResource(R.string.e6dl_settings_logged_in, cfg.username),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenLogin) {
                        Text(stringResource(R.string.e6dl_settings_change_account))
                    }
                    TextButton(onClick = {
                        vm.logout()
                        message = logoutDoneMessage
                        messageType = InfoType.Info
                    }) { Text(stringResource(R.string.e6dl_settings_logout)) }
                }
            } else {
                Text(
                    stringResource(R.string.e6dl_settings_not_logged_in),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenLogin) { Text(stringResource(R.string.e6dl_action_login)) }
            }
            Text(
                text = stringResource(R.string.e6dl_settings_key_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_local_data)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.clearHistory()
                    message = historyClearedMessage
                    messageType = InfoType.Success
                }) { Text(stringResource(R.string.e6dl_settings_clear_history)) }
                OutlinedButton(onClick = {
                    val ok = vm.clearImageCache()
                    message = if (ok) cacheClearedMessage else cacheClearFailedMessage
                    messageType = if (ok) InfoType.Success else InfoType.Warning
                }) { Text(stringResource(R.string.e6dl_settings_clear_image_cache)) }
            }
            Text(
                text = stringResource(R.string.e6dl_settings_dedupe_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.e6dl_settings_section_about)) {
            Text(
                stringResource(
                    R.string.e6dl_settings_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.e6dl_settings_about_build) + "\n" +
                    stringResource(
                        R.string.e6dl_settings_net_lock,
                        stringResource(
                            if (NetGuard.networkAllowed) {
                                R.string.e6dl_settings_net_lock_off
                            } else {
                                R.string.e6dl_settings_net_lock_on
                            },
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.e6dl_settings_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
