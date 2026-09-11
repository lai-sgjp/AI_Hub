# AI 酒馆 · Android

个人使用的原生安卓角色群聊客户端。Android 8.0+，简中 / English / 日本語界面，手机直连自填的 OpenAI 兼容 API。支持多个世界、用户人格、大小记忆、记忆表格与可选向量检索。

## 使用

1. 安装本地构建的 `app/build/outputs/apk/debug/app-debug.apk`。若 APK 包含私有内置资料，首次启动会自动准备对应世界；纯源码构建默认没有内置资料。
2. 点击“管理 API → 添加 API”填写基础地址（例如 `https://example.com/v1`）、密钥及模型。密钥仅在手机本地加密保存。
3. 创建角色，或导入 V1/V2/V3 PNG/JSON 角色卡；检查导入报告。在“管理世界”中配置世界背景和你扮演的人格。
4. 新建聊天，选择 1～8 位角色及 API，可添加世界书和自己的名字、人设。
5. 发消息后默认一位角色回复；可切换两位或点名。停止、离开前台或网络错误会保留部分文字。
6. 点击聊天右上角“记忆中心”查看大小记忆、编辑事实表、整理记忆或重建向量索引。向量化需要在 API 设置填写服务支持的 embedding 模型；未配置则使用关键词检索。
7. 聊天输入框上方可随时切换 API，并添加、编辑或删除已保存配置。生成中切换会保留部分回复，下一次发送使用新配置。

翻译和世界导览处理中可随时点击“停止翻译”。错误最多自动重试 2 次；输出截断最多自动续写 4 次后暂停并保留进度。再次点击原来的翻译按钮可继续单项任务，“恢复自动翻译”恢复世界页自动内容服务。

下载新版 APK 后点开并确认更新即可保留数据升级，请勿卸载旧版。当前没有远程自动下载；签名维护及覆盖升级验证见 [更新说明](docs/UPDATES.md)。

角色和历史在本地保存，但每次请求会向配置的 API 发送相关设定和上下文。自动摘要也会调用同一 API。

## 构建

在 Windows 上安装 Python 3，运行：

```powershell
python scripts/bootstrap.py
# 工具安装在项目 .tools 目录
$env:JAVA_HOME = (Get-ChildItem .tools/jdk -Directory | Select-Object -First 1).FullName
$env:ANDROID_USER_HOME = "$PWD/.tools/android-user"
.tools/android-sdk/cmdline-tools/bin/sdkmanager.bat --sdk_root="$PWD/.tools/android-sdk" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
# 阅读并接受 SDK 许可后
./scripts/build.ps1
```

也可用 Android Studio 打开工程，配置 JDK 17 和 Android SDK 35，运行 `:app:assembleDebug`。
固定组合：AGP 8.9.2、Gradle 8.11.1、Kotlin 2.1.20、Compose BOM 2025.03.00、Room 2.7.0、OkHttp 4.12.0。

## 测试

```powershell
./scripts/build.ps1 -Tasks ':core:test',':core:jacocoTestReport',':app:lintDebug',':app:assembleDebug'
# 连接已授权 ADB 的安卓设备后
./scripts/build.ps1 -Tasks ':app:connectedDebugAndroidTest'
```

JVM 测试与覆盖率报告在 `core/build/reports/`，设备验收范围见 [验证记录](docs/VALIDATION.md)。
导入和行为约定见 [实现说明](docs/IMPLEMENTATION.md)，记忆层次、世界隔离及云同步接口见 [记忆与扩展说明](docs/MEMORY-AND-SYNC.md)。

## 备份及限制

ZIP 备份不含密钥，恢复为新增副本；换手机后需重新填写密钥。请妥善保管含聊天内容的备份。
支持世界书常驻/关键词/优先级/启用子集，不运行酒馆脚本或正则扩展。不含同步、后台生成、语音和图片生成。
上下文用保守估算而非特定模型 tokenizer；复杂设定可能需要手动增大上下文预算。

## 代码结构

- `core`：数据模型、群聊调度、API/SSE、卡片导入、ZIP 备份、摘要与记忆检索、同步接口。
- `app`：Compose 界面、Room 数据库、Keystore 密钥、本地文件选择、前台生命周期处理。
- `docs`：实现约定、兼容范围、验证证据与模拟器截图。

云同步只预留 `SyncPort` 与版本化载荷，不会自动上传任何数据。

私有世界与角色卡的本地打包方法见 [私有内容说明](docs/PRIVATE-CONTENT.md)。原资料、生成的 assets 和 APK 均在 Git 忽略范围内。

## 0.2.0 使用入口

- 进入世界先阅读介绍与角色资料，选择已有开场，或点击“生成更多入口（六个）”；“自定义小剧场”保留自由背景输入。
- 在设置选择语言。AI 内容服务首次显示资料发送提示；世界导览与开场译文缓存到本机。旧消息通过“翻译 / 查看原文”切换。
- “重新生成”保留同一房间的回复版本，点击“回复版本”恢复旧路线和后续剧情；只有“从这里创建分支”增加新房间。
- 世界页“剧情分支图”支持缩放、拖动和跳转；同结局共享图示终点，各路线历史和记忆独立。支持手动标记与撤销收束。
- 本地升级包：`dist/AI-Tavern-0.2.0-private-debug.apk`。覆盖安装，不卸载旧版；内置资料 APK 不公开发布。

验证证据见 [0.2.0 验证记录](docs/validation/story-0.2.0.md)。
