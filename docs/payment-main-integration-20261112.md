# 支付与最新主线本地整合验收

日期：2026-11-12。范围：本地修改、隔离测试、提交及合并本地 main；不推送、不部署、不修改生产数据或配置。本报告不批准生产发布。

## 基线与保护

- 原开发目录 `codex/google-login` / `1b47d89d6373cd03854cd8632f597ba59d81753e` 的 44 项已跟踪修改及原有未跟踪内容保留；未切换、清理、覆盖或 stash。
- 在 `.cache/worktrees/payment-integrated-20261112` 创建 `codex/payment-integrated-20261112`，从本地 main `7a7778afba67bb1b554068b4ca7a5fa6e173b1a6` 开始整合。
- 验收期间 main 新增 `8872168c9e67dd8012a12d4ccaec19bb18d1a9e3`（头像生成 5 积分），已纳入本次整合和联合回归；保留新 Agent、头像、歌词、照片及看板相关已提交功能。
- 两次 `git fetch --all` 均因连接重置失败。远程最新状态不能据此宣称已核对；本轮以明确的本地主线提交为准，正式发布前必须重新 fetch 并核对。
- 独立目录的 sparse checkout 在合并时重新省略 12 个媒体文件。仅对缺失文件使用固定 `8872168` 的原始 Git blob 和 CreateNew 恢复，验证 OID/SHA，已有文件未覆盖，没有媒体内容 Git diff。原失败测试证据保留。

保护证据位于公共仓库忽略目录 `.cache/payment-main-integration-20261112/preservation-before-20261112T130259295865Z.json`，最终复核及制品信息另存于同目录。本报告的路径均相对公共仓库根目录；其中 `.cache` 证据不提交。

## 冲突原因及处理

旧支付候选与最新主线分别占用了同一 Flyway 版本号：支付权益原型的 V33/V34 与主线的地理统计 V33、支付 Inbox/账户余额 V34 含义不同。文件名不同并不能避免 Flyway 编号冲突。旧包装账本还只维护 journal/batch，会漏写主线新增的 `credit_account`。

本次保持主线 V1—V39 内容不变，新建真正整合后的 `V40__credit_entitlement_integration.sql`，不复制或改名旧生产迁移、不 repair 历史。旧原型 SQL 仅作为原字节测试夹具，发布 JAR 禁止携带。V40 的归一化 SHA-256 为 `eb2868df1676875014762fe4b2e61615a69941e2fde7ce3f60002f526bb563f4`，Flyway CRC32 为 `-1311214355`，初始 PREPARING，不自动激活或编造历史批次。

## 实际修复

| 模块 | 本地实现 | 重要边界 |
| --- | --- | --- |
| 统一账本 | 协议 3 owner 首锁；journal、credit_account、FIFO 批次/分摊同事务；失败回滚原分摊；负余额限制消费 | 历史归因不明抛错，不伪造旧 FIFO |
| 原生资金身份 | provider/merchant/environment/nativeID 全局绑定 owner/order/capture/金额/币种；退款与争议返款共享唯一资金流水命名域 | 跨订单或跨用户冲突拒绝，不自动归并 |
| 退款/争议 | 累计成功退款向上取整；订单级 hold；WON 不扣；最终真实损失才永久调整；返还须权威完整证明 | 部分损失与退款无法证明重叠关系时人工核查，不猜测相加或抵消 |
| Stripe | 保留旧测试断言和资金证明逻辑；接入主线持久 Inbox/Outbox；owner 隔离查询、USD 快照、未知创建恢复 | 本轮供应商事实为隔离 mock；不是新 Sandbox 或 Live 验收 |
| PayPal | 保留原创建/授权/Capture/API；到账绑定新批次；验签后 scoped Inbox；成功退款与争议归一化 | CLOSED 不等于 LOST；败诉资源本身不证明实际本金损失 |
| 风险隔离 | capture 级未知风险持久保留对应订单剩余权益，其他订单可消费；risk RELEASE 不伪造资金返还 | REVIEW 自动解除及 PayPal 最终损失/返款证据接线仍未完成 |
| Inbox | 四元身份、payload 指纹、租约、重试、等待期限、人工恢复审计；等待不耗尽技术重试预算 | 原 LEGACY_UNKNOWN 行须盘点绑定，不能猜 scope |
| 歌词/改写 | 超时/网络未知保留预扣与并发占用、不重投，晚到成功结算原请求；明确拒绝才退 | 必须经权威核对处理长期 UNKNOWN，不靠年龄盲退 |
| 最新头像 | 保留主线每张 5 积分、重复键不重扣、UNKNOWN 不退、明确失败原批次恢复 | 验收使用虚构 provider，未调用真实图像服务 |
| 启动门禁 | MySQL runtime 必须显式启用 Fenced、匹配实际库/端口、精确 V40/核心34历史及 25 项结构/触发器检查 | 缺失/PREPARING/旧 writer 拒绝启动；健康 UP 不能替代业务验收 |

