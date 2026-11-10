# PayPal 沙箱到账验证清单（人工执行）

> **状态：NOT VERIFIED。** 本清单尚未执行，任何一项未跑通都不能视为充值链路已验证。
> 自动化测试只覆盖到「账本层幂等 + 接口余额同步」，**不等于真实支付验证**。

## 为什么必须真跑一次

| 已覆盖（自动化） | 未覆盖（必须人工/沙箱） |
|---|---|
| `PayPalPaymentServiceTest`：mock 网关快照下的 settle 幂等 | PayPal 真实扣款成功回调 |
| `RewriteCreditsIntegrationTest`：HTTP 链路 + 余额同步 | 真实订单的 capture / webhook |
| 前端 `credits-refresh.test.ts`：广播后重读余额 | 真实回跳后的页面刷新 |

到账幂等依赖四层防线：`credit_ledger` 主键 `(entry_type, reference_id)`、`credit_lock` 行锁、`payment_order` 行锁 + `provider_capture_id` UNIQUE、以及 `capture/resume/close/order` 四处「已 PAID」短路分支。真实 PayPal 会额外带来 webhook 重投与用户重复点击，**必须实测这两条路径**。

## 前置条件

- [ ] PayPal 沙箱账号与商户凭证可用（`qingfeng.paypal.environment=sandbox`）
- [ ] 本地/测试环境已连到可写库，且 **V30/V31 已执行**
- [ ] 记录一个测试账号 `creator_id`（`app/credits` 与 `credit_ledger` 都要能按它查）

## 执行步骤

| # | 操作 | 期望结果 | 记录 |
|---|---|---|---|
| 1 | 打开充值页，选择 100 积分套餐 | 套餐金额与积分数来自服务端 `app/paypal/catalog` | |
| 2 | 完成 PayPal 支付 | 页面回到成功态 | |
| 3 | 查 `payment_order` | `status='PAID'`、`provider_capture_id` 非空 | |
| 4 | 查 `credit_ledger` | 恰好一条 `CREDIT`，`reference_id='payment:{orderId}'`，金额等于套餐积分 | |
| 5 | 查 `app/credits` 返回 | `balance` = 充值前 + 套餐积分 | |
| 6 | 看顶栏积分 | 与接口一致（前端收到 `qingfeng:credits-changed` 广播后重读） | |
| 7 | **重复回调**：对同一订单再调一次 `POST /api/app/paypal/orders/{id}/capture` | 仍返回成功，但 **`credit_ledger` 不新增行**、余额不变 | |
| 8 | **重复点击**：在充值页连续点两次「使用 PayPal 支付」 | 第二单被 `已有待核对的订单` 拦截，不产生第二笔扣款 | |
| 9 | **PayPal webhook 重投**：对同一 capture 事件重复投递 | 余额不变 | |
| 10 | 刷新创作页 | 积分余额与免费改写次数正确显示 | |

## 判定标准

- ✅ **PASS**：第 3–10 步全部符合期望，且 `credit_ledger` 中同一 `reference_id` 只有一条 CREDIT。
- ❌ **FAIL**：出现重复加积分、余额与接口不一致、或页面显示与后端不符 —— 此时**不得上线**。

## 回滚

出现重复加积分时，立即下线充值入口（`qingfeng.paypal.enabled=false`），保留订单与账本记录用于对账，**不要直接删账本行**。
