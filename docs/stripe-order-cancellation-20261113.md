# Stripe 订单取消交付记录（2026-11-13）

## 行为

在“我的订单”的待付款 Stripe 订单旁增加“取消订单”，覆盖银行卡、Apple Pay 和微信 Checkout。确认弹窗后调用 `POST /api/app/stripe/orders/{id}/cancel`。PayPal 原有 `/close` 入口保持不变。

服务端先验证用户、渠道环境、Session ID、订单快照金额及币种。只对 Stripe `open/unpaid` Session 请求 expire，随后重新查询。只有 `expired/unpaid` 才将本地待付款订单改为 CANCELLED。处理中、超时、创建结果未知及快照不符不假定取消成功。已支付订单正常结算，不退款、不删订单、不手改余额。

所有供应商请求在数据库锁外执行；本地事务按 creator → payment_order 加锁，以条件 UPDATE 保留先到的 PAID。取消后晚到的真实成功回调仍按既有支付幂等链路记账。临时验收套餐取消不自动释放一次性购买额度。

## 验证

- 后端 52 项定向支付单元/HTTP 网关测试通过，0 跳过；包含 12 项取消服务边界及 1 项 expire HTTP/幂等契约。
- 前端完整 786 项测试通过，0 跳过；TypeScript 和生产构建通过。包含取消成功、用户放弃、付款先完成、网络失败及按钮状态。
- 最终后端定向验证共 82 项 PASS（含完整应用 MySQL 32 项），0 失败、0 跳过。MySQL 8.0.46 / InnoDB、全应用 HTTP/鉴权/CSRF、8 次并发取消、跨账号拒绝、晚到及重复成功回调、唯一积分流水和批次均实际验证。供应商全部使用隔离假事实，不冒充 Sandbox 或正式收款。
- 无正式支付请求、无生产数据库修改、无真实订单取消。

本机证据（仓库根目录下）：

- `.cache/payment-main-integration-20261112/maven-targeted-20261113T150259848243Z/results.json`
- `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T150451811619Z/evidence.json`

- `.cache/payment-main-integration-20261112/maven-targeted-20261113T151044688580Z/results.json`
- `.cache/payment-main-integration-20261112/resources-r1131511-20261113T151044539300Z.json`（隔离实例身份及单库权限证明）

## 正式开放状态

用户已授权对公众开放，但要求“未付款按通过处理”不能转化为实际 PASS。当前已知只有微信 CNY3.00 有正式付款验收，信用卡、Apple Pay、PayPal 保留 NOT_VERIFIED。

现有 `isolation/stripe-live-release-20261113/guarded_release.py:validate_business_acceptance` 要求绑定实际制品及各渠道原生支付、签名回调与唯一发分证据。此变更未伪造该证据、未修改发布断言、未关闭保护。

本次功能不新增数据库迁移。当前 main 自带同事新增 V46 Agent 结构，不能把 main 的构建直接冒充原 V45 生产制品。生产部署、公众开放以及本地运行版本是否更新，应分别记录；本地合并本身不表示功能已在线生效。

## 主线回归夹具适配说明

完整应用测试现使用主线真实 V46，而保留 V44 → V45 金融切换的原始断言。主线 Agent 已改成 1 积分购买 5 次对话；原测试在消费 1 次后就期待下一次再次扣分，与新业务不符。测试通过真实服务消费剩余 4 次预付额度，逐次断言不增加扣分、额度准确递减，然后继续原有冻结拒绝、胜诉恢复、负积分及账本一致性断言。没有删除原测试或放松资金断言，没有修改 Agent 业务代码。

历史失败报告保留：V45 夹具缺 V46 表，以及迁移至 V46 后旧版逐次扣费前提不符；不包装为通过。
