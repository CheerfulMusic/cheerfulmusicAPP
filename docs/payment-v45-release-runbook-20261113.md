# V45 受控部署执行契约

本轮用户已授权使用 V45 重新部署。本文和 `isolation/stripe-live-release-20261113/operator-plan.v45.example.json` 是审查材料，含占位符，不能直接执行；不含密钥、真实资金 PASS 或新的付款授权。实际执行结果只以同候选原始收据为准。

## 固定基线和可部署范围

- canonical core 为 V1—V44，V44 是已存在的 `V44__discover_song_publication.sql`，Flyway checksum `-1983576296`，保持原件与历史记录。
- 本轮金融迁移仅为 `V45__credit_entitlement_integration.sql`，LF 内容 SHA-256 `3dd5cebf19dee960ecefb2239668139918d363496fe87710f817c5b3a3241685`；core manifest SHA-256 `736294575e354900170114f8eb9568abaf15223a0bf7c9872d314d65559905e1`。实际 JAR 和数据库必须同时验证，不执行 repair/baseline/ignore/out-of-order。
- 目标是可验证地部署四渠道代码、保留历史净余额及旧订单、使用金融协议3，并先关闭全部新购。PayPal Sandbox 401、Live Reporting 403及四渠道真实资金验收仍保留 BLOCKED，不能因部署授权或代码测试通过而填成付款 PASS。用户本轮要求临时最低套餐，报价及单次限制见下文；Stripe 不支持 USD0.01。
- 当前正确项目 unit 是 `qingfeng-music.service`。另一套 `qingfeng.service` 使用不同应用/数据库，不属于本次停写清单。实际备份/验证 timer/service 必须逐个盘点，不能从文件名推断；样例 `writerInventoryVerified=false` 故意阻断未经盘点的执行。
- 2026-11-13 12:25 UTC只读刷新后，运行基线为 `880e0d57569e30d66f2604f97aa341f2b163b991`，目录 `/opt/qingfeng-music/releases/20261113-agent-final-880e0d5`，MainPID实际打开JAR SHA-256 `250b2eccbad5c231c03b1f3f13a8265fc7ce0f55a85f303410c2e5fbfad9a517`，与current一致。此前的b14c484已失效。样例只记录该次观察，正式执行前必须重查；变化即重新审查，不能继续套用旧基线。

## 明确执行顺序

每个变更阶段都使用 root 拥有、父目录不可由普通用户改写的计划，并给出独立 `--plan-sha256` 和 `--execute-approved`。使用已验证的 Python3.11；不更改系统默认 Python/Java。每次调用持有既有 release 与 backup 文件锁，并重新验证 current、JAR、DB、writer 与证据 SHA。

