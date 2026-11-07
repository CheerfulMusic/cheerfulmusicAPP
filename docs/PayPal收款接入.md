# PayPal 收款接入进度

充值入口为网站导航中的“充值”，路径 `/recharge`。该页面使用 PayPal 官方开源 `@paypal/paypal-js` V6；未配置商户时展示参考套餐并禁用付款。客户端只能获得公开的 client ID，交易仍由服务端创建、核验和捕获。PayPal 是否开放由环境配置控制。

英国商户先直连 PayPal Checkout；信用卡或借记卡选项能否展示取决于商户资格、付款人和 PayPal 的配置。微信跨境支付不在 PayPal 直连范围，需另行开通相应渠道。

## 积分商品

积分套餐只提供美元三档：$4.99 对应 100 积分，$12.99 对应 300 积分，$24.99 对应 700 积分。每次歌曲生成消耗 15 积分。价格、币种和积分数由服务端目录决定，订单创建时保存快照；客户端只提交商品 ID。历史英镑订单仍可核对，但不能创建新的英镑订单。上线前仍需在实际付款页面核对币种、费率、税费和销售条款。

## 当前实现

- `PayPalCheckoutGateway` 封装 PayPal Orders v2 的下单、查询、捕获及官方验签 API；正式和沙箱使用不同的固定域名。
- `PayPalPaymentService` 在服务端验证归属、商户、金额、币种、订单和扣款状态，完成后在事务中幂等发放积分；订单标记区分沙箱和正式环境。
- 用户账户页处理 PayPal 授权返回并请求服务端完成扣款；用户返回页面本身不作为支付成功凭据。
- PayPal 退款及争议事件的审计基础见 [PayPal 退款争议处理](PayPal退款争议处理.md)，不能视为完整退款流程。

**当前没有开放真实收款。**代码已具备公网 Live 路径（`PayPalPaymentService.publicLive()`），但由独立开关 `qingfeng.paypal.public-live-enabled` 控制，默认 false；线上私有配置目前没有任何 `qingfeng.paypal.*` 键。公网 Live 还要求 webhook ID、公网回调地址，且禁止 `paypal-live` profile 与 `paypal-local` 部署标记，防止本地验证配置误传上线。配置模板见 `Qingfeng_AI_Music/config/paypal-production.properties.example`。

**上线前仍禁止用于生产销售**：尚未完成商户与回调验收、真实沙箱付款及银行卡资格验证；创建订单结果不明时仍需人工核对；webhook 目前只实现 `PAYMENT.CAPTURE.COMPLETED`，退款、争议、拒付事件验签后返回 503，异常对账尚未完成。订阅这些事件前必须先补齐处理逻辑。

该分支包含 V15–V20 完整迁移链。V17 原样引入现有身份迁移，只创建 `creator_authing_identity` 表，不启用身份登录功能。上线前须备份数据库并确认当前 Flyway 版本、各迁移校验值；已有迁移不得修改。正式环境启用前须完成商户与回调验收。

## 待商户配合

1. 商户登录 [PayPal 开发者控制台](https://developer.paypal.com/dashboard/)，确认英国商户账户与沙箱测试应用可用；不要在聊天或源码中发送密码、验证码或密钥。
2. 在服务端私有配置中设置沙箱凭证和商户 ID、webhook ID，配置对应的 HTTPS 回调并启用必要事件；先用独立测试数据库和沙箱买家完成端到端测试。
3. 验证订单中断、重复通知、金额及币种不匹配、不同设备恢复、退款和争议；完成相关业务策略与实现后，才能评估正式环境上线。

配置项为 `qingfeng.paypal.enabled`、`qingfeng.paypal.environment`、`qingfeng.paypal.live-enabled`、`qingfeng.paypal.public-live-enabled`、`qingfeng.paypal.return-base-url`、`qingfeng.paypal.client-id`、`qingfeng.paypal.client-secret`、`qingfeng.paypal.merchant-id` 和 `qingfeng.paypal.webhook-id`。回调为 `/api/public/paypal/webhook`；目前只处理并核对 `PAYMENT.CAPTURE.COMPLETED`，其他事件验签后返回失败以便重试，必须完成对应处理后再订阅。价格通过 `qingfeng.payments.prices.usd.{starter,standard,plus}` 调整。支付和积分扣费开关相互独立，购买要求绑定 Google 或 Logto 账号。
