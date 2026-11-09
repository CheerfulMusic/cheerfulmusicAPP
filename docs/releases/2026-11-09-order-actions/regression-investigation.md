# 后续发布覆盖订单修复的核查

2026-11-09 只读核查结果：

- 订单修复发布目录为 `20261109T053023Z-orders-f7d06f8`。
- 服务器 current 已切换到 `20261109T063202Z-analytics-880e59c`，期间还有 d8d0922、a081854 两次 analytics 发布。
- 公网入口为 `/assets/index-DJnkU74X.js`，账号组件为 `/assets/IdentityPage-xdZIrI-9.js`，缺少已发布修复中的 `cancel-order` 标记。
- 本机另一个仓库 `D:/it_ai/人人是歌手` 的 880e59c 订单组件仍为旧版行内按钮，缺少取消操作；该仓库当时不能解析订单修复提交 d482ae3。

结论：后续发布的源码未包含订单修复，覆盖了已上线界面；不能归因为浏览器缓存。未检查或修改用户订单，未执行发布或服务重启。

发布前必须从服务器发布清单取得完整源码 commit，在发布仓库导入该 commit 后执行：

```powershell
./scripts/check-release-ancestry.ps1 -DeployedCommit <线上完整源码commit> -CandidateCommit HEAD
```

脚本只检查提交包含关系，未知提交或未包含线上提交时失败。此脚本尚未接入其他仓库的发布入口，不能声称已经阻断所有发布；各发布入口必须接入后才能形成强制保护。还须运行项目规定的测试、备份与公网功能检查。恢复订单界面应以最新线上源码合入订单修复后重新构建，避免直接回退而丢失 analytics 更新。