| 阶段 | 命令 operation | 真实前置条件与结果 |
| --- | --- | --- |
| 候选只读检查 | `inspect` | 同源构建、guard/gate/policy/JAR/contract/source 精确匹配；新工具放在候选目录 `release-tools/`，此时不覆盖已安装启动 guard |
| 暂停项目自愈 | `cron-pause` | 比较原 root crontab 和媒体脚本 SHA；仅注释项目那一条任务，私有保存完整原文；核对其他活动任务及环境行不变、该脚本进程已结束；不停全系统 cron |
| 停写与备份 | `backup` | 已有暂停证明；先验证 closed 配置可用，再停全部已盘点项目 writer，确认 PID=0，root 全局 PROCESS 观察排空；备份旧 JAR/配置/SQL/数据 |
| 真实备份恢复 | `restore-check` | 同主机新私有恢复 schema，不授权应用账户，不启动应用、不访问供应商；比对全部表 DDL/行数/完整行 hash、积分 journal、源库不变 |
| 恢复证明 R0 | `compose-restore` | 实际生产数据恢复、最终同 JAR 的本地打包 CLI/非空备份恢复/恢复库启动四类原始证据均实际完成；组合证明不含尚未知的 source/plan SHA |
| 冻结历史计划 | `carry-plan` | 所有 writer 仍停，真实恢复与同候选隔离 V45/兼容恢复证明齐全；独立 Java 只读输出 source/plan SHA，保存 `carry-forward-plan.json` |
| 恢复证明 R1 | `bind-restore` | 读取本次真实 frozen plan，将组合证明绑定 source/plan SHA；不改写 R0 或任何原始证据 |
| 只读迁移验证 | `schema-validate` | 在 P1 合同已经绑定真实 source SHA 后执行；固定 FinancialMigrationCommand 只执行 Flyway validate，原库44则报告44，绝不把它报成已升级45 |
| 显式金融 DDL | `migrate-45` | 精确 V45 授权、完整源指纹仍等于备份、四证明和新鲜 writer 观察通过；独立 Java 原生 Flyway 只升45，返回 PREPARING，不激活、不启动 Spring |
| 导入净权益 | `carry-apply` | 绑定真实 V45 结果、外部批准的 frozen source/plan、同一 JAR 和四证明；只追加 LEGACY_OPENING 与旧消费兼容映射，原 journal/订单不变，保持 PREPARING |
| 显式激活 | `carry-activate` | 原始 apply 结果已存在且匹配，同样重新观察 writer 和检查源 hash；只有 Java 实际完成才保存 ACTIVE 结果 |
| 启动前核验 | `carry-verify` | 同一 ACTIVE 状态、投影、净余额和源 hash 均由 Java 再核验；原生 JSON 供 cutover pin，不自动补填 PASS |
| 受控安装 | `install` | 同备份/恢复/ACTIVE/构建证明齐全；原子换配置与 current，安装精确 reviewed guard/gate，启动后核对 MainPID 实际 JAR；四渠道新购仍关闭 |
| 受控真支付 | `canary` | 逐渠道报价与用户金额/次数授权一致，临时最低套餐仅1名精确认证owner；已依据用户截图只读确认唯一认证creator；实际报价必须精确绑定该ID，不得向其他用户开放 |
| 公开新购 | `open` | 同候选四渠道独立实际 Live 付款、签名、唯一到账、归属币种和账本证明齐全；PayPal 未验不得跳过 |
| 自愈恢复 | `cron-resume` | 当前 crontab 仍精确匹配暂停后 SHA，且有该媒体脚本 release-lock/原子配置/健康/金融兼容真实证明；现脚本无锁，保持暂停，不能自动恢复 |

计划分三份排他创建、分别审查和 SHA pin，不能边执行边改同一文件。**P0** 用于 inspect、cron-pause、backup、restore-check 和 compose-restore；其 carry 合同仅承载真实数据库/JAR身份，`plan` 模式不要求虚构的 source/plan SHA。R0 创建后，将其精确路径/hash 放入下一份只读 carry-plan 计划。**P1** 在 carry-plan 后创建，migration/carry 合同、四证明、schema/history 两个 APPROVED 授权均绑定真实 source/plan 和最终 JAR；bind-restore 仍可用 P0 的同一 releaseId/候选输入产生 R1，再把 R1 pin 入 P1。随后依次 schema-validate、migrate-45、carry-apply、carry-activate、carry-verify。**P2** 再 pin 本次真实 carry-verify 与 R1，才可 install。每份计划保持同一 releaseId、旧基线、候选和备份目录；创建下一阶段输入不触发执行。

不能把 schema-validate 放在 carry-plan 之前当作既有前置：当前 FinancialMigrationCommand 即使 validate 也要求 source.sha256。也不能让 carry-plan 等待生产 V45 迁移收据；其需要的 migration45Verified 是**隔离虚构数据同制品**的已执行证据，与生产待执行阶段不同。

原始恢复备份位于 `/var/backups/qingfeng-music/stripe-live-<releaseId>/`。cron 的原文和暂停收据单独保存在同一受控父目录的 `stripe-live-<releaseId>-cron/`。两者都不进入 Git 或普通审查包。native `crontab` 完整文件安装保留其他任务；恢复前发现任何其他编辑即拒绝覆盖。release 文件锁覆盖本项目的发布入口；维护窗口内不得从其他 root 会话同时编辑 crontab。

若 cron 已暂停但旧媒体检查超过30秒仍未结束，命令返回 BLOCKED，保留原文和已暂停状态，不伪报进程排空。进程确实结束后可重入同一 `cron-pause`：只核对已暂停内容、原文 SHA 和无 PID，再完成收据，不重新安装 crontab、不覆盖原文。已有 PASS 收据的重放仍须重新核对当前 crontab 和进程；出现其他编辑即阻断。

## Java 与配置输入

执行器固定候选 fat JAR 的 `PropertiesLauncher` 和两个受审主类，不启动 Spring：

