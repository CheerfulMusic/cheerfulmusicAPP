# 发布保护

生产 systemd 的 `release-guard.conf` 在每次启动之前调用 `verify-release.py`。保护脚本和 `/etc/qingfeng-music/release-policy.json` 均由 root 管理，位于版本目录之外；服务账户只有读取权限。策略以已验收 JAR 的 SHA256 绑定完整源码提交，未知 JAR、源码不匹配、订单/analytics 必需资源缺失均拒绝启动。不能只替换 current 或复用旧发布脚本绕过检查。

发布前 fetch 所有分支，合并 main/dev/yth 和服务器最新完整源码提交；运行根目录 `scripts/check-release-ancestry.ps1 -DeployedCommit <当前线上完整commit>`，未知或不包含线上提交均停止。工作区干净后取 HEAD，依次执行前端 npm test、npm run build、后端 `mvn clean verify -Drelease.commit=<HEAD>`。JAR 内包含源码标识，必须与此次部署清单一致；前端构建必须逐文件与 JAR 中 static 核对。

部署使用共享锁 `/run/lock/qingfeng-release.lock`，在停服前及切换前检查 current 仍等于预期旧版本；发生并发发布则停止。先备份旧版本、配置、数据和数据库，核对归档校验及数据库迁移不变，再切换。新策略临时允许明确的旧 JAR 回滚（legacyRollback），健康及公网检查成功后移除此临时许可。正式策略仅批准新版本；未来发布必须保留新版本源码并经测试后增加新 JAR 许可。不能将任意 JAR 自动加入许可列表。

回滚前应读取本次备份恢复 release-policy.json 的回滚许可（或在核对旧 JAR SHA256 后明确批准旧版本），再切换 current 并重启；不要还原数据库覆盖新增业务数据。首次安装保护的失败处理应恢复旧 systemd 配置。对 root 手动删除保护或伪造审批文件无法提供防护；日常发布应遵循此流程。

`python deploy/test_release_guard.py` 验证完整制品通过、旧订单组件/未知制品/清单不符拒绝；生产验收还应以服务账户验证旧制品被实际安装的脚本拒绝，且新制品通过，不必停服测试拒绝路径。
