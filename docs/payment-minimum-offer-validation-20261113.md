# 四渠道私有最低报价验收记录

记录日期：2026-11-13。本文记录候选代码、隔离验收与尚未满足的正式收款条件。`PASS` 仅属于对应证据的执行范围；没有四笔真实资金付款及唯一到账证据，不能将四渠道整体标为正式收款 PASS。

本次工作位于独立 `payment-v45-release-20261113` 工作树。原日常工作区的329项既有改动不属于本轮清理、覆盖或回滚范围。代码与测试的实施均在该独立工作树进行；生产仅执行了经授权的只读检查，未将隔离数据写入生产。

## 1. 实现及固定报价

正常100/300/700积分套餐、USD9.90/25.90/49.90及微信CNY68.80/178.80/338.80保持原有报价。新增的临时验收报价是默认关闭的独立例外：

| 渠道 | 临时报价 | 积分 | 新订单上限 | 说明 |
| --- | --- | --- | --- | --- |
| Stripe国际信用卡 | USD0.50 | 1 | 1 | 仍需真实供应商成功付款、签名回调及到账 |
| Apple Pay | USD0.50 | 1 | 1 | Stripe card家族；必须实际回读Apple Pay钱包类型，普通卡不能替代 |
| Stripe国际微信支付 | CNY3.00 | 1 | 1 | 本轮Sandbox最低报价检查通过创建未确认对象，不代表Live支付成功 |
| PayPal | USD0.01 | 1 | 1 | 此价格仍待真实Live订单受理验证；OAuth成功不能证明一美分创单受理 |

四项报价合计USD1.01+CNY3.00。任何不同报价、额外付款次数或更换验收身份/nonce都必须重新绑定明确授权，不能用旧资金授权覆盖。不得自动发起真实付款。

代码位置：

| 文件/模块 | 本轮职责 |
| --- | --- |
| `Qingfeng_AI_Music/qf-server/src/main/java/com/qingfeng/music/server/payment/PaymentAcceptanceOffers.java` | 私有受众、金额、到期、SKU、限次策略；不读取当前套餐重算历史订单 |
| 同目录 `StripePaymentService.java`、`StripePaymentMethods.java` | 既有认证目录/创建路径、持久化渠道解析、creator行锁内限次 |
| 同目录 `PayPalPaymentService.java` | 私有目录/创建路径、原支付快照；仅对真实ACTIVE切换边界前已隔离订单排除待核对计数 |
| `Qingfeng_AI_Music_VUE/src/modules/identity/pages/RechargePage.vue`及`model/stripe-payment-methods.ts` | 私有1积分项、价格、到期、限次提示及提交前校验；客户端只传SKU/方法 |
| `isolation/stripe-live-release-20261113/guarded_release.py` | 资金授权、运行配置、真实付款证明之间的owner/nonce/expiry/报价绑定 |

## 2. 精确受众、单次和到期约束

**CODE_CONFIRMED：** 配置前缀为 `qingfeng.payment.acceptance.`，包括 `enabled`（默认false）、唯一 `owner-id` UUID、8位小写十六进制 `nonce`、末尾Z的UTC `expires-at`、`wechat-cny`（默认3.00且至少3.00）。昵称不能替代认证主体UUID。

只沿用现有认证catalog API，不新增公开私有报价接口。须同时满足新购公共开关关闭、认证主体精确匹配、同主体canary授权、未到期且该精确SKU未使用，才显示临时报价。该owner在私有验收模式不能通过普通9.90等SKU发起超出本轮报价的购买；过期/已用返回空项，不回落至普通可购套餐。其他用户及匿名用户不会收到私有SKU。

SKU为 `accept-<8hex>-<channel>-<USD|CNY>`，长度不超过36字符。创建在既有creator行锁内再次检查到期，并统计 `creator_id + 精确package_id + provider` 的全部订单状态。FAILED、CANCELLED、CREATING、结果未知或PAID均占用唯一槽；不会因失败通知或未知结果重新建立远程支付。查询或恢复已有订单继续使用原订单ID。

**CODE_CONFIRMED：** 公开open阶段必须关闭临时例外，清空owner/nonce/expiry并清空canary名单；后台创建接口亦拒绝在公共新购开放时使用临时SKU。前端显示限制不能替代后端限制。

## 3. 晚到付款、退款及历史订单兼容

**CODE_CONFIRMED：** 到期/关闭只影响新购。历史订单查询、Webhook、恢复、积分发放和退款依据持久化金额、币种、积分及原渠道快照，不重新依赖临时报价的开关、nonce或当前价格。Apple验收SKU固定映射card家族；微信固定映射wechat_pay，不能错路由PayPal。

