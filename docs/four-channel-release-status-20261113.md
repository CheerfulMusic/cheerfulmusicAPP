# 四渠道正式收款发布记录（2026-11-13）

## 发布结论

`DEPLOYMENT = BLOCKED`。本轮已获四渠道上线授权，但迁移及资金验收前置条件尚未满足；没有切换生产版本、重启服务、修改生产配置、写入生产数据库或执行真实扣款。不能把微信商户审批通过写成四渠道业务上线完成。

临时分支：`codex/four-channel-live-20261113`，起点 `e524d4b1fd954a2fc6a90167183b9566d825cbeb`。已执行 `git fetch --all`，该起点已包含当时的 `origin/main` 和 `origin/dev/yth`。不切换原 `codex/google-login` 工作区；本轮开始保护的 776 个文件最终逐文件 SHA-256 复核全部未变。未覆盖并行开发、历史订单或新增发现页字段。

## 本轮真实检查

| 检查 | 实际结果 | 状态与证据 |
| --- | --- | --- |
| Stripe Live 账户 | `charges_enabled=true`、`payouts_enabled=true`，卡能力 active；Apple Pay 与 WeChat Pay 配置 available/on | VERIFIED，只读账户能力；根缓存 `.cache/four-channel-live-20261113/stripe-readiness-095029248850.json`，不是付款证据 |
| Live Stripe 回调 | `https://cheerfulmusic.com/api/public/stripe/webhook` 存在且 enabled | VERIFIED 端点元数据；服务器加载及签名配对仍 NOT_TESTED |
| PayPal Sandbox 凭据 | 明确 Sandbox 来源文件中的唯一凭据对认证返回 HTTP 401；无令牌，未继续查询回调 | BLOCKED；`.cache/four-channel-live-20261113/paypal-sandbox-metadata-20261113T100639507806Z.json`。不说明既有 Live PayPal 已失效 |
| 生产运行版本 | 18:12（北京时间）仍为 `0530c444a38db2363e0a6296c52a416b09a14d5f`，服务 active；JAR SHA-256 `e7193ed14ceb273b3eca1e78798bf4995c11bbabcbb494bff44e9baf105314ce` | VERIFIED；`.cache/four-channel-live-20261113/production-final-readonly.json` |
| 当前线上 Stripe 代码 | 运行 JAR 未包含 `StripePaymentService`；已核对的配置和进程变量未发现 Stripe 密钥加载 | VERIFIED 于所查 JAR/配置来源；不把局部配置检查称为完整 Spring 有效绑定 |
| 生产迁移 | 存在成功的 `V44__discover_song_publication.sql`，checksum `-1983576296` | VERIFIED，只读事务 SELECT，已回滚；保留原记录 |
| 候选迁移 | 未上线财务 `V44__credit_entitlement_integration.sql`，checksum `789542731` | CODE_CONFIRMED，与生产同编号不同含义，不能部署 |
| 原工作区 | 776 个受保护文件 hash 未变 | PASS；`.cache/four-channel-live-20261113/protection-final.json` |

## 实际改动与本地验证

1. PayPal 增加独立 `qingfeng.paypal.canary-owner-ids`，默认空；新购总开关关闭时最多允许三个明确用户 UUID。重复、格式错误、空项和超量名单整体拒绝。目录和创建订单使用认证身份；客户端传入 owner 不会替换认证用户。未修改原消费、充值积分、退款或争议算法。
2. 保留既有订单查询、Capture、Webhook 与新购开关分离；原有测试断言保留。新增创建订单断言核对原 USD 9.90/100 积分快照，不创建临时低价套餐。
3. 发布脚本保留原 v1 Stripe 发布契约，新增显式 v2 四渠道阶段清单。关闭阶段不开放新购；canary 只开放明确名单和明确渠道；公开开放必须有四个渠道各自真实付款、供应商回查、签名回调、订单归属和唯一账本证据。信用卡证据不能复用为 Apple Pay 或微信证据。
4. 更新 PayPal 正式配置示例的陈旧说明，新增默认关闭的 Stripe 钱包配置示例；没有把密钥写入模板。微信使用已批准 CNY 68.80 / 178.80 / 338.80，保留 USD 9.90 / 25.90 / 49.90。现有前后端已有微信 Hosted Checkout，不新建第二套支付实现。

