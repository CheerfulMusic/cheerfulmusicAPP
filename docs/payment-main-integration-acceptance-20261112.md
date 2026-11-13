# canonical41 支付整合验收矩阵（含迁移前兼容修复）

任务日期2026-11-12，更新跨至2026-11-13（Asia/Shanghai）。本矩阵不批准生产上线。迁移前兼容修复后最新r12 clean full实际1247项，1239 PASS、0failure、0error、8外部BLOCKED；完整359前端输入对应717测试及构建通过。制品及合并仍待实际交付收据，不能把修复前1208项旧结果当当前计数。V41合成恢复保留原实际范围。最终提交/main合并、新制品和保护结果以本轮新delivery.json实测判定，不预写未来PASS。

## 1. 版本与执行入口

- 被验收主线：`b8a924f624f7b1643c483cf2cc963ab56f1d03ff`，canonical core V1—V40；支付新迁移为 `V41__credit_entitlement_integration.sql`。
- V41 SHA-256：`5b71ce9b5cb86e11c7e2ab8582dcc35cf9f024272632aabb84a4341546a97086`；Flyway CRC32：`-340729157`。
- `c780f7a` 与 `0c1a46b` 仅为旧检查点；已执行 c780 原支付40 的库不能冒充 b8 共享下载40历史。原支付40 SQL 原字节仅留测试夹具，拒绝验证实际执行，无 repair。
- 当前全新r12运行：`.cache/payment-main-integration-20261112/maven-full-20261113T030731559249Z/`；exit0，1247 total、1239 PASS、0failure、0error、8skipped/BLOCKED。上轮165559只有中断收据，不能当通过。
- clean full命令：`mvn.cmd -o -Dmaven.repo.local=<公共仓库>/.cache/maven clean verify`，由隔离runner执行；旧报告先归档。兼容修复后的最终命令由030731实际收据证明。
- 修复前旧full：`.cache/payment-main-integration-20261112/maven-full-20261112T160457197145Z/{invocation.json,results.json,maven.log}`；旧 `tested-production-classes.json` 的647类不覆盖后加Java修复，fresh Git export必须匹配最新030731 passing reactor重新生成的649个production类逐类SHA。
- 最新focused：`.cache/payment-main-integration-20261112/maven-targeted-20261112T163539647952Z/results.json`，46/46、0fail/0error/0skip，包含配置mock12、ACTIVE完整应用MySQL19、legacy core40完整应用MySQL4、旧Inbox H2契约11。
- 独立mock：`.cache/payment-main-integration-20261112/rollout-gates-20261112T162830199853Z/results.json`（配置12）和 `.cache/payment-main-integration-20261112/pre-migration-gates-20261112T164452532698Z/results.json`（迁移前策略10）均PASS；配置12与focused重复，不重复累计；策略10不在46内，也不代表真实Flyway执行。
- invocation 明确未继承生产凭据，端口23316/57326；原主线兼容 target40，新金融契约显式target41。H2兼容结果不充当 ACTIVE MySQL 或资金验收。

兼容修复前旧检查点总数 **1208**：**1200 PASS、0 failure、0 error、8 skipped/BLOCKED**；仅保留其原范围。最新代码新增默认target40、CORE40_CHECKS16、旧Inbox兼容及pre-Flyway启动门禁，最新r12全量 **1247 total、1239 PASS、8 skipped/BLOCKED**。555只改一份发布文档13增/1减，未改业务/SQL/前端。实际合并以新delivery为准，不自动追并其他提交；旧1169/1208都不套作当前计数。命令exit0也不代表8项外部或资金门禁已清除。

## 2. 关键验收与证据等级

