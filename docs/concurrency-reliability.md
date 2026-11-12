# 支付、Agent 并发与任务恢复

本次增加 V34–V37 正向迁移。代码合并不代表生产迁移、基础设施部署或运行版本切换。历史迁移不修改，日常本地入口仍运行已上线版本，并显式关闭新增后台消费者。

## 数据与执行边界

- 积分流水仍是审计依据，`credit_account` 为按账号锁保护的余额快照；首次写入从原流水初始化，余额、流水和业务订单在同一数据库事务内提交。没有全站积分互斥锁。旧 `credit_lock` 表保留，改写准入使用独立锁。
- 支付平台先验签，再将规范化事件写入 `payment_webhook_inbox`；`(provider,event_id)` 是幂等键，远端订单及收款 ID 也按 provider 唯一。HTTP 成功只表示事件已持久接收，入账由 worker 核对平台权威结果后完成。
- Inbox、生成任务、语义索引任务与 `task_event_outbox` 在同库事务内落盘。RabbitMQ 消息只含 outbox UUID，持久消息、quorum queue、publisher confirm 和 mandatory return 共同判断投递；消费者重新读取数据库事件并唤醒有租约的业务 worker。
- 消息允许重复。数据库状态、唯一键和租约 token 防止业务重复提交；RabbitMQ 不能提供跨数据库的“恰好一次”。数据库轮询保留为 broker 故障恢复路径。投递失败指数退避，20 次后进入 DEAD；业务任务仍留在原任务表。数据库轮询可完成原任务，无需重新生成付费请求。
- 未知的音乐提交、支付创建或 Agent 执行不会自动换新标识重试。PayPal 创建明确被拒绝才进入 FAILED；网络结果未知保留 CREATION_UNKNOWN，可用既有远端订单做只读关联恢复。定时核对只查询已有订单，不自动 capture 或再次创建付款。
- 退款和争议按已核实的收款关联建立风险记录，冻结账号新增扣费，不猜测退款积分或自动扣成负数。独立财务管理员通过 `/api/app/payment-risk/cases` 与 `/{id}/review` 审核，需当前事件 ID、具名操作者和理由；新事件使旧审核失效。此接口不在 Agent 工具列表。余额纠正仍须有核实后的账务方案，RELEASE 本身不调整余额。

PayPal 对账每轮最多检查 10 个 PENDING/CANCELLED 订单和近 30 天的 10 个 PAID 收款，每单至少间隔 5 分钟，按最久未检查的顺序轮转。漏投回调但已反映为 REFUNDED/PARTIALLY_REFUNDED/REVERSED 的收款状态进入同一持久 inbox，稳定事件标识防止重复冻结已审核记录。超过窗口的历史交易以及未改变 capture 状态的争议仍需 webhook 或人工核查，不声称覆盖所有支付生命周期。

Stripe 接入应复用 inbox / outbox / account 事务，以独立 provider 名称保存事件和远端 ID；实现 `VerifiedPaymentProcessor` 并注册为 Bean 后，共享轮询和 MQ 通知会调用该渠道处理器。签名验证、事件映射、订单校验由 Stripe 适配器实现。本次不包含 Stripe 适配器或实际收款验收。

## 准入和资源上限

Agent 默认使用 MySQL 跨进程准入：全局并发 8、每用户 1、全局每分钟 60、每用户每分钟 6。专用执行线程默认 8，没有内存等待队列；过载在调用模型前拒绝。调用总期限默认 150 秒，外部请求使用剩余期限，失去租约或取消后禁止启动后续步骤；已发出的外部请求不能承诺取消。

每日预算保守预留两轮规划调用的输入 UTF-8 字节上限与输出上限，默认每轮最多 65,536 输入字节。它是防止成本失控的保守额度，并非供应商实际 Token 账单；执行开始后的失败/未知请求不会返还预算。歌词、图片、音乐继续使用各自已有用量及积分限制。`config/usage-limits.properties` 中有 Agent 参数。请求指纹保留用于防止旧 key 重放，不重复存储用户文本。

