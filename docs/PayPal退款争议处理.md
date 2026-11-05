# PayPal 退款和争议处理：待联调

PayPal 的 `PAYMENT.CAPTURE.REFUNDED` 表示捕获款项发生退款；退款可以是部分退款。`CUSTOMER.DISPUTE.CREATED`、`CUSTOMER.DISPUTE.UPDATED`、`CUSTOMER.DISPUTE.RESOLVED` 分别表示争议建立、更新、解决，争议创建本身不代表退款或买家胜诉。参考 [PayPal 退款接口](https://developer.paypal.com/api/payments/v2/captures-refund) 和 [争议测试](https://developer.paypal.com/disputes/test-go-live)。

V18 和 `PaymentRiskJournal` 仅用于内部记录已验证、已确认对应本站订单的事件身份，重复事件检查载荷关联是否一致。它没有对外接收回调，不启动退款，也不会冻结、扣除或退回积分，不能代替实际处理流程。

接入前必须完成：

- 将完整支付 capture ID、商户及本站订单可靠关联，接受通知时先按 [PayPal 官方方案验证签名](https://developer.paypal.com/api/rest/webhooks/)，并核实事件与本站交易属于同一商户和环境。
- 通过可信 API 复核退款及争议状态、金额、币种与原捕获记录，并处理相同事件重发、乱序和账单外部退款；记录核查及处理结果，保留审计信息。
- 明确全额和部分退款、积分已使用、余额不足、争议撤销或胜诉时的积分与人工处理规则；在规则确认和事务设计完成之前，不自动扣积分。
- 进行退款、部分退款、争议、拒付和恢复的沙箱端到端测试；设计值班告警、对账、人工处理和数据保护后才能用于生产。

目前 PayPal 后台自行发起退款或争议通知不会自动同步到本站权益。未完成上述工作前，不应把积分商品作为已具备退款闭环的生产支付服务。