| 编号 | 验证目标 | 实际结果 | 证据与限制 |
| --- | --- | --- | --- |
| BASE-41 | 固定b8 core1—40与新41，历史/结构/checksum不漂移 | PASS / MYSQL_CONFIRMED，12项迁移套件 | `CreditEntitlementV40MySqlMigrationTest`是历史类名；当前实际链到41 |
| BASE-OLD40 | 独立空库真正执行c780原payment40，再canonical validate/migrate必须拒绝 | PASS / MYSQL_CONFIRMED | 原40 fixture SHA精确相同；验证version40冲突、readiness拒绝、历史/结构不变、41未执行；不repair |
| START-40 | 默认target40，仅原core40且未安装任何41对象时可保旧writer | PASS / MYSQL_CONFIRMED，legacy应用5项 | 最新030731 full全部通过；真实core40 Boot/HTTP/PayPal合成资金和journal/account核验、target41/latest预迁移拒绝，Stripe关闭且无外调，不是生产验收 |
| CORE40-GATE | CORE40_CHECKS全部16条历史/结构/唯一键/Inbox约束必过 | CODE_CONFIRMED；逐项mock PASS | 真实core40正向及target41/latest拒绝已在最新full执行；16条结构负例逐项为mock，未执行16种真实MySQL破坏测试，不能互相替代 |
| INBOX-LEGACY | 原二元PK、零scope/audit的accept/claim/complete/owned/lease/retry兼容 | PASS / TEST_CONFIRMED，H2 11项；另最新MySQL应用5项覆盖调用 | 原pointer及outbox身份、owner→Inbox、事务/租约及>8原retry；H2不代替真实MySQL |
| INBOX-NO-DOWNGRADE | 新scoped/WAITING/resume在旧布局拒绝，scope Delivery及部分布局不能降级 | PASS / TEST_CONFIRMED | H2布局契约；真实core40另验证scoped accept/claim拒绝，不丢商户或环境 |
| INSTALLED-NO-OLD | 已安装ACTIVE41时fence=false必须在ready前拒启，金融事实不变 | PASS / MYSQL_CONFIRMED，ACTIVE第19项 | 最新030731 full `installedActive41RejectsAFalseWriterBeforeReadyAndLeavesEveryFinancialFactUnchanged`；不是只看mock或健康码 |
| PRE-FLYWAY | target>40/非固定目标在migrate前要求已获批精确ACTIVE41；不安装/repair/映射/激活 | 分项PASS / MYSQL_CONFIRMED及独立mock10 | 最新030731 full的Legacy第5项真实core40分别target41/latest拒启、资金逐行SHA/表数/trigger数未变、无41历史或P0 DDL；旧164749整轮FAIL保留。固定target≤40仍可能执行core迁移，不是只读或生产授权 |
| LEDGER-41 | ACTIVE协议3账户/journal/batch、退款/争议/返还/全局身份/并发/负积分/原分摊恢复 | PASS / MYSQL_CONFIRMED，48项 | `IntegratedCreditAccountMySqlTest`；原生资金HTTP未调用 |
| APP-41 | 完整Spring Boot、真实支付HTTP、SDK HMAC、真实Inbox/Outbox及账本、重启恢复 | PASS / MYSQL_CONFIRMED，19项 | 最新030731 full； `IntegratedPaymentApplicationMySqlTest`；仅supplier远端事实mock，未知ID明确失败；含新启动拒绝测试 |
| APP-PRICE | 欢迎50、歌词1/歌曲5、改写1、头像5，旧价格消费退原金额 | PASS，在当前full内 | 完整应用动态服务端价格和原debit断言；不改变历史USD订单快照 |
| APP-WRITER | 真实ACTIVE写词UNKNOWN不failover/不退款；明确未受理才fallback且一次扣费 | PASS / MYSQL_CONFIRMED | `activeCustomComposerNeverFailsOverOrRefundsUnknownButDefiniteNonAcceptanceFailsOverWithOneCharge`；模型远端仍为隔离mock |
| APP-DL-1 | 每账号同作品下载10分一次；并发/重投/多个已分享版本不二扣 | PASS / MYSQL_CONFIRMED | 完整应用 `activeSharedDownloadsChargeTenOnceForAllSharedVersionsOfOneWorkUnderConcurrentRetries` |
| APP-DL-2 | 下载跳过被冻结批次，负账户不授予下载权益；失败事务不留扣费/授权 | PASS / MYSQL_CONFIRMED | 完整应用下载两个对应测试；虚构音频资源，不是OSS/CDN/实际播放验收 |
| APP-DL-3 | 真实作品删除与共享购买两种先后顺序，work→share锁与已购访问保留 | PASS / MYSQL_CONFIRMED | `realWorkDeletionAndActiveSharedPurchaseFollowWorkThenShareInBothWinningOrders`，latch与实际DB阻塞断言 |
| FE-B8（历史检查点） | 原702项加主线download5项完整前端回归 | PASS / TEST_CONFIRMED，707/707，0跳过 | `.cache/payment-main-integration-20261112/frontend/b8-acceptance-20261112T145705521332Z/` |
| FE-BUILD | 完整输入对应vue-tsc与Vite正式构建 | PASS / TEST_CONFIRMED | `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T030939326320Z/{evidence.json,inputs.json,dist-manifest.json}`；359 inputs含全部build/public，前后不变；717零失败/跳过，test/typecheck/build exit0；dist114与旧717逐字节相同；Git export比对仍待实际制品门禁 |
| FE-MERGE | Stripe+下载decoder/文案、嵌入/旧订单保护、SSE、新welcome响应和writer字段保留 | CODE_CONFIRMED；717前端回归通过 | `.cache/payment-main-integration-20261112/frontend/preservation-b8-20261112T145619935680Z/evidence.json` 与 `.cache/payment-main-integration-20261112/frontend/preservation-de36-20261112T160116969342Z/evidence.json`；仅解决法律页测试冲突、全部新增断言保留 |
| LEGACY-100 | 正常已绑定身份登录不重复执行欢迎赠送hook | CODE_CONFIRMED；真实历史NOT_TESTED | Google/Logto hook源码只读；只有旧welcome流水却无身份时UNKNOWN，不造数据修复 |
| GAP-01 | 不同owner、不同nativeID同空索引区间RR并发 | INFERRED / NOT_TESTED | 不把同owner/同ID并发PASS外推；待真实屏障复现，未凭推演修改锁API |
| RESTORE-41 | canonical41 fresh app-r6备份→restore-r2，再真实恢复核验 | PASS / MYSQL_CONFIRMED，限定合成数据 | `.cache/payment-main-integration-20261112/backup-restore-20261112T155805381022Z/results.json`：75表、41迁移、17trigger、31owner守恒/结构比对，源未写；不代替生产恢复 |
| EXPORT-JAR | 最终新提交fresh Git export、最新649类逐字节比对、完整359输入/717-dist、仅canonical迁移、SHA | PENDING，由本轮新delivery实际判定 | 必须匹配030731 passing reactor的tested-production-classes.json；旧647类/旧JAR不能覆盖新Java，未执行或不匹配不通过 |
| JAR-RESTORE-START | 已打包制品在已证明合成restore-r2上的匿名会话落点/读取及关闭资金门禁 | PENDING，未执行不填PASS | 计划校验指定库creator/session真实存在、guest积分与流水匹配、25资金表前后SHA不变；不冒充Google/OIDC、非零历史余额HTTP或供应商/Live验收；实际jar-restore收据随delivery |
| PRESERVE-END | 原开发320/旧候选51保护文件SHA复核 | PASS，限2026-11-13 11:08:50 Asia/Shanghai时间点 | `.cache/payment-main-integration-20261112/preservation-final-20261113T030850937386Z.json`，两组均0变化；新delivery引用准确范围 |
| FE-DE36 | 额外de36前端、欢迎响应与法律页新增断言、writer字段 | PASS / TEST_CONFIRMED，717/717，0跳过 | `.cache/payment-main-integration-20261112/frontend/de36-acceptance-20261112T160232178871Z/`；typecheck/build通过，源码输入未变；保Stripe/下载/SSE |
| MAIN-DELTA | 兼容修复后的最新联合clean full、提交和main合并 | PENDING | 旧160457的1208仅旧检查点；实际新full和合并引用新delivery，不自引用尚未生成commit |

