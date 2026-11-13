# 固定主线支付整合验收（本地候选）

任务日期：2026-11-12，更新跨至2026-11-13（Asia/Shanghai）。授权范围：本地修改、隔离测试及经授权的本地 Git 整合；不推送、不部署、不修改生产数据库、积分、支付或服务器配置。本报告不批准生产发布。

**本地最新联合回归已完成：独立新r12库的 `maven-full-20261113T030731559249Z` 实际1247项，1239 PASS、0 failure、0 error、8项外部资源BLOCKED；完整359项前端输入清单对应717/717测试及TypeScript/Vite通过。V41合成备份恢复通过。最终提交、main合并、fresh Git export JAR及打包启动恢复仍以新delivery.json实际收据判定，不在本文预写PASS。生产收款仍BLOCKED。**

## 1. 固定基线与文件保护

| 对象 | 事实与边界 |
| --- | --- |
| canonical迁移主线 | `b8a924f624f7b1643c483cf2cc963ab56f1d03ff`；包含最新下载权益功能，作为 canonical V1—V40 的唯一源码来源 |
| 支付旧检查点 | `c780f7a90ac3952fda8dea9ab38a7a42b901e7ed`；其中支付 V40 已成为旧候选历史，不是当前 canonical V40 |
| 价格/SSE 整合检查点 | `0c1a46b9c75edb2f8a12f626d08b88c0d86df1de`；只表示此前检查点，不代表已经吸收所有后续主线 |
| 独立工作树 | `.cache/worktrees/payment-integrated-20261112`，`codex/payment-integrated-20261112`；未覆盖原开发工作区 |
| 当前代码候选 | 已保存的 `a9084cc238c6e7a63f46bd26e0a0138f4d6056de` 加 `de36e702866e86490e3f8ec29f43c914f5f06a9c`、ACTIVE回归及新增启动/旧Inbox兼容；最新r12全量1247项已执行（1239 PASS、8外部BLOCKED）；不能沿用修复前1208数作为当前计数 |
| main文档增量 | `555ce8bfdcf06c4a6516746dab879abb149d07d8` 仅改 `docs/releases/2026-11-12-agent-audit.md`（13增/1减），不改变业务代码、SQL或前端输入；是否最终合并以delivery为准。本报告不自动追并其他新提交 |
| 原开发内容 | 原 `codex/google-login` 开发目录与旧候选均保留；`.cache/payment-main-integration-20261112/preservation-final-20261113T030850937386Z.json` 实际复核主工作区320、旧候选51个受保护文件，均0变化；最终delivery继续引用准确复核范围 |

所有证据路径相对公共仓库根目录，其中 `.cache` 不提交。初始保护清单位于 `.cache/payment-main-integration-20261112/preservation-before-20261112T130259295865Z.json`。最终保护状态、提交、合并和制品身份必须以新交付收据为准，不能套用旧检查点收据。

sparse checkout 在合并时再次省略 12 个媒体文件。本轮只从固定 b8 的原始 Git blob 以 CreateNew 恢复缺失文件，核对 Git OID 与 SHA-256，没有覆盖已有文件或下载替代音频。证据：`.cache/payment-main-integration-20261112/frontend/preservation-b8-20261112T145619935680Z/evidence.json`。源码自动合并保留 Stripe、下载新契约/文案、Agent SSE、模板与协议草案；未将整个旧候选目录覆盖到新主线。

## 2. 必须保留的业务规则

| 业务 | 当前固定规则 |
| --- | --- |
| 首次验证登录赠送 | 新规则 50 积分，每账号一次；不追溯扣减已合法获得的旧 100 积分 |
| 歌词、歌曲、改写 | 歌词 1、歌曲 5、改写 1 积分；一键歌词加音乐当前合计 6，服务端价格为准 |
| 头像 | 每次 5 积分；同请求不重复扣，供应商 UNKNOWN 不盲退，明确失败恢复原分摊 |
| 下载 | 每账号、每作品首次 10 积分；同作品已经授权的其他版本不重复扣；分享撤销与已购内容保留按主线权限规则处理 |
| USD 套餐 | 100/$9.90、300/$25.90、700/$49.90；不改 GBP。历史订单金额、币种及积分使用创建时快照 |
| 支付退款 | 只按累计实际成功退款金额计算 `ceil(原始积分 × 有效成功退款金额 / 实付金额)`；只补差额；pending/failed 不当成功 |
| 争议与负余额 | 只冻结对应订单当前剩余权益，WON 不永久扣；最终已证实本金损失才调整。允许负积分，后端统一入口禁止继续消费 |
| 消费失败返还 | 恢复原 debit/FIFO 分摊金额，不按当前降价后的成本重新计算 |

