# 正式收款执行记录

日期：2026-11-13，Asia/Shanghai。**当前NO-GO：已推进本地修复、真实隔离验收及生产只读核对，尚未部署或开放正式收款。**

本轮执行依据是公共仓库根目录 `.cache/payment-go-live-reaudit-20261113/payment-go-live-audit-and-execution-plan-20261113.md`。实现基于已合并的钱包版本 `30e6374e13a06ac5f5f068e47e97ddf78a809279`，位于独立工作树 `.cache/worktrees/payment-go-live-20261113`；本轮全量及V44隔离恢复已通过，最终提交、main合并及fresh制品身份仍以最终交付收据为准，不能沿用上轮制品。

本文所有 `.cache/...` 证据均相对公共仓库根目录，而非工作树内部。VERIFIED表示有实际执行证据；CODE_CONFIRMED表示只确认代码；BLOCKED/NOT_TESTED均不代表通过。

## 1. 生产与账户的实际核对

| 项目 | 实际结论 | 证据与限制 |
| --- | --- | --- |
| 生产运行提交 | VERIFIED：`0530c444a38db2363e0a6296c52a416b09a14d5f` | `.cache/payment-go-live-execution-20261113/production-readonly-20261113T060232657215Z.json`；current与实际进程打开JAR一致，active、NRestarts=0，不等于全部业务健康 |
| 生产JAR | VERIFIED：SHA-256 `e7193ed14ceb273b3eca1e78798bf4995c11bbabcbb494bff44e9baf105314ce` | 仍无StripePaymentService及JdbcFencedCreditsLedger，不能仅配置密钥就获得本轮收款功能 |
| 生产数据库 | VERIFIED：MySQL8.0.46、qingfeng_music、既有已确认实例，V1—V43 | 本轮读连接transaction_read_only=1，结束ROLLBACK/退出；不是隔离测试库，也没有安装V44 |
| 生产Stripe配置观察 | 所检已知属性和进程命名环境变量中未发现三类Stripe密钥 | 只代表所检来源，不冒充完整Spring有效绑定；本机密钥存在不代表服务器已经加载 |
| Stripe正式账户 | VERIFIED_GET_ONLY：GB、charges_enabled=true、payouts_enabled=true、card_payments=active，当前所检due/past_due为0 | `.cache/payment-go-live-execution-20261113/live-preflight-20261113T061814625622Z.json`；仅GET，不是收款或资金结算验收 |
| Stripe正式Webhook | 目标正式端点启用、Live、11类要求事件无缺失，API版本2026-10-28.endive | 同上；**签名密钥实际配对、服务器有效加载及真实签名POST链路仍NOT_VERIFIED** |
| 微信支付资格 | 所检Live配置available=false、display_preference=off | 不能把人民币Checkout测试会话创建成功写成微信商户已开通，不自动修改后台 |
| PayPal Sandbox认证 | BLOCKED：当前所检凭据OAuth返回401 | `.cache/payment-go-live-execution-20261113/paypal-sandbox-auth-readonly-20261113T064254000864Z.json`；没有调用Live或执行资金操作。先前成功记录仍保留，不要求重开账户或重复原付款；本次新增退款/争议回归尚缺正确Sandbox访问条件 |
| 远端Git核对 | GitHub已认证连接器读到remote main为0530c44、dev/yth为b2140dc；fetch仍BLOCKED | 本轮3次原生fetch网络失败，连接器读取不能代替AGENTS要求的完整fetch门禁；不将其标PASS或据此推送上线 |

生产只读继续使用已获批准的应用账号例外并强制只读事务；该账号本身仍有写权限，不能标为最小权限只读账号PASS。源码当前core迁移合同与生产历史的核对，不自动证明所有旧迁移执行作业、人员或原始发布目录已经溯源完成。

## 2. 真实历史盘点及不能自动裁决的事项

证据：`.cache/payment-production-inventory-20261112/session-inventory-20261113T062234872324Z.json`，只读一致性快照。

- 42个有journal的owner，journal净额5369，负余额样本0。**5369是该时刻账面积分，不是营业收入，不是未来可硬编码的切换金额。**
- 30个有journal的owner没有credit_account缓存行。旧实现支持懒初始化和从journal读取余额，因此这不是已确认积分丢失；仍须逐owner核对后建立正确开账投影，禁止把缺行直接当0或自动改真实余额。
- 4笔人工增加积分共3390，原始引用和owner仅以脱敏别名输出。来源、业务批准和开账处理尚未由用户确认，不能归并成已核实充值收入。
- 先前7笔历史映射中5笔匹配、2笔未匹配。已匹配记录的本地CANCELLED、没有capture或没有到账流水，都不证明远端未付款；2笔未匹配也不允许猜删。历史404及远端资金状态继续UNKNOWN。
- 缺少所需表列的旧模型检查明确NOT_RUN/UNKNOWN，不把“没有查到”报告成异常数量0。