启用 `qingfeng.redis.enabled=true` 和 `qingfeng.agent.admission.mode=redis` 后，Redis Lua 原子维护共享并发/RPM；数据库预算与身份仍是权威记录。Redis 不可用时拒绝新请求，不回退到无保护执行。公共 API 也可共享 Redis 限流，支付 webhook 有独立有界限流桶。

PayPal 共用 HTTP 客户端和单次刷新 Token 缓存，单 gateway 同时最多 32 个调用，无内存等待队列。调用前容量不足立即返回 503；明确尚未发出的创建请求不会标为支付结果未知。该限制保护单进程资源，不代表供应商账户在整个集群中的 API 配额；扩容前仍需按渠道配额调整共享入口限流。

## 配置与分离进程

`Qingfeng_AI_Music/deploy/production.properties.example` 列出新增私有配置项。Redis、RabbitMQ、Qdrant 默认不启用外部访问。`deploy/concurrency/compose.yml` 是单机基础设施模板，需私有密码，端口只绑定 loopback；它不等同于高可用集群。生产高可用须独立规划 Redis 故障切换、至少三个 Rabbit 节点、Qdrant 副本、MySQL 故障切换及备份恢复。RabbitMQ 支持窗口见[官方发布信息](https://www.rabbitmq.com/release-information)。

默认单进程继续执行数据库恢复扫描。需要分离时，使用 `mysql,api` 与 `mysql,worker` profiles 和相同私有数据库/broker配置：API 只接收请求及投递 outbox；worker 的 HTTP 只绑定 `127.0.0.1:8081` 用于健康检查，不接入公网反代。启用 Rabbit 的两个角色必须使用同一 virtual host。Worker 的各模型及支付渠道 enabled 参数仍来自原私有配置，角色本身不会打开未授权的供应商。语义索引需另行配置 embedding 模型、维度、collection 和凭据，详见 [agent-retrieval.md](agent-retrieval.md)。

内部指标 `qingfeng.task.outbox{state=READY|SENDING|SENT|DEAD}` 提供投递积压；Rabbit 健康仅在启用时检查。公网仍只暴露健康状态，不公开指标、消息内容或数据库配置。告警还应覆盖最老未完成 inbox、任务租约过期、模型限流/超时、支付待核对及语义索引失败；容量结论需根据真实业务负载压测，不能从单元测试推导 QPS 承诺。

## 上线及回滚要求

1. 按项目发布流程 fetch、合并、前后端验证、构建、备份代码/配置/数据库，并核对生产最新 Flyway 版本。若 V34–V37 已被同事占用，发布前顺延未执行的新迁移。
2. 这是余额写入协议变更，切换时暂停写请求与旧 worker、排空在途事务；禁止旧账本实现和新账本实现同时写同一主库。完成迁移后全量切换新代码，再恢复接流与 worker。
3. 新功能优先通过关闭 Redis/Rabbit/RAG 开关回退，保留新版支付账本/inbox 代码。不可在新余额表已有写入后直接滚回只写旧流水的 JAR，再混跑新版本；这会使快照过期。确需代码回滚，先暂停写入，按流水核对并重建余额快照，保留所有已发生的真实交易，不能用上线前整库备份覆盖新交易。
4. 单独验证健康、同一回调重放一次入账、非财务角色拒绝、模型限流、broker 故障恢复和跨用户向量隔离。真实支付只以真实平台结果记账，禁止在主库写模拟成功记录。

本地 `scripts/start-main.ps1` 关闭 outbox、支付 inbox、语义索引及 Rabbit 消费；不自动执行 V34–V37。自动测试仅使用内存 H2 或显式隔离端口：MySQL `57137/qf_execution_concurrency_test`、Redis `16379`、RabbitMQ `15673`、Qdrant `16333`，不读取生产连接配置。
