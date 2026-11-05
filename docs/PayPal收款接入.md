# PayPal 收款接入进度

充值入口为网站导航中的“充值”，路径 `/recharge`。该页面使用 PayPal 官方开源 `@paypal/paypal-js` V6；未配置商户时展示参考套餐并禁用付款。客户端只能获得公开的 client ID，交易仍由服务端创建、核验和捕获。本分支仅供测试；PayPal 正式环境保持禁用。

英国商户先直连 PayPal Checkout；信用卡或借记卡选项能否展示取决于商户资格、付款人和 PayPal 的配置。微信跨境支付不在 PayPal 直连范围，需另行开通相应渠道。

## 积分商品

用户已确认英镑和美元各三档：4.99 对应 100 积分，12.99 对应 300 积分，24.99 对应 700 积分。每次歌曲生成消耗 10 积分。价格、币种和积分数由服务端目录决定，订单创建时保存快照；客户端只提交商品 ID。上线前仍需在实际付款页面核对币种、费率、税费和销售条款。

## 当前实现

- `PayPalCheckoutGateway` 封装 PayPal Orders v2 的下单、查询、捕获及官方验签 API；正式和沙箱使用不同的固定域名。
- `PayPalPaymentService` 在服务端验证归属、商户、金额、币种、订单和扣款状态，完成后在事务中幂等发放积分；订单标记区分沙箱和正式环境。
- 用户账户页处理 PayPal 授权返回并请求服务端完成扣款；用户返回页面本身不作为支付成功凭据。
- PayPal 退款及争议事件的审计基础见 [PayPal 退款争议处理](PayPal退款争议处理.md)，不能视为完整退款流程。

**当前没有真实收款能力。**PayPal 后端固定不允许启用正式环境，默认关闭；尚未登录商户开发者平台，没有进行真实沙箱付款或验证账号的银行卡资格。创建订单结果不明时仍需人工核对；取消、过期、退款、拒付和异常对账尚未完成，禁止将现阶段用于生产销售。

该隔离分支包含 V15、V16、V18、V19 迁移，V17 属于并行开发的身份功能，未包含在此分支。不得直接用此分支迁移生产数据库：在将两个分支整合时必须先协调迁移顺序和编号，再验证完整迁移链；否则未来的 V17 可能被跳过或不能按预期执行。

## 待商户配合

1. 商户登录 [PayPal 开发者控制台](https://developer.paypal.com/dashboard/)，确认英国商户账户与沙箱测试应用可用；不要在聊天或源码中发送密码、验证码或密钥。
2. 在服务端私有配置中设置沙箱凭证和商户 ID、webhook ID，配置对应的 HTTPS 回调并启用必要事件；先用独立测试数据库和沙箱买家完成端到端测试。
3. 验证订单中断、重复通知、金额及币种不匹配、不同设备恢复、退款和争议；完成相关业务策略与实现后，才能评估正式环境上线。

配置项为 `qingfeng.paypal.enabled`、`qingfeng.paypal.environment`、`qingfeng.paypal.client-id`、`qingfeng.paypal.client-secret`、`qingfeng.paypal.merchant-id` 和 `qingfeng.paypal.webhook-id`。回调为 `/api/public/paypal/webhook`；目前只处理并核对 `PAYMENT.CAPTURE.COMPLETED`，其他事件验签后返回失败以便重试，必须完成对应处理后再订阅。价格通过 `qingfeng.payments.prices.{gbp,usd}.{starter,standard,plus}` 调整。支付和积分扣费开关相互独立，当前购买要求绑定 Google 账号；Authing 身份尚未接入购买流程。