旧 100 积分登录兼容只作 **CODE_CONFIRMED**：`Qingfeng_AI_Music/qf-business/src/main/java/com/qingfeng/music/credits/FirstLoginCredits.java:5` 现在发放50；`Qingfeng_AI_Music/qf-business/src/main/java/com/qingfeng/music/identity/application/IdentityService.java:65` 和 `Qingfeng_AI_Music/qf-server/src/main/java/com/qingfeng/music/server/security/LogtoIdentityService.java:65` 的正常已绑定身份登录分支不会再次执行首次赠送hook。这不是生产历史数据验收。若仅有一条旧 welcome 流水，却缺少对应身份/发放背景，仍为 **UNKNOWN**，必须进入人工盘点；不伪造身份、不补发、不扣回、不改历史流水。

## 3. 迁移整合与不可跨越的历史边界

旧候选支付 V33/V34 与主线地理统计 V33、Inbox/账户余额 V34 含义冲突。之后旧支付候选使用的 V40，又与新主线 `V40__shared_download_entitlement.sql` 冲突。文件名不同、把数字改成 41，都不能使已经执行过旧 V40 的数据库自动成为兼容历史。

当前 canonical 方案：

1. 固定 b8 的 V1—V40 内容保持原字节，V40 是共享下载权益。
2. 新建 `V41__credit_entitlement_integration.sql`，SHA-256 `5b71ce9b5cb86e11c7e2ab8582dcc35cf9f024272632aabb84a4341546a97086`，Flyway CRC32 `-340729157`。这是针对 canonical core40 的新前向集成，初始 PREPARING，不自动生成真实历史批次或激活。
3. c780 原支付 V40 只保存为 `Qingfeng_AI_Music/qf-server/src/test/resources/legacy-payment-migrations/V40__credit_entitlement_integration.sql` 原字节测试夹具，SHA-256 `eb2868df1676875014762fe4b2e61615a69941e2fde7ce3f60002f526bb563f4`，CRC32 `-1311214355`，不进入发布迁移位置。
4. 已执行旧支付 V40 的真实隔离库，使用当前 canonical validate/migrate 必须拒绝：当前 12 项迁移测试已实际验证 version40 不兼容、历史/结构保持不变、V41 未执行、readiness 拒绝。没有 repair、改 Flyway 历史或跳过校验。

证据：`Qingfeng_AI_Music/qf-server/src/test/resources/credit-migration-reference.json` 及修复前检查点 `.cache/payment-main-integration-20261112/maven-full-20261112T160457197145Z/results.json`。测试类保留历史名称 `CreditEntitlementV40MySqlMigrationTest`，实际验证的是 b8 core1—40 → V41，不能按类名误称“支付 V40 已适配新下载 V40”；新增兼容修复后的最新r12全量收据见第5节。

### 3.1 迁移前安全兼容与启动阻断

- 默认 `spring.flyway.target=${CREDIT_SCHEMA_TARGET:40}`，不因新JAR内存在41而自动安装金融模型。只有未安装任何完整或部分V41对象、且通过 `CreditSchemaReadiness.CORE40_CHECKS` 全部16条历史/列/主键/外键/唯一键/旧Inbox检查的真实MySQL core40，才允许保留旧 `JdbcCreditsLedger`。无法读取、部分结构或不符历史必须拒绝；逐项失败分支的mock结果不能冒充16种真实旧schema破坏/拒绝测试。新增真实MySQL第5项已验证 core40 上显式 target41/latest、即使开启writer仍在迁移前拒绝，资金/结构未变；旧164749因另一项断言失败不标全量PASS，最新030731整轮已复验通过（8外部资源仍阻断）。
- `PaymentInbox` 按实际连接的 H2 `CURRENT_SCHEMA()` / MySQL `DATABASE()` 检测并缓存固定布局：core40原二元 `(provider,event_id)`、零scope/零新audit时，旧accept/claim/complete/renew/retry继续使用原SQL，`completeOwned`仍先锁owner再Inbox，跳过新audit；旧retry保持原PENDING退避语义。完整scope四元PK与两audit表才进入新路径，部分布局拒绝。
- 新scoped accept/claim、WAITING及人工 `resumeReviewed` 在旧布局必须拒绝；带非LEGACY merchant/environment的Delivery不能借complete/owned/renew/retry丢scope降级。旧兼容不意味着已具备订单级权益模型或Stripe资金能力。
- 一旦V41已安装或部分安装，`writer-fence=false` 不允许回落旧writer；ACTIVE41完整应用实际拒绝此启动，保留全部资金事实不变。显式fence=true仍要求精确库/端口、协议3 ACTIVE及全部结构校验。
- `CreditSchemaMigrationConfiguration` 的策略在调用 `Flyway.migrate()` **之前**拦截target>40及latest/current/next等非固定core目标：未启用fence、库/端口不符、未装41或PREPARING/不可读均拒绝；只有另行获批且已准确ACTIVE41的库才能继续正常Flyway校验。策略不安装、不repair、不映射、不激活41。固定target≤40仍可能执行core迁移，绝不是只读保证，也不构成生产启动/迁移授权。

