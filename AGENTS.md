# AI 酒馆项目约定

## 代码与验证

- `core/` 是独立 JVM 模块：模型、API、调度、记忆、导入和备份。
- `app/` 是 Android Compose UI、Room、Keystore 与生命周期适配。
- 执行 `./scripts/build.ps1` 验证核心测试、核心行覆盖率、APK 与 Android Lint。
- 设备测试使用 `scripts/test-device.ps1 -Serial <device> -AdbPath <adb.exe>`。
- 不把模拟 API / 模拟器验证写成真实厂商 API / 实体手机验证。

## 私有资料与凭据

- 用户世界观、角色卡和玩家人格文件留在 `private-content/` 与 `app/src/main/assets/bundled/`，必须保持 Git 忽略。
- APK/AAB、截图、数据库、密钥与构建缓存不提交；带内置资料的 APK 不上传 GitHub Releases。
- 提交前检查 `git diff --cached --name-only` 与 `git check-ignore`，不得使用 `git add -f` 添加私有资料。
- API 密钥只经 SecretStore 保存，不能进入 Snapshot、备份、同步载荷或日志。

## 数据行为

- 内置包只初始化一次；不能在启动时覆盖用户编辑。
- 记忆默认隔离到世界及房间。分支不得继承切点之后的自动事实。
- 摘要、覆盖游标、阶段记录与事实更新必须原子提交。
- 云同步仅预留接口，未实现远程服务；不要暗中启用上传。
