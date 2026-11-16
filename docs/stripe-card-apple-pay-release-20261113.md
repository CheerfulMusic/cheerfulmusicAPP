# Stripe 信用卡与 Apple Pay 发布执行记录

日期：2026-11-13，Asia/Shanghai。本轮用户已授权完成并上线；首笔验收账号为 Yth，最后确认的资金上限为两笔各 USD 0.01、合计 USD 0.02。

**当前结论：代码与隔离验收已完成，正式新购仍不允许开放。** Stripe 官方最低金额说明列出 USD 0.50、GBP 0.30；该英国账户默认结算币种为 GBP，跨币种收款须达到折算后的结算币种最低额。USD 0.01 无法用于本次真实付款验收。没有提高用户授权金额、创建真实订单、扣款、退款或制造支付成功记录。官方依据：https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts 。

**最新范围：用户已明确“合并即可”，本轮到本地 main 合并为止，不再执行生产停服、迁移、配置激活或新购开放。线上新增字段和已经存在的迁移记录原样保留。**

17:19 的实际只读复核确认生产历史已推进到 `V44__discover_song_publication.sql / -1983576296`；该记录与候选金融 `V44__credit_entitlement_integration.sql / 789542731` 撞号。运行0530 JAR及候选的V1—43 SQL内容一致，但运行JAR不带发现V44，已执行发现V44原件未在已检Git/工作树/发布目录中找到，来源保持UNKNOWN。用户确认新增字段保留，不等于两条不同V44已兼容。**当前候选不得直接用于支付生产发布。** 未重命名迁移、repair、忽略missing或补造V44；后续若要求支付上线，应先统一已执行迁移基线，再重新验收尚未上线的金融迁移编号。

后续已保留并合入并行 main `36c990b` 的8个Agent界面/头像文件。合并后的业务候选为 `2e97f4c038df49b060cd2cac5f95506ccfd1f015`：前端760/760通过，381个完整输入，证据 `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T090041430451Z/evidence.json`。后端输入不变，最终fresh制品逐字节匹配1416项全量实际使用的生产类；制品证据 `.cache/payment-main-integration-20261112/artifact-20261113T090244748744Z/results.json`，JAR SHA `ca395c743f7bee60137a18e848d96faa0a803504257c972ac64ed82a5f1e581f`。该JAR在非空还原隔离库启动通过并已停止，证据 `.cache/payment-main-integration-20261112/jar-restore-20261113T090508017398Z/results.json`。它只暂存于服务器独立的 `stripe-release-staging`，没有切换current；迁移撞号使其保持NO-GO。后续仅发布工具/文档修正不冒充该旧JAR已含新提交。

服务器默认Python3.6.8和Java11不能执行本工具链，另有Python3.11及现网服务Java17可用。已只读核实并为发布脚本增加执行前拒绝门禁；实际使用Java17执行的生产只读计划被 `HISTORY_SCRIPT_OR_CHECKSUM_MISMATCH` 正确阻断，没有执行写入。

## 范围与代码

- 独立候选从本地 main `f77830b` 建立，合入本次实际 fetch 得到的远端 main `d9233b8`；`origin/dev/yth` 已包含。合入后的审查起点为 `f74489a600a6e420e042057301f5e90981367a2d`。最终提交及 JAR 身份以公共仓库 `.cache/stripe-live-release-20261113/delivery.json` 的实际收据为准。
- 原 `codex/google-login` 及其未提交文件不切换、不覆盖。开始时对 776 个非忽略文件保存 SHA-256 保护清单。
- 不重新追溯历史人工积分来源；不删除、改写、归零旧订单或原始流水。`LEGACY_OPENING` 明确表示保留账面权益，不冒充原始充值或历史 FIFO。
- `CreditHistoricalCutover` 新增 carry-forward 计划、导入、显式激活与启动前核验；原严格归因计划保持原拒绝规则。
- `CreditCarryForwardCommand` 是独立 Java 工具，不启动 Spring、不自动迁移、不调用供应商。绑定数据库物理身份、source/plan/JAR SHA、真实停写/恢复/兼容回滚/旧单隔离证据。实际 JDBC 时间解码须与数据库 epoch 一致。
- 负历史余额、账户风险冻结、缓存与流水不一致、消费退回冲突和未来旧订单时间继续阻断。不得伪造历史分摊以凑平余额。
- PayPal、Airwallex 的切换前旧单保留查询，资金操作和自动对账被显式只读边界拦截；已验签回调继续持久化为 MANUAL 并保留操作记录。PayPal 新购暂缓，不关闭整个验签服务。
- 旧单列表仅在确属只读时增加 `legacyReadOnly: true`。默认接口字段保持原契约；前端只对明确的布尔 true 解除旧渠道未决单对新 Stripe 的阻断。Stripe 自身未决单仍需核对。
- Stripe 新购关闭时，可仅允许最多三个已认证 UUID 账号进行后续受控验收；默认空名单。公众开放后不保留隐藏账号例外。不存在未获批准的 USD 0.50 或 USD 0.01 临时报价。
- 正式 USD 100/300/700 积分报价仍为 9.90/25.90/49.90；微信人民币报价仍为 68.80/178.80/338.80，本轮不开放微信。