上述兼容修复后 focused 46/46 PASS：`.cache/payment-main-integration-20261112/maven-targeted-20261112T163539647952Z/results.json`，包含真实ACTIVE应用MySQL19、真实legacy core40应用MySQL4、H2旧Inbox11、配置mock12。配置mock独立12 PASS见 `rollout-gates-20261112T162830199853Z/results.json`，迁移策略mock独立10 PASS见 `pre-migration-gates-20261112T164452532698Z/results.json`（均位于同一 `.cache/payment-main-integration-20261112/`）；后者只验证调用顺序/拒绝分支，不算真实迁移通过。后续164749全量为FAIL，165559中断为ABORTED；当前fresh r12 clean full实际1247项、0失败/错误、8外部跳过；fresh export及合并仍须交付收据。

## 4. 本地实现与剩余能力边界

| 模块 | 本地实现 | 尚未解决的边界 |
| --- | --- | --- |
| 统一权益账本 | owner 首锁、协议3、journal/credit_account/FIFO 批次与分摊同事务；负余额消费限制 | 不伪造历史 FIFO；归因缺失拒绝自动处理 |
| 原生资金身份 | provider/merchant/environment/nativeID 绑定 owner/order/capture/金额币种；返款共享唯一命名域 | 跨订单、跨用户冲突拒绝，不自动归并 |
| Stripe | 保留创建、查询、恢复、严格 Checkout URL、资金证明；主线 scoped Inbox/Outbox；USD 快照 | 本轮供应商 HTTP 为隔离 mock，不是新 Sandbox/Live 资金验收 |
| PayPal | 保留创建/授权/Capture/旧 API；core40使用真实旧Inbox/旧账本兼容，获批ACTIVE41后才到账绑定批次和使用scoped Inbox | CLOSED/败诉状态不单独证明本金损失；最终损失/返还、权威解除 REVIEW 未闭环 |
| 风险与恢复 | capture 风险只对对应订单持久 REVIEW/hold；pending 不耗技术重试预算，7天绝对期限后人工 | risk RELEASE 不代表资金返还，也不擅自解除权益 REVIEW；未知资金状态不猜扣/退 |
| 歌词/音乐/改写/头像 | 统一真实准入；未知结果不failover、不退预扣或重投；晚成功使用原请求；明确未受理才允许fallback且只扣一次，明确失败原批次恢复 | 长期 UNKNOWN 仍需权威核对，不按年龄退款或重投 |
| 下载与删除 | canonical 下载权益、10分同作品一次，权益与账本共同约束；完整应用加入下载/删除真实并发 | 本地虚构音频及 provider 不能替代真实 OSS/CDN、分享播放与生产验收 |
| 启动与发布门禁 | 默认target40；未装41且真实core40全部16检查通过可用旧writer；金融41路径必须显式Fenced、匹配库/端口及协议3 ACTIVE完整校验 | 已装/部分41时旧writer拒绝；target>40非ACTIVE在Flyway前拒绝；UP不代表全部业务健康 |

实际锁序必须逐调用链表达，详见 [payment-integrated-lock-order.md](payment-integrated-lock-order.md)：Fenced 的 account/order 与同步 settle 的 order/account 子序不同；同用户公共 owner 首锁提供串行化，不能因此声称全局所有子锁完全一致。不同 owner/nativeID 的空区间 gap 候选仍需专项真实复现，未包装成通过。

## 5. 当前真实执行结果

金融专项使用 MySQL 8.0.46 / InnoDB / REPEATABLE READ，明确 `127.0.0.1:23316`、UUID `05c27d3c-dda3-11f1-aba0-84a938421519`、`R:/data/`，独立 schema/账号、合成数据，核对实际身份、SHOW GRANTS、可见库。原主线兼容回归使用另一个隔离实例 `127.0.0.1:57326`，`R:/integration-compatibility-r1/`，不使用生产隧道 13306。