- `com.qingfeng.music.server.config.FinancialMigrationCommand validate|migrate`。
- `com.qingfeng.music.infrastructure.persistence.credits.CreditCarryForwardCommand plan|apply|activate|verify`。

Java 二进制、DB 私有 properties、migration approval properties、carry approval properties 均以绝对路径及 SHA pin。命令环境不继承 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`CLASSPATH`、`LOADER_PATH`，外部 loader 配置指向空文件；不传生产支付密钥。Flyway/驱动原始日志不进入 stdout 或收据，只接受最终脱敏 JSON。[Spring Boot launcher 规则](https://docs.spring.io/spring-boot/specification/executable-jar/launching.html)

运行配置保持 Flyway 禁用、`target=45`、SQL 初始化禁用；JDBC 必须真实 UTC 会话，应用继续使用原生产账户，不改变其用户/密码/数据库目标。closed 阶段 Stripe 三方法与新购均 false，Stripe/PayPal canary 名单为空，PayPal 新购 false；原回调接收与存量查询能力保留。

生产实查 `log_bin=1`、`log_bin_trust_function_creators=0`，应用账号没有 SUPER，不能创建本次触发器。操作器单独允许已验证的现有本机管理员，不改全局变量、不授新权限、不将管理员写入运行配置。采用该例外时，计划必须显式有 `operatorIdentity={mode:existing-local-root-empty-password,username:root,runtimeAccountUnchanged:true}`；approval contract 必须有 `allow-local-root-with-empty-password=true`；私有 DB properties 的 password 键必须实际存在且恰为空、username 精确 root、目标只能127.0.0.1:3306及本库。CLI还核对实际 CURRENT_USER；URL user/password 或 userinfo 覆盖一律拒绝。此处不把管理员身份冒称应用账号：fresh writer 观察分别排空原应用账户与操作器账户，运行配置仍与备份中的原应用凭据完全一致。

migration properties 必须绑定 `expected.uuid/database/port`、`candidate.jar.path/sha256`、`source.sha256`、固定 `core.manifest.sha256`、`migration.version=45`、`migration.sha256`、`approval.evidence`；写阶段另需 `migration.authorized=true` 和四个 `receipt.<kind>.path/sha256`。

carry properties 另绑定 `plan.sha256`。四证明为 `writers`、`backup-restore`、`rollback`、`legacy-boundary`。每次写阶段会生成真实 root 全局 PROCESS 观察并只更新临时私有 contract 的 writer receipt，不能改变已批准 source/plan/JAR 或其他三证明。不得从“应用已停”猜测连接池和手工隧道已断开。

rollback 证明要求协议3、schemaVersion45、关闭新购、真实恢复启动和精确兼容 JAR。legacy-boundary 为 contractVersion2，确认 `legacyOrdersReadOnly`、`balancesPreserved`、`allNewOrdersUseFencedLedger` 为 true，`paymentEnvironment=LIVE`，`allowedNewPaymentProviders` 精确为 STRIPE_LIVE 和 PAYPAL_LIVE。不伪造历史 FIFO 或资金来源。

## 授权与验收收据分开

`schemaAuthorization` 是 `status=APPROVED`，operation 为 `MIGRATE_CANONICAL_44_TO_FINANCIAL_45`；精确绑定 DB、候选 JAR、source SHA、core44/financial45、SQL SHA 和真实用户授权证据 SHA。

`historyAuthorization` 是 `status=APPROVED`，operation 为 `PRESERVE_EXISTING_NET_BALANCES`；除同 DB/JAR/source，还绑定 frozen plan SHA、`legacyOrdersReadOnly=true`、`balancesPreserved=true`、`historicalFundingProvenance=UNKNOWN_UNCHANGED`。批准保留余额不等于证明过去资金来源，也不表示可删旧订单。

`restore` 是执行证明而非授权：status PASS，绑定同生产 SQL 备份 SHA、候选 JAR、真实 `actualRestoreExecuted/dataIntegrityVerified` 和分别实测的 `migration45Verified/compatibleRecoveryVerified`。须引用原始实际数据恢复与隔离应用验证，不能把后者写成在真实用户副本中启动了应用。`cutover` 必须 pin 本次 `carry-verify` 的真实原生 JSON。

`restoreEvidence` 必须恰有 `artifact`、`packagedFinancial`、`isolatedBackupRestore`、`isolatedJarStartup` 四个 `{path, sha256}` pin。组合器逐一核验 PASS、作用域、最终候选 commit/JAR、本地实例真实 UUID/端口/账号隔离、8个打包 CLI 步骤、非空恢复库的45条正确迁移/17触发器/权益守恒，以及同一恢复库同 JAR 启停前后的财务快照。缺项、不同 JAR、步骤失败或金融事实变化一律 BLOCKED。实际生产备份恢复另读取本次 out 中的 `backup-receipt.json`、`actual-data-restore-receipt.json` 和 SQL SHA，重新比对源库指纹。

`compose-restore` 排他生成 `restore-composite-preplan.json`（`sourceBound=false`）；`bind-restore` 排他生成 `restore-composite-bound.json`（`sourceBound=true`，附真实 frozen-plan 路径/hash）。两者保留全部原始证据路径/hash，并明确 `productionCloneApplicationStarted=false`、`productionCloneMigratedTo45=false`、`compatibilityData=LOCAL_SYNTHETIC_ONLY`、`realPaymentAcceptance=NOT_TESTED`。本工具不将合成数据验收冒称生产克隆业务验收。

Java 四证明的扁平数据库身份、recordedAt、协议等字段与上述组合收据是不同契约；需要据 R1 及其原始证据形成可审查、source/JAR 一致的 backup-restore 与 rollback 两份操作证明，引用 R1 hash，不能只重命名组合文件。rollbackJar 必须是实际在非空隔离恢复库启动过的协议3/schema45制品；允许与候选相同，仅代表关闭新购的兼容恢复，不能回退至 core44 旧 writer。legacy-boundary 仍须由实际边界验收支持，不能由恢复测试推定。writers 由每次操作时全局 PROCESS 实查刷新，其余证明不能自动填补或替代。

现有 `verify_backup_restore.py` 对 dump 文件布局和格式有独立要求；本执行器的 `database.sql`、复制的 release/config 和 data tar 不能直接冒充该工具所要求的 history.tsv/rollback-files.tar.gz 单行规范备份。旧恢复记录也不能替代本轮最终 SQL 的真实 restore-check。

`migration-45-receipt.json`、`carry-apply-receipt.json`、`carry-activate-receipt.json`、`carry-verify-receipt.json` 均只在对应 Java 实际 PASS 后排他创建，不能覆盖旧收据。没有执行的阶段不生成 PASS。

## 失败与当前能走到的边界

DDL 隐式提交，不能把异常回滚描述为 schema 回退。V45 开始后任何失败都不自动启动旧 writer、不恢复旧库覆盖新事实，不 repair、手工改 Flyway 或忽略冲突。保持服务停止及项目自愈暂停；按已实际验证的协议3兼容 JAR 前向修复/恢复。

`abort-before-ddl` 仅适用于已决定中止、尚未尝试金融 DDL 的旧批准基线：cron-pause 预存的 current/JAR、运行配置、policy、guard/gate、unit 和附加配置 hash 全不变；候选中完整 V1—V44 原件与生产成功 Flyway 历史逐条一致；无任何 p0 金融表/触发器、新 inbox 结构及 V45 历史；无 migrate-45 writer观察、临时批准合同或任何迁移/开账收据；存在最终备份时源指纹还须未变。此阶段持同一双锁，仅调用原服务 start、检查健康和实际打开的旧 JAR，再精确恢复原项目 cron；不还原数据库、不覆盖配置、不重启整机或其他 unit。原 cron 任何并行修改、原媒体进程未退出、权限或校验异常均拒绝。迁移曾被尝试即保守拒绝，即使未生成成功收据；DDL 后永不通过该命令恢复旧 writer。成功收据仅为 `ABORTED_BEFORE_DDL_OLD_BASELINE_RESUMED`。

完整前置验证通过后，本轮可以安全走到 **V45 + 净余额兼容开账 ACTIVE + 新版 JAR 运行 + 所有新购关闭**。这不是四渠道已正式收款。当前独立资金阻断未解除，不能进入 canary/open；旧媒体自愈也不能未经锁兼容验证直接恢复。测试结果与生产阶段是否真正执行，仍由最终实际收据逐项汇报。

## 一名认证用户的临时最低报价验收

正常 USD 9.90/25.90/49.90 与 CNY 68.80/178.80/338.80 及其100/300/700积分均保持不变。临时例外由 `qingfeng.payment.acceptance.enabled`（默认false）、唯一 `owner-id` UUID、8位小写十六进制 `nonce`、UTC `expires-at`（末尾Z）和 `wechat-cny`（默认3.00，至少3.00）明确配置。card/Apple各 USD0.50、PayPal USD0.01、微信 CNY3.00，每笔仅1积分、每渠道仅1个槽。PayPal一美分是待供应商实际创建/支付确认的报价，代码允许正金额不代表Live已受理；不得提前写真实资金PASS。CNY1.00未达到本轮实际Sandbox兑换最低金额检查，不能用它占用唯一槽。

只在新购公用开关关闭、认证身份精确匹配且有同账号canary授权时提供私有报价，沿用原catalog API。owner此模式只获得各渠道尚未使用且未过期的1积分项，不能借canary购买普通套餐；匿名、非owner、已过期/已用项均不返回私有SKU。SKU为 `accept-<nonce>-<channel>-USD|CNY`（不超过36字符），客户端仅提交SKU/方法，不能指定金额、owner或积分。后端在既有creator行锁内重验有效期，并按creator+精确SKU+provider统计**所有订单状态**，已有FAILED/CANCELLED/未知状态也不释放槽，杜绝一次验收重复创建远程资金请求。结果未知保留原订单核对，不换nonce重试。

过期或关闭只阻止新购；已创建订单的查询、回调、恢复、退款永远使用持久化金额/币种/积分快照和SKU中固定渠道，不依赖当前报价开关。Apple验收SKU路由card家族，但正式业务验收仍须真实设备产生 `walletType=apple_pay`，普通银行卡不能替代。公开open配置必须disabled且清空owner-id/nonce/expires-at；历史私有订单继续正常结算。

临时报价的 `canaryFunding` 与 `business` 额外包含 `temporaryOffer={ownerId,nonce,expiresAt,wechatCnyMinor:300}`。每个channelQuote明确 `packageId`、`ownerId`、`credits:1`、`maxPurchases:1`、`approvedTransactionLimit:1`、实际货币最小单位金额及授权证据，四项合计 USD1.01+CNY3.00。canary配置必须与资金授权逐字段一致。每份真实channelAcceptance还需对应订单 `orderCreatedAt` 早于该UTC expiry；回调可以晚到，不能据此改写原订单金额。原有真实供应商回读、签名、独立交易/订单/账本引用、四渠道归属、唯一到账和账本核对要求全部保留。此扩展只改变明确授权的验收报价，不会让未执行的资金验证成为PASS。

## 本编排工具的实际离线证据

`python -B isolation/stripe-live-release-20261113/test_guarded_release.py` 本轮实际执行 **67/67 通过**。原52个测试全部保留。追加9项覆盖复合证明缺项/失败/作用域错误、不同 JAR/source、CLI步骤及迁移CRC错配、恢复快照变化、R0/R1真实计划绑定，以及 DDL 尝试或 schema/配置变化时严拒启动旧 writer、纯 DDL 前恢复顺序；另追加6项覆盖私有报价身份/单次/到期/精确金额/公开禁用，以及晚到回调保留创建快照。原有 Java隔离、审批、锁、cron等断言未删改；这些是离线契约测试，不能替代真实MySQL、生产恢复或资金验收。

此前52项证据位于本临时工作树 `.cache/payment-v45-release-20261113/operator-contract-tests-20261113T105745501371Z.json`；61项阶段证据为同目录 `operator-contract-tests-final-20261113.json`，67项临时报价追加证据为 `operator-contract-tests-acceptance-20261113.json`，均绑定当时脚本和测试 SHA、保留实际输出，不覆盖旧证据。临时报价 Java聚焦47项实际PASS，根目录证据 `.cache/payment-main-integration-20261112/maven-targeted-20261113T121822145949Z/results.json`（含原方法、纯策略、H2服务事务/并发/晚到快照；不冒称MySQL或供应商验收）。新增完整应用MySQL Order40必须另随新隔离队列全量实际运行，未运行不能填写PASS。作用域为离线编排契约；本代理本轮仅只读核对过服务器文件/进程，不曾生产修改、查询业务数据或调用供应商，未执行真实迁移或批准资金操作。真实 Java/MySQL 与生产执行结果必须另附同候选实际证据。
