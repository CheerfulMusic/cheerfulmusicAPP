# 新生产环境准备包（未执行）

状态：BLOCKED / NOT_APPROVED。没有创建服务器、数据库、Bucket、RAM、CDN、DNS或证书；没有部署或切流。
模板有意缺少有效资源和凭据，不能当成可以直接运行的部署包。

## 已准备

- application.properties.example：强制显式NEXT_*，没有当前主库/旧桶回退；自动迁移、支付、调度、CDN和OAuth初始关闭。
- runtime.env.example：只列新资源秘密引用；不得复制现有生产密钥，不得提交实际文件。
- qingfeng-music-next.service.example：独立用户/目录、loopback18080、ExecStartPre强制外部审批与制品校验。
- release-policy.json.example：空批准清单；不会自批准新包、旧包或回滚。
- readiness.json：逐项UNKNOWN/NOT_RUN；不是环境真实性证明。

## 实施前必须解决

1. 确认新服务器归属、地区、网络边界、Java17/Python3.12、时间同步、磁盘与备份目标。香港/北京资源不能仅凭名称视为专用或安全。
2. 用户当前只允许日常生产库；本准备包不建库、不连接主库、不运行夹具。未来更换数据库必须另批：确认准确MySQL版本、账号GRANT、全局/角色权限、原始迁移链、脱敏验证和恢复演练。
3. V27/V28原件与执行来源仍UNKNOWN。不能通过改名、repair、baseline或手工补表使门禁通过。
4. 审批私有Bucket、最小权限身份、CDN回源与客户端鉴权的联合方案。真实冷/热缓存、Range、过期续期、旧分享/封面及匿名源站拒绝必须执行；不复用匿名Allow。
5. PayPal新购门禁、退款/部分退款/争议/拒付/已消费积分闭环必须在合格环境验收。用户曾本地付款0.10美元不替代这些验收；六笔404保持待核实。
6. 给usage-limits.properties、invite-gate.properties和application.properties分别准备经审核的新环境版本，显式加载；不再隐式读取旧站/etc路径。新properties不得含额外spring.config.import。
7. 仅使用可信构建目录生成的app.jar、release-contract.json、source-commit.txt；独立审批清单必须绑定JAR/contract/schema证据SHA。verify-release.py与release_gate.py安装在应用不可写的同目录，策略归root所有。
8. 新服务保持不启用、DNS不切换、后台worker关闭，直到数据/对象/回调/权限与回滚验收完毕。不能让新旧实例同时执行同一生产队列。

## 数据与切换设计（UNKNOWN，待审批）

先保留现网。新库准备由用户批准准确目标后实施，按原始schema链和一致性快照迁移；记录数据水位、在途支付/任务、账本总量和对象引用。真实核对不得模拟支付成功或覆盖历史分录。

切换窗口中明确唯一写入者和任务执行者；短暂冻结新提交与资金处理需业务批准，存量Webhook持久接收与已付权益不可丢。不能只切DNS而忽略旧客户端、旧Webhook、队列和缓存。

回滚区分应用回滚与数据回退。新库已产生真实业务写入后，不得把旧快照覆盖回去；必须有反向同步/前向修复和资金对账设计。没有经过真实演练的RPO/RTO均UNKNOWN。

媒体回滚保持私有授权，不恢复整桶匿名Allow；告警探测不自动改配置、不重启应用。新版监控需要显式--run、--props、--state-dir、--log-dir，禁止直接搬用旧站路径或旧cron命令。

没有任何环境资源、配置或健康状态在本轮被标为VERIFIED。模板不是审批；必须取得用户对资源、费用、数据迁移、部署和切流的分别授权。
