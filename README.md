# E6DL（Android）

e621 的第三方 Android 客户端：浏览发现、按标签搜索批量下载、收藏与黑名单管理。
Kotlin + Jetpack Compose + Material 3 实现，与桌面版 [E621Downloader](https://github.com/MikeSteve6597/E621Downloader) 功能对齐。

> [!WARNING]
> e621 是面向成年人的内容站点，部分内容可能包含成人向素材。本项目仅提供第三方客户端工具，与 e621 官方无任何关联。
> 请确认你已年满 18 周岁，并遵守所在地法律法规及 e621 的使用条款。

## 当前版本

**v1.0.1**（versionCode 4）

- 修复：e621 接口返回结构改版导致的解析失败（列表由 `{posts:[…]}` 变为数组、作品字段由 `file/sample/preview` 变为 `files.*`）
- 修复：错误提示不再把原始 JSON 显示到界面
- 功能：发现 / 搜索下载 / 收藏 / 黑名单 / 设置 / 详情 / 登录，与桌面版对齐

## 下载与安装

1. 到 [Releases](../../releases/latest) 下载 `E6DL-<版本>-arm64-v8a.apk`；
2. 允许安装未知来源应用后安装（**仅 arm64-v8a**，Android 7.0+）；
3. 首次启动为**单机模式**（不联网），需要联网时到 **设置 › 运行模式** 手动切到「在线模式」。

> 默认单机是本项目的设计取向：未经用户明确切换，程序不会发起任何网络请求。

## 功能

- **发现**：热门 / 最新 / 随机三种排序、关键词过滤、滚动加载
- **搜索下载**：多标签、临时黑名单、三种画质（原图/采样/预览）、页数 1–50、每页 1–320、最低评分
- **下载**：实时进度、可取消、`.part` 临时文件、MD5 去重、应用专属目录、可导出 zip 到任意文件夹
- **收藏**：逐页拉取（每页 200、最多 25 页、按 id 去重）、批量下载
- **黑名单**：持久化标签，发现/搜索/收藏统一过滤
- **设置**：运行模式、主题（跟随系统/浅/深）、默认画质、关键词记忆条数、账号、清空历史/缓存
- **自适应布局**：手机底部导航；≥600dp 自动切左侧导航栏；网格列数随窗口自适应

## 从源码构建

需要 JDK 21 + Android SDK（platform 37.2、build-tools 37.0.0）。

```bash
# 1. 指定 SDK 路径（该文件不入库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 调试包（无 INTERNET 权限，被设计为不能联网）
./gradlew :app:assembleDebug

# 3. 发布包（需要自备签名，见下）
./gradlew :app:assembleRelease
```

**版本约束**：AGP 9.5.2 内置 Kotlin 编译器为 2.2（最高读 2.3 元数据），因此依赖不能选编译自 Kotlin 2.4 的版本
（例如 Coil 3.5+/OkHttp 5.4+ 会把 `kotlin-stdlib` 拉到 2.4 导致编译失败）。当前锁定 Coil 3.4.0 + OkHttp 5.3.0。

## 签名

发布包使用**自签名**（不上架 Google Play）。`keystore.properties` 与 `*.jks` 均被 `.gitignore` 排除，仓库中不含任何密钥。
自行构建发布包时，请用自己的 keystore 覆盖 `keystore.properties` 后再执行 `assembleRelease`。

## 数据与隐私

| 内容 | 位置 |
| --- | --- |
| 配置、账号（用户名 + API Key） | 应用私有 DataStore（`/data/data/com.mikesteve.e621downloadler/…`） |
| 下载文件 | 应用专属外部目录 `Android/data/com.mikesteve.e621downloadler/files/E6dl/`（卸载即删） |
| 去重记录 | 同上目录下的 `downloaded_md5.txt`（仅登录且勾选保存历史时写入） |
| 图片缓存 | 应用缓存目录（上限 256 MB，可在设置页清空） |

- 所有数据仅保存在本机，程序不含遥测或上传行为。
- API Key 保存在本机应用私有目录；请勿分享配置，也不要把 `keystore.properties`、`*.jks` 提交到仓库。

## 免责声明

- 本项目为第三方开源工具，与 e621 官方及其运营方无任何关联。
- 请自行确保使用行为符合 e621 的服务条款、API 使用规范以及你所在地的法律法规。
- 请勿使用本工具大量抓取内容或对 e621 服务器造成压力。
- 使用者需自行承担因使用本工具产生的一切后果。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。

第三方依赖：Jetpack Compose（Apache-2.0）、OkHttp（Apache-2.0）、Coil（Apache-2.0）。
