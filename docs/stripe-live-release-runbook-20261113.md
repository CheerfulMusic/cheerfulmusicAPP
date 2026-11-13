# Stripe 信用卡与 Apple Pay 正式发布编排

2026-11-13。范围仅为 Stripe 信用卡、Apple Pay 的新充值。微信待批准，保持关闭；PayPal 新购暂缓，原查询、验签及持久入箱保留。保留所有旧订单、积分余额及原始流水，不再以人工积分来源追溯作为本次新渠道开发前置。

本文和 `isolation/stripe-live-release-20261113/guarded_release.py` 是待审查的执行工具，不是生产已完成的证据。当前已执行的仅有 **12 项离线配置负例/准入测试**，没有在生产执行本脚本、迁移、重启或资金操作。最终结果以主流程实际收据为准。

## 1. 明确的职责及执行顺序

| 阶段 | 工具/操作 | 必须先取得的真实证据 | 成功含义 |
| --- | --- | --- | --- |
| 代码与制品 | fetch、合入 main/dev/yth/本次分支、前端 `npm test` 与 build、后端 `mvn verify`、真实隔离 MySQL 回归、同源 JAR 构建 | 同一 sourceCommit/JAR 的日志、源码与前端字节、迁移与 release-contract 证据 | 本地验收通过，不表示已上线 |
| inspect | guarded_release.py inspect | 根拥有的精确审批计划、JAR/contract/policy/guard SHA | 当前版本匹配、候选通过正式 release guard；不迁移/部署 |
| backup | guarded_release.py backup | 完整旧 writer/媒体自愈/并发发布入口清单，确认维护窗口 | 停具体旧 writer 并确认 PID=0；生成服务器私有备份；应用保持停止 |
| 真实备份恢复 | guarded_release.py restore-check | 刚生成的 production backup SHA、批准的服务器本机恢复schema、数据保密条件 | 真正恢复同一SQL并逐表比较DDL/行数/按列HEX的完整行hash及credit journal，源库未变；不启动应用/外部服务 |
| 兼容恢复演练 | 主流程单独执行并留证 | 真实生产备份恢复收据，以及同候选隔离MySQL迁移/恢复启动收据 | 分别说明真实数据恢复与虚构隔离应用兼容；不能把隔离启动写成生产备份恢复应用已启动 |
| 显式迁移 | 独立 Flyway 执行器，完整 canonical V1—43 校验后仅升级44 | 备份恢复通过，旧 writer 已停，精确 JAR/SQL/hash | V44 为 PREPARING；迁移不激活权益写入；DDL 隐式提交 |
| 兼容开账与激活 | `CreditCarryForwardCommand plan/apply/activate/verify` | 冻结后的 source/plan SHA，四份已验证收据：writer、备份恢复、协议3恢复、旧订单隔离 | LEGACY_OPENING 保留真实余额；旧 DEBIT/REFUND 映射；ACTIVE/protocol3；不伪造历史充值归因 |
| install | guarded_release.py install | 同备份恢复、ACTIVE、最终构建与策略收据；所有旧 writer 仍停 | 新配置原子替换、新 current 原子切换、进程真实 JAR 一致、UP；新购仍关，**业务未验收** |
| canary | guarded_release.py canary | 运行密钥/账户/正式回调配置已核对，测试账户本人已准备好；实际报价不超过用户明确授权金额/次数 | 只允许最多3个认证 owner 进入新购；公开新购仍关；等用户本人真实付款 |
| 真实资金验收 | 用户本人信用卡与 Apple Pay 付款；后台只读对账 | 同账户Live、签名Webhook、原始支付/金额/USD/用户/订单完整绑定 | 两种渠道实际付款、积分只发一次、订单和账本一致；不能用HTTP200或UP代替 |
| open | guarded_release.py open | 同 JAR 的真实资金/回调/到账验收收据 | 才对普通用户开放；移除 canary 例外，微信仍关 |
| recover | guarded_release.py recover | 已验收的同协议候选、原ACTIVATION/恢复证据 | 保留新账本和新资金事实，清空canary并关闭新购，以兼容新JAR恢复；不回旧0530，不还原旧快照 |

数据库迁移不在 Python 发布器中自动执行。不得把 Flyway 的 validate/target 属性改为 repair、baseline 或忽略失败；不得复制或改名历史 SQL。`CreditCarryForwardCommand` 的控制输入和审批收据继续按该命令的精确契约准备。

## 2. 执行器的具体保护