## 3. 兼容修复前160457 clean verify重点套件（历史检查点）

以下保留修复前1208项中的重点套件，绝不代表兼容修复后最新full；完整列表以原results.json为准。新focused的ACTIVE套件已19项，旧表18项只对应旧执行。不能把多层覆盖或重复mock相加成独立业务验收数量。

| Suite | 数量 | 失败 | 错误 | 未执行 | 实际状态 |
| --- | ---: | ---: | ---: | ---: | --- |
| agent.AgentStateEventStreamTest | 7 | 0 | 0 | 0 | PASS |
| AgentEventsHttpTest | 1 | 0 | 0 | 0 | PASS |
| avatar.AvatarBillingTest | 10 | 0 | 0 | 0 | PASS |
| avatar.AvatarGenerationFenceTest | 5 | 0 | 0 | 0 | PASS |
| avatar.KieAvatarImageProviderTest | 7 | 0 | 0 | 0 | PASS |
| config.LyricsProviderConfigurationTest | 7 | 0 | 0 | 0 | PASS |
| CostControlBatchTest | 10 | 0 | 0 | 0 | PASS |
| CostControlConcurrencyTest | 7 | 0 | 0 | 0 | PASS |
| CostControlEdgeCasesTest | 6 | 0 | 0 | 0 | PASS |
| CostControlMySqlConcurrencyTest | 6 | 0 | 0 | 0 | PASS |
| CreditLockOrderMySqlTest | 5 | 0 | 0 | 0 | PASS |
| CreditsIntegrationTest | 9 | 0 | 0 | 0 | PASS |
| download.DownloadControllersTest | 2 | 0 | 0 | 0 | PASS |
| download.DownloadEntitlementsMySqlTest | 3 | 0 | 0 | 0 | PASS |
| download.DownloadEntitlementsTest | 4 | 0 | 0 | 0 | PASS |
| FirstLoginCreditsIntegrationTest | 8 | 0 | 0 | 0 | PASS |
| LyricsCreditsIntegrationTest | 16 | 0 | 0 | 0 | PASS |
| OpenAiLyricsFlowTest | 6 | 0 | 0 | 0 | PASS |
| payment.AirwallexSandboxGatewayTest | 1 | 0 | 0 | 0 | PASS |
| payment.CreditAccountConcurrencyTest | 5 | 0 | 0 | 0 | PASS |
| payment.CreditAccountMySqlTest | 6 | 0 | 0 | 0 | PASS |
| payment.CreditEntitlementV40MySqlMigrationTest | 12 | 0 | 0 | 0 | PASS |
| payment.IntegratedCreditAccountMySqlTest | 48 | 0 | 0 | 0 | PASS |
| payment.IntegratedPaymentApplicationMySqlTest | 18 | 0 | 0 | 0 | PASS |
| payment.LogtoPaymentEligibilityIntegrationTest | 1 | 0 | 0 | 0 | PASS |
| payment.PaymentCatalogTest | 2 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxRecoveryTest | 10 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxRunnerTest | 2 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxScopeTest | 10 | 0 | 0 | 0 | PASS |
| payment.PaymentIntegrationTest | 7 | 0 | 0 | 0 | PASS |
| payment.PaymentRiskAdminTest | 3 | 0 | 0 | 0 | PASS |
| payment.PaymentRiskJournalTest | 1 | 0 | 0 | 0 | PASS |
| payment.PayPalCheckoutGatewayTest | 4 | 0 | 0 | 0 | PASS |
| payment.PayPalEntitlementAdapterTest | 8 | 0 | 0 | 0 | PASS |
| payment.PayPalFlowTest | 5 | 0 | 0 | 0 | PASS |
| payment.PayPalNewPurchaseGateTest | 12 | 0 | 0 | 0 | PASS |
| payment.PayPalPaymentServiceTest | 2 | 0 | 0 | 0 | PASS |
| payment.PayPalPublicLiveTest | 7 | 0 | 0 | 0 | PASS |
| payment.PayPalTransportConcurrencyTest | 4 | 0 | 0 | 0 | PASS |
| payment.StripeCheckoutGatewayTest | 10 | 0 | 0 | 0 | PASS |
| payment.StripeEndiveEnvelopeTest | 14 | 0 | 0 | 0 | PASS |
| payment.StripeInboxProcessingTest | 11 | 0 | 0 | 0 | PASS |
| payment.StripePaymentServiceTest | 18 | 0 | 0 | 0 | PASS |
| payment.StripePrincipalProofTest | 17 | 0 | 0 | 0 | PASS |
| payment.StripeRefundReturnProofTest | 13 | 0 | 0 | 0 | PASS |
| QuickCreationIntegrationTest | 13 | 0 | 0 | 0 | PASS |
| QuickCreationMySqlIntegrationTest | 13 | 0 | 0 | 0 | PASS |
| RewriteCreditsIntegrationTest | 12 | 0 | 0 | 0 | PASS |
| UsageBudgetIntegrationTest | 18 | 0 | 0 | 0 | PASS |

