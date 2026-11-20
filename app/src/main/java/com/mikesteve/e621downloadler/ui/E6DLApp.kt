package com.mikesteve.e621downloadler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.ui.pages.BlacklistPage
import com.mikesteve.e621downloadler.ui.pages.DetailPage
import com.mikesteve.e621downloadler.ui.pages.DiscoverPage
import com.mikesteve.e621downloadler.ui.pages.FavoritesPage
import com.mikesteve.e621downloadler.ui.pages.SearchPage
import com.mikesteve.e621downloadler.ui.pages.SettingsPage
import com.mikesteve.e621downloadler.ui.theme.E6DLTheme

private enum class Destination(val label: String, val icon: ImageVector) {
    Discover("发现", Icons.Filled.Whatshot),
    Search("搜索下载", Icons.Filled.Search),
    Favorites("我的收藏", Icons.Filled.Favorite),
    Blacklist("黑名单", Icons.Filled.Block),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 应用外壳：自适应导航 + 五页切换 + 详情页覆盖 + 登录对话框。
 *
 * 导航形态按窗口宽度选择（Google Window Size Class 的三档阈值）：
 *  - Compact  (<600dp)  → 底部 NavigationBar
 *  - Medium/Expanded (≥600dp) → 左侧 NavigationRail
 * 这样手机、折叠屏展开、平板、分屏都能得到合理布局，而不是把手机布局拉宽。
 */
@Composable
fun E6DLApp() {
    val vm: AppViewModel = viewModel()
    val cfg by vm.config.collectAsState()

    var destination by remember { mutableStateOf(Destination.Discover) }
    var detailPost by remember { mutableStateOf<Post?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    val widthDp = LocalConfiguration.current.screenWidthDp
    val compact = widthDp < 600

    val dark = when (cfg.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    E6DLTheme(darkTheme = dark) {
        // 详情页作为整屏覆盖，返回键先关详情
        val current = detailPost
        if (current != null) {
            BackHandler { detailPost = null }
            DetailPage(vm = vm, post = current, onBack = { detailPost = null })
            return@E6DLTheme
        }

        val content: @Composable (Modifier) -> Unit = { innerModifier ->
            when (destination) {
                Destination.Discover -> DiscoverPage(vm, onOpenPost = { detailPost = it }, modifier = innerModifier)
                Destination.Search -> SearchPage(vm, modifier = innerModifier)
                Destination.Favorites -> FavoritesPage(vm, onOpenPost = { detailPost = it }, modifier = innerModifier)
                Destination.Blacklist -> BlacklistPage(vm, modifier = innerModifier)
                Destination.Settings -> SettingsPage(vm, onOpenLogin = { showLogin = true }, modifier = innerModifier)
            }
        }

        if (compact) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = destination == dest,
                                onClick = { destination = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding))
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEach { dest ->
                        NavigationRailItem(
                            selected = destination == dest,
                            onClick = { destination = dest },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    content(Modifier.fillMaxSize())
                }
            }
        }

        if (showLogin) {
            LoginDialog(vm = vm, onDismiss = { showLogin = false })
        }
    }
}
