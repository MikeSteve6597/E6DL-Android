# E6DL（Android）

e621 的第三方 Android 客户端：浏览发现、按标签搜索批量下载、收藏与黑名单管理。
Kotlin + Jetpack Compose + Material 3 实现，与桌面版 [E621Downloader](https://github.com/MikeSteve6597/E621Downloader) 功能对齐。

> [!WARNING]
> e621 是面向成年人的内容站点，部分内容可能包含成人向素材。本项目仅提供第三方客户端工具，与 e621 官方无任何关联。
> 请确认你已年满 18 周岁，并遵守所在地法律法规及 e621 的使用条款。

## 当前版本

**v1.3.0**（versionCode 7）

- **修复启动运行模式**：先加载持久化配置再发请求；上次设为「在线」后重启直接按在线加载，不再误报单机，也不用重选
- **修复评分 0 / 不显示作者**：适配 e621 新 Posts 结构（`stats.score.total`、`stats.fav_count`、`stats.comment_count`、`uploader_name`），同时兼容旧结构；卡片与详情显示作者（artist 标签优先，其次上传者）
- **主页常驻复选框**：发现/收藏页每张卡片可勾选，批量下载 / 收藏 / 拉黑；长按卡片也可进入选择
- **应用内视频播放**：详情页用 Media3/ExoPlayer 播放（webm/mp4/采样逐级回退；不支持编码提示浏览器打开）
- **评论**：详情页查看评论、登录后发表评论（置顶/隐藏/分数/时间）
- 单元测试 14 → **48**；文案资源化继续（`strings.xml` 共 163 条 `e6dl_*`）
- 保留 v1.2.0/v1.1.0 的批量操作、分享、标签搜索、自适应布局、Keystore 加密等全部能力

## 下载与安装

1. 到 [Releases](../../releases/latest) 下载 `E6DL-<版本>-arm64-v8a.apk`；
2. 允许安装未知来源应用后安装（**仅 arm64-v8a**，Android 7.0+）；
3. 首次启动为**单机模式**（不联网），需要联网时到 **设置 › 运行模式** 手动切到「在线模式」。

> 默认单机是本项目的设计取向：未经用户明确切换，程序不会发起任何网络请求。

## 功能

- **发现**：热门 / 最新 / 随机三种排序、关键词过滤、滚动加载；多选批量收藏/拉黑/下载
- **搜索下载**：多标签、临时黑名单、三种画质（原图/采样/预览）、页数 1–50、每页 1–320、最低评分；历史记录可点击回填
- **下载**：实时进度、可取消、`.part` 临时文件、MD5 去重、应用专属目录、可导出 zip 到任意文件夹
- **收藏**：逐页拉取（每页 200、最多 25 页、按 id 去重）、黑名单过滤、多选批量取消收藏/拉黑/下载
- **详情**：大图/视频播放、评分/分级/收藏数/评论数/作者/标签；收藏、单张下载、系统分享、在浏览器打开；点标签直接搜索
- **评论**：查看评论列表（公开），登录后发表评论
- **黑名单**：持久化标签，发现/搜索/收藏统一过滤
- **设置**：运行模式、主题（跟随系统/浅/深）、默认画质、关键词记忆条数、账号、清空历史/缓存
- **自适应布局**：手机底部导航；≥600dp 左侧 Rail；≥840dp 发现/收藏为 List-Detail 双栏；
  搜索/黑名单/设置整页展示（最大 900dp 居中）；网格列数随窗口自适应

## 从源码构建

需要 JDK 21 + Android SDK（platform 37.2、build-tools 37.0.0）。

```bash
# 1. 指定 SDK 路径（该文件不入库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 调试包（无 INTERNET 权限，被设计为不能联网）
./gradlew :app:assembleDebug

# 3. 单元测试（解析兼容性 / 配置编解码 / 搜索标签）
./gradlew :app:testDebugUnitTest

# 4. 发布包（需要自备签名，见下）
./gradlew :app:assembleRelease
```

**版本约束**：AGP 9.5.2 内置 Kotlin 编译器为 2.2（最高读 2.3 元数据），因此依赖不能选编译自 Kotlin 2.4 的版本
（例如 Coil 3.5+/OkHttp 5.4+ 会把 `kotlin-stdlib` 拉到 2.4 导致编译失败）。当前锁定 Coil 3.4.0 + OkHttp 5.3.0。

### 本机验证（可选）

- `tools\android-verify.ps1`：在模拟器上按 360/600/840/1200dp × 横竖屏 × 深浅色跑截图矩阵；
  加 `-IncludeFoldableStates` 可测折叠屏合盖/半折/展开。debug 包用
  `adb shell am start -n com.mikesteve.e621downloadler/.MainActivity --ez e6dl_demo true`
  启动会注入本地演示数据（仅 debug，开发期硬禁网时也能验证布局）。

## 签名

发布包使用**自签名**（不上架 Google Play）。`keystore.properties` 与 `*.jks` 均被 `.gitignore` 排除，仓库中不含任何密钥。
自行构建发布包时，请用自己的 keystore 覆盖 `keystore.properties` 后再执行 `assembleRelease`。

## 数据与隐私

| 内容 | 位置 |
| --- | --- |
| 配置、账号（用户名 + API Key） | 应用私有 DataStore；**API Key 由 Android Keystore 加密**后单独存放 |
| 下载文件 | 应用专属外部目录 `Android/data/com.mikesteve.e621downloadler/files/E6dl/`（卸载即删） |
| 去重记录 | 同上目录下的 `downloaded_md5.txt`（仅登录且勾选保存历史时写入） |
| 图片缓存 | 应用缓存目录（上限 256 MB，可在设置页清空） |

- 所有数据仅保存在本机，程序不含遥测或上传行为。
- API Key 使用 Android Keystore（不可导出密钥）加密；应用卸载或系统清除凭据后需重新登录。
- 请勿分享配置，也不要把 `keystore.properties`、`*.jks` 提交到仓库。

## 免责声明

- 本项目为第三方开源工具，与 e621 官方及其运营方无任何关联。
- 请自行确保使用行为符合 e621 的服务条款、API 使用规范以及你所在地的法律法规。
- 请勿使用本工具大量抓取内容或对 e621 服务器造成压力。
- 使用者需自行承担因使用本工具产生的一切后果。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。

第三方依赖：Jetpack Compose（Apache-2.0）、OkHttp（Apache-2.0）、Coil（Apache-2.0）。
