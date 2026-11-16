# 发布保护

生产 systemd 的 `release-guard.conf` 在每次启动之前调用 `verify-release.py`。保护脚本和 `/etc/qingfeng-music/release-policy.json` 均由 root 管理，位于版本目录之外；服务账户只有读取权限。策略以已验收 JAR 的 SHA256 绑定完整源码提交，未知 JAR、源码不匹配、订单/analytics 必需资源缺失均拒绝启动。不能只替换 current 或复用旧发布脚本绕过检查。

发布前 fetch 所有分支，合并 main/dev/yth 和服务器最新完整源码提交；运行根目录 `scripts/check-release-ancestry.ps1 -DeployedCommit <当前线上完整commit>`，未知或不包含线上提交均停止。工作区干净后取 HEAD，依次执行前端 npm test、npm run build、后端 `mvn clean verify -Drelease.commit=<HEAD>`。JAR 内包含源码标识，必须与此次部署清单一致；前端构建必须逐文件与 JAR 中 static 核对。

部署使用共享锁 `/run/lock/qingfeng-release.lock`，在停服前及切换前检查 current 仍等于预期旧版本；发生并发发布则停止。先备份旧版本、配置、数据和数据库，并在隔离环境验证恢复及迁移兼容性，再切换。`verify-release.py` 明确拒绝 `legacyRollback` 绕过；回滚制品也必须通过完整源码、制品和当前数据库兼容校验。正式策略只批准经过验收的精确 JAR；不能将任意 JAR 自动加入许可列表。

回滚前必须核对当前数据库和金融写入协议，使用已验收的兼容回滚制品。V44 权益账本开始写入后，不得切回不理解订单冻结和新协议的旧 JAR，也不得还原旧数据库快照覆盖新增资金事实；保留新订单及 Webhook 水位，优先兼容前滚或关闭新购后恢复。恢复旧 release-policy.json 本身不构成兼容性证明。首次安装保护的失败处理应恢复旧 systemd 配置。对 root 手动删除保护或伪造审批文件无法提供防护；日常发布应遵循此流程。

`python deploy/test_release_guard.py` 验证完整制品通过、旧订单组件/未知制品/清单不符拒绝；生产验收还应以服务账户验证旧制品被实际安装的脚本拒绝，且新制品通过，不必停服测试拒绝路径。
