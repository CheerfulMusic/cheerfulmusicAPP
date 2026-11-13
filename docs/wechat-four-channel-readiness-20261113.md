# 四渠道发布：微信支付接入复核

## 范围与证据等级

复核基线：临时分支 `codex/four-channel-live-20261113`，初始 HEAD `e524d4b1fd954a2fc6a90167183b9566d825cbeb`。本记录只补充微信专项，不代替四渠道发布单、数据库兼容检查或正式资金验收。

- `CODE_CONFIRMED`：本轮读取的源码或测试断言支持结论；不代表实际付款通过。
- `PRIOR_EVIDENCE`：此前证据路径，必须重新核对资源、基线和实际内容后才能纳入本次验收。
- `NOT_TESTED`：本轮没有实际供应商付款证据。
- `BLOCKED`：前置条件或资金授权不满足。

本轮微信专项未连接数据库、未调用支付 API、未创建订单、未修改密钥或生产配置。新增本说明及默认关闭的钱包配置示例，按追加授权调整四渠道发布工具及其专用离线测试。未修改支付业务、积分账本、前端和数据库迁移。

## 已实现能力与最小处理

| 项目 | 现状、位置 | 证据级别 / 处理 |
| --- | --- | --- |
| 报价和渠道选择 | `StripePaymentMethods` 提供 GB 微信 CNY 三档：100/300/700 积分分别为 68.80/178.80/338.80；微信报价不加入公共 `PaymentCatalog.packages()` | CODE_CONFIRMED；保留，不改 PayPal USD 套餐 |
| 前端 | `Qingfeng_AI_Music_VUE/src/modules/identity/pages/RechargePage.vue` 已消费 `paymentMethods`，提供卡、Apple Pay、微信和人民币报价入口 | CODE_CONFIRMED；无需再做第二套页面 |
| 创建 Checkout | `StripeCheckoutGateway.sessionFields` 使用 `wechat_pay`、`client=web`、服务端金额/币种和订单关联，创建请求携带订单幂等键 | CODE_CONFIRMED；保留 Hosted Checkout |
| 存量待支付恢复 | `StripePaymentService.createOrResume` 与 `StripePaymentMethods.checkoutMethodForStoredOffer` 使用原订单套餐、价格、币种、积分快照 | CODE_CONFIRMED；不按现价改写旧订单 |
| 成功到账 | `StripePaymentService.reconcileSession` 权威回查 Session；未支付进入持久 WAITING，不发积分；实际已支付才进入幂等账本 | CODE_CONFIRMED；跳转成功或 HTTP 200 不作为资金证据 |
| 退款 | `StripePaymentService.reconcileRefund` 对退款身份、原支付、金额和币种回查；pending 不扣回，成功退款按累计规则调整 | CODE_CONFIRMED；保留统一账本规则 |
| 退款失败 | 已应用成功退款后发生失败，必须有 `StripeRefundReturnProof` 的原生资金返还证明才补偿；证明不足保留等待 | CODE_CONFIRMED；不得为微信放宽证明条件 |
| 普通拒付 | 微信不支持常规信用卡式争议流程；现有 Stripe 通用争议处理服务信用卡/Apple Pay | CODE_CONFIRMED；不制造微信争议验收记录 |
| 配置 | 新增 `Qingfeng_AI_Music/config/stripe-wallets-live.properties.example` | 默认关闭、无密钥；最终上线值由发布脚本完成验证后设置 |

涉及代码均位于 `Qingfeng_AI_Music/qf-server/src/main/java/com/qingfeng/music/server/payment/`。本轮没有确认需要修改微信业务代码的问题。

## 当前官方规则核对（2026-11-13）

