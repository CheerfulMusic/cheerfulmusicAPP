# 支付整合：协议 3 锁序及权益写入契约

本文件记录候选实现的代码契约（CODE_CONFIRMED），不代表 MySQL、Sandbox 或生产验收通过。实际命令和结果由整合验收报告记录。对应隔离测试不连接生产、不执行真实资金操作；另行批准的生产只读盘点见整合报告，不等同于生产修改或资金验收。

## 统一写入与锁序

`JdbcFencedCreditsLedger` 包装主线 `JdbcCreditsLedger`，不引入旧候选的全局 G 事务管理器。主线原有 API 保持不变；实际金融写入必须使用包装后的 bean。

| 路径 | 锁及处理顺序 | 不变量 |
| --- | --- | --- |
| 普通发分、消费、消费失败退回 | creator(owner) 排他 → policy 共享 → credit_account(owner) 排他 → journal 唯一插入 → batch/allocation | journal、缓存余额、批次权益同事务提交或回滚 |
| 支付绑定 | creator → policy 共享 → account → payment_order → payment_binding 唯一插入 | 同一 capture 不得绑定其他订单 |
| 退款、失败退款终态 | creator → policy 共享 → account → payment_order/binding → shared native identity 唯一插入 → 对应退款事实 → 批次与流水 | 同一渠道/商户/环境/退款原生 ID 只能归属一组 owner/order/capture/金额/币种 |
| 争议 | creator → policy 共享 → account → payment_order/binding → shared native identity 唯一插入 → dispute → 有效退款/争议当前行 → batch/journal | 只冻结对应订单剩余权益；永久调整须为已确认最终损失 |
| 退款/争议资金返还 | 同上，先共享 native identity 对原生资金流水做唯一 claim，再写专属不可变返款事实 | 两种返款不能复用同一原生资金流水；退款恢复为 REFUND_RETURN，争议恢复为 PAYMENT_RETURN |
| 歌词准入 | creator → session → model_usage_lock → 包装账本/消费记录 → request | UNKNOWN 保持扣费、分摊和并发占用；新 key 不得重投同一未决作品 |
| 改写准入 | creator → rewrite_admission_lock → prior rewrite row/在途当前行 → quota → 包装账本 → rewrite row | 仅 RESERVED 可因未提交超时退回；SUBMITTED 超时转 UNKNOWN，不退款、不重投 |

表中的账本顺序是包装器内部顺序，不能据此省略服务层已经获取的锁。实际支付成功是 creator → payment_order → 包装账本；回调提交是所有相关 creator（排序）→ scoped Inbox lease → 订单/审核记录 → 包装账本。`PaymentRiskService` 是 creator → 风险记录。所有这些订单、账户、批次均属于已锁 owner；同 owner 的公共首锁把这些子路径串行化，不应将其描述为任意事务均可自由组合的全局总锁序。跨 owner 的通路、引入新全局锁或在锁后获取另一 creator 必须独立复核和真实 MySQL 验证。

本轮真实 MySQL 曾复现两条全局锁反序并保留等待图：歌词请求空范围锁 → LYRICS usage，与 usage → 请求插入互等；改写状态索引当前读与未持 admission 的状态更新互等。修复后歌词在任何请求锁前获取 LYRICS usage，改写的 reserve/submitted/unknown/succeed/fail/stale 均在状态行前获取 rewrite_admission。并发强度没有降低，不以遇死锁重试替代锁序修复。具体结果见本轮整合报告。

同一 owner 所有路径以 creator 行串行；不同 owner 不取 `credit_lock`，policy 使用 MySQL `FOR SHARE`。没有把 policy 升级为全局排他锁。调用方如涉及多 owner，必须审查其 owner 获取顺序；实际邀请实现的 invitee→inviter 既有链路保留，并用受控并发测试验证不再形成旧 G 与 creator 互等。

真实隔离 MySQL 曾复现 `lyrics_composition_request` 空范围 supremum 锁 → usage 锁与 usage 锁 → INSERT request 的反序；claim 已统一在任何 request 锁之前获取 LYRICS usage。改写的 reserve 会读取在途状态索引，因此 submitted、unknown、succeed、fail 及 stale 状态写入也统一 creator → rewrite_admission → row，避免 current scan 的索引范围锁与另一个 owner 的状态更新反序。不得通过仅在 H2 验证、重试或降低并发代替该锁序回归。

