# 多世界内置包与角色画廊 TDD 证据

本次变更把新的世界放在独立的 `bundled/bundles/<id>/` 目录；已有根目录 bundle 不会被生成脚本删除，也不会因应用重启覆盖用户编辑。

## RED → GREEN

- RED 检查点：`624d3f6`，新增嵌套资源前缀测试；当 `BundleManifest` 尚未支持 `assetPrefix` 时，`:core:test` 编译失败。
- GREEN：实现 `assetPrefix`、多清单发现、PNG V3 卡片打包与显式 `characterCards` 后，核心测试通过。
- 核心测试结果：48 项通过，0 失败，0 错误，0 跳过。
- 核心行覆盖率：JaCoCo 报告 87%，覆盖率门槛通过；分支覆盖率 61%，未宣称达到 80%。

## 集成验收

- `:app:assembleDebug` 通过。
- `:app:assembleDebugAndroidTest` 通过。
- `:app:lintDebug` 通过。
- 从实际 APK 读取到根目录清单、嵌套世界清单、8 张 `chara_card_v3` PNG 卡和 8 个画廊引用。
- 重新生成私有包前后，已有根目录清单 SHA-256 与文件数保持不变。

## 未完成的设备验证

本机当前没有可用的 `adb` 设备连接，且直接调用项目内 `adb.exe` 受本机 Android 用户目录权限环境阻断，因此未把模拟器/实体手机安装与运行结果写成已通过。源码、JVM、APK 打包和静态 APK 资源验收不等同于设备验证。

私有角色卡、世界书、来源审计和包含资料的 APK 继续留在 Git 忽略路径，不进入本文件或公开仓库。
