# PanClone

一个面向 Android 12 及以上设备的第三方网盘客户端实验项目，使用 Kotlin 与 Jetpack Compose 编写。项目重点验证文件浏览、上传下载、分享、图片/音视频预览，以及基于实时背景采样的悬浮玻璃界面。

> **非官方项目。** 本项目与百度、百度网盘及其关联公司没有隶属、合作、授权、审核或背书关系。

## 当前能力

- 网盘 Cookie 登录与加密保存
- 文件夹列表/平铺浏览、搜索、排序和多选操作
- 新建、重命名、复制、移动、删除、分享与目录定位
- 上传与下载任务管理
- 图片左右翻页、缩放、长按下载
- Media3 音频预览、上一曲/下一曲、可收起播放列表
- Media3 视频在线播放、进度记忆与倍速播放
- 浅色、深色和跟随系统主题
- 文件页悬浮顶栏、底栏及操作条的实时背景模糊

## 技术与组件

| 用途 | 实现 |
|---|---|
| 语言与 UI | Kotlin 2.4.20、Jetpack Compose、Material 3 |
| Android 构建 | AGP 9.5.2、Gradle 9.9.2、JDK 21、compileSdk 37.2 |
| 状态管理 | AndroidX Lifecycle ViewModel、StateFlow、Compose state |
| 实时模糊 | `io.github.kyant0:backdrop:2.0.1` |
| 玻璃形状 | `io.github.kyant0:shapes:1.2.1` |
| 图片加载 | Coil Compose 3.6.3、Coil OkHttp |
| 音视频播放 | AndroidX Media3 ExoPlayer 1.11.1 |
| 网络 | OkHttp 5.5.1、Kotlinx Serialization |
| 登录信息存储 | AndroidX Security Crypto、EncryptedSharedPreferences |
| 异步任务 | Kotlin Coroutines 1.10.2 |

第三方组件继续适用其各自的许可证和版权声明；本仓库的 MIT 许可证不会覆盖第三方代码、商标、图片、图标、二进制库或服务端接口。

## 关键实现

### 文件页与实时模糊

文件页支持 `LazyColumn` 列表和 `LazyVerticalGrid` 平铺，两种展示方式在排序面板内切换并分别记忆滚动位置。页面内容通过一个 `LayerBackdrop` 统一采样，顶栏、排序面板、悬浮按钮、多选操作条和悬浮底栏只消费该背景层，保证列表与平铺的实时模糊一致。排序面板参考 123 网盘 PanNative 的右上锚定结构，以 Compose 和实时 backdrop 重新实现。

主要代码：

- `ui/screen/files/FilesScreen.kt`
- `ui/screen/files/FilesViewModel.kt`
- `ui/components/TopFadeBlur.kt`
- `ui/components/FloatingActionFab.kt`
- `ui/components/LiquidGlassTabBar.kt`

### 搜索结果定位

搜索结果中的单个文件进入多选后可选择“所在位置”。客户端根据文件完整路径直接加载父目录；目录项目超过一页时会继续分页，直到找到目标文件或到达目录末尾。加载完成后使用无动画的直达定位将目标置于可见区域，只把目标文件名标红，不添加背景或边框。

### 图片预览

图片使用 Compose `HorizontalPager`。每张图片按 `fs_id` 独立保存加载状态，并预加载相邻页面；原图加载期间继续显示缩略图，避免切换时闪黑或串图。预览控制项默认隐藏，单击显示，切换图片后自动收起。长按图片会显示复用实时模糊组件实现的下载操作条。

主要代码：

- `ui/screen/preview/PreviewScreen.kt`
- `ui/screen/preview/PreviewViewModel.kt`

### 音频与视频

音频和视频均使用开源的 AndroidX Media3 ExoPlayer。音频播放列表从当前目录中的音频文件生成，右上角计数按钮可展开播放列表、直接选曲或下滑收起；封面优先读取音频文件内嵌 artwork，并以网盘缩略图和默认图标降级。视频播放器支持进度记忆、播放暂停、倍速和横屏。

主要代码：

- `ui/screen/player/AudioPlayerScreen.kt`
- `ui/screen/player/PlayerScreen.kt`
- `ui/screen/player/PlayerViewModel.kt`


### APK 安装

下载完成的 APK 通过 `FileProvider` 生成只读 URI，显式授予系统安装器读取权限，并在 Android 8 及以上检查“安装未知应用”授权。Release 使用递增的 `versionCode`，可作为上一版本的更新安装。


### 上传（应用内管理器）

上传不再调用系统文件选择器。`LocalFilePickerScreen` 提供应用内浏览、多选文件与文件夹选择；文件夹上传会先按相对路径创建云端目录，再逐文件加入上传队列（`UploadCoordinator`）。第三方应用通过 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 分享的文件，会先弹出目录选择，再进入同一上传链路。

### 音乐页

底栏新增独立的「音乐」入口，图标为自绘线性音符。进入后直接列出网盘根目录下的音频文件，点击即用同一套 MediaSession 播放，与全屏播放器、迷你播放器共享播放状态。

### 播放器行为

- 播放队列在开始时即补齐当前曲之后的曲目，通知栏与迷你播放器始终具备上一曲/下一曲。
- 点击列表任意一首会在播放器队列中跳转；未取链的曲目会先取链再播放。
- 播放视频时自动停止音频并隐藏迷你播放器。
- 迷你播放器支持左右跟手滑动移除（同时停止前台服务）与上拉跟手展开完整播放器。
- 播放中每秒刷新进度，暂停后降频轮询以降低耗电。