| 实际命令 | 结果 | 证明范围 |
| --- | --- | --- |
| `python isolation/payment-main-integration-20261112/run_tests.py targeted --workspace . --migration-revision r30 --credits-revision r30 --application-revision r30 --legacy40-revision r30 --core40-revision r30 --compatibility-port 57336 --tests PayPalNewPurchaseGateTest,PayPalCanaryControllerTest,PayPalPublicLiveTest,PayPalFlowTest,PayPalPaymentServiceTest,PayPalEntitlementAdapterTest,PayPalCaptureRecoveryServiceTest,PayPalCaptureRecoveryProofTest,StripeWalletPaymentServiceTest` | PASS：71 tests、0 failures、0 errors、0 skipped；Maven verify 成功 | 当前候选针对性回归，包含 HTTP 身份解析和模拟供应商/H2 测试；不是 MySQL 或 Sandbox 资金验收。收据：`.cache/payment-main-integration-20261112/maven-targeted-20261113T101009681378Z/results.json` |
| `python -B isolation/stripe-live-release-20261113/test_guarded_release.py` | PASS：33/33，原断言保留 | 离线发布契约；没有执行生产发布、备份恢复、迁移或真实付款。子代理与主代理分别实际执行 |
| `git diff --check` | PASS | 本轮文件差异检查 |

本轮没有重新执行全量前端测试、全量 Maven 测试、真实 MySQL 新迁移、备份恢复或完整供应商验收。此前其他候选的通过记录不能直接作为本分支发布 PASS。针对性 verify 产生的 JAR 不认定为经过完整制品门禁的正式发布包。

## 仍阻断上线的条件

### 1. 生产 V44 与候选 V44 不同

生产发现页 V44 原文件仍 UNKNOWN；精确搜索本地 Git、相关工作树、缓存、会话证据、服务器临时目录及可用历史 JAR 未找到可信原件。当前结构快照不能证明原 SQL 或数据修改。

最小处理是找回真实发现页 V44 原件并校验其 checksum，保留其执行历史和字段；然后给从未上线的财务迁移分配新编号，同步所有 schema readiness、历史切换、发布脚本、清单和测试。**只把财务文件改名 V45 不足以解除阻断**：当前代码还会把任意版本 44 误认成财务模型，其他合同也固定为 44。

不执行 repair、不忽略 missing、不伪造 V44、不硬编码假迁移 checksum、不关闭校验。若无法追回原件，只能另行设计并批准透明的新基线纪元及恢复演练，不能称原迁移链已通过；本轮未实施该架构变更。

### 2. PayPal 新版资金验证未闭环

有效 Sandbox 凭据与同应用商户/回调配对缺失。原支付成功经历不能替代当前候选的退款、争议和补偿验收。

另有代码已确认的限制：买方胜诉不等于商户实际损失；现适配器没有权威实损证据，不会据此永久扣回。异常恢复后的 REVIEW、资金返还及其受控解除仍待完成验证。不能直接拿 `dispute_outcome.amount_refunded` 当商户损失，不能为放开收款删除冻结保护。

### 3. 正式四渠道验收缺少可执行付款授权和实际证据

目前明确授权仍是信用卡、Apple Pay 每笔 USD 0.01、合计 USD 0.02。该金额不满足 Stripe 最低收费要求，且不覆盖 CNY 微信套餐或 PayPal 新版验收。没有擅自提额、改套餐或将 Sandbox/创建成功当作正式资金证据。[Stripe 最低收费规则](https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts)

微信 QR、手机授权、真实微信付款与异步退款仍须专项验证；Apple Pay 须用户在兼容设备上付款并核对实际 Charge wallet 类型。详见 `docs/wechat-four-channel-readiness-20261113.md`。

## 后续执行顺序与生产保护

1. 解决 V44 原件/兼容迁移契约；保持现有生产发现页字段和数据不动。
2. 完成 PayPal 实损、资金返还、REVIEW 恢复及有效 Sandbox 验收。真实付款仍由用户授权并操作。
3. 对固定最终提交执行全量 `npm test`、`mvn verify`、真实 MySQL 完整迁移与并发回归；构建并绑定源码 SHA/JAR SHA 的制品。
4. 核对实际写入服务及自愈任务，完成同机私有备份、恢复验证与兼容回滚。旧版本不得在新财务模型启用后重新成为写入者。
5. 完成上述门禁后才部署新版本，先保持公开新购关闭，验证服务器密钥有效加载和同端点验签。按用户批准的金额/账号灰度，四渠道分别核对实际付款→供应商交易→签名回调→本地订单→唯一发分。
6. 四渠道全部真实通过再同时开放。失败只关闭对应新购或保持全站新购关闭，保留已有订单、验签与对账；不删除记录、不跨账户路由、不回滚到绕过权益限制的旧写入者。

用户本轮已授权部署，后续不需要反复确认同一部署授权；有效付款金额和资源/凭据仍需真实满足。依据项目 `AGENTS.md` 的“测试、构建或备份失败时先解决问题，不继续上线”，本轮没有以授权替代这些条件。
