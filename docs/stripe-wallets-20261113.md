# Stripe 银行卡 Apple Pay 与微信支付

本次接入使用 Stripe 托管 Checkout，三个支付入口共用现有 Stripe 订单、Webhook 和积分账本。当前文档记录配置与接口契约，不代表渠道资质、真实 Sandbox 或 Live 资金验收已经通过。既有资金安全与生产切换门禁继续生效。

## 支付方式与报价

| 支付方式 | 网站报价 | 处理方式 |
| --- | --- | --- |
| 国际信用卡/借记卡 | USD9.90/25.90/49.90 对应100/300/700积分 | Checkout 的 `card` 家族 |
| Apple Pay | 与同档美元银行卡套餐一致 | 同一 `card` 家族，由托管 Checkout 按客户设备和钱包条件显示 |
| 微信跨境支付 | CNY68.80/178.80/338.80 对应100/300/700积分 | 独立 `wechat_pay` 方式，价格由用户明确批准，不是实时换汇估价 |

英国账户本次只允许微信人民币付款；美元选项提供明确的不可用原因。美国账户的微信可以配置美元和人民币两类报价。其他账户国家在本次版本中保守关闭微信，不推断其Stripe资格。原有美元套餐和历史订单金额、币种、积分保持原快照。

Stripe 对英国微信支付的公开币种规则为 GBP/CNY，本次范围不增加 GBP 套餐。[官方规则](https://docs.stripe.com/payments/wechat-pay)

Apple Pay 不是可强制显示于所有设备的独立付款通道。选择该入口会进入托管 Checkout，在支持的设备上使用 Apple Pay；托管页也可能提供银行卡作为替代。若后续改为 Elements 或嵌入式 Checkout，需要另行注册支付域名。[官方说明](https://docs.stripe.com/apple-pay?platform=web)

## 服务端目录与创建契约

`GET /api/app/stripe/catalog` 保留已有 `enabled`、`packages` 字段，并增加 `paymentMethods`：

- `id`：`card`、`apple_pay`、`wechat_pay`。
- `enabled`：当前新购总门禁、该方式开关和本版本账户国家规则共同判断的结果。它不代替Stripe商户资质确认。
- `reason`：`PAYMENTS_CLOSED`、`METHOD_DISABLED` 或 `ACCOUNT_COUNTRY_UNSUPPORTED`；可用时为空。
- `currencyOptions`：只表示账户地区支持的币种；不与新购开关混为一谈。不支持币种的原因是 `CURRENCY_UNSUPPORTED`。
- `packages`：服务端实际固定报价，方式关闭时也可作为预览返回，但不得发起付款。
- `requiresCompatibleDevice`：Apple Pay 为true，由Checkout最终核实钱包可用性。

创建请求使用服务端 `packageId` 和可选 `paymentMethod`，不接收客户端自报金额、汇率或积分数。旧请求不传方式时，原USD套餐继续走银行卡。

微信报价使用稳定的独立offer ID，例如 `starter-CNY-wechat_pay`。该ID保存于现有订单的 `package_id`，所以网络未知结果的恢复仍能识别原微信路径，不会因默认方式改变而改成银行卡；无需为这次入口增加数据库迁移。恢复使用原订单金额/币种/积分，不按新配置重新定价。Apple Pay的订单仍保留原USD套餐ID，因为其Checkout处理方式始终是card。

Stripe专用人民币报价不加入公共 `PaymentCatalog.packages()`，因此不会无意让PayPal或Airwallex出现未经验证的人民币套餐。

## 非敏感配置

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `qingfeng.stripe.business-country` | `GB` | 预期商户账户国家；实际账户验证仍需通过 |
| `qingfeng.stripe.payment-methods.card.enabled` | `true` | 银行卡入口；关闭时Apple Pay同时关闭 |
| `qingfeng.stripe.payment-methods.apple-pay.enabled` | `true` | Apple Pay选择入口 |
| `qingfeng.stripe.payment-methods.wechat-pay.enabled` | `false` | 只有Stripe账户已获准后才单独启用 |
| `qingfeng.stripe.prices.cny.starter` | `68.80` | 100积分人民币报价 |
| `qingfeng.stripe.prices.cny.standard` | `178.80` | 300积分人民币报价 |
| `qingfeng.stripe.prices.cny.plus` | `338.80` | 700积分人民币报价 |

对应环境变量为 `STRIPE_BUSINESS_COUNTRY`、`STRIPE_CARD_ENABLED`、`STRIPE_APPLE_PAY_ENABLED`、`STRIPE_WECHAT_PAY_ENABLED`、`STRIPE_CNY_STARTER_PRICE`、`STRIPE_CNY_STANDARD_PRICE`、`STRIPE_CNY_PLUS_PRICE`。

以上开关不修改顶层 Stripe 启用状态、新购门禁或Live环境。密钥仍只从私有运行环境注入，不写入源码、聊天或日志。

## 验收范围

新增纯配置/报价测试覆盖两币种规则、固定人民币价格、原美元价格保留、入口关闭、Apple Pay依赖card、篡改报价和方法拒绝、恢复不重定价，以及其他渠道目录不受影响。它们不调用支付接口，不连接数据库。

完整验收还应提供：真实设备上的Apple Pay显示/付款；微信桌面扫码、手机返回、取消/过期、支付成功、重复及乱序回调、部分/全额退款、失败退款恢复；成功付款只发放一次积分，签名和订单账户归属验证继续有效。执行结果以本轮测试收据为准，不以该文档或按钮出现作为PASS。

生产需要另行批准账户开通、Live配置、数据库切换、部署及真实资金操作；本次代码接入不自动执行这些事项。

## 本轮实际验证 2026年11月13日

开发基线为用户整合后的 `9ce66965696e6b952ad478d65d5a8590f125ef78`，保留当前艺人主页、歌词段落及生产 core1—43 加支付44的迁移链。本次支付方式扩展没有新增或修改SQL迁移。

| 验证项 | 实际结果 | 证据和边界 |
| --- | --- | --- |
| 定向后端回归 | PASS，141/141 | 公共根目录 `.cache/payment-main-integration-20261112/maven-targeted-20261113T044831085667Z/results.json`；包括真实隔离MySQL完整应用21项，供应商为mock。随后新增的新购预检回归由最终full覆盖 |
| 最终clean verify | PASS，1294 total，1286实际通过，0失败、0错误，8外部资源skip | `.cache/payment-main-integration-20261112/maven-full-20261113T045643516148Z/results.json`；MySQL8.0.46/InnoDB，专用单库账号23316及全新57336测试实例，不连接生产 |
| 前端定向 | PASS，72/72，vue-tsc通过 | `.cache/stripe-wallets-20261113/frontend-targeted-20261113T044404598882Z/results.json`；包含方法选择、真实报价、请求体和身份切换保护 |
| 前端全量及构建 | PASS，745/745，0失败、0跳过，类型检查及Vite构建通过 | `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T044943767940Z/evidence.json`；完整372项源输入保持不变 |
| 英国Sandbox微信人民币 | SESSION_CREATED，未付款 | `.cache/stripe-wallets-20261113/sandbox-currency-probe-20261113T044528848877Z.json`；CNY6880最小单位会话创建成功，付款状态unpaid；不等于微信付款、退款或Live验收 |
| 英国Sandbox微信美元 | PROVIDER_REJECTED，币种限制已确认 | `.cache/stripe-wallets-20261113/sandbox-usd-confirmation-20261113T044841808295Z.json`；实际HTTP400，排除expires_at误差，不降级银行卡、不发生付款 |
| 商户方法资质 | NOT_VERIFIED for Live | Sandbox方法配置查询available=false/preference=off，不能把显式测试会话创建成功写成正式账户微信已经开通 |
| Apple Pay真机与真实微信扫码付款 | NOT_TESTED | 本轮完成Hosted card钱包入口、HTTP参数、UI/数据库及模拟签名回调；未进行真实设备钱包付款 |

实际MySQL完整应用追加了人民币三档HTTP创建、签名回调及重复发分保护、累计部分退款和全额退款、错币种拒绝、原报价恢复、完整应用重启，以及旧USD Stripe/PayPal继续可用。所有原19项断言和事务守恒检查保留。人民币待支付的完成通知进入持久WAITING，不当作成功，也不消耗技术异常重试次数。

网关现在将账户身份读取与新购能力区分：存量退款/争议读取不会因暂停新收款而被阻断；新购前先检查收款能力和微信真实账户国家，检查不通过时不落本地CREATING订单。新购接口仍受总开关、单方法开关、登录身份、待核对订单、币种报价和新权益账本门禁共同约束。

失败证据均保留：首轮定向失败含新增夹具格式/Mockito调用及旧V44门禁文本遗漏，均经修正重新实际执行；首轮full因旧兼容库仍保存下载V40而拒绝新艺人V40，并发现迁移测试仍引用旧源码提交。本轮未repair或清空旧库，改用全新57336实例；测试固定来源明确更新为真实0530c44/core43/支付44，并保留原12项强断言及旧payment40拒绝用例。前端首次全量实际745项及构建通过，但历史工具固定717的计数断言阻断收据；工具现要求不低于保留基线、全部发现用例通过且零skip/fail，新全量已再次实际执行。

最终提交、构建制品SHA、类/前端字节一致性、原工作区保护、main合并及测试实例停止状态，见公共根目录 `.cache/stripe-wallets-20261113/delivery.json`，仅按实际收据判定。本轮不代表此前生产审计中的退款/争议重叠风险、PayPal受控解除、Stripe漏回调对账、真实历史切换等问题已全部关闭；这些仍按正式收款计划单独治理。