- 只在 Linux root 环境、根拥有且父目录不可被普通用户改写的审批文件上执行。命令行必须给出审批计划的独立 SHA；所有变更阶段还必须显式给出 `--execute-approved`。
- 每次操作持有 `/run/lock/qingfeng-release.lock` 与现有 `/run/lock/qingfeng-music-backup.lock`。两次调用之间不会持续持锁，因此每次重查 current、精确旧/新JAR、旧 writer 停止状态；中间有其他发布即拒绝继续。
- `writerInventoryVerified=true` 不能凭猜测设置。systemd unit 清单只代表能由本工具停止的对象；cron、自愈脚本、第二实例、人工本地生产连接等必须由主流程调查、停用或断开并另行留证。Java 切换器在 apply/activate/verify 实际检查其他同库连接，不接受“服务停了”替代连接排空。四proof中的writers还必须是5分钟内root全局PROCESS观察，含 `globalProcesslistVerified=true`, `observerHasProcessPrivilege=true`, `otherSchemaConnections=0`, `otherApplicationAccountConnections=0`；三个Java步骤前分别重新观察并更新SHA。应用账号只见自身时不能替代此证据。
- 备份留在生产主机私有目录 `/var/backups/qingfeng-music/stripe-live-<releaseId>/`，包括精确旧release、`/etc/qingfeng-music`、systemd有效单元、完整SQL（含triggers/routines/events）、`/var/lib/qingfeng-music`。SQL及配置不入Git、不入普通审查包、不打印。备份不是恢复验收。
- MySQL备份/恢复检查使用本机root socket，不传密码命令行。restore-check只允许新建 `qf_stripe_restore_...` schema，无新账号授权、无drop/truncate/重用、失败schema保留；全量真实数据不外传，也不启动恢复应用。源存在view/trigger/routine/event时拒绝自动恢复，防止跨库引用或定时事件副作用，需另行审查。仅有恢复SQL导入成功不算通过，还要逐表DDL/完整行hash/源不变核对。
- 私有配置通过新文件+fsync+原子replace切换，文件root:qfmusic、0640。只允许本次Stripe、writer fence、旧订单只读边界、PayPal新购暂停、明确UTC JDBC及Flyway关闭字段变化；其他登录、创作、媒体和密钥字段必须保留。
- 信用卡/ApplePay使用USD原价；微信开关强制false；Stripe environment/account、真实Live key格式、端点signing secret、fence数据库名称/3306均校验。密钥值绝不打印。格式校验仍不证明账户配对，须另做实际只读账户核验及真实签名回调。
- JDBC必须实际设置 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`；应用 `FinancialClockStartupGuard` 继续校验会话、JDBC Instant与数据库epoch。`serverTimezone=UTC` 名称不构成验收。
- 仍调用正式 `verify-release.py`，并核对安装的guard/helper与已审源码SHA。审批策略由主流程在真正通过后独立提供；脚本不生成自批准策略，也没有legacyRollback绕过。
- start后检查MainPID打开的JAR原始字节，不能把current文件一致称为进程版本已更新。UP仅表示启动阶段通过；资金收款放行另有业务验收收据。

## 3. 非秘密审批计划的结构

不要直接把下列说明当可执行批准。所有 SHA、路径、提交及PASS字段都必须从实际执行证据中填写；占位符会被拒绝。

```json
{
  "version": 1,
  "scope": "stripe-card-apple-pay",
  "releaseId": "<最终发布目录名称>",
  "database": {"uuid":"f71f7437-d6a8-11f1-baa2-00163e1ce234","schema":"qingfeng_music","port":3306},
  "expected": {"directory":"/opt/qingfeng-music/releases/<核验旧目录>","sourceCommit":"<40hex>","jarSha256":"<64hex>"},
  "candidate": {"directory":"/opt/qingfeng-music/releases/<releaseId>","sourceCommit":"<40hex>","jarSha256":"<64hex>","contractSha256":"<64hex>"},
  "writerInventoryVerified": false,
  "writerUnits": ["qingfeng-music.service"],
  "restoreDatabase":"qf_stripe_restore_<唯一小写字母数字下划线后缀>",
  "guardSha256":"<64hex>",
  "gateSha256":"<64hex>",
  "approvedPolicy":"/etc/qingfeng-music/<独立批准策略>.json",
  "approvedPolicySha256":"<64hex>",
  "closedProperties":"/etc/qingfeng-music/<新购关闭私有配置>.properties",
  "canaryProperties":"/etc/qingfeng-music/<仅内部用户私有配置>.properties",
  "openProperties":"/etc/qingfeng-music/<通过验收后开放配置>.properties",
  "receipts": {
    "build":{"path":"<服务器根拥有收据>","sha256":"<64hex>"},
    "restore":{"path":"<服务器根拥有收据>","sha256":"<64hex>"},
    "cutover":{"path":"<Java verify原始收据>","sha256":"<64hex>"},
    "canaryFunding":{"path":"<实际报价与用户资金授权收据>","sha256":"<64hex>"},
    "business":{"path":"<真实资金验收收据>","sha256":"<64hex>"}
  }
}
```

`build` 收据必须 status=PASS、sourceCommit/jarSha256匹配，以下字段分别为实际PASS：`fetchAll`, `mergedMainAndDev`, `frontendTest`, `frontendBuild`, `backendVerify`, `realMySql`, `negativeAndFrozenAgentRegression`。缺失或skip不填PASS。

`restore` 是主流程的组合收据，必须 status=PASS、`backupDatabaseSha256`匹配刚创建SQL，`candidateJarSha256`匹配候选，`sourceSha256`匹配Java cutover source；`actualRestoreExecuted`, `dataIntegrityVerified`, `migration44Verified`, `compatibleRecoveryVerified`均为布尔true。它必须分别pin实际 `actual-data-restore-receipt.json`、同候选隔离MySQL迁移44及兼容JAR恢复启动原始收据，并写明后两者实际环境。不得从只有backup-created或data-restore的结果自动填出全部true，不得声称生产数据恢复应用已启动。生产备份包含真实敏感数据，只能在已确认的服务器本机受控恢复范围使用；不得导入普通开发库或启动会联系生产供应商的恢复应用。

`cutover` 使用 Java `verify` 的实际原始输出：status=PASS，state=ACTIVE，protocol=3，数据库物理UUID/schema/port、候选JAR一致；包含四份证明SHA：`oldWriterStoppedEvidenceSha256`, `backupRestoreEvidenceSha256`, `rollbackCompatibilityEvidenceSha256`, `legacyOrderIsolationEvidenceSha256`。它不是Python从标志自造的结果。

`business` 必须 status=PASS、jarSha256匹配、accountId为已确认正式账户、environment=live；`signedWebhook`, `creditCardPaid`, `applePayDevicePaid`, `creditedExactlyOnce`, `ownershipAndCurrency`均为有原始证据的PASS。只保存脱敏订单别名及证据定位；不能在未付款、仅Checkout创建或仅签名测试时填写。

`canaryFunding` 必须绑定候选JAR、USD、`selectedPackageAmountMinor`和`approvedMaximumMinor`（整数美分）、`approvedTransactionLimit=2`及具体用户授权证据。当前原套餐实际金额只允许990/2590/4990美分，不制造临时报价。**用户当前只授权两笔各USD0.01，不能覆盖当前任一套餐，所以canary真实资金验收仍BLOCKED。** 不得将授权改写为0.50/9.90，不启用未批准的canary报价。可以继续完成关闭全部Stripe新购的安全发布准备；open仍必须等真实资金证据。

私有配置必须完整保留现有字段，按阶段配置 `qingfeng.stripe.accepting-new-orders` 与 `qingfeng.stripe.canary-owner-ids`：install/recover为空名单+false；canary为最多3个准确UUID+false；open为空名单+true。名单来自认证账户，不得从请求payload冒充。`qingfeng.payment.legacy-orders-read-only=true`、`qingfeng.paypal.accepting-new-orders=false`；不关闭PayPal整体验签服务。

## 4. 命令与故障处理

命令通过明确参数运行，以下以inspect为例，不包含任何凭据：

```text
python3 guarded_release.py inspect --plan /etc/qingfeng-music/<已批准计划>.json --plan-sha256 <独立核对64hex>
```

生产变更必须将operation换为具体阶段并显式添加 `--execute-approved`。每次阶段完成保存stdout的有限结构化收据；错误只输出固定BLOCKED代码，不输出底层命令、SQL、配置或驱动异常。

backup开始会停服务，恢复演练或后续门禁失败时保持停止。执行前必须安排维护窗口和明确值守；不能为了恢复可用性越过V44已安装事实而随意启动旧writer。**V44之前**若需要恢复旧应用，主流程必须重新确认没有安装金融schema、旧配置/策略/制品仍匹配，并按独立批准的core43恢复步骤执行。本工具没有自动旧应用回滚。**V44之后**只允许已验证的协议3兼容恢复；新资金发生后不得还原旧数据库覆盖新事实。

canary/open是启动配置切换，会短暂停止并启动同一个应用；需要核对后台任务租约及恢复，不能宣传零停机。只有主服务自动重启，其他自愈/消费者unit保持停止直至逐项确认兼容后恢复，不能一键启动整个旧unit清单。

## 5. 本地实际验证

```text
python -B isolation/stripe-live-release-20261113/test_guarded_release.py
```

实际结果：12/12通过（2026-11-13）；覆盖不可修改无关登录/媒体/PayPal整体开关、不可切换数据库/密码、UTC标签不等于实际会话、不得借UTC更改其他JDBC能力、Sandbox/占位符不可混入Live、未获批微信/关闭fence/自动Flyway拒绝、恢复时清空canary、canary UUID唯一及最多3人、全开放时去除名单、0.01授权不得扩成0.50或正常套餐、实际报价/次数/候选身份须匹配授权。仅测试纯输入契约，**不证明Linux系统调用、真实备份/恢复、生产发布或真实资金验收通过**。