| 验收 | 当前状态 | 证据与限定 |
| --- | --- | --- |
| b8 前端回归 | PASS / TEST_CONFIRMED | 707/707，0失败、0跳过，包含原702和新增下载5项；`.cache/payment-main-integration-20261112/frontend/b8-acceptance-20261112T145705521332Z/` |
| b8 TypeScript/Vite | PASS / TEST_CONFIRMED | 同目录 build 日志 exit0；114个 dist 文件，index SHA-256 `5bd93f9dffcdf271f1e341f00967a2624e517821ccb2edf6a0a4cd604272d3be`；不是最终 Git export JAR |
| b8/a908 clean verify检查点 | PASS；8项 BLOCKED | `.cache/payment-main-integration-20261112/maven-full-20261112T153735148207Z/`；1169 total，实际1161 PASS，0 failure、0 error、8 skipped；不沿用旧1114数字 |
| ACTIVE 账本组件 | PASS / MYSQL_CONFIRMED，48项 | 最新030731 full真实事务/唯一身份/并发/回滚/三方一致性；不是供应商验收 |
| 完整应用 MySQL | PASS / MYSQL_CONFIRMED，19项 | 最新030731 full全部通过，包含原18项及已装ACTIVE41旧writer在ready前拒绝、25表SHA不变；真实Boot/HTTP/SDK HMAC/Inbox/Usage/头像/下载/删除并发/重启/写词；供应商HTTP强制mock |
| 默认core40兼容应用 | PASS / MYSQL_CONFIRMED，5项 | 最新030731 full全部通过：默认target40启动、PayPal合成创建/Capture/并发回调一次到账、scoped拒绝，以及target41/latest预迁移拒绝；资金逐行SHA/结构保持，Stripe关闭无外调，不安装41 |
| 旧Inbox布局契约 | PASS / TEST_CONFIRMED，11项H2 | 最新030731 full； 原幂等pointer/outbox、claim/complete/lease/retry、owner先锁及回滚、scope与混合布局拒绝；不代替MySQL |
| 配置/迁移前策略 | PASS / TEST_CONFIRMED，mock12/10 | 最新030731 full全部通过；独立旧收据只保留原范围，不重复累计或当实际迁移；真实负例由core40第5项覆盖 |
| canonical 迁移及旧40拒绝 | PASS / MYSQL_CONFIRMED，12项 | 最新030731 full；core1—40→41以及原支付40真实拒绝验证，未repair |
| V41静态编译 | PASS / TEST_CONFIRMED | `.cache/payment-main-integration-20261112/static-v41-20261112T150514041444Z/results.json`，限定 STATIC_JAVAC_ONLY，不冒充 MySQL |
| 新V41备份/恢复 | PASS / MYSQL_CONFIRMED，限定合成隔离数据 | fresh application r6 → restore r2，75表/41条迁移/17触发器/31owner守恒、源未写；`.cache/payment-main-integration-20261112/backup-restore-20261112T155805381022Z/results.json`。不是生产恢复验收 |
| 新提交 fresh Git export JAR/启动恢复 | PENDING，以本轮新delivery实测为准 | 必须逐字节匹配新增兼容修复后passing reactor的649个production类及完整359输入对应717回归dist，核对canonical1—41资源、JAR SHA及恢复库启动/业务不变量；旧647类清单不覆盖最新Java改动 |
| 原目录保护复核 | PASS，限2026-11-13 11:08:50 Asia/Shanghai时间点 | `.cache/payment-main-integration-20261112/preservation-final-20261113T030850937386Z.json`：320/51均0变化；最终delivery记录准确范围 |
| de36前端增量 | PASS / TEST_CONFIRMED | 717/717、0跳过，TypeScript/Vite exit0；`.cache/payment-main-integration-20261112/frontend/de36-acceptance-20261112T160232178871Z/`。index SHA-256 `194caef46af1d7caf0e4db722d106f8fdf0918df95d943ad762669425cd33d5e` |
| de36后端旧联合检查点 | 旧PASS命令；1200实际PASS/8外部BLOCKED | `.cache/payment-main-integration-20261112/maven-full-20261112T160457197145Z/`：1208 total、0failure、0error、8skip；647类SHA只覆盖兼容修复前源码 |
| 兼容修复后最终clean full | PASS命令；1239实际PASS、8外部BLOCKED | `.cache/payment-main-integration-20261112/maven-full-20261113T030731559249Z/{invocation.json,results.json,maven.log}`：1247 total、0failure、0error、8skip；独立全新r12库；同目录tested-production-classes.json固定649类SHA，不沿用旧647类或1208计数 |
| 完整前端输入/最终回归 | PASS / TEST_CONFIRMED | `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T030939326320Z/evidence.json`：359 inputs含build/public、前后完全不变，717/717零失败/跳过，TypeScript/Vite exit0；114 dist与旧717逐字节相同，index SHA保持194caef46af1d7caf0e4db722d106f8fdf0918df95d943ad762669425cd33d5e |