## 4. 8项外部隔离资源阻断

| Suite | skipped | 当前状态 | 解除条件 |
| --- | ---: | --- | --- |
| RedisAgentAdmissionIntegrationTest | 3 | BLOCKED | 独立Redis身份、权限及隔离网络经证明后实际执行 |
| AgentSemanticQdrantIntegrationTest | 1 | BLOCKED | 独立Qdrant资源/集合和权限后实际执行 |
| RabbitApiRoleIntegrationTest | 1 | BLOCKED | 独立Rabbit资源和角色配置后实际执行 |
| RabbitRoleIntegrationTest | 1 | BLOCKED | 同上 |
| RabbitTaskIntegrationTest | 2 | BLOCKED | 独立队列、持久化/重复处理及故障验证 |

未删除测试、改条件或将skipped填为PASS。原52项纯计算模型及旧49项静态门禁有历史PASS，但不替代本次真实MySQL、canonical41或供应商验收；V41静态javac PASS限定见 `.cache/payment-main-integration-20261112/static-v41-20261112T150514041444Z/results.json`。

## 5. 失败、历史与未决资金

| 项目 | 真实状态 | 必须保留的限定 |
| --- | --- | --- |
| 未clean的旧target残留payment40 | FAIL，1095 total/0failure/394error/8skip | `.cache/payment-main-integration-20261112/maven-full-20261112T152158595213Z/`，日志重复version40；394为上下文错误，不能说394个业务缺陷都修好了 |
| 新门禁164749 clean verify | FAIL，1247 total/1failure/0error/8skip | `.cache/payment-main-integration-20261112/maven-full-20261112T164749261443Z/`；新增ACTIVE19只匹配旧后置门禁原文，新预迁移gate更早正确拒启；仅补第二个完整准确原因，Ready及25表SHA等资金断言保留，原18项未改 |
| 165559新r11运行中断 | ABORTED，不计PASS | `.cache/payment-main-integration-20261112/maven-full-20261112T165559381498Z/interruption.json`；最后日志2026-11-13 01:02:20 Asia/Shanghai，无最终results；日志及库保留，随后当前r12实际重新验收，旧中断不被覆盖 |
| 修复前160457 clean verify | 历史PASS command，1200实际PASS/8BLOCKED | 只覆盖兼容修复前代码；旧失败及153735检查点保留，未删失败断言 |
| 兼容修复后最新r12 clean full | PASS命令；1239实际PASS/8BLOCKED | `.cache/payment-main-integration-20261112/maven-full-20261113T030731559249Z/`：1247 total/0failure/0error/8skip；新策略mock10、core40应用5及ACTIVE19均在该轮执行；不以旧1208冒充 |
| 旧payment40 backup/restore receipt | 只作历史证据 | `.cache/payment-main-integration-20261112/backup-restore-20261112T143325796884Z/` 不证明canonical41恢复 |
| 用户指定两笔历史订单 | 未找到；实际删除0 | `.cache/payment-production-inventory-20261112/session-inventory-20261112T130447991683Z.json`；不按金额/时间猜删，不因404/缺失断言未付款或结案 |
| 孤立旧welcome流水 | UNKNOWN | 不能靠改50规则扣回/补发或伪造身份完成迁移 |
| 真实历史映射与切换 | BLOCKED | 人工例外核查、dry-run、停全部旧writer、备份/恢复和审批未完成；合成测试数据不是生产迁移 |
| Stripe真实退款失败返款 | BLOCKED | 原返款pending，available_on记录2026-11-19 08:00 Asia/Shanghai；须实际权威available证据，不改时间或造补偿 |
| PayPal REVIEW权威解除/最终资金覆盖 | BLOCKED | 管理员risk RELEASE不等于权益解除/返款；需实际本金证据与流程 |
| 当前整合版Stripe/PayPal Sandbox | NOT_TESTED | 本轮全部远端事实隔离mock；旧候选Sandbox和SDK合成签名不代表当前版本资金生命周期通过 |
| Live/生产迁移/真实积分修改 | BLOCKED | 独立具体审批、前置门禁满足后才能另行实施 |

## 6. 判定规则与待收尾证据

PASS要求真实执行与适用范围对应；CODE_CONFIRMED只表示源码确认；MYSQL_CONFIRMED不等于SANDBOX/PRODUCTION_CONFIRMED；INFERRED/UNKNOWN不提升等级。pending未知资金不得自动永久调整。HTTP200、UP、任务提交、文件存在或构建成功不代表资金到账/业务闭环。

最新030731/r12完整clean verify命令PASS：1247 total、1239实际通过、0failure、0error、8外部BLOCKED；最新complete前端359 inputs、717测试及构建PASS，canonical41合成恢复保留原范围。030850保护复核320/51均未变。最终提交/main合并、fresh Git export JAR/恢复库打包启动仍只按本轮新delivery实际收据判定，必须匹配最新649类passing reactor清单及完整359前端输入集合；旧1208/647类或旧制品不能覆盖最新Java。本文件不预写未执行项PASS。生产发布/收款仍BLOCKED，未推送、部署或执行生产数据库写入。
