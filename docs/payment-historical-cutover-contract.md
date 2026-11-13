# 历史积分开账工具契约

日期：2026-11-13（Asia/Shanghai）。本说明描述本地实现，不预填测试PASS，不批准生产迁移或激活。

## 工具入口

`CreditHistoricalCutover` 位于 `qf-infrastructure/.../persistence/credits`，不是Spring组件，不在启动流程自动执行，也没有HTTP入口。

| 操作 | 行为 |
| --- | --- |
| `inventory()` | 单一只读一致性事务读取真实数据库身份、完整canonical迁移历史、creator ID/风险标记、已有余额、不可变积分流水、订单快照和风险事实摘要；不读取个人资料或支付事件payload |
| `dryRun(snapshot, approval)` | 纯计算，输出不可变源快照、问题清单、开账批次、旧消费映射及SHA；不访问数据库 |
| `applyIsolated(plan, approvedPlanSha256)` | 仅允许已证明身份/权限的本地独立MySQL，或明确标记为H2的契约环境；PREPARING下单事务应用并核验；不激活 |
| `isHistoricalCreditReplay(...)` | 新writer对旧CREDIT的窄幂等适配；不发放积分、不改账本 |

工具使用现有V44结构，没有新增、重命名或修改迁移。生产应用和生产激活执行器尚不包含在本工具内。

## 开账规则

1. 每位历史owner汇总原始journal。已有`credit_account`必须与journal一致；不存在的缓存不伪造为0，仍以旧账本真实汇总为准。
2. 每位owner建立一个`LEGACY_OPENING`批次，原始值等于切换时的**剩余净权益**，不声称它是某笔历史充值，也不填造历史FIFO消费分摊。
3. 原始CREDIT、DEBIT、REFUND及时间不修改；旧DEBIT按原reference、owner、金额建立`p0_legacy_debit_mapping`，归因为`UNKNOWN`。已有全额消费REFUND精确匹配后标记restored；部分金额、跨用户或孤立REFUND阻断。
4. `payment:` CREDIT、已PAID订单、历史退款/争议/永久资金调整和风险事实目前不允许归并成通用开账权益，须另有权威订单级映射。
5. 负历史余额为`NEGATIVE_OPENING_REQUIRES_EXPLICIT_DEBT_STRUCTURE`。本版本没有独立历史开账债务结构，禁止以伪造正批次、虚构历史消费或调整量掩盖债务。
6. 非标准赠送和人工CREDIT必须按确切reference批准并附证据ID；批准缺失或不存在的陈旧批准均阻断。`first-login:`及`referral:`只保留其账本事实，不补造真实身份。
7. 未PAID本地订单仍需明确列入人工隔离清单。隔离确认不表示远端未付款，不删除订单、不变更状态、不结案404。

## 不可变计划与幂等

源SHA绑定真实engine/version/schema/UUID/port、canonical core1—43历史、所有owner风险/缓存、全部原始journal、订单金融快照和风险摘要。V44自身DDL不加入源SHA，所以可验证43→44不改变任何旧资金事实。

计划SHA另外绑定批准reference、隔离订单ID和操作证据。apply必须接收外部认可的准确计划SHA，并重新计算plan与数据库当前快照；源漂移、计划变造、部分导入或已有新资金事实都拒绝，不自动修复。

首次应用将`history:<源SHA>`写入cutover evidence，仍保持PREPARING。重放必须同时匹配全部开账批次及旧映射，而不是只看evidence或行数。任何失败整体回滚本次DML；既有Flyway DDL不属于该事务回滚范围。

## 锁序和旧引用重放

独立开账事务使用SERIALIZABLE，并按主键顺序锁全部creator，然后锁writer policy及cutover state，再锁余额/订单/journal与开账投影。该工具只允许维护期PREPARING，不与正常ACTIVE writer混跑。

开账`created_at`使用数据库切换时刻，必须严格晚于全部原始journal时间。opening reference是`legacy-opening:<该owner全部旧journal精确内容SHA>`，source_id与cutover evidence完全一致。旧CREDIT幂等重放必须满足：

- 同owner唯一opening，source_id为正确的`history:<64hex>`并匹配ACTIVE evidence。
- 原CREDIT的reference/owner/amount一致，时间严格早于opening边界；`payment:`永久排除这条通用兼容路径。
- 重算该owner边界之前**全部**journal内容SHA并匹配opening reference。

因此不能仅凭时间戳判断历史。新缺批次行即使因时钟回拨或人为回填落入旧时间区间，也会改变完整prefix SHA并拒绝。边界相等及未来时间拒绝；正常旧CREDIT重放不新增任何journal或批次。

## 本地MySQL资源门禁

apply硬性要求MySQL8.0.46、实例UUID `05c27d3c-dda3-11f1-aba0-84a938421519`、23316端口、127.0.0.1绑定、R盘专用data目录及`p0_historical_cutover_20261113rN`数据库；要求无active role、无其他库权限或可见业务库。主库、SSH生产隧道和其他实例不符合门禁。

实际连接必须使用UTC会话（`@@session.time_zone`为`+00:00`或`UTC`）。仅设置JDBC的`serverTimezone=UTC`不能证明MySQL会话也是UTC；应使用`connectionTimeZone=UTC`和`forceConnectionTimeZoneToSession=true`并实际查询验证。所有新writer及开账连接必须采用一致策略。工具遇到非UTC会话拒绝，不自动修改历史时间。

首轮真实MySQL已发现这一配置边界：DB时间开账为本地14:26，而随后应用Instant绑定的REFUND记为06:26，合法新流水误入旧prefix后兼容重放安全拒绝。失败收据保留；测试连接已显式强制并实查UTC，增加后续真实REFUND严格晚于opening且旧CREDIT仍可重放的断言，不放宽hash或边界。

正式生产切换仍需单独的已审阅执行器、实际历史例外审批、停全部旧writer/断开旧会话、备份实际恢复证明、在途支付及Webhook保全、人工核验和显式ACTIVE步骤。不能移除此隔离门禁后直接运行生产。

## 测试入口和证据范围

`CreditHistoricalCutoverTest`为纯计算计划测试；`CreditHistoricalCutoverMySqlTest`为全新独立MySQL的真实43→44、源守恒、dry-run、失败回滚、幂等、旧CREDIT重放、旧消费失败恢复和时间/hash边界测试。

MySQL测试必须显式提供`P0_HISTORICAL_CUTOVER_MYSQL_{URL,DATABASE,USER,PASSWORD,UUID}`，密码只从本机私有配置注入，不写日志或报告。测试拒绝有表的旧cohort，不清库，不复用生产数据，不调用供应商。

测试是否PASS及实际命令由本轮统一Maven收据记录，本文件不以实现存在替代执行。纯计算或H2结果不代替MYSQL_CONFIRMED；隔离MySQL结果不代替真实生产开账。
