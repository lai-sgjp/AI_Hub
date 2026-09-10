# 安装更新

当前版本 0.1.1（versionCode 2），应用包名固定为 `cn.aitavern.app`。

以后下载新版 APK，在手机的下载列表中点开，按安卓提示确认“更新”。首次使用该下载来源时，系统可能要求允许安装应用。无需卸载旧版；覆盖安装会保留本机聊天、角色、世界、记忆及加密保存的 API 密钥。卸载会清除这些本机数据，更新时不要卸载。

当前没有远程更新服务器或应用内自动下载。私人内置资料随本地 APK 交付，不放入公开 GitHub Releases。安卓安装确认仍由用户操作。

## 后续构建约定

- 每次交付提高 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`。
- 本机已将首版签名文件复制到 Git 忽略的 `private-content/signing/tavern.keystore`，避免清理 `.tools` 时丢失。不要替换它；请自行保管其离线备份。此文件是个人测试渠道的原始 debug 签名，不能当作生产发布证书。
- 构建会优先使用该文件。公开源码构建在没有私有签名时使用开发者自己的 debug 签名，不能覆盖本人的私人安装版。
- 保持 Room 数据迁移兼容，不使用破坏性数据库重建，不重新覆盖已初始化的内置资料。
- 交付前用 `apksigner verify --print-certs` 比较旧、新 APK 的证书，再在已有数据的测试设备运行 `scripts/check-upgrade.py --adb <adb> --serial <device> --apk <new.apk>`。
- `./scripts/package-update.ps1 -PreviousApk <上次交付.apk>` 自动构建并检查签名、包名及递增版本号，再输出新 APK 到忽略目录 `dist/`。缺少原始签名、版本未递增或输出已存在时会停止。
- 此脚本执行覆盖安装，对比数据库记录及加密凭据文件；仅输出计数，不输出私人内容。实体手机安装确认和厂商限制仍需真机验证。

Android 官方说明：[更新匹配规则](https://developer.android.com/google/play/app-updates)、[版本号规则](https://developer.android.com/studio/publish/versioning)。

## API 切换与管理

世界选择页、世界首页右上角及设置页均有管理入口；聊天输入框上方显示当前 API，点击即可切换到任意已保存配置。每个配置保存自己的名字、地址、模型和加密密钥，可以为同一服务添加多个不同密钥。

切换仅作用于当前房间，保存后重启仍保留。生成中切换先取消当前生成，保留部分回复并停止后续角色请求；下一次发送才使用新配置，不自动重发。管理页支持添加、编辑、删除，正在被房间使用的配置需先为相关房间切换 API 后才能删除。
