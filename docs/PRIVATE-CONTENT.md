# 私有内置内容

仓库不含角色卡、世界观资料、玩家人设数据或包含这些资料的 APK。源码在没有私有资料时仍可构建，启动后可手动创建世界、导入角色卡。

## 本地打包

在被忽略的 `private-content/bundle.json` 中配置：

```json
{
  "id": "my-personal-world-v1",
  "charactersDir": "D:/my-library/characters",
  "worldDir": "D:/my-library/world",
  "worldName": "我的世界",
  "worldDescription": "共同遵守的世界背景和玩家身份规则",
  "playerName": "玩家",
  "playerDescription": "用户扮演的人格，不由 AI 代演",
  "preferredCharacters": [],
  "characterGalleries": {
    "alice.json": [
      {"path": "gallery/alice-01.png", "title": "日常立绘"}
    ]
  }
}
```

```powershell
python scripts/bundle-content.py --config private-content/bundle.json
./scripts/build.ps1
```

脚本读取源目录，复制 JSON、Markdown、PNG/JPEG/WebP 到 `app/src/main/assets/bundled/`；它不修改外部源目录。JSON 角色卡和世界书进入清单，Markdown 作为世界的参考资料，`characterGalleries` 将独立图片关联到角色卡。Markdown 资料可在“管理世界”查看，不会整本注入聊天上下文。

`BundledLibrary` 将清单转换为稳定 UUID 的世界、角色、世界书和玩家人格。与玩家同名的卡不会加入 AI 角色。每个 bundle id 只初始化一次，标记与内容使用同一数据库事务保存；用户后续编辑不会被重启覆盖。

新增 bundle id 可导入为另一个世界。V1 不会用相同 id 自动更新已安装资料，以免覆盖用户编辑。

## 上传边界

`.gitignore` 排除：

- `private-content/`
- `app/src/main/assets/bundled/`
- `dist/`、所有 APK/AAB
- `docs/private/`、本地截图
- 构建缓存、数据库、密钥文件和环境变量文件

源码测试仅包含合成示例。私有内容验证从本机 APK assets 读取清单，不在测试代码里写入个人世界或角色资料。

不要使用 `git add -f` 强制加入上述目录。发布 APK 前也需要检查它是否包含个人资料；此仓库不通过 GitHub Releases 分发带私有内容的包。
