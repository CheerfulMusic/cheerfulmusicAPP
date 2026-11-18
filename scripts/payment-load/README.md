# 支付回调与积分压测

只测试独立应用及隔离 MySQL，不操作主库，不修改支付生产配置。脚本不创建用户、订单、模拟付款或清库；数据库连接只执行 SELECT/SHOW，写入通过真实回调入口及后台处理完成。依赖 asyncio + aiohttp、PyMySQL。

## 准备

1. 使用生产等效的完整迁移版本初始化独立 MySQL 8.0，数据库名以 `_payment_load` 结尾，端口例如 23306。独立后端监听 `127.0.0.1:18080`，明确连接该数据库，不加载 `.secrets/production-main.local.properties`。禁止连接日常 8080、5173、13306 或默认数据库3306；不要为此改动日常主库配置。
2. 启用隔离后台 Inbox worker，确认 Stripe 配置为 sandbox，商户匹配。应用必须运行新版账本（包含 credit_account、p0_credit_batch 和带 merchant_scope/env_scope 的 Inbox）。监控账户须有业务表只读权限和 SHOW GLOBAL STATUS 权限；死锁计数读取information_schema.INNODB_METRICS.lock_deadlocks（MySQL 8.0没有Innodb_deadlocks全局状态变量），要求PROCESS权限；额外锁等待SQL可能需要 PROCESS、performance_schema/sys 的读取权限。
3. 准备102个不同的隔离用户及102笔 PENDING 订单，账户行已初始化。前两个用于50并发重复、乱序；其余100用于不同用户同时支付。用户在测试期间不得有其他积分业务。每笔订单对应正确的 Stripe sandbox Checkout Session、金额、币种、商户、payment intent 与支付事实。准备完后暂停自动回调投递，确保测试前没有到账；本脚本是这批事件的唯一投递方。
4. 每个订单准备一份原始 sandbox `checkout.session.completed` 或 `checkout.session.async_payment_succeeded` JSON。乱序订单再准备独立 event ID 的 `checkout.session.async_payment_failed`、`checkout.session.expired` JSON，均指向同一 Session。必须包含 `livemode:false`。后两项是隔离环境的故障注入事件；不能假装同一实际支付既成功又失败。
5. 渠道查询必须能返回准备好的权威事实：可选真实 Stripe Sandbox（会受供应商限流影响），或在隔离测试专用构建中注入可控 Stripe 网关，保留签名校验、Inbox和数据库事务。不能给生产代码添加跳过验签的开关。伪造一个 paid JSON 并不能让真实渠道查询认可付款；没有匹配渠道事实，测试应失败或等待超时，而不是视为通过。

目前脚本针对本项目 Stripe 接口 `/api/public/stripe/webhook`。PayPal 验签涉及服务端渠道验证，不能复用 Stripe HMAC；后续需要独立的 PayPal Sandbox事件及传输头适配。

## 清单与运行

安装：`python -m pip install -r scripts/payment-load/requirements.txt`（建议独立虚拟环境）。

将私有 manifest 和事件放到忽略提交的 `.cache/payment-load/`。manifest结构如下；`users` 必须填满100项，不能复制相同账号或订单：

```json
{
  "base_url": "http://127.0.0.1:18080",
  "isolated_app_and_db_confirmed": true,
  "scope": {"provider":"STRIPE_SANDBOX","merchant":"acct_YOUR_SANDBOX_MERCHANT","environment":"sandbox"},
  "settle_timeout_seconds": 120,
  "stable_seconds": 5,
  "accepted_http_statuses": [200],
  "duplicate": {
    "id":"LOCAL_ORDER_UUID_A", "owner":"ISOLATED_USER_UUID_A", "credits":100,
    "events":[{"id":"evt_duplicate_a","body_file":"events/duplicate.json"}]
  },
  "out_of_order": {
    "id":"LOCAL_ORDER_UUID_B", "owner":"ISOLATED_USER_UUID_B", "credits":100,
    "events":[
      {"id":"evt_ordered_success","body_file":"events/ordered-success.json"},
      {"id":"evt_ordered_failure","body_file":"events/ordered-failure.json"},
      {"id":"evt_ordered_cancel","body_file":"events/ordered-cancel.json"}
    ]
  },
  "users":[
    {"id":"LOCAL_ORDER_UUID_001", "owner":"ISOLATED_USER_UUID_001", "credits":100,
     "events":[{"id":"evt_user_001","body_file":"events/user-001.json"}]}
  ]
}
```