打包恢复启动脚本的验收范围仍为 **PENDING**，只计划验证匿名会话在指定恢复库真实落点、会话积分API与流水一致、25张资金表前后SHA不变、价格及关闭支付门禁。匿名会话不是Google/OIDC认证验收，guest零余额不能证明非零历史余额的HTTP展示或真实资金闭环；未执行前不填PASS。最终实际结果仅由本轮新delivery及对应jar-restore收据确认。

旧de36前端717测试/构建与114个dist证据保留，但313项inputs漏掉build/public，不能单独证明整个提交源码来源。已在独立complete-acceptance运行补全359项输入并重跑全部717测试及构建，前后不变，114 dist与旧证据逐字节相同；fresh Git export仍须对完整输入集合及内容核验，制品门禁不靠仅比较dist放行。

前阶段 27+14+11 纯计算模型52项 PASS、旧49项静态门禁及旧前端672/702和 Maven1114结果均保留其原范围，不相加为独立业务数量，不替代当前 canonical41 或最新提交验收。详细矩阵见 [payment-main-integration-acceptance-20261112.md](payment-main-integration-acceptance-20261112.md)。

## 6. 失败证据保留与构建问题

- `.cache/payment-main-integration-20261112/maven-full-20261112T152158595213Z/results.json` 为真实 FAIL：1095 total、0 failure、394 error、8 skipped。日志明确 `Found more than one migration with version 40`；原因是未 clean 的 target 残留旧支付 V40，与当前主线下载 V40 同时进入构建 classpath，不能把这394个上下文错误当作394个独立已修复业务问题。
- `.cache/payment-main-integration-20261112/maven-full-20261112T164749261443Z/results.json` 为真实 FAIL：1247 total、1 failure、0 error、8 skipped。唯一失败是新增 ACTIVE 第19项只接受旧 Usage 后置门禁原文；新 Flyway 门禁在此前正确拒启，日志包含精确预迁移审批提示。修复仅让该新增断言接受第二个完整准确的 writer 门禁原因；拒启、Ready=false、25表逐行SHA、余额及原应用仍有效等断言全部保留，没有接受任意启动异常，也未改变原18项断言。该轮 legacy core40 第5项真实 target41/latest 拒绝与零41 DDL已通过，不能因此把整轮FAIL改写成PASS。
- `.cache/payment-main-integration-20261112/maven-full-20261112T165559381498Z/interruption.json` 记录 **ABORTED**：回合中断，最后日志为2026-11-13 01:02:20（Asia/Shanghai），进程已消失且无最终results.json。部分执行不计PASS，保留库及日志；不得由部分XML补造完整成功收据。随后使用全新r12资源执行 `.cache/payment-main-integration-20261112/maven-full-20261113T030731559249Z/`，真实结果为1247 total、1239 PASS、0 failure、0 error、8 skipped/BLOCKED；中断记录不被覆盖。
- runner 已改为先归档既有测试报告，再执行离线 `mvn ... clean verify`。新证据153735为独立实际执行结果；保留失败日志、报告和原库，不通过删除断言或跳过并发获得 PASS。
- 之前媒体缺失、Endive输入校验顺序、原主线真实锁序、UNKNOWN预期及请求键夹具长度等失败记录继续保留。明确失败退款断言保留；UNKNOWN按真实业务规则保留预扣、禁止重投。

## 7. 历史订单与真实数据

用户指定的两笔订单，经批准的同连接 READ ONLY 事务只读重查，仍无法按原身份找到。证据 `.cache/payment-production-inventory-20261112/session-inventory-20261112T130447991683Z.json`。实际匹配的删除目标0，实际 DELETE0；没有按金额/日期猜删其他订单。无法找到本地记录、此前远端404，都不证明未付款或资金已经结清，继续待核查。

