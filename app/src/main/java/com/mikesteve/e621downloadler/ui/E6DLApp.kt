package com.mikesteve.e621downloadler.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.R
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.net.AppUpdateInfo
import com.mikesteve.e621downloadler.net.UpdateChecker
import com.mikesteve.e621downloadler.ui.components.EmptyState
import com.mikesteve.e621downloadler.ui.pages.BlacklistPage
import com.mikesteve.e621downloadler.ui.pages.BrowsePage
import com.mikesteve.e621downloadler.ui.pages.DetailPage
import com.mikesteve.e621downloadler.ui.pages.DiscoverPage
import com.mikesteve.e621downloadler.ui.pages.FavoritesPage
import com.mikesteve.e621downloadler.ui.pages.SearchPage
import com.mikesteve.e621downloadler.ui.pages.SettingsPage
import com.mikesteve.e621downloadler.ui.pages.TagWikiPage
import com.mikesteve.e621downloadler.ui.theme.E6DLTheme
import kotlinx.coroutines.launch

private enum class Destination(val labelRes: Int, val icon: ImageVector) {
    Discover(R.string.e6dl_tab_discover, Icons.Filled.Whatshot),
    Search(R.string.e6dl_tab_search, Icons.Filled.Download),
    Favorites(R.string.e6dl_tab_favorites, Icons.Filled.Favorite),
    Blacklist(R.string.e6dl_tab_blacklist, Icons.Filled.Block),
    Settings(R.string.e6dl_tab_settings, Icons.Filled.Settings),
}