锁序实情与适用范围见 [payment-integrated-lock-order.md](payment-integrated-lock-order.md)。

## 真实执行证据

本轮环境：MySQL 8.0.46 / InnoDB / REPEATABLE READ，固定 127.0.0.1:23316、UUID `05c27d3c-dda3-11f1-aba0-84a938421519`、datadir `R:/data/`。各迁移、账本、应用 cohort 使用独立空库和仅该 schema 权限的新账号，核对 SHOW GRANTS 与可见库，不复制生产业务数据或密钥。旧主线并发回归使用另一新建的 127.0.0.1:57326 实例，datadir `R:/integration-compatibility-r1/`；不使用生产隧道 13306。

| 验收 | 状态 | 证据/说明 |
| --- | --- | --- |
| 原离线模型 27+14+11 | PASS / TEST_CONFIRMED | `.cache/payment-release-acceptance-20261112/legacy-offline-20261112T134811672307Z.json`；52 项原源码不变；不等同 Java/MySQL |
| 前端最终联合回归及构建 | PASS / TEST_CONFIRMED | 86 文件、672 项全部通过、无跳过；含头像新测试。新工作树 `.cache/payment-integrated-validation-20261112/frontend-restored-final-20261112T140154021790Z/` |
| 发布/迁移静态门禁 | PASS / TEST_CONFIRMED | 10+31+8 项；`.cache/payment-main-integration-20261112/static-20261112T140751141796Z/results.json` |
| 真实迁移与启动结构 | PASS / MYSQL_CONFIRMED | `CreditEntitlementV40MySqlMigrationTest` 11 项；真实 1—40 与 25 项 gate，不使用 H2 替代 |
| ACTIVE 真实组件并发 | PASS / MYSQL_CONFIRMED | `IntegratedCreditAccountMySqlTest` 48 项；FIFO/冻结/负债/返还/身份冲突/快照/并发/UNKNOWN |
| 完整应用 MySQL | PASS / MYSQL_CONFIRMED | `IntegratedPaymentApplicationMySqlTest` 12 项；完整 Boot/真实 HTTP/SDK HMAC/Inbox/账本/上下文重启；供应商 HTTP 强制隔离 mock |
| Maven 最终 verify | 命令 PASS，8 项未执行 | 1114 项中实际通过 1106，0 failure/0 error，8 skipped；`.cache/payment-main-integration-20261112/maven-full-20261112T140434069814Z/results.json` |
| 真实 Stripe/PayPal Sandbox | NOT TESTED（本轮） | 保留前阶段证据，不将旧候选或 mock 结果冒充本次整合版真实供应商验收 |
| 生产切换/真实资金验收 | BLOCKED | 没有执行授权，也未满足历史切换及资金恢复的全部条件 |

失败证据未清理：首次 144 项有两处测试/适配失败；首次全量揭露两条真实死锁及旧 UNKNOWN 测试预期错误；187 项专项中仅完整应用夹具请求键过长失败。实际修复后使用新的 migration/credit/application cohort 重跑，不重置旧库，不降低并发或跳过失败测试。错误 UNKNOWN 断言改为保留预扣、禁止重投、接受晚成功；明确失败退款场景的余额/退款条数断言保留。

## 两笔历史订单

用户要求删除的两笔订单，经过已批准的同连接 READ ONLY 生产重查，当前仍无法按原始身份找到。证据 `.cache/payment-production-inventory-20261112/session-inventory-20261112T130447991683Z.json`。实际可删除目标为 0，未执行 DELETE，也没有按金额、日期猜测删除其他订单。保留脱敏例外记录；未找到本地订单及此前远端 404 都不证明未付款或资金已经结清。

## 仍然阻断正式收款的事项