真实历史切换仍 BLOCKED：人工加分来源、无法归因消费、旧事件 scope、孤立 welcome 流水、资金例外不能由合成数据补造。本地合成历史映射和恢复演练不等于生产历史已迁移。

## 8. 仍阻断正式发布/收款的事项

| 优先级 | 项目 | 状态与解除条件 |
| --- | --- | --- |
| P0 | 真实历史切换 | BLOCKED：逐项盘点、全局身份映射、冲突隔离、人工签字、dry-run和恢复验证；不得伪造来源 |
| P0 | 生产旧writer/权限切换 | BLOCKED：审批停写窗口、全部实例/任务/人工入口封堵；禁止新旧写入者并存 |
| P0 | PayPal最终资金事实与REVIEW解除 | BLOCKED：权威实际损失/返还及退款重叠证明、解除流程、Sandbox回归未完成；不能将管理员risk RELEASE当作权益返还 |
| P0 | Stripe退款失败后的真实资金返还 | BLOCKED：此前真实返款尚为pending；记录available_on为2026-11-19 08:00 Asia/Shanghai。时间到达本身不是资金可用证明，须权威查询；禁止改时钟或制造成功 |
| P1 | 整合版供应商验收 | NOT_TESTED：隔离回调重验Stripe/PayPal Sandbox；旧候选真实证据不等于当前b8/41已验收，Sandbox也不代表Live |
| P1 | 8个外部资源测试 | BLOCKED：Redis3、Qdrant1、Rabbit4缺少显式隔离资源而skipped；未改条件，不计PASS |
| P1 | 不同owner/不同nativeID同空区间并发 | INFERRED / NOT_TESTED：缺失行FOR UPDATE再插入的RR gap候选尚未专项复现；现有同owner/同身份并发通过不能外推任何负载都无死锁。先复现、验证Inbox恢复，再决定窄修复 |
| P1 | 最终制品与生产恢复 | 新提交制品/最后复核结果只由本轮新delivery实际收据判定；当前V41隔离恢复通过不代替生产备份/灾难恢复审批和演练 |
| P2 | readiness负载 | CODE_CONFIRMED：每次gate有多条结构/触发器查询；压力成本未测。后续缓存必须绑定库/版本/epoch且失败关闭 |

## 9. 生产顺序与回滚边界（本轮不执行）

1. 核对当时远程/main、生产current/JAR、实际数据库历史与候选制品。固定检查点通过不代表动态main或生产已经一致。
2. 获批完成真实历史例外与精确映射；旧充值不覆盖、旧消费不伪造FIFO。无法确定项隔离等待人工裁决。
3. 使用准确生产历史的隔离副本dry-run、冲突检查、备份恢复及逐owner journal/account/batch对账。
4. 审批资金入口隔离和停写窗口：只关闭新购，不破坏存量查询/验签接收；持久保存事件，停所有旧writer，避免双写。
5. 获批执行准确迁移与映射；PREPARING不消费、不授予新权益，核验通过才显式ACTIVE；不repair旧支付40去冒充core40。
6. 新购仍关闭时验证存量资金事件、scope、金额、权限和账本；完成另行审批的真实验收后再考虑开放。

未来若获批部署V41，不能只把current指回旧JAR：旧writer不理解订单级冻结，且协议3触发器会拒绝旧写入。首次新账本写入前也必须按获批停写备份恢复方案及匹配制品撤回；新写入后优先协议3兼容回滚或前向修复。若需恢复数据，必须保全切换后的支付/Webhook并重放对账，不能丢弃新订单。旧payment40备份恢复PASS不证明canonical41可恢复；本地新恢复PASS也不自动授权生产恢复。

## 10. 当前交付判断

最新迁移前安全兼容修复已完成真实r12联合clean verify：1247 total、1239 PASS、0failure、0error、8外部BLOCKED；完整359输入对应717项前端及构建通过。canonical41合成恢复通过，030850保护复核320/51均未变。fresh Git export JAR、最终提交/main合并及恢复库打包启动仍以本轮新delivery.json实际收据判定，本文不预写PASS；须匹配最新passing reactor类清单及完整359前端输入，不能复用旧647类或旧JAR结论。生产收款仍 **BLOCKED**。源码文档不自引用尚未生成的最终提交哈希。

代码保存不代表8080/5173日常运行版本更新，也不代表生产已生效。没有推送、部署、生产数据写入或Live真实扣款/退款；后续生产备份、迁移、激活、权限/密钥或Live开关变更、真实资金验收均须另行审批。