## 实际执行证据

所有证据路径相对公共仓库根目录，不是候选工作树内部。PASS 仅代表所列实际执行范围。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Git fetch 与功能合入 | PASS | 实际 fetch 从 0530c44 更新 origin/main 到 d9233b8，独立候选 merge 成功；dev/yth 已包含 |
| 第一轮定向 | PASS，55 项、无跳过 | `.cache/payment-main-integration-20261112/maven-targeted-20261113T082632468723Z/results.json`；后续全局 PROCESS/JDBC 时间核验补丁由最终全量覆盖 |
| 第二轮定向 | PASS，70 项、无跳过 | `.cache/payment-main-integration-20261112/maven-targeted-20261113T082923637060Z/results.json`，含整应用 MySQL、Agent 与旧单边界 |
| 第一轮全量 | FAIL，1 项兼容回归 | `.cache/payment-main-integration-20261112/maven-full-20261113T083959338914Z/results.json`；新增默认返回字段违反原断言，已修复默认响应，未改断言 |
| 最终后端 mvn verify | PASS，1416 总项、1408 实际通过、0 失败、0 错误、8 跳过 | `.cache/payment-main-integration-20261112/maven-full-20261113T084912235369Z/results.json`；完整 production 输入在执行前后不变，保存全部实际类与 Java 输入快照 |
| 最终前端 npm test、类型检查与构建 | PASS，758/758、无跳过 | `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T084106353155Z/evidence.json`；376 个完整输入，运行前后不变 |
| 发布编排纯配置契约 | PASS，12/12 | `python -m unittest discover -s isolation/stripe-live-release-20261113 -p 'test_*.py' -v`；不等于 Linux 生产执行验收 |
| 非空隔离数据库备份恢复 | PASS | `.cache/payment-main-integration-20261112/backup-restore-20261113T085505182243Z/results.json`，application r30 至独立 restore r4；不是生产备份恢复 |
| 最终制品与打包启动 | 以最终收据为准 | 没有对应 SHA 和启动收据不得标 PASS，不沿用 00a1ed7 制品 |

8 项未执行的 Redis/Qdrant/RabbitMQ 外部集成测试继续单列未验，不充作成功用例。供应商 mock、H2 和真实 MySQL 分别记录；本轮真实资金与新候选 Sandbox 均不由上述结果替代。

## 正式环境只读结果

- 2026-11-13 16:15 左右重新核验：生产仍为 `0530c444a38db2363e0a6296c52a416b09a14d5f`，JAR SHA `e7193ed14ceb273b3eca1e78798bf4995c11bbabcbb494bff44e9baf105314ce`，实际进程与 current 一致、服务 active；数据库 MySQL 8.0.46、V1—V43。连接强制只读并 ROLLBACK，未读历史订单和积分业务汇总。证据 `.cache/stripe-live-release-20261113/production-readonly-081501598016.json`。
- 16:41 Live GET：商户归属符合、信用卡 active、收款/提现能力开启、待补/逾期资料数量 0；Apple Pay available/on。正式 Webhook 启用且要求的 11 事件齐全。仅证明元数据，签名运行配对仍未验。证据 `.cache/stripe-live-release-20261113/live-preflight-084108680649.json`。
- 同次 GET 的微信配置出现 available/on；这不触发本轮应用开放，也不代替正式审批详情和真实扫码到账验证。
- 没有将密钥写入源码、Git、报告或测试日志；本机已有 Live 密钥不等于服务器已经加载。

## 剩余上线门禁

1. 用同一最终提交构建新 JAR，逐字节核对实际测试类、完整前端、迁移与嵌入来源；合并本地 main 后仍需精确核对远端和服务器候选身份。
2. 最终生产停写、备份、真实本机恢复、旧 writer 排空、V44 独立迁移、兼容开账与显式激活尚未执行。既有隔离测试不能替代这些实际生产步骤。
3. 发布脚本运行前需要真实证据文件；不得填造 PASS、伪造 root PROCESS 观察或仅以任意 SHA 代替已执行恢复。旧版 0530 不兼容新冻结协议，V44 后不得直接回退旧 writer。
4. 先部署关闭 Stripe 新购的兼容版本，再验证有效 Live 配置与回调。用户必须批准一个供应商支持的真实付款范围，才能打开对应验收账号；当前 USD 0.01 不满足该条件。
5. 信用卡与真实兼容 Apple Pay 设备各完成付款，并核对权威交易、签名回调、订单归属/币种、积分只到账一次，才允许向公众开放。未付款、仅 HTTP 200、健康 UP 或 Checkout 创建都不能替代。

发布操作步骤见 `docs/stripe-live-release-runbook-20261113.md`。本记录不声称已经上线或完成资金验收；最终发生的 Git、服务器暂存及清理操作由 delivery.json 单独记录。