PayPal历史待核对订单仅在 `legacyOrdersReadOnly=true` 且数据库确有唯一ACTIVE切换记录和非空activated_at时，按切换时间隔离；PENDING/CREATION_UNKNOWN原状态、金额和远端身份保持不变。未启用隔离仍统计全部待核对订单；缺ACTIVE时拒绝新购；切换边界后的未知订单继续阻止新单。不能将未知订单当作未付款或擅自删除。

对应新增测试包括未登录/非owner、非法配置、不同nonce/方法/币种、普通SKU拒绝、到期边界、全状态永久占槽、Stripe/PayPal双线程竞争、晚到结算、原金额退款。完整应用MySQL新增 `IntegratedPaymentApplicationMySqlTest` 的Order40：四渠道各4线程竞争、唯一1积分订单、重复回调、移除整个例外并重启后PayPal晚到结算、USD0.50/CNY3.00退款及两笔旧PENDING/CREATION_UNKNOWN快照不变。本轮full已实际执行并通过，属于隔离MySQL/合成供应商事实验收。

## 4. 实际执行证据

以下路径中“根”指 `C:/Users/yth/Documents/ChatGPT/人人是歌手`；测试工作树内的证据另行注明。未加载生产密钥，不把合成供应商事实称为真实资金验收。

| 项目 | 状态与实际范围 | 证据位置及SHA-256 |
| --- | --- | --- |
| 前端完整测试/类型检查/构建 | **PASS / TEST_CONFIRMED**：778/778，0失败、0待定；397个输入前后不变；npm build含类型检查；未访问数据库/供应商 | 根 `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T122513381561Z/evidence.json`；`f482c3075a9e166d67dd9f7aee7a904ab09c55b9cad5e1fc6dbfe1b048de2999` |
| 真实隔离MySQL积分专项 | **PASS / MYSQL_CONFIRMED**：`IntegratedCreditAccountMySqlTest` 55项，0失败/错误/跳过；合成资金事实，不是Sandbox/Live付款 | 根 `.cache/payment-main-integration-20261112/maven-targeted-20261113T124100847678Z/results.json`；`04d194eb4f369c297e7d5e1f10c3f36da743c9ebe2887189441345fe9f1fb270` |
| 发布门禁离线契约 | **PASS / TEST_CONFIRMED**：67项；原52项及后续9项全部保留，追加6项临时报价边界；不访问网络/MySQL/systemd | 工作树 `.cache/payment-v45-release-20261113/operator-contract-tests-acceptance-20261113.json`；`822a96ff828a4ec1af0741e65d475013633a84547f5d8cc508446b27fd99b3b9` |
| 最终后端完整verify | **PASS / TEST_CONFIRMED / MYSQL_CONFIRMED**：1509项，1501项执行通过，0失败/错误，8项原有Redis/Rabbit/Qdrant外部条件测试跳过；资金/MySQL无跳过；生产源码输入前后不变。完整应用MySQL30项、积分MySQL55项均通过 | 根 `.cache/payment-main-integration-20261112/maven-full-20261113T124247576101Z/results.json`；`d7313579c849acbc40944ec795ebff7d5740d1c6389cac6cd010c2867e72f3ec` |
| 隔离非空数据备份恢复 | **PASS / MYSQL_CONFIRMED**：application r43恢复至独立r8，核对数据、账本和结构；不代表生产备份已恢复 | 根 `.cache/payment-main-integration-20261112/backup-restore-20261113T124715884900Z/results.json`；`d9407db84d09507179dda712cf90928efb9a95e1bb0c962163f2c4d6d875da03` |
| 四渠道逐笔真实付款/签名/唯一到账 | **NOT TESTED / NOT_VERIFIED**：本轮没有四笔完整Live付款证据 | 不用API认证、Checkout创建、健康UP或历史报告替代 |

门禁脚本SHA为 `9aa7543b6749b898af0b8a62b015fba2562a08819f031985ac143e710f87f537`，测试文件SHA为 `01790f40f7989ded4d8adca91e00b890e617877fe3b21072fac3301f84db1f96`。其PASS只证明离线拒绝/接受契约，不证明生产条件或真实付款。

## 5. 外部与生产条件

以下为本轮主执行代理已核对的结果；其状态不相互替代：

| 检查 | 已证实事实 | 仍未证明 |
| --- | --- | --- |
| PayPal Live认证 | OAuth HTTP200 | 低价订单创建、买家付款、Capture、签名回调、唯一到账及资金事件闭环 |
| PayPal交易报告权限 | HTTP403 | 自动报表对账权限与可用性；不得把403当成“没有交易” |
| PayPal Sandbox | 现有凭据HTTP401 | 新版Sandbox付款验收仍BLOCKED，不能用Live资金测试替代 |
| PayPal Webhook订阅 | 当前仅capture completed | 须在批准部署后的明确变更中补齐退款/争议所需订阅，并验证相应签名及处理路径 |
| 生产当前运行版本 | 主执行代理观察source短ID `880e0d5`、JAR SHA短值 `250b2ecc…`；本轮尚未替换 | 不能把先前b14基线或本地新代码当作现行运行版本；正式执行前重新pin完整commit/JAR/迁移/配置 |
| 临时验收账号 | 用户截图显示Google昵称为th Y；按名称与邮箱摘要双精确匹配，生产只读确认唯一creator及Google绑定 | **VERIFIED**：内部UUID仅存忽略的private收据；未根据旧拼写Yth猜测授权。实际canary配置仍待受控制品与生产前置验证 |