本轮没有删除订单、真实用户积分或历史流水，没有把生产原始业务数据复制进测试库。

## 3. 计划项落实与剩余边界

| 编号 | 本地处理与代码位置 | 当前边界 |
| --- | --- | --- |
| PAY-01 | canonical core1—43加金融V44已在前序整合完成；本轮保留所有正式SQL原件，校验和不变 | 生产仍V43；不能由应用自动迁移/激活V44，也不repair旧冲突历史 |
| PAY-02 | `JdbcFencedCreditsLedger.paymentDispute` 持久化首次LOST与退款重叠的UNKNOWN事实和订单级剩余权益冻结，原观测写append-only audit；重复去重，其他订单不冻 | 模糊重叠不猜永久净损失；已有永久损失不清除。人工权威净额裁决及PAY-06仍未闭环，详见[重叠契约](payment-dispute-overlap-resolution.md) |
| PAY-03 | `StripeCheckoutGateway` 已将存量身份读取与新购charges能力分离，新购预检先于本地落单 | 非法账户/环境仍拒绝；实际商户暂停场景的真实供应商验收不能用mock替代 |
| PAY-04 | `StripePaymentService.enqueueOrderReconciliations/reconcileOrder`、`PaymentInboxRunner` 增加有界持久订单对账，保留创建幂等身份、未知结果和人工核查 | 不把远端404当未付款，不对超出安全恢复窗口的未知创建盲目重POST；同候选真实漏回调/未知恢复仍待验 |
| PAY-05 | `StripePaymentService.reconcileRefund` 将已证实的终态失败及原生本金反转与商户payout可用性区分 | 必须有完整同订单/退款/交易反转证明；只收到failed或缺proof仍等待。Pending商户结算不再单独延迟已证反转的权益恢复；当前候选真实Sandbox反转仍需补证 |
| PAY-06 | 保留订单级REVIEW及权威资金证明门禁，不以管理员RELEASE直接解冻或返分 | **BLOCKED**：PayPal实损、资金返还及受控REVIEW解除没有取得完整闭环验收，不能开放新购后依赖人工随意改表 |
| PAY-07 | 新增`PayPalCaptureRecoveryProof`及服务适配，区分原始成功capture和后续部分/全额退款，双权威GET绑定商户、订单、owner及原金额 | 退款明细仍独立核对，未知部分不猜；PayPal Sandbox401使当前候选真实乱序资金验收BLOCKED |
| PAY-08 | 新增`CreditHistoricalCutover`：只读盘点、不可变SHA计划、PREPARING隔离导入、原DEBIT/REFUND映射及完整旧journal hash重放适配 | **真实历史切换BLOCKED**；工具仅允许专用隔离库，不能作为生产导入器；负历史余额、支付归因缺失和未批准人工来源拒绝，详见[开账契约](payment-historical-cutover-contract.md) |
| PAY-09 | 新真实不同owner/不同nativeID竞争复现了MySQL1213；缺行读取改为不争抢其他owner空区间，并保留owner首锁、原生身份唯一绑定和真实竞争断言 | 最新定向通过只证明所测竞争矩阵，不宣称任意负载绝无死锁；仍需生产监控与Inbox可恢复 |
| 新P1时钟 | 历史r1真实发现DB时间与应用Instant写入相差8小时；工具要求实际UTC，新增`FinancialClockStartupGuard`校验实际连接UTC、JDBC解码epoch与应用时钟 | 启动检查及完整应用UTC正负例已纳入定向；正式连接必须显式强制UTC，不只设置serverTimezone标签；不改旧时间掩盖差异 |
| PAY-10 | 已完成Live账户/事件GET预检及本轮离线/隔离回归 | 当前候选真实Stripe/PayPal Sandbox、Apple Pay真机、微信扫码及生产签名业务闭环仍未完整验收 |

退款按累计实际有效成功金额计算并向上取整；争议只冻结订单剩余权益；未知损失不永久扣；负积分统一禁止消费；USD旧价格和历史快照不变。微信CNY报价仍为68.80/178.80/338.80，未开通时不开放购买。

