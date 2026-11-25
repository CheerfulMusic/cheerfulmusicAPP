# Agent 创作确认与记账验收

2026-11-25。在独立工作树上测试「打磨歌词」「生成歌曲」「预览 MV」。验收从对话 HTTP 接口发请求，在同一合成账户下读取任务、歌词请求、MV 项目及积分账本。模型、歌曲供应商和音频时长探测使用测试替身。

## 核对的版本

| 对象 | 提交 | 依据 |
| --- | --- | --- |
| 较早版本 | `542ae62e` | 修复打包后的 Vue 入口识别，是本次开发基线的祖先 |
| 本机日常运行版及本地保存的发布清单 | `caf64e3a` | 两份清单的 `sourceCommit` 一致，本机 8080 和 5173 已在监听 |
| 开工时拉取的远端 main | `caf64e3a` | `git fetch --all` 后固定 |
| 独立工作树的起点 | `1f16b3a5` | 本地 main 包含远端全部提交和另外 8 个提交。与 `542ae62e` 相比多 45 个提交，包含 MV 只读预览及歌曲状态修正、显示对象绑定和异步队列 |
| 模型回退与播放器联调基线 | `f3b99d76` | 纳入并行开发的 `bc1b0065` 模型超时回退和 `b64b127f` 播放器配色，在同一副本重跑回归 |
| 功能提交的父提交 | `9da085fc` | 包含并行开发的公开作品与搜索页面更新 |
| 合并功能时的本地 main | `aea02cc4` | 在 `9da085fc` 之上只修改了播放器动画 `GalaxyScene.vue` |

候选提交的完整文件清单可用 `git show --name-status <候选提交>` 查看。日常服务、线上部署及账户账本均未用于验收。

## 请求和业务边界

- 现有消息接口接收原始 `text` 和 `displayedContext`。三个入口发送 `creationAction`、`workId`、`revisionId`，预览携带选中音频，菜单选择另放入 `creationChoice`。旧版中文和英文按钮文案仍可解析。
- 服务端按登录身份回查当前作品、歌词、音频。询价返回价格，取消结束本次操作。缺少对象、目标不一致或描述有歧义时，助手请用户选定版本或明确操作。MV 预览返回分镜和报价，规划模型离线时按选中音频的时长提供基础分镜。
- 报价随现有会话保存。服务端生成确认编号，记录动作、作品、歌词版本、作品版本及积分，MV 另绑定音频和制作参数。服务端在确认前复核有效期、当前对象和金额，在歌词或歌曲实际记账时再核对一次。旧未定价的待确认操作需要重新报价。歌词打磨计入该作品已有的免费次数。
- 同一报价的确认在同步和排队入口使用同一业务键。歌曲任务冻结用户确认的歌词版本与编曲参数，重放可取回原结果。模型给出的步骤和客户端的聊天付费标志都不能充当创作确认。查询「歌曲呢？」读取真实任务和音频。会话遗漏任务编号时，从选中作品和歌词版本查找已有任务，保留用户明确选择的音频。

## 复测命令

在独立工作树分别从对应子项目运行。Maven 依赖已缓存于 `D:/maven`。专项测试使用随机端口和内存 H2。完整 Maven 验证还使用了仓库金融回归要求的专用 MySQL 8.0.46 实例和刚构建的前端静态文件。

```powershell
# Qingfeng_AI_Music，改动前的 Agent HTTP 基线
mvn.cmd -o "-Dmaven.repo.local=D:/maven" -pl qf-server -am "-Dtest=AgentChatFlowIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

# Qingfeng_AI_Music_VUE
npm.cmd ci --prefer-offline --no-audit --no-fund --cache D:/it_ai/codex-build-cache/npm
npm.cmd test -- --maxWorkers=4 --testTimeout=15000
npm.cmd run typecheck
npm.cmd run build

# Qingfeng_AI_Music，改动后的请求、账本、并发和队列验收
mvn.cmd -o -B -ntp "-Dmaven.repo.local=D:/maven" -pl qf-server -am "-Dtest=AgentChatFlowIntegrationTest,AgentChatServiceTest,AgentPlanServiceTest,AiCapacityHttpTest,ModelTimeoutFallbackIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

# Qingfeng_AI_Music，完整回归，在临时 MySQL 测试环境中执行
mvn.cmd -o -B -ntp "-Dmaven.repo.local=D:/maven" verify
```

本机完整回归的外层命令为 `python .cache/phase1-verify/verify.py D:/it_ai/qf-daily/.cache/payment-mysql-8046-20261111/mysql-8.0.46-winx64 verify`。它使用现有 MySQL 程序，在独立工作树内新建数据目录和测试账户，校验实例身份后运行 Maven 并关闭实例。专项命令可单独复跑。

改动前 HTTP 基线 22 项通过。最终专项验收 110 项全部通过，包含 29 项真实 HTTP 与数据库测试、3 项异步容量测试和 2 项回环模型回退集成测试。前端 136 个测试文件的 1,143 项全部通过，类型检查和生产构建通过。后端完整 `mvn verify` 成功生成 JAR，三个有测试的模块共统计 2,152 项，其中 2,076 项通过，76 项按环境或测试条件跳过，失败和错误均为零。

