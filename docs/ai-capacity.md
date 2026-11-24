# 写词、封面改图和助手对话的并发容量

三条队列分别容纳 1,000 个执行中任务和默认 200 个额外请求。写词包括纯文字和照片写词。封面已由供应商接收、等待生成时仍占封面执行名额，查询和归档阶段各用独立线程池。助手等待下游歌词时保留会话顺序和下游请求标识，释放助手执行名额，拿到歌词后重新取得名额才执行下一步。

入队与作品、积分或对话次数预留由同一数据库事务提交。重复请求使用原请求键读取同一个结果。提交前中断可安全重试，外部付费调用结果不明时会保留原记录。助手实例停止时，如果依赖的歌词已保存，恢复任务会将已保存作品反馈给用户，后续工具动作需用户重新确认。生成中的封面以已记录的供应商任务编号查询，归档重复执行可复用已入库的图片。

## 进程与数据库

各进程使用同一个 JAR 和同一个 V57 主库。现有启动方式默认关闭新队列。启用前检查积分写入模式与 Flyway 版本，按现行发布流程核验 V57 的编号与校验和。与旧版本切换时先暂停受影响入口并排空写词、改图和助手的在途请求，再统一启停新队列，保留未结算的请求和积分记录。

| 进程 | 配置 |
| --- | --- |
| API 实例 | `qingfeng.ai.capacity.enabled=true`，`qingfeng.ai.capacity.worker-lanes=` |
| 写词执行器 | `worker-lanes=lyrics`，`lyrics.start-threads` 初始可按 6 实例 × 200 配置 |
| 封面执行器 | `worker-lanes=cover`，按图片供应商提交速率和峰值设置 `cover.start-threads`、`cover.poll-threads`、`cover.archive-threads` |
| 助手执行器 | `worker-lanes=agent`，`agent.start-threads` 初始可按 6 实例 × 200 配置，并开启 `qingfeng.agent.chat.enabled` |

表里简写的键均位于 `qingfeng.ai.capacity.` 下。各进程每条队列的 `running`、`backlog`、`queue-timeout-seconds` 配置应一致。每实例启动线程默认 32，可根据 CPU、内存、HTTP 客户端和数据库连接池调整，最高 256。JDK 17 上等待歌词期间仍占本实例一个线程，需为这类混合请求留出线程余量。执行器端口保持内网访问，复用已有的任务开关关闭无需重复运行的支付、音乐、快速创作、自动封面、视频和其他后台调度。

使用对象存储让 API 和执行器共享上传素材及归档文件。照片原件经过图片校验后暂存在私有 `qingfeng/ai-inputs/lyrics/` 前缀，终态清理。可在对象存储为此前缀配置生命周期，回收连接中断留下的临时对象。本机离线验证可使用共享目录。MySQL 连接统一时区，使用 `connectionTimeZone=SERVER` 或明确配置两端约定的时区。 [MySQL Connector/J 时区说明](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)

## 供应商配额

- 文本和视觉模型预算按供应商账套共享。可配置 `qingfeng.ai.capacity.text.{qwen|openai|ark}.account`、`rpm`、`tpm`、`daily-tokens`。自定义模型网关用 `qingfeng.ai.capacity.text.endpoint-groups.<域名中的点替换成横线>` 指定组。相同供应商账套的节点使用相同标识和配额。发送每次 HTTP 调用前按分钟和日窗口预留，收到供应商 token 用量后结算。
- 独立 KIE 封面账号使用 `qingfeng.providers.kie.image-api-key` 和 `qingfeng.providers.kie.image-rate-limit.{scope,creates-per-ten-seconds,reads-per-second,max-wait-seconds}`。同账号的旧配置继续使用共享速率限制。封面短期需要频繁轮询时，预留每个已接收任务的读请求和结果下载带宽。
- KIE 回调由 `qingfeng.ai.capacity.cover.callback-base` 和 `callback-secret` 开启。配置可访问的 HTTPS 源站、共享随机签名密钥，安排路由到 API 实例。回调只唤醒状态查询，状态和下载地址由服务端主动按供应商任务编号确认。查询仍会处理回调丢失或提前到达。 [KIE 任务查询文档](https://docs.kie.ai/market/common/get-task-detail)

1000 并发是同时执行任务数。估算配额时先测每次外部调用耗时，按 `每秒所需调用数 ≈ 并发数 ÷ 平均占用秒数 × 每任务调用次数` 换算。助手多步规划和歌词质量校验会增加调用次数，图片出图耗时也影响 1,000 个在途任务所需的每秒提交量。配额和各角色的副本数在端到端压测后确定。

## 观察与验收

Micrometer 指标 `qingfeng.ai.capacity.queued`、`executing`、`unknown` 按队列分别记录队列深度、占用的执行名额和保留的未确认结果。执行器记录阶段耗时和完成结果。API 的助手 SSE 批量读取会话游标，按变化推送，浏览器可按原请求键读取最终结果。代理允许 SSE 请求保持连接并关闭该路由的响应缓冲。

`AiCapacityQueueTest` 的 MySQL 用例要求独立实例监听本机 57857，提供该实例的 UUID（`-Dqf.ai.isolated-mysql-uuid`），验证 3,000 个同时占用的执行名额、超额排队和名额归还。加 `-Dqf.ai.mock-workers=true` 可用独立执行器和离线供应商保持三条队列同时满载。普通回归测试跳过仅在显式启用时运行的 MySQL 用例。`AiCapacityHttpTest` 使用隔离 H2 和装配好的离线模型，覆盖入口、回调、幂等与跨队列等待。

真实供应商和并发连接验收使用隔离账号和素材，逐档升到每条队列 1,000 个同时执行任务，检查结果成功率、P95 延迟、排队与拒绝分布、重复扣费、MySQL 锁与连接池、执行器重启及 SSE 连接。测试期间持续观察旧业务入口的可用性。
