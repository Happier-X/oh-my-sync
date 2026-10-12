# 轻同步

使用 Kotlin 和 miuix 构建的原生安卓文件夹同步应用。支持手机文件夹到 OneDrive 的单向上传，并提供开发中的双向曲库模式，支持 Android 8.0 及以上。

## 已实现功能

- 系统文件夹选择器与持久化只读授权，递归同步文件夹和子文件夹。
- OneDrive 浏览器登录、OAuth 授权码与 PKCE 校验、令牌刷新；不使用客户端密钥。
- Android Keystore 加密保存令牌，禁止备份和设备迁移这些应用数据。
- 多个文件夹任务，手动同步和定时同步，任务编辑、暂停和恢复。
- 不计费网络、充电和低电量约束；前台同步通知与停止操作。
- SHA-256 内容比较，云端版本检查，10 MiB 分块上传、当前会话内的上传位置恢复、限流和临时故障重试。
- 上传进度、冲突提示与最近 100 条同步记录。
- miuix 原生主题、脚手架布局、卡片、按钮、输入框、开关和底部导航，支持系统深色模式。

## 技术栈

| 部分 | 版本或实现 |
|---|---|
| 构建 | Android Gradle Plugin 9.4.1，Gradle 9.8.1 |
| 语言 | Kotlin 2.4.21，使用 AGP 内置 Kotlin 支持 |
| 界面 | Jetpack Compose，版本清单 2026.09.00，miuix 0.9.4 |
| 安卓平台 | 编译及目标 API 37，最低 API 26 |
| 任务 | WorkManager 2.12.0，协程与前台任务 |
| 数据 | DataStore 1.2.1，Kotlin 序列化 1.11.0 |
| 网络 | OkHttp 5.5.0，Microsoft Graph v1.0 |

工程遵循 [miuix 官方示例](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4/example) 的主题和脚手架用法，并使用其发布组件库；当前为安卓单模块工程。

## 构建与安装

需要 JDK 17 或更高版本，以及包含 API 37 的安卓 SDK。建议使用近期稳定版 Android Studio 打开工程。构建脚本的 Java 字节码目标为 17。

在 Windows PowerShell 中执行，路径按本机实际位置调整：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

安装包位于 `app/build/outputs/apk/debug/app-debug.apk`，可传到手机安装。通过安卓调试桥安装：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

当前版本为 **0.0.1 预览版**，发行内容与限制见 [发行说明](docs/releases/0.0.1.md)。

发行构建执行 `./gradlew.bat :app:assembleRelease :app:lintRelease`。本机发行签名配置位于被忽略的 `.signing/release.properties`，包含 `storeFile`、`storePassword`、`keyAlias` 与 `keyPassword`，密钥不会上传仓库。没有签名配置时发行构建产出未签名包。请妥善离线备份发行密钥和配置，后续升级必须沿用同一签名。

## OneDrive 首次连接

应用已内置“轻同步”的公开客户端 ID，用户无需注册 Azure、提供银行卡或填写客户端 ID。

1. 在“账户”页面点击“连接 OneDrive”，通过系统浏览器登录微软账号并授权。
2. 登录具有 OneDrive 的账户，再创建任务，选择手机目录与云端路径。目标路径相对于 OneDrive 根目录，例如 `手机备份/照片`。

开发者配置：客户端 ID 为 `af01680f-8310-4a44-b712-ba168cec5729`，支持组织目录及个人微软账户；公共客户端回调为 `dev.ohmysync://oauth`。应用请求 Microsoft Graph 委托权限 `Files.ReadWrite` 和 `offline_access`，使用授权码与 PKCE，不使用客户端密钥。组织账户可能需要管理员同意。

协议依据：[微软授权码与 PKCE 文档](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)、[Graph 上传会话文档](https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession?view=graph-rest-1.0)。

## 同步行为

新任务默认选择双向曲库模式；原有任务继续单向上传。曲库模式以 SHA-256 和共同基线判断单端增改，下载时验证云端版本及手机落盘内容，修改前将已观察版本保存到曲库之外的 `轻同步历史/<任务ID>`，并提供历史恢复为副本。两端不同修改保留冲突；删除目前只报告待确认项，不会执行。必须用系统选择器授予手机目录读写权限。

双向模式目前仍需隔离目录的真机验证，尚未实现原子文件替换、跨进程恢复日志和自动删除。历史会占用手机与 OneDrive 空间，默认不清理。Windows 使用普通 OneDrive 客户端，不能保证保存轻同步尚未观察到的电脑中间版本。完整设计与当前限制见 [曲库同步方案](docs/曲库同步方案.md)。

以下规则适用于原有单向任务：不会把云端内容下载到手机，不会自动删除任何手机或云端文件。

默认保留同名云端冲突：只有本任务此前成功上传、且云端版本标记没有变化的文件，才会被自动更新。首次遇到同名云端文件，或者云端文件后来发生修改，会保留云端内容并记录冲突。云端文件缺失时会重新上传。开启“允许覆盖云端同名文件”后，手机内容可以替换云端同名文件；请求仍附带读取时的版本标记。

任务绑定 OneDrive 网盘 ID，连接其他账户不会把旧任务上传到新账户。断开账户会停止运行任务并暂停所有任务，重新连接原账户后可手动恢复。

定时任务最短间隔为 15 分钟。Android 的省电策略可能延后启动；miuix 页面中的“仅 Wi-Fi”实际使用系统的“不计费网络”条件。手动同步同样遵守网络、充电和低电量约束。部分手机需要在系统应用设置中允许后台运行和自启动。

单个文件分块上传仅使用约 10 MiB 分片内存。当前会话支持根据服务器进度恢复上传；进程被终止后，未完成文件会在下一次任务中重新上传，不承诺跨进程续传。扫描时会读取完整文件计算哈希，超大目录可能耗时较长；上传中发生可检测的本地修改会报错并在下一次重新处理，建议避免同时编辑正在同步的文件。

系统文件选择器会限制部分存储根目录及 `Android/data` 等位置。这是安卓存储授权限制，应用不申请全盘管理权限。

## 验证

单元测试覆盖陌生云端文件保护、远端修改冲突、已上传文件更新、缺失远端文件恢复、显式覆盖、路径校验和上传协议。HTTPS 模拟服务验证分片大小、字节范围、版本标记以及上传 URL 不附带访问令牌。

构建与模拟器启动可在没有 OneDrive 账户的情况下验证。已配置正式注册的客户端 ID，并于 2026-10-12 在安卓真机完成微软登录和 OneDrive 文件夹上传验证。厂商后台保活仍需长期验证。

## 扩展

`CloudStorage` 定义云端目录创建、文件查询和上传接口，`OneDriveClient` 是首个实现。新增其他网盘时可复用任务、文件扫描和调度流程；还需要增加对应的授权和账户管理。

后续可继续增加双向同步、跨进程断点续传、文件过滤、多账户、云端目录选择器和冲突处理页面。