| 优先级 | 项目 | 状态与解除条件 |
| --- | --- | --- |
| P0 | 历史账本切换 | BLOCKED：真实盘点已只读执行，但人工加分来源、无法归因历史消费、旧消息 scope 及例外仍需签字核查。禁止将虚构测试映射套用生产 |
| P0 | 生产旧 writer/权限 | BLOCKED：需审批停写窗口、所有实例/定时任务/人工入口封堵及账号权限安排；不能只部署新 JAR 后保留旧写进程 |
| P0 | PayPal 最终损失/返还 | BLOCKED：真实本金损失、退款覆盖及解除 REVIEW 的权威证据接线、Sandbox 及人工流程须另行完成。当前只提供安全冻结和人工待核 |
| P0 | 外部资金恢复验收 | BLOCKED：前阶段 Stripe 退款失败资金返还仍需可用资金事实；此前记录的 available_on 为 2026-11-19 08:00 Asia/Shanghai。不能改时钟或制造补偿成功 |
| P1 | 整合版供应商验收 | NOT TESTED：须在隔离 callback 重新验证 Stripe/PayPal Sandbox，不能复用生产密钥或生产首页回调 |
| P1 | 关联中间件回归 | BLOCKED：3 个 Redis、1 个 Qdrant、4 个 Rabbit 测试因缺少显式隔离资源未执行；8 skipped 不算通过。未删除或更改这些条件 |
| P1 | 不同原生资源空区间并发 | INFERRED / NOT TESTED：退款/争议事实表存在缺失行 FOR UPDATE 再插入的形状；同用户和同 nativeID 并发已测，不同用户、不同 nativeID 的 RR gap 屏障尚未专项复现。不能据现有 PASS 声称任何负载均无死锁；需要补真实场景并验证 Inbox 持久恢复，不凭推测重构 |
| P1 | 生产备份恢复/切换演练 | BLOCKED：本地虚构数据恢复不替代生产备份可恢复性，正式操作须单独审批 |
| P2 | readiness 负载 | CODE_CONFIRMED：当前每次 gate 有 25 条结构查询，含触发器比较；负载成本未测量。后续缓存设计必须绑定库身份/迁移/epoch、失败关闭，不能删 gate 追求通过 |

## 推荐生产变更顺序（本轮不执行）

1. 重新 fetch 并核对生产/current、远程 main、候选制品与数据库实际版本；未核对不得发布。
2. 单独批准历史例外处置、只读盘点结果及精确映射。历史充值原记录不覆盖、历史消费不伪造分摊，无法判定项隔离并签字。
3. 隔离库执行与真实历史结构匹配的 dry-run、冲突隔离及备份恢复；核对每 owner 的 journal/account/batch 和未决退款/争议。
4. 审批维护窗口及资金入口隔离策略；仅关闭新购，不关闭存量查询/验签接收。保存持久事件并保证未成功持久化的回调不应答成功；停所有旧 writer，禁止新旧并写。
5. 经审批执行准确迁移链及映射，PREPARING 期间不消费、不授予新权益；核验通过才显式激活，启动协议 3 制品。不得自动 repair 或重新编号历史。
6. 在新购仍关闭条件下验证存量事件、金额/账户/环境、账本及 API 权限；完成审批范围内真实小额支付/退款验收后再开放新购。

回滚不能仅将 current 指回旧 JAR：V40 触发器会拒绝旧 writer，且旧应用不知道订单级冻结。首次新账本写入前可按经验证的停写备份恢复方案撤回；新写入后优先使用协议 3 兼容回滚制品/前向修复，必要数据恢复必须保全并重放切换后的资金事件并再次对账，不能丢弃新到账订单。生产备份、迁移、激活、权限变更、Live 开关、真实支付验收、推送及部署均需要独立授权。

## 本地交付结论

代码与覆盖范围内的隔离验收已完成，可以按用户授权提交并合并本地 main。逐套件明细见[验收矩阵](payment-main-integration-acceptance-20261112.md)；最终提交、制品哈希、恢复收据及主线合并结果以忽略目录的本轮 `delivery.json` 为准，避免在提交内自引用尚未生成的提交哈希。原开发目录 320 个文件及旧支付候选 51 个受保护文件在实际逐项 SHA 复核中未变化。

代码合并不代表 8080/5173 日常运行版本更新，更不代表生产生效。生产收款门禁保持 BLOCKED；未获生产操作授权。下一步须先处理真实历史映射和旧 writer 切换条件、资金返还与 PayPal 权威证据、整合版供应商验收及上表其余未执行项，再申请具体生产变更。