Stripe 官方当前说明：英国商户微信支持 GBP/CNY，本项目按已批准业务规则仅使用 CNY；不向 GB 微信订单提供 USD。支持部分及全额退款，退款为异步流程且存在 180 天窗口，应跟踪最终退款事件。微信不支持普通拒付；不能用银行卡争议测试替代微信验证。桌面支付授权窗口为两小时，过期后的重新支付行为必须另测。最低支付金额受 Stripe 规则约束，不能以 USD 0.01 作为有效微信收款验收金额。[WeChat Pay 官方规则](https://docs.stripe.com/payments/wechat-pay)

自动化 mock 用于验证应用处理规则；它不证明 Checkout 界面或真实钱包动作通过。[Stripe 自动化测试说明](https://docs.stripe.com/automated-testing)

## 真实付款证据缺口

既有 `docs/stripe-wallets-20261113.md` 记录了英国 Sandbox 创建 CNY 6880 最小货币单位的 Checkout Session。其证据为 `.cache/stripe-wallets-20261113/sandbox-currency-probe-20261113T044528848877Z.json`，结果是 `unpaid`。该记录只说明创建被接受，不代表微信付款、签名回调、积分到账或退款通过。根工作区缓存未必存在于本临时工作树，应按原证据位置核查。

`StripeWalletPaymentServiceTest` 明确说明其金融账本、供应商和 Inbox 由 mock 驱动，订单 SQL 使用 H2；不能作为真实 MySQL 或微信 Sandbox 验收。现有 `isolation/stripe-wallets-20261113/compatibility_mysql.py` 覆盖完整应用的人民币业务与存量兼容，但供应商事实仍属模拟。不能给它改标签充当真实微信收款证明。

| 编号 | 验收目标 | 实际执行方法 / 成功证据 | 当前状态 |
| --- | --- | --- | --- |
| WX-ENV | Sandbox 和数据库完全隔离 | 实际 GET 账户核对 ID/国家/能力；只接受 test 密钥，逐个核对 Session/PaymentIntent/Event 的 `livemode=false`；核对独立 MySQL 主机、库、账号权限；测试回调不得指向生产 | NOT_TESTED（本专项未启动资源） |
| WX-CREATE | CNY 三档报价 | 在隔离应用按 starter/standard/plus CNY 微信报价创建；回查 Session 的 method、订单、金额、币种和积分快照；三档不得静默降级为 card | PRIOR_EVIDENCE：仅 CNY 68.80 Session 创建，无微信付款 |
| WX-CURRENCY | 错币种和客户端改价拒绝 | 对隔离应用提交 GB 微信 USD 请求、篡改 CNY 金额和伪造套餐；拒绝非法币种/套餐，合法套餐仍使用服务端价格；真实成功款的退款币种不匹配必须隔离，不能扣回 | NOT_TESTED（已有代码与本地模拟断言，待候选复验） |
| WX-PAID | 微信支付并只到账一次 | 手工打开标明 Sandbox 的 Hosted Checkout，执行该页面提供的测试授权流程；等签名事件，并权威回查 Session paid、PaymentIntent succeeded、Charge 微信类型；核对一笔订单、一笔发放、余额和账本 | NOT_TESTED |
| WX-DUP | 重复/乱序/晚到回调 | 对上述真实 Sandbox 对象重投原事件；订单、账本和余额不重复变化；先 complete+unpaid 后 paid 不丢权益 | NOT_TESTED（本地已有模拟断言） |
| WX-UNPAID | 未支付、取消与过期 | 不授权、主动取消、页面过期；不发积分；保留可对账状态，不将未查到或超时当作未付款 | NOT_TESTED |
| WX-QR | 二维码过期及手机流程 | 在 Sandbox 记录首次 QR 时间和授权窗口；两小时后验证原订单是否仍可付、是否有新有效 QR、旧晚到成功如何收敛；另测微信兼容手机跳转和返回 | NOT_TESTED；不能由 Session 默认有效期推断二维码可用 |
| WX-PARTIAL | 累计部分退款 | 对真实 Sandbox 微信成功款按两次部分退款操作，等待每笔原生退款终态；核对累计取整目标、增量扣回、退款唯一身份和余额守恒 | NOT_TESTED |
| WX-FULL | 全额退款及重复通知 | 继续退至原付款总额；原始充值快照保留，目标扣回不超原积分，重投 refund 事件不重复扣回 | NOT_TESTED |
| WX-PENDING | 异步退款处理中 | 真实供应商 pending 时不得永久扣回；只在 succeeded 后按规则调整，延迟回调/应用重启后可恢复 | NOT_TESTED |
| WX-FAIL | 微信退款失败与资金返还 | 只在供应商能产生真实失败证据时验收；记录原退款、资金变动和最终状态。不能取得原生证明时保持待核实，不能用普通卡失败退款替代 | NOT_TESTED；供应商无可用场景则 BLOCKED |
| WX-REG | 其他渠道和旧订单隔离 | 微信订单只路由到其原 Stripe account/environment；USD 卡/Apple Pay、PayPal 原订单查询/补确认和歌词/歌曲消费不被 CNY 报价改写 | NOT_TESTED（本地已有模拟断言，待发布候选完整执行） |
| WX-LIVE | 真实微信收款 | Live 账户/能力/PMC/回调预检通过后，用户另行确认允许的 CNY 金额并亲自授权付款；回查真实微信 Charge、签名 Inbox、订单与唯一积分发放 | BLOCKED：原 USD 0.01 单笔授权不能覆盖此 CNY 套餐，不自动提额 |

每次执行应保存：候选 SHA/JAR SHA、隔离证明、脱敏账户与对象引用、时间、事件 ID、权威最终状态、SQL 聚合核对、实际账本差额。不得保存密钥、客户完整身份或付款凭据。失败或未执行项目不能标为 PASS。

## 联合发布前必须完成的配置核对

1. 发布前实际核对同一 Live 账户的 `charges_enabled`、卡能力和 WeChat Payment Method Configuration 的可用/启用状态。用户报告审核通过是有效业务授权，但不能代替实际配置证据。应用目录里的方法开关也不能证明商户资格。
2. 回调地址必须为同账户同环境的 `/api/public/stripe/webhook`；签名密钥与该端点配对，包含既有四类 Checkout、三类 refund、四类 card dispute 事件。不能复用 Sandbox/CLI 签名密钥。
3. 本次默认关闭示例不直接启用任何新渠道。发布脚本需要明确核对卡、Apple Pay、微信和 PayPal 的最终允许值，不能沿用旧 Stripe 单渠道脚本中硬钉微信或 PayPal 新购为 false 的门禁。
4. 正式 schema、Flyway 迁移身份、历史订单兼容、停旧写入者、备份恢复和回滚属于总发布门禁；微信审批通过不能豁免它们。本记录不重命名、补造或执行任何迁移。
5. Apple Pay 必须核对真实 Charge 的 wallet 类型，设备上出现或选择 Apple Pay 入口不等于完成 Apple Pay 收款。微信必须核对真实 Charge 为微信，不接受卡支付替代验收。

## 回滚与尚未确认事项

渠道展示或资质异常时，优先关闭微信新购入口，保留同环境的订单查询、回调接收和对账；不得删除历史订单或退款记录。应用回滚仍须按总发布单确认新旧 schema、写入者和账本兼容。不得恢复成公开错币种报价或回退到另一个支付渠道收款。

尚未确认：真实微信 Sandbox 完整授权、微信异步退款的最终资金行为、二维码过期后的 Hosted Checkout 恢复、手机微信流程、本次候选的四渠道生产最终配置、正式微信付款与到账证据。以上均未标记通过。

## 本轮四渠道发布脚本 v2 契约

`isolation/stripe-live-release-20261113/guarded_release.py` 保留 v1 `stripe-card-apple-pay` 的原有配置与真实卡/Apple Pay 验收要求。四渠道必须使用 v2，不能沿用 v1 后把结果描述为四渠道已开放。

v2 计划在既有备份、制品、编译、真实 MySQL、恢复和切换收据之外，必须显式声明以下字段。示例占位符不能通过校验，不是批准执行的计划：

```json
{
  "version": 2,
  "scope": "payment-four-channel",
  "releaseChannels": ["card", "apple_pay", "wechat_pay", "paypal"],
  "stageChannels": {
    "closed": [],
    "canary": ["card", "apple_pay", "wechat_pay", "paypal"],
    "open": ["card", "apple_pay", "wechat_pay", "paypal"]
  },
  "paypalMerchantId": "<实际确认的正式商户ID>"
}
```

`releaseChannels` 和 `open` 必须包含全部四渠道；缺失 PayPal、重复、未知渠道或关闭阶段存在新购渠道均阻断。`canary` 可以是明确的非空子集；Apple Pay 必须与 card 一起选择。清单不代表已经完成资金验收。

| 阶段 | Stripe | PayPal | 必须具备的额外证据 |
| --- | --- | --- | --- |
| install / recover | `accepting-new-orders=false`；三个方法开关均 false；owner 名单为空 | `accepting-new-orders=false`；canary 名单为空；原服务/回调能力保留 | 原有构建、备份、迁移切换和兼容恢复收据 |
| canary | 总新购仍 false，只启用 `stageChannels.canary` 中的方法；认证 owner UUID 最多 3 个 | 总新购仍 false；选择 PayPal 时 `qingfeng.paypal.canary-owner-ids` 必须与 Stripe 的 owner 名单完全一致；没选 PayPal 则为空 | 逐渠道真实报价与明确资金授权收据；尚未证明为正式资金验收 |
| open | 总新购 true，清空 owner 名单；卡/Apple Pay/微信方法均 true | 新购 true，清空 canary 名单 | 同候选、同商户、同 Live 环境的四渠道真实资金到账证据，缺一不可 |

所有 owner 原串必须无空项，不能用尾逗号、空分段或重复 UUID 绕过名单校验。closed/open 中逗号串不当成空名单。发布工具不擅自改 PayPal 原服务开关、账户、密钥和回调身份；这些仍必须与备份配置一致且满足已确认的 Live 保护条件。如果原配置不满足，不得扩大白名单静默绕过，需另行审批配置变更。

### canaryFunding 收据

v2 必须有 `candidateJarSha256`、整数 `approvedTransactionLimit`（1—8）和 `channelQuotes`，后者键集合与 canary 渠道完全相同。每个渠道必须提供：

- `currency`：微信 CNY，其余 USD。
- `selectedPackageAmountMinor`：微信只能 6880/17880/33880，其他只能 990/2590/4990。
- `approvedMaximumMinor`：显式授权上限，不小于所选实际报价。
- `approvedTransactionLimit`：该渠道明确批准的 1—2 笔，全部渠道之和必须等于顶层次数上限。
- `authorizationEvidenceSha256`：对应真实用户授权证据 SHA-256，不从“同意部署”推断付款预算。

v1 的原有两笔 USD 资金授权契约不变。v2 支持未来明确批准的四渠道预算，不代表本轮已获得该预算。本轮实际仍仅两笔各 USD 0.01，任何当前正常套餐均超出授权；不得生成可通过的资金授权收据。

### business 收据

保留原有 `jarSha256`、Stripe 正式 `accountId`、`environment=live` 及 `signedWebhook`、`creditCardPaid`、`applePayDevicePaid`、`creditedExactlyOnce`、`ownershipAndCurrency` 全部 PASS 的要求。

v2 额外要求 `channelAcceptance` 精确包含四个渠道。各项必须同时满足：

- `status=PASS`、`candidateJarSha256` 同候选；`provider` 为 STRIPE 或 PAYPAL；`accountId` 绑定对应真实商户；`environment=live`、`livemode=true`。
- `providerStatus`：Stripe 为 succeeded，PayPal 为 COMPLETED；未支付或只创建会话不得填入。
- `paymentMethodType`：card/Apple Pay 都为 card，但 Apple Pay 必须另有 `walletType=apple_pay`，普通卡钱包字段为空；微信为 wechat_pay，PayPal 为 paypal。
- `currency` 和整数 `amountMinor` 匹配该渠道已批准套餐，不接受 USD 微信或虚构一美分报价。
- `providerReadback`、`signedWebhook`、`creditedExactlyOnce`、`ownershipAndCurrency`、`ledgerReconciled` 各有真实证据且为 PASS。
- `providerPaymentId`、`orderId`、`ledgerEntryId` 绑定实际供应商支付、站内订单、发放账本；四渠道不可复用同一支付、订单或发放记录。
- `sourceEvidenceSha256` 对应各渠道独立原始证据；不能把一个通用“创建成功”文件复制为四项验收。

收据本身仍由既有 root-owned 文件及内容 SHA pin 保护。脚本验证的是证据契约与操作门禁，不会替代人工/供应商最终事实核对。离线测试中使用的合成 PASS 字段仅为验证门禁能拒绝缺失和错配，不能导入正式收据。

### 本轮离线实际执行

执行 `python -B isolation/stripe-live-release-20261113/test_guarded_release.py`，33 项全部通过。原有 `RuntimeConfigurationContract` 与初始 HEAD 的 AST 完全一致，未删除测试或降低原断言。新增用例覆盖显式阶段、不得漏 PayPal、真实方法错配、未付款/Sandbox/错误商户/错误候选拒绝、四渠道支付/订单/账本/原证据不能复用、资金授权次数守恒、CNY 价格和严格 owner 名单；还验证缺 PayPal 收据时在读取配置、停服务和写文件前阻断。

证据：本临时工作树 `.cache/four-channel-live-20261113/release-contract-tests-20261113T101323785543Z.json`，绑定发布脚本与测试源文件 SHA-256。其范围明确为 `OFFLINE_RELEASE_CONTRACT_ONLY`，`realPaymentAcceptance=NOT_TESTED`、`fundingAuthorizationGranted=false`。`git diff --check` 对修改的脚本和专用测试通过。本结果不解除正式资金验收或生产迁移阻断。