## 并发唯一身份

`JdbcCreditNativeIdentity` 使用共享表 `p0_credit_native_identity` 的唯一插入仲裁，不能以两张事实表分别先查后插代替。

- `REFUND_BINDING`：固定 `refund:` + SHA-256(UTF-8 原退款 ID)。
- `DISPUTE_BINDING`：固定 `dispute:` + SHA-256(UTF-8 原争议 ID)。
- `REFUND_RETURN`、`DISPUTE_RETURN`：原生资金流水 ID 共用同一命名空间。

重放必须逐项核对 owner、order、capture、kind、resource、金额和币种。返回身份不允许冒用带冒号的内部 binding 命名域。唯一资源冲突进入失败/人工处理，不自动合并。

## 快照与事务

外层 REPEATABLE READ 事务可能早于 creator 锁建立一致性快照。金融累计值使用 `FOR UPDATE` 当前行读取后进行 Java 精确求和；不能对普通 `SUM` 快照结果推导有效退款或实损。余额账户初始化保留主线唯一插入及当前锁定余额读取，因此旧快照未看到已建立账户时也不能覆盖余额。

消费先使用唯一流水插入判定幂等，再执行 FIFO 分摊；准入失败必须回滚流水和余额缓存。失败退回严格按原 allocation 恢复，禁止把历史消费伪造成 FIFO 分摊。历史来源无法归因时抛出 `LEGACY_ATTRIBUTION_REQUIRED`，只能通过独立审批的映射/切换契约处理。

## 旧写入者与风险标志

canonical V41 的 protocol 3、epoch、ACTIVE journal 触发器拒绝未持有效会话 epoch 的旧 writer。包装账本在每次写操作内设置并恢复会话 epoch。裸 `JdbcCreditsLedger` 保留旧风险标志语义但无法绕过真实 MySQL 的 ACTIVE 触发器；包装后的消费按订单批次冻结与负余额准入，不因 `creator.payment_risk_frozen` 冻结其他正常批次。

协议不是生产权限隔离的替代品。正式切换仍须停写、排空和保存异步事实、备份恢复验证、旧账号撤权及切换核验；V41 初始 PREPARING，不能自动宣告历史映射完成或激活。原支付 V40 仅保留在历史测试夹具中，不能与主线下载 V40 混用或 repair 冒充。

分阶段兼容：默认 Flyway target40、fence=false，仅完整且精确的 core40 可以使用既有账本及旧 Inbox；这保留原有能力，不代表资金治理已经启用。新 scoped Inbox 接口不得在旧布局降级。启用新账本时必须显式匹配数据库/端口并通过全部 ACTIVE41 检查；PREPARING、错误库、触发器不完整均拒启。V41 已安装时关闭 fence 也拒启。mysql 的迁移前置门禁禁止启动过程自行安装 V41，即使外部设置 target=latest/41；只有已审批安装、映射并 ACTIVE 的精确 V41 才能继续正常 Flyway 校验。H2 仅用于明确 test profile 的兼容回归，不是 MySQL 资金验收。

PayPal capture 级撤销或退款风险缺少确切退款/实际损失时，使用对应批次的持久 `REVIEW` 状态及剩余 hold，不伪造原生争议 ID，不永久扣回。Inbox 保持 WAITING，超过等待期限进入 MANUAL；消费失败原分摊恢复后同样保持该 hold。风险后台 RELEASE 只结束审核，不自动解除权益保留。解除 REVIEW 仍需独立的权威资金覆盖证明和审批；不能宣称此路径已自动闭环。

## 验收边界

`IntegratedCreditAccountMySqlTest` 使用标准事务管理器、真实 core1—40 加 canonical41、独立空库和虚构历史，验证上述当前写入契约、旧锁不阻断、邀请、快照、FIFO、订单冻结、负债、退款/争议身份及 UNKNOWN。它不是完整 Spring 应用、历史生产切换或真实支付验收。完整应用分别由 ACTIVE41 及 legacy core40 专项覆盖。保留主线原测试；H2 结果不替代真实 MySQL 验收。
