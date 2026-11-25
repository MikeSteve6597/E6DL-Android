package com.mikesteve.e621downloadler.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.mikesteve.e621downloadler.BuildConfig
import com.mikesteve.e621downloadler.data.Config
import com.mikesteve.e621downloadler.data.ConfigStore
import com.mikesteve.e621downloadler.data.Post
import com.mikesteve.e621downloadler.data.Quality
import com.mikesteve.e621downloadler.download.DownloadEngine
import com.mikesteve.e621downloadler.download.DownloadProgress
import com.mikesteve.e621downloadler.net.ApiClient
import com.mikesteve.e621downloadler.net.SearchKeyword
import com.mikesteve.e621downloadler.net.TranslationRepository
import com.mikesteve.e621downloadler.net.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 全局状态容器（等价于桌面版 `MainWindow` 持有的 config / session / worker）。
 *
 * v1.1 起把「页面数据」也放进 ViewModel：
 *  - 旋转屏幕 / 折叠屏展开收起 / 分屏拖动时，ViewModel 不重建，列表内容与选中状态得以保留；
 *  - 列表详情（List-Detail）布局需要一个统一的"当前选中作品"状态，放在这里最自然。
 *
 * 刻意不用 Hilt：工程规模还小，一个 AndroidViewModel 就够，少一层依赖少一处坑。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    val api = ApiClient()
    private val engine = DownloadEngine(app, api)

    val config: StateFlow<Config> =
        store.config.stateIn(viewModelScope, SharingStarted.Eagerly, Config())

    /**
     * 已保存配置是否已经从 DataStore 读出来。
     *
     * [config] 的初始占位值是 [Config]（默认单机模式）。DataStore 首次发射之前如果就开始组合页面，
     * 页面会拿着"默认单机"配置发起首次请求（上次存在线时也会被判成单机），并且之后配置变成在线
     * 也不会自动重载——这正是「上次切到在线、重启后主页仍提示单机」的根因。
     * 因此 UI 要等本标记为 true 后再组合任何页面（见 `E6DLApp`）。
     */
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded

    init {
        // v1.0.x 的明文 API Key → Keystore 加密存储（幂等，无明文时什么都不做）
        viewModelScope.launch { runCatching { store.migrateLegacyPlaintextKey() } }
        // 等 DataStore 首次发出已保存配置后再让 UI 组合页面；
        // 读失败也置 true，宁可让页面按默认单机模式报错，也不能永久停在加载态。
        viewModelScope.launch {
            runCatching { store.config.first() }
            _configLoaded.value = true
        }
    }

    val downloadDir: java.io.File get() = engine.downloadDir

    // ------------------------------------------------------------------ 发现页

    data class DiscoverState(
        val order: String = ORDER_SCORE,
        val keyword: String = "",
        val posts: List<Post> = emptyList(),
        val loading: Boolean = false,
        val reachedEnd: Boolean = false,
        val error: String? = null,
        val demo: Boolean = false,
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
        val busy: Boolean = false,
    ) {
        val selectedPosts: List<Post> get() = posts.filter { it.id in selected }
    }

    private val _discover = MutableStateFlow(DiscoverState())
    val discover: StateFlow<DiscoverState> = _discover
    private var discoverJob: Job? = null

    /**
     * 发现页搜索栏里已选的关键词气泡（v1.5.2）。
     *
     * 放在 ViewModel 而不是页面内部：打开搜索结果页 / 切到其它 Tab 再回来时气泡不会丢，
     * 旋转屏幕、折叠屏切换也天然保留（ViewModel 不重建）。
     */
    private val _searchKeywords = MutableStateFlow<List<SearchKeyword>>(emptyList())
    val searchKeywords: StateFlow<List<SearchKeyword>> = _searchKeywords

    fun setSearchKeywords(keywords: List<SearchKeyword>) {
        _searchKeywords.value = keywords
    }

    fun setDiscoverOrder(order: String) {
        if (_discover.value.order == order) return
        _discover.update { it.copy(order = order) }
        loadDiscover(reset = true)
    }

    fun setDiscoverKeyword(keyword: String) {
        if (_discover.value.keyword == keyword) return
        _discover.update { it.copy(keyword = keyword) }
        loadDiscover(reset = true)
    }

    /** 加载发现页；[reset] = true 重新从第一页开始。 */
    fun loadDiscover(reset: Boolean = false) {
        val snapshot = _discover.value
        if (snapshot.demo) return
        if (!reset && (snapshot.loading || snapshot.reachedEnd)) return
        if (reset) {
            discoverJob?.cancel()
            discoverJob = null
            _discover.update {
                it.copy(
                    posts = emptyList(),
                    loading = true,
                    reachedEnd = false,
                    error = null,
                    selected = emptySet(),
                )
            }
        } else {
            _discover.update { it.copy(loading = true, error = null) }
        }

        val order = snapshot.order
        val keyword = snapshot.keyword
        discoverJob = viewModelScope.launch {
            try {
                val cfg = config.value
                val tags = listOf(order, keyword.trim()).filter { it.isNotEmpty() }
                // 与桌面版一致：游客模式下发现页不使用黑名单
                val blacklist = if (cfg.loggedIn) cfg.blacklist else emptyList()
                val page = if (reset) 1 else _discover.value.posts.size / PAGE_LIMIT + 1
                val batch = api.fetchPosts(
                    cfg = cfg,
                    tags = if (order == ORDER_VIDEO) listOf(ORDER_VIDEO, ORDER_SCORE) else tags,
                    blacklist = blacklist,
                    limit = PAGE_LIMIT,
                    page = page,
                )
                // v1.5.0：没有缩略图/文件地址（内容不可见）的作品整卡隐藏；demo 数据例外。
                val visibleBatch = if (DebugFlags.demo) batch else batch.filter { it.hasVisibleCard }
                _discover.update { cur ->
                    if (cur.order != order || cur.keyword != keyword) {
                        cur // 过期响应（用户已换排序/关键词）
                    } else {
                        val merged = if (reset) visibleBatch else (cur.posts + visibleBatch).distinctBy { it.id }
                        cur.copy(
                            posts = merged,
                            loading = false,
                            reachedEnd = batch.size < PAGE_LIMIT,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _discover.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    fun setDiscoverSelectionMode(enabled: Boolean) {
        _discover.update { it.copy(selectionMode = enabled, selected = if (enabled) it.selected else emptySet()) }
    }

    fun toggleDiscoverSelected(id: Long) {
        _discover.update { st ->
            val next = if (id in st.selected) st.selected - id else st.selected + id
            st.copy(selected = next)
        }
    }

    fun selectAllDiscover() {
        _discover.update { it.copy(selected = it.posts.map { p -> p.id }.toSet()) }
    }

    fun clearDiscoverSelection() {
        _discover.update { it.copy(selected = emptySet()) }
    }

    /** 批量收藏选中的作品（与桌面版 `_bulk_favorite` 一致：逐项请求、失败继续）。 */
    fun batchFavorite(posts: List<Post>, onFinish: (String) -> Unit) {
        if (posts.isEmpty()) {
            onFinish("请先选择要收藏的作品。")
            return
        }
        viewModelScope.launch {
            val cfg = config.value
            if (!cfg.loggedIn) {
                onFinish("批量收藏需要先登录账号。")
                return@launch
            }
            _discover.update { it.copy(busy = true) }
            var ok = 0
            var failed = 0
            for (post in posts) {
                try {
                    api.addFavorite(cfg, post.id)
                    ok++
                    updatePostEverywhere(post.copy(isFavorited = true, favCount = post.favCount + 1))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            _discover.update { it.copy(busy = false) }
            markFavoritesDirty()
            onFinish("已收藏 $ok 项" + if (failed > 0) "，失败 $failed 项" else "")
        }
    }

    /** 批量拉黑：把所选作品的全部标签加入黑名单（与桌面版 `_bulk_blacklist` 一致）。 */
    fun batchBlacklist(posts: List<Post>, onFinish: (String) -> Unit) {
        val tags = posts.flatMap { it.tagsFlat }.distinct()
        if (tags.isEmpty()) {
            onFinish("所选作品没有可加入黑名单的标签。")
            return
        }
        viewModelScope.launch {
            var added = 0
            store.update { cfg ->
                val merged = cfg.blacklist.toMutableList()
                tags.forEach { if (it !in merged) { merged.add(it); added++ } }
                cfg.copy(blacklist = merged)
            }
            markFavoritesDirty()
            onFinish("已加入 $added 个标签到黑名单")
        }
    }

    // ------------------------------------------------------------------ 浏览页（搜索结果 / 作者页 / 标签页）
    //
    // v1.5.0：首页搜索、作者页、标签页共用同一个作品浏览页（与发现页同卡片/同批量/同分页），
    // 只是查询条件不同。排序用 e621 的 order:score / order:score_asc / order:hot / order:hot_asc。
    // 黑名单（D10）在查询时通过 -tag 排除；无缩略图的不可见作品（需求 2）在结果里过滤掉。

    data class BrowseState(
        val active: Boolean = false,
        val title: String = "",
        val query: String = "",
        val sort: String = BROWSE_SORT_SCORE,
        val ascending: Boolean = false,
        val posts: List<Post> = emptyList(),
        val loading: Boolean = false,
        val reachedEnd: Boolean = false,
        val error: String? = null,
    )

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse
    private var browseJob: Job? = null

    /** 打开通用浏览页（搜索结果 / 作者作品 / 标签作品）。 */
    fun openBrowse(
        title: String,
        query: String,
        sort: String = BROWSE_SORT_SCORE,
        ascending: Boolean = false,
    ) {
        browseJob?.cancel()
        browseJob = null
        // debug 演示数据：离线也能看到浏览页卡片（搜索/作者/标签页共用）。
        if (DebugFlags.demo) {
            val all = _discover.value.posts
            val posts = if (query.contains(ORDER_VIDEO) || title.contains("视频")) {
                all.filter { it.isVideo }
            } else {
                all
            }
            _browse.value = BrowseState(
                active = true,
                title = title,
                query = query,
                sort = sort,
                ascending = ascending,
                posts = posts,
                loading = false,
                reachedEnd = true,
            )
            return
        }
        _browse.value = BrowseState(
            active = true,
            title = title,
            query = query,
            sort = sort,
            ascending = ascending,
        )
        loadBrowse(reset = true)
    }

    fun closeBrowse() {
        browseJob?.cancel()
        browseJob = null
        _browse.value = BrowseState()
    }

    fun setBrowseSort(sort: String, ascending: Boolean) {
        val cur = _browse.value
        if (!cur.active) return
        if (cur.sort == sort && cur.ascending == ascending) return
        _browse.update { it.copy(sort = sort, ascending = ascending) }
        loadBrowse(reset = true)
    }

    /**
     * 首页搜索（v1.5.2）：接收搜索栏里的**中文气泡列表**，界面只用中文名字；
     * 真正发请求时把每个气泡的英文 tag 拼进查询（未解析的中文先在线翻译成 tag）。
     *
     * 翻译失败时按原文兜底，不阻塞用户；任意一个可用的 tag 就会进入搜索结果页。
     */
    fun searchFromHome(keywords: List<SearchKeyword>) {
        val usable = keywords.filter { it.tag.isNotBlank() }
        if (usable.isEmpty()) return
        viewModelScope.launch {
            val cfg = config.value
            val tags = usable.map { keyword ->
                try {
                    TranslationRepository.translateToEnglish(getApplication(), cfg, keyword.tag)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    keyword.tag
                }
            }.filter { it.isNotBlank() }
            if (tags.isEmpty()) return@launch
            // 标题用气泡里的中文名，后端查询用 tag（v1.5.2 需求）。
            val title = "搜索结果：" + usable.joinToString(" ") { it.label }
            openBrowse(title = title, query = tags.joinToString(","))
        }
    }

    /** 作者页（W1）：点击作者后进入，查询用 artist 标签（没有 artist 时用 uploader 名）。 */
    fun openAuthor(author: String) {
        val name = author.trim()
        if (name.isEmpty()) return
        openBrowse(title = "作者：$name", query = name)
    }

    /** 标签页（C1）：点击标签后进入，不再跳下载搜索框。 */
    fun openTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty()) return
        openBrowse(title = "标签：#$t", query = t)
    }

    fun loadBrowse(reset: Boolean = false) {
        val snapshot = _browse.value
        if (!snapshot.active) return
        if (DebugFlags.demo) {
            _browse.update { it.copy(loading = false, error = null, reachedEnd = true) }
            return
        }
        if (!reset && (snapshot.loading || snapshot.reachedEnd)) return
        if (reset) {
            browseJob?.cancel()
            browseJob = null
            _browse.update { it.copy(posts = emptyList(), loading = true, reachedEnd = false, error = null) }
        } else {
            _browse.update { it.copy(loading = true, error = null) }
        }

        val title = snapshot.title
        val query = snapshot.query
        val sort = snapshot.sort
        val ascending = snapshot.ascending
        val orderTag = when (sort) {
            BROWSE_SORT_HOT -> if (ascending) "order:hot_asc" else "order:hot"
            else -> if (ascending) "order:score_asc" else "order:score"
        }
        val tags = buildList {
            add(orderTag)
            addAll(query.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() })
        }
        val page = if (reset) 1 else snapshot.posts.size / PAGE_LIMIT + 1
        browseJob = viewModelScope.launch {
            try {
                val cfg = config.value
                val batch = api.fetchPosts(
                    cfg = cfg,
                    tags = tags,
                    blacklist = cfg.blacklist,
                    limit = PAGE_LIMIT,
                    page = page,
                )
                val visibleBatch = if (DebugFlags.demo) batch else batch.filter { it.hasVisibleCard }
                _browse.update { cur ->
                    if (!cur.active || cur.query != query || cur.title != title ||
                        cur.sort != sort || cur.ascending != ascending
                    ) {
                        cur // 过期响应
                    } else {
                        val merged = if (reset) visibleBatch else (cur.posts + visibleBatch).distinctBy { it.id }
                        cur.copy(
                            posts = merged,
                            loading = false,
                            reachedEnd = batch.size < PAGE_LIMIT,
                            error = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browse.update { it.copy(loading = false, error = friendlyMessage(e)) }
            }
        }
    }

    // ------------------------------------------------------------------ 收藏页

    data class FavoritesState(
        val posts: List<Post> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val dirty: Boolean = true,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val demo: Boolean = false,
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
        val busy: Boolean = false,
    ) {
        val selectedPosts: List<Post> get() = posts.filter { it.id in selected }
    }

    private val _favorites = MutableStateFlow(FavoritesState())
    val favorites: StateFlow<FavoritesState> = _favorites
    private var favoritesJob: Job? = null

    /** 收藏页在收藏状态发生变化后需要重新拉取。 */
    fun markFavoritesDirty() {
        _favorites.update { it.copy(dirty = true) }
    }

    fun loadFavorites(force: Boolean = false) {
        val cfg = config.value
        if (!cfg.loggedIn) {
            _favorites.update {
                it.copy(
                    posts = emptyList(),
                    loaded = false,
                    loading = false,
                    dirty = true,
                    message = null,
                    selectionMode = false,
                    selected = emptySet(),
                    busy = false,
                )
            }
            return
        }
        val state = _favorites.value
        if (state.demo) return
        if (!force && !state.dirty && state.loaded) return
        if (favoritesJob?.isActive == true) return

        _favorites.update { it.copy(loading = true, message = null, messageIsError = false) }
        favoritesJob = viewModelScope.launch {
            try {
                val all = api.fetchAllFavorites(config.value) { count ->
                    _favorites.update { it.copy(message = "已加载 $count 项…", messageIsError = false) }
                }
                // 与桌面版一致：收藏页也按黑名单过滤，并且标记为已收藏
                val visible = all
                    .filterNot { post -> post.tagsFlat.any { tag -> tag in cfg.blacklist } }
                    .filter { post -> DebugFlags.demo || post.hasVisibleCard }
                    .map { it.copy(isFavorited = true) }
                // 刷新后保留仍存在的选中项，避免"已选 N 项"里混入已被取消收藏的作品
                val visibleIds = visible.map { post -> post.id }.toSet()
                _favorites.update {
                    it.copy(
                        posts = visible,
                        loading = false,
                        loaded = true,
                        dirty = false,
                        message = if (visible.isEmpty()) "还没有收藏" else null,
                        messageIsError = false,
                        selected = it.selected.intersect(visibleIds),
                        selectionMode = if (visible.isEmpty()) false else it.selectionMode,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _favorites.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        dirty = false,
                        message = friendlyMessage(e),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun setFavoritesSelectionMode(enabled: Boolean) {
        _favorites.update { it.copy(selectionMode = enabled, selected = if (enabled) it.selected else emptySet()) }
    }

    fun toggleFavoritesSelected(id: Long) {
        _favorites.update { st ->
            val next = if (id in st.selected) st.selected - id else st.selected + id
            st.copy(selected = next)
        }
    }

    fun selectAllFavorites() {
        _favorites.update { it.copy(selected = it.posts.map { p -> p.id }.toSet()) }
    }

    fun clearFavoritesSelection() {
        _favorites.update { it.copy(selected = emptySet()) }
    }

    /**
     * 批量取消收藏（收藏页多选，与桌面版一致：逐项请求、失败继续）。
     * 成功一项就立刻从 `_favorites` 里移除，不必等整批结束；结束后标脏以便下次刷新对齐服务端。
     */
    fun batchRemoveFavorite(posts: List<Post>, onFinish: (String) -> Unit) {
        if (posts.isEmpty()) {
            onFinish("请先选择要取消收藏的作品。")
            return
        }
        viewModelScope.launch {
            val cfg = config.value
            if (!cfg.loggedIn) {
                onFinish("批量取消收藏需要先登录账号。")
                return@launch
            }
            _favorites.update { it.copy(busy = true) }
            var ok = 0
            var failed = 0
            try {
                for (post in posts) {
                    try {
                        api.removeFavorite(cfg, post.id)
                        ok++
                        updatePostEverywhere(post.withFavorite(false))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                }
            } finally {
                _favorites.update {
                    it.copy(busy = false, selectionMode = false, selected = emptySet())
                }
                markFavoritesDirty()
            }
            onFinish("已取消收藏 $ok 项" + if (failed > 0) "，失败 $failed 项" else "")
        }
    }

    // ------------------------------------------------------------------ 详情页

    private val _selectedPost = MutableStateFlow<Post?>(null)
    val selectedPost: StateFlow<Post?> = _selectedPost

    /**
     * 详情页左右滑动所需的「上下文列表」：点开详情时由来源列表（发现/收藏）提供，
     * 详情页用 `HorizontalPager` 在它们之间滑动切换上一个/下一个作品。
     * 按 id 去重、保持来源顺序；只有单个作品时退化为一页。
     */
    private val _detailPosts = MutableStateFlow<List<Post>>(emptyList())
    val detailPosts: StateFlow<List<Post>> = _detailPosts

    /**
     * 打开详情页。
     *
     * [contextPosts] 是当前列表页的作品列表（发现页 / 收藏页），用于左右滑动切换；
     * 从其它入口（如设置页跳转、深链）打开时保持默认的 `listOf(post)`，即单页详情。
     * 被点击的作品即使不在 [contextPosts] 里（列表刚好刷新过）也会补进末尾，
     * 保证详情页至少能显示它自己，不会出现空白页。
     */
    fun openDetail(post: Post, contextPosts: List<Post> = listOf(post)) {
        val merged = (contextPosts + post).distinctBy { it.id }
        _detailPosts.value = merged
        _selectedPost.value = merged.firstOrNull { it.id == post.id } ?: post
    }

    /** 左右滑动切换详情页作品：只更新当前作品，不动上下文列表（供 TopAppBar/分享等使用）。 */
    fun showDetailPost(post: Post) {
        _selectedPost.value = post
    }

    fun closeDetail() {
        _selectedPost.value = null
        _detailPosts.value = emptyList()
    }

    /** 收藏 / 取消收藏。成功后会同步更新发现页、收藏页与详情页里的同一作品。 */
    fun setFavorite(post: Post, favorited: Boolean, onFinish: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val cfg = config.value
            if (!cfg.loggedIn) {
                onFinish(false, "收藏需要先登录账号。")
                return@launch
            }
            try {
                if (favorited) api.addFavorite(cfg, post.id) else api.removeFavorite(cfg, post.id)
                val updated = post.withFavorite(favorited)
                updatePostEverywhere(updated)
                markFavoritesDirty()
                onFinish(true, if (favorited) "已添加到收藏" else "已取消收藏")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFinish(false, friendlyMessage(e))
            }
        }
    }

    private fun updatePostEverywhere(post: Post) {
        _selectedPost.update { if (it?.id == post.id) post else it }
        // 详情页左右滑动列表里的同一作品也要同步（收藏状态/收藏数变化后翻回去仍是新数据）
        _detailPosts.update { list -> list.map { if (it.id == post.id) post else it } }
        _discover.update { st -> st.copy(posts = st.posts.map { if (it.id == post.id) post else it }) }
        _browse.update { st -> st.copy(posts = st.posts.map { if (it.id == post.id) post else it }) }
        _favorites.update { st ->
            val posts = if (post.isFavorited) {
                st.posts.map { if (it.id == post.id) post else it }
            } else {
                st.posts.filterNot { it.id == post.id }
            }
            st.copy(posts = posts)
        }
    }

    // ------------------------------------------------------------------ 下载

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress
    private var downloadJob: Job? = null

    val isDownloading: Boolean get() = downloadJob?.isActive == true

    fun startDownload(
        tags: List<String>,
        blacklist: List<String>,
        quality: String,
        maxPages: Int,
        limit: Int,
        minScore: Int?,
        onFinish: (String) -> Unit,
    ) {
        if (isDownloading) return
        val cfg = config.value
        downloadJob = viewModelScope.launch {
            _progress.value = DownloadProgress(message = "准备中…")
            try {
                val result = engine.download(
                    cfg = cfg,
                    tags = tags,
                    blacklist = blacklist,
                    quality = quality,
                    maxPages = maxPages,
                    limit = limit,
                    minScore = minScore,
                    onProgress = { _progress.value = it },
                )
                onFinish(result.message)
            } catch (e: CancellationException) {
                onFinish("已取消")
            } catch (e: Exception) {
                onFinish(friendlyMessage(e))
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun downloadSingle(post: Post, quality: String, onFinish: (String) -> Unit) {
        viewModelScope.launch {
            val cfg = config.value
            val url = post.imageUrl(quality)
            if (url.isNullOrBlank()) {
                onFinish("没有可下载的图片地址")
                return@launch
            }
            val target = java.io.File(engine.downloadDir, post.targetFilename())
            val tmp = java.io.File(engine.downloadDir, "${post.targetFilename()}.part")
            val ok = try {
                engine.downloadOne(cfg, url, tmp, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok && cfg.loggedIn && cfg.rememberHistory && post.md5.isNotBlank()) {
                engine.recordSeenMd5(post.md5)
            }
            onFinish(if (ok) "已保存：${target.name}" else "下载失败")
        }
    }

    fun exportZip(uri: Uri, onFinish: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val count = engine.exportZip(uri)
                onFinish(if (count == 0) "下载目录为空" else "已导出 $count 个文件")
            } catch (e: Exception) {
                onFinish("导出失败：${e.message}")
            }
        }
    }

    fun openDownloadDirHint(): String = engine.downloadDir.absolutePath

    /** 批量下载给定作品（收藏页/批量选择用），按顺序逐张下载，失败不影响后续。 */
    fun downloadPosts(posts: List<Post>, quality: String, onFinish: (String) -> Unit) {
        if (posts.isEmpty()) {
            onFinish("没有可下载的作品")
            return
        }
        viewModelScope.launch {
            val cfg = config.value
            val remember = cfg.loggedIn && cfg.rememberHistory
            var ok = 0
            var skipped = 0
            var failed = 0
            for (post in posts) {
                if (post.isVideo) {
                    skipped++
                    continue
                }
                val url = post.imageUrl(quality)
                if (url.isNullOrBlank()) {
                    failed++
                    continue
                }
                val target = java.io.File(engine.downloadDir, post.targetFilename())
                if (target.exists() && target.length() > 0) {
                    skipped++
                    if (remember && post.md5.isNotBlank()) engine.recordSeenMd5(post.md5)
                    continue
                }
                val tmp = java.io.File(engine.downloadDir, "${post.targetFilename()}.part")
                try {
                    if (engine.downloadOne(cfg, url, tmp, target)) {
                        ok++
                        if (remember && post.md5.isNotBlank()) engine.recordSeenMd5(post.md5)
                    } else {
                        failed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            onFinish("完成：成功 $ok，跳过 $skipped，失败 $failed")
        }
    }

    // ------------------------------------------------------------------ 配置

    fun setMode(mode: String) = update { it.copy(mode = mode) }
    fun setTheme(theme: String) = update { it.copy(theme = theme) }
    fun setQuality(quality: String) = update { it.copy(quality = quality) }
    fun setTranslationEngine(engine: String) = update {
        it.copy(
            translationEngine = if (engine == Config.TRANSLATION_LOCAL) {
                Config.TRANSLATION_LOCAL
            } else {
                Config.TRANSLATION_GOOGLE
            },
        )
    }
    fun setTranslateTags(enabled: Boolean) = update { it.copy(translateTags = enabled) }

    /** 用户选择“跳过此版本”后不再为该 tag 弹更新提示。 */
    fun skipUpdateVersion(tag: String) = update { it.copy(skippedUpdateTag = tag) }
    fun setRememberHistory(enabled: Boolean) = update { it.copy(rememberHistory = enabled) }
    fun setKeywordMemory(limit: Int) = update { it.copy(keywordMemory = limit.coerceIn(0, Config.MAX_KEYWORD_MEMORY)) }
    fun setSearchDefaults(maxPages: Int, limit: Int, minScore: Int) = update {
        it.copy(
            maxPages = maxPages.coerceIn(1, 50),
            limit = limit.coerceIn(1, 320),
            minScore = minScore,
        )
    }

    fun saveLogin(username: String, apiKey: String) = update {
        it.copy(username = username.trim(), apiKey = apiKey.trim())
    }

    fun logout() = update { it.copy(username = "", apiKey = "") }

    fun addBlacklist(tag: String) = update { cfg ->
        val t = tag.trim()
        if (t.isEmpty() || cfg.blacklist.contains(t)) cfg else cfg.copy(blacklist = cfg.blacklist + t)
    }

    fun removeBlacklist(tag: String) = update { cfg ->
        cfg.copy(blacklist = cfg.blacklist.filterNot { it == tag })
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun forgetKeyword(keyword: String) = update { cfg ->
        cfg.copy(recentKeywords = cfg.recentKeywords.filterNot { it == keyword })
    }

    fun rememberKeyword(keyword: String) {
        viewModelScope.launch { store.addRecentKeyword(keyword) }
    }

    fun rememberHistory(tags: List<String>, blacklist: List<String>) {
        viewModelScope.launch { store.addHistory(tags, blacklist) }
    }

    fun validateLogin(username: String, apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (ok, _, msg) = api.validateLogin(username, apiKey)
            onResult(ok, msg)
        }
    }

    /** 清空图片缓存：同时清 Coil 的磁盘缓存与内存缓存（旧实现只删目录，句柄未释放时无效）。 */
    fun clearImageCache(): Boolean = try {
        val loader = SingletonImageLoader.get(getApplication())
        loader.diskCache?.clear()
        loader.memoryCache?.clear()
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ 调试演示数据

    /**
     * 仅 debug 构建 + 启动参数 `e6dl_demo=true` 时调用：
     * 注入一批**本地假数据**，用于在没有网络（开发期硬禁网）的情况下验证三种尺寸下的
     * 网格/列表/详情布局。release 构建永远不会进入这里。
     */
    fun seedDebugShowcase() {
        if (!BuildConfig.DEBUG) return
        val samples = buildList {
            repeat(12) { index ->
                val id = 900000L + index
                val wide = index % 3 == 0
                add(
                    Post(
                        id = id,
                        md5 = "demo%04d".format(index).padEnd(32, '0').take(32),
                        ext = "jpg",
                        fileUrl = null,
                        sampleUrl = null,
                        previewUrl = null,
                        width = if (wide) 1920 else 1200,
                        height = if (wide) 1080 else 1600,
                        score = 42 + index,
                        rating = when (index % 3) { 0 -> "s"; 1 -> "q"; else -> "e" },
                        tags = linkedMapOf(
                            "general" to listOf("demo_tag_$index", "sample"),
                            "artist" to listOf("demo_artist"),
                        ),
                        isFavorited = index < 6,
                        favCount = 100 + index,
                        uploaderName = "demo_uploader",
                        commentCount = 3 + index,
                    ),
                )
            }
            // 演示用视频作品：videoUrl 指向一个不可达地址，用来验证播放器 UI 与错误提示
            // （开发构建没有 INTERNET 权限，本来也放不了真实视频；发布版会替换成 e621 的视频地址）。
            add(
                Post(
                    id = 900012L,
                    md5 = "demoVideo00000000000000000000000".take(32),
                    ext = "webm",
                    fileUrl = null,
                    sampleUrl = null,
                    previewUrl = null,
                    width = 1280,
                    height = 720,
                    score = 55,
                    rating = "q",
                    tags = linkedMapOf(
                        "general" to listOf("demo_video", "sample"),
                        "artist" to listOf("demo_artist"),
                    ),
                    isFavorited = false,
                    favCount = 9,
                    uploaderName = "demo_uploader",
                    commentCount = 1,
                    videoUrl = "https://example.invalid/demo.webm",
                ),
            )
        }
        _discover.value = DiscoverState(
            posts = samples,
            loading = false,
            reachedEnd = true,
            demo = true,
        )
        _favorites.value = FavoritesState(
            posts = samples.take(8).map { it.copy(isFavorited = true) },
            loading = false,
            loaded = true,
            dirty = false,
            demo = true,
        )
    }

    private fun update(transform: (Config) -> Config) {
        viewModelScope.launch { store.update(transform) }
    }

    init {
        // 演示数据必须在首帧之前就位，避免"先显示空态、随后才出现数据"的闪动。
        // 注意：这段 init 必须放在 _discover / _favorites 等属性初始化之后（Kotlin 按声明顺序执行）。
        if (DebugFlags.demo) seedDebugShowcase()
    }

    companion object {
        /** 与桌面版 `DiscoverPage.PAGE_LIMIT` 一致。 */
        const val PAGE_LIMIT = 24
        const val ORDER_SCORE = "order:score"
        const val ORDER_NEWEST = "order:newest"
        const val ORDER_RANDOM = "order:random"
        /** v1.5.0：替换“随机”板块的“视频”板块（e621 的 type:video）。 */
        const val ORDER_VIDEO = "type:video"
        const val BROWSE_SORT_SCORE = "score"
        const val BROWSE_SORT_HOT = "hot"
    }
}