## 4. 实际执行结果与失败证据

| 范围 | 状态 | 真实证据与限制 |
| --- | --- | --- |
| 定向联合回归 | PASS，201/201，0失败/错误/跳过 | `.cache/payment-main-integration-20261112/maven-targeted-20261113T063547592260Z/results.json`；含真实隔离MySQL权益、历史开账/并发、完整应用以及离线供应商proof；供应商mock不代表Sandbox |
| 原27+11项独立模型 | PASS，各自27、11 | `.cache/payment-go-live-execution-20261113/original-model-tests-20261113T063925242520Z.json`；无数据库/供应商调用 |
| 原14边界 | PASS，14/14 | `.cache/payment-go-live-execution-20261113/original-14-boundaries-20261113T064312114482Z.json`；网络禁止，不替代MySQL |
| 前端完整输入及构建 | PASS，745/745，0失败/跳过；372输入不变，类型检查和构建通过 | `.cache/payment-main-integration-20261112/frontend/complete-acceptance-20261113T064226322782Z/evidence.json` |
| 首轮编译 | FAIL，实际0项测试 | `.cache/payment-main-integration-20261112/maven-targeted-20261113T062133685131Z/results.json`；JUnit Boolean重载歧义已精确修复，不降低断言 |
| 次轮定向 | FAIL，195项，1失败/3错误 | `.cache/payment-main-integration-20261112/maven-targeted-20261113T062548435664Z/results.json`；真实1213及历史时区边界均保留证据，修复后由201轮重验 |
| 首轮全量 | FAIL，1368项、8失败、0错误、8外部skip | `.cache/payment-main-integration-20261112/maven-full-20261113T063925268029Z/results.json`；7项SPA404、1项分享503。新工作树未构建dist及稀疏媒体源遗漏，未改期望或删测试 |
| 全量重验 | PASS，1368总项、1360实际通过、0失败/错误、8外部skip | `.cache/payment-main-integration-20261112/maven-full-20261113T064621213453Z/results.json`；本轮682个production class及362个Java精确输入快照已保存。12个媒体源以CreateNew从固定30e Git blob恢复并核对，见`.cache/payment-go-live-execution-20261113/frontend-sparse-materialization.json`；构建后真实重跑，不复用失败轮结果 |
| 当前V44非空备份恢复 | PASS，限定虚构隔离数据 | `.cache/payment-main-integration-20261112/backup-restore-20261113T065105489096Z/results.json`；application r27→restore r3，源库不写，表结构/数据比较通过；不是生产备份恢复，也未启动生产服务 |
| 最终fresh制品/启动 | 以最终交付收据为准 | 不沿用V41/r6恢复或上轮30e JAR代替本次新代码。最终JAR、682类、362个Java输入、完整前端和迁移须绑定本次通过测试的同一提交；缺少该收据仍不能放行 |

外部Redis3/Qdrant1/Rabbit4缺隔离资源的skip仍单独BLOCKED，不计PASS。测试数量不相加为独立资金场景数。最终提交、制品、打包启动及工作区保护状态在生成后以公共根`.cache/payment-go-live-execution-20261113/delivery.json`为准；该收据产生前不预写其PASS、提交或SHA。

## 5. 当前发布判断及下一步

当前只能确认若干本地缺口已修复、限定隔离矩阵通过；**没有达到正式收款放行条件**。顺序如下：

1. 将已通过的本轮全量、V44非空恢复与最终fresh制品/启动绑定到同一提交；本地合并不代表运行已更新。
2. 保留和复用先前已核实的Sandbox成功订单及事件证据，具备正确访问条件后，在当前最终候选验证新增退款/争议等必要资金场景；不要求为重复已通过项目重开账户或重复付款。无法通过的首期渠道不开放新购，但保留存量接收和查询。
3. 完成PAY-06权威处理路径与真实历史人工例外批准；形成可执行、经过脱敏/隔离dry-run验证的生产专用开账、旧writer切换和恢复方案。
4. 逐项审阅[生产变更申请](payment-live-change-request-20261113.md)，门禁全部通过后才进入具体生产窗口。
5. 实际Live业务健康及真实资金验证另按明确用户、套餐、金额上限和退款范围执行，不自动创建真实交易。

本轮已进行生产只读数据库核对及Stripe Live GET预检；**生产数据/配置写入、生产迁移、服务重启、Git推送、部署、正式扣款或退款均为0**。不关闭现有PayPal Live，不修改OSS/CDN/RAM，不清日志、旧测试数据或用户文件。