/**
 * 应用外壳（v1.1 自适应版）。
 *
 * 布局策略（与 `Android/UI_DESIGN_GUIDELINES.md` 对齐）：
 *  - **窗口尺寸**用 `WindowAdaptiveInfo`（WindowSizeClass）判断，不再用机型/屏幕宽度写死；
 *  - **Compact（<600dp）**：底部 NavigationBar + 单栏；点开作品后详情占满整屏，返回回到列表；
 *  - **Medium（600–839dp）**：左侧 NavigationRail + 单栏；
 *  - **Expanded（≥840dp）**：左侧 Rail + **List-Detail 双栏**（列表保持可见，详情在右栏）；
 *  - **折叠屏**：竖向分离铰链时强制双栏并由 `PaneScaffoldDirective` 自动避开铰链；
 *    tabletop（横向半折）时 directive 会把双栏改为上下分区，详情页布局随之变成上下排布；
 *  - **状态保留**：选中作品与列表数据在 ViewModel，旋转/折叠/分屏拖动不丢。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun E6DLApp() {
    val vm: AppViewModel = viewModel()
    val cfg by vm.config.collectAsState()
    // 配置是否已从 DataStore 读出：未读出前 config 只是默认占位值（单机），不能先组合页面。
    val configLoaded by vm.configLoaded.collectAsState()
    val selectedPost by vm.selectedPost.collectAsState()
    val browse by vm.browse.collectAsState()

    var destinationName by rememberSaveable { mutableStateOf(Destination.Discover.name) }
    val destination = Destination.valueOf(destinationName)
    var showLogin by rememberSaveable { mutableStateOf(false) }
    // v1.5.2：Tag Wiki 是覆盖在列表栏之上的整页（发现页图标 / 设置页入口都能打开）。
    var tagWikiOpen by rememberSaveable { mutableStateOf(false) }
    // 详情页点标签 → 切到「搜索下载」并把标签预填进关键词框。
    // 搜索页消费后必须置空，否则每次进搜索页都会被旧标签覆盖用户输入。
    var searchPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val context = LocalContext.current
    // v1.5.0：启动自检更新（release + 在线模式；debug/单机跳过，静默失败不打扰用户）
    LaunchedEffect(configLoaded, cfg.mode) {
        if (!configLoaded || BuildConfig.DEBUG) return@LaunchedEffect
        if (cfg.mode != Config.MODE_ONLINE) return@LaunchedEffect
        val info = runCatching { UpdateChecker.checkLatest(cfg) }.getOrNull() ?: return@LaunchedEffect
        if (info.tagName != cfg.skippedUpdateTag) updateInfo = info
    }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val sizeClass = adaptiveInfo.windowSizeClass
    val useRail = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    // 展开的折叠屏：即使宽度仍算 Medium，也优先用双栏（竖向铰链天然是分割线）
    val hasSeparatingVerticalHinge = adaptiveInfo.windowPosture.separatingVerticalHingeBounds.isNotEmpty()

    // 只有「发现 / 收藏」两个页面需要 List-Detail 双栏；搜索/黑名单/设置是整页表单，
    // Tag Wiki 也是整页，直接用整页布局（见下方 Row 分支），避免右侧详情占位把内容挤到一半宽。
    val listDetailEnabled = !tagWikiOpen &&
        (browse.active || destination == Destination.Discover || destination == Destination.Favorites)

    val directive = remember(adaptiveInfo, hasSeparatingVerticalHinge) {
        val base = if (hasSeparatingVerticalHinge) {
            calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo)
        } else {
            calculatePaneScaffoldDirective(adaptiveInfo)
        }
        // 关闭 scaffold 的"自动聚焦当前面板"：否则首屏会把焦点交给列表里第一个可聚焦控件
        // （发现页的关键词输入框），导致启动即弹出输入法。
        PaneScaffoldDirective(
            maxHorizontalPartitions = base.maxHorizontalPartitions,
            horizontalPartitionSpacerSize = base.horizontalPartitionSpacerSize,
            maxVerticalPartitions = base.maxVerticalPartitions,
            verticalPartitionSpacerSize = base.verticalPartitionSpacerSize,
            defaultPanePreferredWidth = base.defaultPanePreferredWidth,
            defaultPanePreferredHeight = base.defaultPanePreferredHeight,
            excludedBounds = base.excludedBounds,
            shouldAutoFocusCurrentDestination = false,
        )
    }

    // 调试日志：验证折叠/分屏时自适应判断是否生效（release 不输出）
    LaunchedEffect(adaptiveInfo) {
        if (com.mikesteve.e621downloadler.BuildConfig.DEBUG) {
            android.util.Log.d(
                "E6DL-Ada",
                "widthDp>=600:${sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)} " +
                    ">=840:${sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)} " +
                    "tabletop:${adaptiveInfo.windowPosture.isTabletop} " +
                    "hinges:${adaptiveInfo.windowPosture.hingeList.size} " +
                    "verticalHinge:${hasSeparatingVerticalHinge}",
            )
        }
    }

    // 内容键用 Long（作品 id），可安全放进 Bundle；作品对象本身存在 ViewModel 里
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>(scaffoldDirective = directive)
    val scope = rememberCoroutineScope()

    LaunchedEffect(destination, directive) {
        if (com.mikesteve.e621downloadler.BuildConfig.DEBUG) {
            android.util.Log.d(
                "E6DL-Ada",
                "dest=$destination partitions=${directive.maxHorizontalPartitions} " +
                    "scaffoldValue=${navigator.scaffoldValue}",
            )
        }
    }

    // 配置变更（旋转/折叠/分屏）后 navigator 会重建：用 ViewModel 里保存的选中作品恢复详情栏
    LaunchedEffect(Unit) {
        val stored = vm.selectedPost.value
        if (stored != null && navigator.currentDestination?.contentKey != stored.id) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, stored.id)
        }
    }
    // 系统返回/返回手势关掉详情后，同步清空 ViewModel 选中状态。
    // 注意：只有「明确回到列表栏」才清；初始 null 不能清，否则旋转后恢复详情的时序会被打断。
    LaunchedEffect(navigator.currentDestination) {
        val dest = navigator.currentDestination
        if (com.mikesteve.e621downloadler.BuildConfig.DEBUG) {
            android.util.Log.d(
                "E6DL-Ada",
                "dest=${dest?.pane} content=${dest?.contentKey} canBack=${navigator.canNavigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)}",
            )
        }
        if (dest != null && dest.pane != ListDetailPaneScaffoldRole.Detail) {
            vm.closeDetail()
        }
    }

    val dark = when (cfg.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    E6DLTheme(darkTheme = dark) {
        // 启动时先等 DataStore 读回已保存配置，再组合任何页面：
        // 否则页面会先用 config 的默认占位值（单机）发首次请求，"上次设为在线"也会被当成单机。
        if (!configLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@E6DLTheme
        }

        // 打开详情时把「当前列表页」的作品一起交给 ViewModel：详情页据此支持左右滑动
        // 切换上一个/下一个作品。搜索/黑名单/设置这类非列表页没有可滑动上下文，只传当前作品。
        val openDetail: (Post) -> Unit = { post ->
            val contextPosts = if (browse.active) {
                browse.posts
            } else {
                when (destination) {
                    Destination.Discover -> vm.discover.value.posts
                    Destination.Favorites -> vm.favorites.value.posts
                    else -> listOf(post)
                }
            }
            vm.openDetail(post, contextPosts)
            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, post.id) }
        }
        val switchDestination: (Destination) -> Unit = { dest ->
            destinationName = dest.name
            tagWikiOpen = false
            vm.closeBrowse()
            if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
                vm.closeDetail()
                scope.launch { navigator.navigateBack() }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEach { dest ->
                        NavigationRailItem(
                            selected = destination == dest,
                            onClick = { switchDestination(dest) },
                            icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            }
            if (useRail && !listDetailEnabled) {
                // 搜索 / 黑名单 / 设置 / Tag Wiki：整页表单布局，占满 Rail 之外的宽度
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ListPane(
                        vm = vm,
                        destination = destination,
                        compact = false,
                        browseActive = browse.active,
                        tagWikiOpen = tagWikiOpen,
                        onOpenTagWiki = { tagWikiOpen = true },
                        onCloseTagWiki = { tagWikiOpen = false },
                        onSelect = switchDestination,
                        onOpenPost = openDetail,
                        onOpenLogin = { showLogin = true },
                        initialSearchTags = searchPrefill,
                        onSearchPrefillConsumed = { searchPrefill = null },
                    )
                }
            } else {
                NavigableListDetailPaneScaffold(
                    navigator = navigator,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // 双栏模式下 detail 栏即使没有选中项也保持展开（显示占位），
                    // 此时 scaffoldValue 在"列表+详情占位"与"列表+详情"之间不变；
                    // 默认的 PopUntilScaffoldValueChange 会认为无法返回 → 返回键直接退出应用。
                    // 改成按"当前目的地变化"判断，返回手势就能从详情退回列表。
                    defaultBackBehavior = BackNavigationBehavior.PopUntilCurrentDestinationChange,
                    listPane = {
                        AnimatedPane {
                            ListPane(
                                vm = vm,
                                destination = destination,
                                compact = !useRail,
                                browseActive = browse.active,
                                tagWikiOpen = tagWikiOpen,
                                onOpenTagWiki = { tagWikiOpen = true },
                                onCloseTagWiki = { tagWikiOpen = false },
                                onSelect = switchDestination,
                                onOpenPost = openDetail,
                                onOpenLogin = { showLogin = true },
                                initialSearchTags = searchPrefill,
                                onSearchPrefillConsumed = { searchPrefill = null },
                            )
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            val post = selectedPost
                            if (post != null) {
                                DetailPage(
                                    vm = vm,
                                    post = post,
                                    onBack = {
                                        vm.closeDetail()
                                        scope.launch { navigator.navigateBack() }
                                    },
                                    onLoginRequired = { showLogin = true },
                                    onSearchTag = { tag ->
                                        vm.closeDetail()
                                        scope.launch { navigator.navigateBack() }
                                        vm.openTag(tag)
                                    },
                                    onOpenAuthor = { author ->
                                        vm.closeDetail()
                                        scope.launch { navigator.navigateBack() }
                                        vm.openAuthor(author)
                                    },
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                                    EmptyState(
                                        title = stringResource(R.string.e6dl_detail_no_selection_title),
                                        description = stringResource(R.string.e6dl_detail_no_selection_desc),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        if (showLogin) {
            LoginDialog(vm = vm, onDismiss = { showLogin = false })
        }

        // 启动更新提示：有新版 → 引导去 GitHub Release 下载页；可跳过此版本。
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text(stringResource(R.string.e6dl_update_title, info.versionName)) },
                text = {
                    Text(stringResource(R.string.e6dl_update_message))
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                vm.skipUpdateVersion(info.tagName)
                                updateInfo = null
                            },
                        ) { Text(stringResource(R.string.e6dl_update_skip)) }
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)),
                                    )
                                }
                                updateInfo = null
                            },
                        ) { Text(stringResource(R.string.e6dl_update_download)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) {
                        Text(stringResource(R.string.e6dl_update_later))
                    }
                },
            )
        }
    }
}

/** 列表栏：Compact 用底部导航，其余尺寸用外层 Rail，这里只负责内容区 + 系统栏内边距。 */
@Composable
private fun ListPane(
    vm: AppViewModel,
    destination: Destination,
    compact: Boolean,
    browseActive: Boolean,
    tagWikiOpen: Boolean,
    onOpenTagWiki: () -> Unit,
    onCloseTagWiki: () -> Unit,
    onSelect: (Destination) -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenLogin: () -> Unit,
    initialSearchTags: String?,
    onSearchPrefillConsumed: () -> Unit,
) {
    val content: @Composable (Modifier) -> Unit = { innerModifier ->
        if (tagWikiOpen) {
            TagWikiPage(onBack = onCloseTagWiki, modifier = innerModifier)
        } else if (browseActive) {
            BrowsePage(vm = vm, onOpenPost = onOpenPost, modifier = innerModifier)
        } else when (destination) {
            Destination.Discover -> DiscoverPage(
                vm,
                onOpenPost = onOpenPost,
                onSearch = { keywords -> vm.searchFromHome(keywords) },
                onOpenTagWiki = onOpenTagWiki,
                modifier = innerModifier,
            )
            Destination.Search -> SearchPage(
                vm,
                modifier = innerModifier,
                initialTags = initialSearchTags,
                onPrefillConsumed = onSearchPrefillConsumed,
            )
            Destination.Favorites -> FavoritesPage(
                vm,
                onOpenPost = onOpenPost,
                onOpenLogin = onOpenLogin,
                modifier = innerModifier,
            )
            Destination.Blacklist -> BlacklistPage(vm, modifier = innerModifier)
            Destination.Settings -> SettingsPage(
                vm,
                onOpenLogin = onOpenLogin,
                onOpenTagWiki = onOpenTagWiki,
                modifier = innerModifier,
            )
        }
    }

    if (compact) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = destination == dest,
                            onClick = { onSelect(dest) },
                            icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            content(Modifier.fillMaxSize().padding(padding))
        }
    } else {
        // 内容区由 Scaffold 负责状态栏/导航栏 insets，避免双栏时内容压到系统栏下面。
        // 整页表单（搜索/设置等）最宽限制 900dp 并居中，避免大屏上输入框被拉伸得过长。
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = 900.dp).fillMaxHeight()) {
                    content(Modifier.fillMaxSize())
                }
            }
        }
    }
}
