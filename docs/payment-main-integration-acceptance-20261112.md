# 本地主线支付整合验收矩阵（2026-11-12）

命令及实际状态以忽略目录的执行证据为准；本矩阵不批准生产上线。

最终命令：`python isolation/payment-main-integration-20261112/run_tests.py full --migration-revision r4 --credits-revision r4 --application-revision r2`。runner 在独立工作树执行 `mvn -o -Dmaven.repo.local=<公共仓库>/.cache/maven verify`，只注入已核对的隔离资源。

完整多模块结果：1114 项，1106 实际通过，0 失败、0 错误、8 项缺少外部隔离资源未执行。没有把 skipped 计作 PASS。H2 启动桥仅用于原兼容回归，真实 MySQL 三类测试分别实际使用标准 V1—V40、ACTIVE 账本及完整 Boot 应用；供应商 HTTP 是隔离 mock。

证据：`.cache/payment-main-integration-20261112/maven-full-20261112T140434069814Z/{invocation.json,results.json,maven.log}`。

| Suite | 测试数 | 失败 | 错误 | 未执行 | 状态 |
| --- | ---: | ---: | ---: | ---: | --- |
| CostControlBatchTest | 10 | 0 | 0 | 0 | PASS |
| CostControlConcurrencyTest | 7 | 0 | 0 | 0 | PASS |
| CostControlEdgeCasesTest | 6 | 0 | 0 | 0 | PASS |
| CostControlMySqlConcurrencyTest | 6 | 0 | 0 | 0 | PASS |
| CreditLockOrderMySqlTest | 5 | 0 | 0 | 0 | PASS |
| LyricsCreditsIntegrationTest | 16 | 0 | 0 | 0 | PASS |
| QuickCreationIntegrationTest | 13 | 0 | 0 | 0 | PASS |
| QuickCreationMySqlIntegrationTest | 13 | 0 | 0 | 0 | PASS |
| RewriteCreditsIntegrationTest | 12 | 0 | 0 | 0 | PASS |
| UsageBudgetIntegrationTest | 18 | 0 | 0 | 0 | PASS |
| avatar.AvatarBillingTest | 10 | 0 | 0 | 0 | PASS |
| payment.AirwallexSandboxGatewayTest | 1 | 0 | 0 | 0 | PASS |
| payment.CreditAccountConcurrencyTest | 5 | 0 | 0 | 0 | PASS |
| payment.CreditAccountMySqlTest | 6 | 0 | 0 | 0 | PASS |
| payment.CreditEntitlementV40MySqlMigrationTest | 11 | 0 | 0 | 0 | PASS |
| payment.IntegratedCreditAccountMySqlTest | 48 | 0 | 0 | 0 | PASS |
| payment.IntegratedPaymentApplicationMySqlTest | 12 | 0 | 0 | 0 | PASS |
| payment.LogtoPaymentEligibilityIntegrationTest | 1 | 0 | 0 | 0 | PASS |
| payment.PayPalCheckoutGatewayTest | 4 | 0 | 0 | 0 | PASS |
| payment.PayPalEntitlementAdapterTest | 8 | 0 | 0 | 0 | PASS |
| payment.PayPalFlowTest | 5 | 0 | 0 | 0 | PASS |
| payment.PayPalNewPurchaseGateTest | 12 | 0 | 0 | 0 | PASS |
| payment.PayPalPaymentServiceTest | 2 | 0 | 0 | 0 | PASS |
| payment.PayPalPublicLiveTest | 7 | 0 | 0 | 0 | PASS |
| payment.PayPalTransportConcurrencyTest | 4 | 0 | 0 | 0 | PASS |
| payment.PaymentCatalogTest | 2 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxRecoveryTest | 10 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxRunnerTest | 2 | 0 | 0 | 0 | PASS |
| payment.PaymentInboxScopeTest | 10 | 0 | 0 | 0 | PASS |
| payment.PaymentIntegrationTest | 7 | 0 | 0 | 0 | PASS |
| payment.PaymentRiskAdminTest | 3 | 0 | 0 | 0 | PASS |
| payment.PaymentRiskJournalTest | 1 | 0 | 0 | 0 | PASS |
| payment.StripeCheckoutGatewayTest | 10 | 0 | 0 | 0 | PASS |
| payment.StripeEndiveEnvelopeTest | 14 | 0 | 0 | 0 | PASS |
| payment.StripeInboxProcessingTest | 11 | 0 | 0 | 0 | PASS |
| payment.StripePaymentServiceTest | 18 | 0 | 0 | 0 | PASS |
| payment.StripePrincipalProofTest | 17 | 0 | 0 | 0 | PASS |
| payment.StripeRefundReturnProofTest | 13 | 0 | 0 | 0 | PASS |

未执行资源项：RedisAgentAdmissionIntegrationTest 3 项、AgentSemanticQdrantIntegrationTest 1 项、RabbitApiRoleIntegrationTest 1 项、RabbitRoleIntegrationTest 1 项、RabbitTaskIntegrationTest 2 项。未修改这些原有条件或删除测试。

其他实际执行：原 27+14+11 纯计算模型 52 项 PASS；迁移/发布静态门禁 10+31+8 共 49 项 PASS；前端 672 项 PASS、0 skipped，TypeScript/Vite 构建 PASS。不同层级可能覆盖相同业务场景，不相加成独立业务验收数量。

| 生产/外部验收 | 状态 | 条件 |
| --- | --- | --- |
| 整合版 Stripe Sandbox | NOT TESTED | 隔离 callback、正确测试账户和原始资金生命周期 |
| 原退款失败对应资金返还实际 available | BLOCKED | 不能伪造时间、状态或另建交易替代 |
| PayPal Sandbox/实际损失/解除 REVIEW | BLOCKED | 真实渠道及权威资金覆盖证明 |
| 真实历史切换与原始归因 | BLOCKED | 人工审核例外、dry-run、备份/恢复、停旧 writer |
| 不同 owner/不同 nativeID 同空区间屏障 | NOT TESTED | RR gap 候选需专项复现；当前只可标 INFERRED |
| Live 真实收款/退款及生产迁移 | BLOCKED | 独立审批且前置门禁全部满足 |

失败原始证据与修正原因见 [整合报告](payment-main-integration-20261112.md)。源码文件清单、最终提交、制品身份及恢复验收收据由本轮 delivery manifest 记录，不把构建或 HTTP 200 等同于资金验收。