### 视频画中画

播放中按 Home 离开任务时由系统自动进入画中画；返回键与顶部返回始终是正常退出，不触发画中画。

### 图片预览

图片的顶部信息栏与底部操作栏使用同一显隐状态：默认隐藏，单击图片显示，切换图片后自动收起。底部一级为下载/分享/删除/更多，二级为复制/移动/重命名/文件详细；重命名输入框与操作栏会自动避让输入法。

### 分享链接（暂缓）

百度分享链接的深链识别、转存与分享下载当前**未启用**：实测未登录访问 `share/list` 返回 `9019 need verify`，必须依赖登录 Cookie 与 `BDCLND` 校验，缺少真实分享样本无法完成端到端验证。相关入口已关闭，不影响其他功能。

### 后台音频与迷你播放器

音频播放由 `MediaSessionService` 和单例 `MediaController` 驱动，支持自动下一曲、后台播放、系统媒体通知、锁屏控制和主界面悬浮迷你播放器。迷你播放器复用悬浮底栏的 `vibrancy + blur + lens` 参数，但不修改原底栏源码。音频内嵌封面保存到应用缓存目录，后续播放不再重复联网提取；播放中的封面使用 `NowPlayingAlbumArtShape.Waved` 并仅在播放时缓慢旋转。后台进度轮询在播放时为 1 秒、暂停时为 5 秒，以降低耗电。

### 视频画中画

视频页面使用 Android 原生 Picture-in-Picture。Android 12 及以上在播放中按 Home 自动进入画中画，点击返回也会优先进入画中画继续播放；进入画中画后自动隐藏 Compose 控制层。

### 分享深链

应用注册 `pan.baidu.com`、`yun.baidu.com`、`eyun.baidu.com` 和 `bdnetdisk://` 深链，并识别其他应用通过 `ACTION_SEND text/plain` 分享的百度网盘链接。分享页支持目录浏览、多选、选择目标路径转存，以及取得分享直链后加入下载队列。实现参考未加密 123 网盘 PanNative 的深链、`share/verify`、`share/list`、`share/transfer` 和分享下载流程，并针对本项目数据模型重新实现。

### 图片文件操作

图片预览底部常驻实时模糊操作栏，一级提供下载、分享、删除和更多；二级提供复制、移动、内联重命名和文件详细。文件页与图片预览的重命名都直接覆盖原操作栏显示输入框，不再打开单独重命名弹窗。

### 网络与登录态

客户端通过 OkHttp 请求网盘接口。登录 Cookie 使用 `EncryptedSharedPreferences` 保存。任何 Cookie 都等同于账号访问凭据，请只在可信、自行审核的构建中使用，不要上传日志、截图或配置文件中的登录信息。

## 构建

### GitHub Actions

推送到 `main` 后，`.github/workflows/build.yml` 会：

1. 配置 JDK 21 与 Gradle 9.9.2；
2. 安装 Android SDK 37.2；
3. 执行 `:app:assembleRelease`，并启用 R8 代码压缩、混淆和资源收缩；
4. 上传 APK Artifact；
5. 为 `main` 推送创建 GitHub Release。

### 本地 Debug

准备 JDK 21、Gradle 9.9.2 和 Android SDK 37.2 后执行：

```bash
gradle :app:assembleDebug
```

Release 构建需要自行提供合法的签名文件与 `keystore.properties`。不要把私人签名密码、Cookie 或访问令牌提交到公开仓库。

## 安全、合规与免责声明

- 本项目仅用于学习、研究和个人技术验证，不提供任何网盘服务。
- 使用者必须遵守所在地法律法规、百度网盘服务协议及接口使用规则，不得用于绕过权限、滥用接口、侵犯版权或访问无权使用的数据。
- 项目不保证接口长期可用、数据完整、上传下载可靠、账号安全或与官方客户端行为完全一致。
- 使用、编译、安装或分发本项目所造成的账号限制、数据丢失、设备故障、隐私泄露或其他损失，由使用者自行承担。
- “百度”“百度网盘”及相关名称、商标和官方视觉资源归其权利人所有。仓库中可能存在用于兼容性研究的第三方图标、图片或二进制资源，这些内容不属于 MIT 授权范围；如有权利问题请提交 Issue，维护者将核实并处理。
- 不应将本项目描述为百度官方客户端、授权客户端或安全审计通过的产品。

## AI 生成声明

本项目的大部分源代码、UI 迭代、调试修改和文档由项目维护者通过 AI 编程助手生成或辅助生成。AI 生成内容可能存在逻辑错误、安全问题、无效实现或许可证识别遗漏，且没有经过百度官方审核。使用或贡献代码前请进行人工审查、真机测试和安全检查。

## 开源协议

项目维护者原创部分采用 [MIT License](LICENSE) 发布。

以下内容不包含在 MIT 授权中：

- 第三方依赖及其源码；
- 百度及其他权利人的名称、商标、图标、图片与视觉资源；
- 未由项目维护者原创的二进制库；
- 远端服务、接口、数据和内容。

分发修改版时，请保留本 README、MIT License、AI 生成声明与免责声明，并根据实际使用的第三方组件补充对应许可证。