## 隔离库证据

每组场景使用独立合成账户，歌曲失败回调沿用刚提交的那笔任务。第一列的场景名也是 `AgentChatFlowIntegrationTest` 输出的 `PHASE1` 标记。扣费、退款栏依次为笔数和积分合计。

| 场景 | 歌曲任务 | 歌词请求 | MV 项目 | 创作扣费 | 创作退款 | 聊天扣费 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `read-only-and-rejected` 生成改询价、取消、缺少对象、外账户版本、缺少确认 | 0 | 0 | 0 | 0 | 0 | 0 |
| `changed-or-expired-quote` 歌曲改价 5 → 7、报价过期、切换歌词版本后确认 | 0 | 0 | 0 | 0 | 0 | 0 |
| `polish-without-target` 只有灵感、没有歌词版本 | 0 | 0 | 0 | 0 | 0 | 0 |
| `planner-omitted-mode` 规划模型建议改词，省略模式或误报新建 | 0 | 0 | 0 | 0 | 0 | 0 |
| `mv-preview-offline` 选中导入音频，仅要分镜与报价，规划模型不可用 | 0 | 0 | 0 | 0 | 0 | 0 |
| `confirmed-and-replayed` 并发确认并重发，旧客户端换消息编号，再模拟会话遗漏任务指针 | 1 | 0 | 0 | 1 / −5 | 0 | 0 |
| `duplicate-failure` 同一任务重复报告失败 | 1 | 0 | 0 | 1 / −5 | 1 / +5 | 0 |
| `polish-free-then-paid` 同一作品先免费打磨，再确认付费打磨并重发 | 0 | 2 | 0 | 1 / −2 | 0 | 0 |
| `read-only-paid-chat` 聊天免费额度用尽后只询价并重发 | 0 | 0 | 0 | 0 | 0 | 1 / −1 |

测试按 `music_work.owner_id` 联查 `generation_task`、`lyrics_composition_request`、`music_video_project`。积分直接查 `credit_ledger`，使用 `entry_type IN ('DEBIT','REFUND')`，入账种子积分不计入。聊天积分使用 `reference_id LIKE 'agent-chat:%'` 单列。用尽免费额度的合成账户获准购买 5 次聊天，询价和重发后还剩 4 次。歌曲任务与扣费通过同一确认编号关联，下面列出脱敏字段。

```text
generation_task.work_id      = <作品 A>
generation_task.revision_id  = <确认的歌词版本 R>
generation_task.request_key  = <服务器签发的确认 Q>
credit_ledger.reference_id   = music:<作品 A>:<确认 Q>
同一 owner 和 reference_id 下的流水为 DEBIT −5、REFUND +5，各一行
重复失败后 status = FAILED，MUSIC 预留记录剩余 0
```

排队模式还从真实消息结果接口轮询同一报价，并验证旧客户端使用另一消息编号依然只增加一版歌词。模型回退测试用回环 HTTP 模拟主模型超时和备用模型提议生成，核实确认前任务数为零、确认后只创建一项任务。真实模型和音乐、视频供应商的生成效果未测。

## 改动文件

| 所在位置 | 文件 |
| --- | --- |
| 前端 `src/modules/agent` | `pages/AgentStudioPage.vue`、`composables/useAgentConversation.ts`、`model/creation-tools.ts`、`model/conversation.ts`、`model/assistant-result.ts` |
| 前端测试 | `tests/agent-studio-page.test.ts` |
| qf-server 对话、队列和 HTTP | `agent/AgentCreationRequest.java`、`agent/AgentChatService.java`、`agent/AgentChatExecutor.java`、`agent/AgentCapacityHandler.java`、`agent/AgentSongStatus.java`、`config/AgentConversationChatConfiguration.java`、`web/app/AgentChatController.java` |
| qf-business | `agent/api/AgentTurnStore.java`、`agent/application/SongCardProjector.java`、`agent/application/handler/AgentCreationSupport.java`、`GenerateLyricsHandler.java`、`GenerateSongHandler.java`、`credits/ConfirmedCreation.java`、`generation/api/GenerationApi.java`、`generation/application/GenerationService.java` |
| qf-infrastructure | `persistence/agent/JdbcAgentTurnStore.java`、`persistence/usage/JdbcUsageBudget.java` |
| 后端测试 | `AgentChatFlowIntegrationTest.java`、`AgentChatServiceTest.java`、`AgentPlanServiceTest.java`、`AiCapacityHttpTest.java`、`config/ModelTimeoutFallbackIntegrationTest.java` |

## 回滚

在本地 main 中运行 `git revert <本次功能提交>`，复跑专项测试。此改动使用既有表与索引，没有 SQL 迁移。已有账户流水继续由原有业务机制处理。再次切换日常运行版本或发版需要另行授权。
