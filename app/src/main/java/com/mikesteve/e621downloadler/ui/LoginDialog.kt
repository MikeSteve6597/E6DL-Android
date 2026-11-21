@file:Suppress("DEPRECATION") // VisualTransformation 迁移到 SecureTextField 留待下个版本

package com.mikesteve.e621downloadler.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import com.mikesteve.e621downloadler.R

/**
 * 登录对话框（对齐桌面版 `LoginDialog`）：用户名 + API Key。
 *
 * 开发构建里「保存并校验」会失败（网络锁），这是预期；所以额外提供「仅保存」，
 * 方便在开发期把账号先存好，等发布构建再校验。
 */
@Suppress("DEPRECATION") // PasswordVisualTransformation 仍是当前稳定 API；Material3 的 SecureTextField 迁移留待下个版本
@Composable
fun LoginDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.e6dl_login_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.e6dl_login_desc))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.e6dl_login_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                TextButton(onClick = { showKey = !showKey }) {
                    Text(
                        if (showKey) {
                            stringResource(R.string.e6dl_login_hide_key)
                        } else {
                            stringResource(R.string.e6dl_login_show_key)
                        },
                    )
                }
                hint?.let {
                    Text(it, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (username.isBlank() || apiKey.isBlank()) {
                        hint = "用户名与 API Key 不能为空。"
                        return@TextButton
                    }
                    busy = true
                    hint = "正在校验…"
                    vm.validateLogin(username, apiKey) { ok, msg ->
                        busy = false
                        if (ok) {
                            vm.saveLogin(username, apiKey)
                            hint = null
                            onDismiss()
                        } else {
                            hint = "$msg（可以点「仅保存」先存下来）"
                        }
                    }
                },
            ) { Text(stringResource(R.string.e6dl_login_save_validate)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (username.isNotBlank() && apiKey.isNotBlank()) {
                        vm.saveLogin(username, apiKey)
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.e6dl_login_save_only)) }
        },
    )
}