## 6. 下一步准入顺序

1. 已完成最终full并核查MySQL Order40、生产源码输入未变、失败/跳过范围；发布前仍须保证所构建源码与该收据一致。
2. 已按用户截图只读核实唯一认证UUID；实际启用仍须绑定单一owner、nonce、UTC期限、四项报价和各1次的明确付款授权。
3. 基于实际最新主线和生产版本形成同源最终制品及SHA，重新验证生产结构/迁移、备份恢复、停旧writer和兼容恢复条件。V45发布步骤沿用受控runbook，当前测试通过不授权绕过这些条件。
4. 按批准范围部署后先保持公共新购关闭；确认实际密钥加载、回调地址/签名配对和所需事件订阅。禁止把私有配置带入公开open。
5. 用户本人完成信用卡、真实Apple Pay设备、微信及PayPal四笔已授权付款；每项独立回读供应商成功状态、钱包类型/币种/金额、订单归属、签名回调及唯一账本到账。
6. 任一渠道缺失证据就保留其明确阻断，不填四渠道整体PASS。四项真实验收齐全后，再按审核后的公开配置关闭并清空临时例外、保留历史订单处理能力，并执行正式新购开放门禁。

**本记录结论：** 代码与部分隔离验收已有真实证据；最终full和隔离MySQL已通过；生产同源发布与四渠道真实资金验收尚未闭环。目前不能依据本文宣称“四渠道已正常正式收款”。

## 7. PayPal本轮恢复安全边界

Reporting成功资金事实的获取在数据库事务外；结算、退款、争议、解冻遵循owner优先锁序。新增同版本LOST(null)补实损只允许精确PAYPAL商户/环境/订单/原Capture和已存原生资金证明匹配，通用争议状态机未放宽。自动对账在GET前和owner事务内核对完整风险状态，不能覆盖人工HOLD；GET期间新增人工决定同样阻断自动解除。真实InnoDB已覆盖重复/并发/身份冲突、退款重叠和人工HOLD竞争。

自动资金证明目前只支持USD/GBP同币种、单一争议、成功T1201本金实扣，以及最终胜诉后精确全额T1202/T1205原路返本。FX、部分返本、多争议、退款/拒付资金重叠等继续REVIEW/人工核查；真实Reporting403和Sandbox401未解除，不能宣称全自动资金闭环。

此前中止的全量、构建目录竞争失败和六项重复原生订单ID夹具错误的完整报告均保留。夹具仅改独立交易编号，没有删除用例、删除唯一约束、降低断言或清理失败证据。最终通过结果只指以上r43新队列的完整执行。

## 8. 正式制品演练边界

首个实际fat-JAR的core44校验、无授权拒绝、45迁移与幂等重放已实际成功；carry-apply因使用迁移专库而被HISTORY_APPLY_NOT_ISOLATED正确拒绝。只修改隔离演练脚本，改用既有严格白名单的历史切换专库（--history-revision），不放宽Java库白名单、不移动生产数据。旧失败收据保留。正式执行必须以最终提交、最终JAR及完整plan/validate/reject/migrate/replay/apply/activate/verify的同制品收据为准。

## 9. 最后发布工具及Agent补充验证

完整应用MySQL额外31项专项实际通过（maven-targeted-20261113T131504408882Z/results.json），新增Order41从真实Boot注入Agent付费入口：负余额与全部冻结时，完整金融表、admission、DEBIT和分摊精确快照均不变；仅A冻结时仅从B扣费。先确认免费额度已耗尽，避免误测免费分支。原Order25—28及全部原断言保留。该31项是补充专项，不冒充重新执行了1509项全量，也不替代供应商资金验收；生产Java代码未变。

只读确认生产配置存在三组完全同值的重复键，媒体cron末尾有已存在的stderr重定向。发布工具仅接受trim后完全同值的重复项，异值仍拒绝；只接受经核验的完整cron命令，不接受任意命令。原67项保留并新增4项边界，71/71通过，根忽略证据 `.cache/payment-final-go-live-20261113-new/operator-syntax-71-tests.json`。未修改生产原配置或cron。

最终受审脚本SHA `224550dd9b4780c3c5234da283ea5266907d0210cd3270d2c4aa4f8c1289d3b2`，测试SHA `220556f0b16dade4ce3780c40192077fc859a083bff4c9746933a04a6650e29c`。本报告前表67项为此前阶段记录；实际执行应使用最后71项验证的脚本及新制品绑定。