在 PowerShell 设置以下环境变量（密码与 sandbox webhook secret 从私有配置读取，勿放入仓库或聊天）：

```powershell
$env:LOAD_DB_HOST = '127.0.0.1'
$env:LOAD_DB_PORT = '23306'
$env:LOAD_DB_NAME = 'benchmark_payment_load'
$env:LOAD_DB_USER = 'benchmark_reader'
# LOAD_DB_PASSWORD、LOAD_STRIPE_WEBHOOK_SECRET 通过私有环境注入
python scripts/payment-load/callback_load.py .cache/payment-load/manifest.json --output .cache/payment-load/report.json
```

每轮使用全新订单、用户、event ID。不得通过删除账本重跑。脚本以相同原始body发送50次，按每次发送时刻重签 Stripe-Signature，避免签名过期；不会更改原始JSON字节或自动重试HTTP请求。默认只接受200；只有明确接口契约时才增加其他状态码，不得将任意409/500视为幂等成功。

## 三项断言

- 50并发重复：同订单、同event ID，50次HTTP成功；Inbox只存在一个同作用域event，最终DONE；只有一笔正确的 `CREDIT/payment:<orderId>`、一个权益批次；账户与全量账本一致，余额只增加应得积分。
- 乱序：先等待成功回调入账并稳定，再分别发送失败及取消事件，每次都等待处理并重新验证。最终订单仍PAID，余额仍只增加一次。“且不发积分”指后续失败/取消通知不再发积分，成功事件应发的一次积分仍保留。
- 100不同用户：100个独立订单经同一个启动屏障并发发起；逐用户核对到账，不允许仅比较总余额。连接池100，不在客户端排队串行发送。

报告包括HTTP nearest-rank P99、平均耗时、每请求状态、到账总等待时间、InnoDB锁时间/锁等待次数/死锁次数增量及最终数据库快照。HTTP P99是回调接收耗时，不是到账P99；settled_elapsed_seconds包含轮询和稳定观察窗口，也不是逐订单到账延迟。MySQL死锁增量必须0，即使应用重试后全部返回200，也判失败。计数回退或数据库重启则测量无效。全局计数受其他负载影响，必须使用独占测试实例。

执行 `lock-monitor.sql` 可观察锁等待及最近死锁。测试期间持续采集应用日志，检索1213、1205、Deadlock、Lock wait timeout；SQLSTATE/错误日志应与报告一并留存，HTTP成功不能证明没有内部数据库错误。

## 范围与极限

这三个场景是一轮突发正确性验证，不能单凭100个请求宣布容量极限或高可用达标。使用新fixtures逐级测试并发10/25/50/100/200及持续负载，每级运行至少数分钟、重复多轮，并记录HTTP与到账延迟、错误率、worker积压、线程池/连接池、CPU/内存/磁盘和渠道429。根据事先约定的SLO找出拐点，不把供应商限流误判为数据库瓶颈。

同一event重复只能覆盖事件级去重；进一步还需对同订单使用不同event ID重复成功、不同订单争抢同一渠道付款资源、同用户多订单、验签失败/金额币种不匹配，以及进程在Inbox提交后/账本提交前被终止等情形分别验证。上述扩展不在本次三项脚本通过结论内。

参考：[aiohttp客户端](https://docs.aiohttp.org/en/stable/client_quickstart.html)、[Stripe回调签名与重试](https://docs.stripe.com/events/manage-webhook-endpoints)、[MySQL锁等待](https://dev.mysql.com/doc/refman/8.0/en/innodb-information-schema-transactions.html)。
