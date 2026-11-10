# 本地稳定性修复、可信构建门禁与新生产准备

> 后续执行更新（2026-11-10）：以下原文为初轮快照，其中“未提交/构建未运行”等描述不代表最新状态。本地已提交5fba33c1，并在847fe4a0合并刚核实的现网685ecc6d微信兼容修复、固定Node24.22.1官方校验工具链。847fe4a0的真实前端403项测试及构建、后端396项测试与mvn verify已通过，制品位于公共仓库.cache/b/720a391f/package。新增Java新购门禁另见[专项说明](paypal-new-purchase-gate.md)。
>
> 现网只读核对：source及JAR嵌入source均685ecc6dd53ee878737f294f58d754a012ed1366；JAR SHA256为4e027727e4b95a2296868f6db00cde6e4325ab1d6ade45004a95907fc2ed3dc7。本轮未执行该线上切换。原3d9ffac1审计证据保持历史含义。
>
> 用户再次选择“保持只用生产库，数据库验收继续阻断”。因此不建隔离MySQL，不向主库写测试数据，不能将总体治理或生产发布标PASS。V27/V28原件搜索扩展到114个提交树和444个悬空文本对象仍未找到；不补造、不迁移。最终候选执行结果以公共仓库.cache/production-closure内证据为准。

> 2026-11-10（Asia/Shanghai）。本轮只修改本地main工作树；未提交、推送、部署、重启或连接生产。
> 当前开发目录codex/google-login的201个已有修改/未跟踪文件保护；修复位于仓库内.cache/worktrees/welcome-credits-20261106。
> main HEAD仍为3d9ffac1d83f54f8768603fe78220e88d55624ed，改动尚未提交。本地运行版本和线上运行版本均未更新。
> VERIFIED限定本轮真实执行范围；CODE_CONFIRMED是代码证据；INFERRED/UNKNOWN不得升级成真实业务通过。

## 1. 已修复的明确问题

| 项目 | 修改与范围 | 证据/限制 |
|---|---|---|
| 自愈改配置/整站重启/假成功 | scripts/media_health_check.py删除自动配置切换和systemctl路径；兼容函数始终拒绝动作，不返回恢复成功 | VERIFIED：实际候选函数的离线回归；没有执行生产自愈 |
| 匿名源站被当可用性兜底 | 签名CDN探测与匿名拒绝探测分离；匿名200/206为安全失败，403正常，404不是权限证明 | VERIFIED：内存HTTP替身；真实Bucket/CDN仍UNKNOWN |
| 意外执行生产探测 | 默认不读配置/DB/HTTP；执行需显式--run、--props、--state-dir、--log-dir和已存在的绝对目录 | VERIFIED：默认/缺路径/锁冲突测试；实际DB/HTTP未执行 |
| 探测重复触发 | OS文件锁，不删除活锁inode；固定单次HTTP/DB超时，无无限重试 | VERIFIED：Windows实际文件锁测试和超时分支；Linux锁与真实超时未测 |
| 构建停止运行实例 | build-local.ps1移除stop-local，后端build.ps1转入统一可信构建入口 | VERIFIED：真实入口拒绝发生在构建/停进程前；没有停任何运行实例 |
| 旧配置和主库默认回退 | application.yml只保留classpath默认资源；application-mysql.yml删除旧相对目录/全局文件导入，URL必须显式提供，无3306默认地址 | VERIFIED：SnakeYAML真实解析与策略断言；Spring实际启动NOT_RUN |
| 制品来源/前后端/迁移漂移 | 外层和嵌套JAR完整性、重复/危险路径、嵌入commit、前端字节与迁移字节对比；契约需独立审批哈希 | VERIFIED：正常/损坏/篡改/旧SQL/旧前端等回归；不是支付或MySQL验收 |
| 回滚绕过门禁 | verify-release.py不再因legacyRollback直接返回；必须制品契约和schema证据审批 | VERIFIED：回归拒绝；此变更未安装到现网 |
| Windows深路径构建失败 | 导出使用仓库公共缓存短目录.cache/b/<id>，提取前检查路径预算 | VERIFIED：完整导出/封装流程的离线替身测试；真实npm/Maven构建仍BLOCKED |

音乐、歌词、积分、支付Java业务、作品/分享存储标识没有修改。并行看板和歌词积分开发文件没有覆盖。

## 2. 实际验证结果

入口：
~~~powershell
python -B -X utf8 scripts/verify-local-stability.py
~~~

最终证据目录：[20261110-192553](../.cache/stability-hardening/20261110-192553/summary.json)。

- VERIFIED：30项稳定性/构建策略回归 + 8项发布guard回归，共38项通过。测试子进程拒绝socket、子进程启动、系统命令和私有.secrets/旧etc配置读取；外部API均为内存替身。
- VERIFIED：12组本地验证检查无失败，包括上述两套测试、源码阻断、默认监控阻断、PowerShell解析、两种启动入口阻断、构建入口阻断、Java YAML编译/解析及两种真实本地旧缓存制品拒绝。
- 负向入口的PASS指“确实按预期拒绝”，不表示构建、启动或生产发布PASS。
- VERIFIED：SnakeYAML2.6和Java17直接解析候选YAML，仅加载源文件，不启动Spring，不连接数据库。
- VERIFIED：此前无法读取的本地缓存JAR被拒绝；旧production-main缓存也不能满足新契约。未重新下载、覆盖或删除缓存。
- UNKNOWN/BLOCKED：完整npm test/npm build/mvn verify的真实流水线没有运行，因为候选尚未提交，源门禁按设计阻断。完整导出构建流程仅以替身验证命令顺序、离线参数、环境清洗和封装，不把它写成真实编译。
- UNKNOWN/BLOCKED：真实MySQL、Sandbox、私有回源、冷/热缓存、长播放、退款/争议和生产业务仍未验收；V27/V28仍UNKNOWN。
- 未安装新依赖、未启动Docker、未创建数据库或云资源。没有用H2替代生产MySQL验收。
- 开发中的失败记录未作为最终通过使用；192325版本的测试曾定位Windows导出路径问题，修复后192553为最终结果，旧证据保留。

最终38项是本地代码/契约层结果，不是I层环境成立或P层资金验收。

## 3. 可信构建入口与信任边界

支持的main入口为scripts/build-local.ps1及Qingfeng_AI_Music/scripts/build.ps1；后者委托前者。手工mvn/java不能算受支持的正式发布入口。

scripts/release_gate.py实现：
1. source：工作树含未跟踪文件也必须干净，候选包含已登记生产基线；不stash/reset/clean，不自动提交。
2. build：先源门禁，再git archive冻结源码到仓库内新短目录。不复制未跟踪文件、.secrets、旧dist或旧配置。
3. 清洗子进程环境，仅传操作系统/工具链必要信息；独立home、npmrc、Maven settings与仓库缓存；npm ci --offline --ignore-scripts，Maven -o；不自动联网补依赖。
4. 顺序执行frontendTest、frontendBuild、backendVerify，失败保存日志并阻断；Java17校验，npm engine-strict按包声明检查Node。
5. JAR中的迁移SHA必须与冻结源码逐字节相同，前端必须与本次dist/后端静态资源相同；不根据文件名认最新版。
6. 输出app.jar、source-commit.txt、release-contract.json；记录制品SHA、前端树/字节摘要、SQL摘要、真实命令日志SHA。
7. productionReady始终false；构建工具不能生成生产批准。没有MySQL/业务/资金证据不能转为正式发布PASS。

当前源码有本轮未提交修改，故真实build被正确拒绝。延续本轮“不提交、不推送”的约束，不为通过门禁擅自提交。
历史baseline.json只是一份已知基线约束，不声称本轮已重新核对服务器。

**CODE_CONFIRMED｜信任边界**：契约自身可被编辑，不能自证可信。可信锚点是独立、应用不可写、经人工审核的审批清单中JAR/contract/schema证据哈希。日志SHA只能发现后续变更，不替代原始执行真实性或审批。文件系统权限和真实数据库schema证据仍需新环境验收。

## 4. 启动门禁

main的start-main.ps1在创建生命周期状态、读取私有properties、准备线上gateway、SSH和Start-Process之前先运行startup检查。start-local.ps1兼容入口委托同一检查。

只读检查：
~~~powershell
pwsh -NoProfile -File scripts/start-main.ps1 -CheckOnly
~~~
当前实际结果BLOCKED：缺有效契约/兼容证据，未创建生产连接或应用进程。

实际运行还要求独立的AllowProductionConnection显式意图；这个开关不能跳过制品、前端和schema检查，不代表部署授权。
运行清单新增必须项：
- artifactContractSha256：独立核准的release-contract.json哈希。
- jarSha256/sourceCommit：必须与契约及JAR嵌入值匹配。
- schemaCompatibility.status=VERIFIED、对应jarSha256、evidenceSha256：必须指向实际审核的数据库兼容证据，不能为V27/V28填假的VERIFIED。
- frontendRoot必须是匹配契约且干净的main前端树；后续既有入口检查仍有效。

根目录当前codex/google-login里的旧启停文件属于用户未跟踪/并行内容，未覆盖；它们没有自动获得这套新门禁，不能将该开发分支当正式运行入口。新门禁目前只在所链接main工作树中实现。
现网systemd/guard完全未修改。新guard不兼容旧无契约/无schema证据的包，禁止直接热替换现网guard导致旧版无法启动；必须先准备并验证成套新制品及兼容回滚包。

## 5. 新生产环境准备包

见[准备说明](../Qingfeng_AI_Music/deploy/next-production/README.md)与[阻断清单](../Qingfeng_AI_Music/deploy/next-production/readiness.json)。

已生成六份禁用材料：说明、readiness、application.properties.example、runtime.env.example、
独立systemd示例、空release-policy示例。未填实际凭据，未创建资源，未启用服务或DNS。
- 独立/opt/qingfeng-music-next、/etc/qingfeng-music-next、/var/lib/qingfeng-music-next。
- 初始loopback18080，无公网放行；新环境Java17/Python3.12。
- 显式新数据库/OSS变量，无生产目标回退；调度、Flyway、SQL初始化、PayPal、CDN、OAuth初始关闭。
- 新服务ExecStartPre使用应用不可写的外部guard与审批清单；私有配置/额度/邀请码显式加载。
- 实际NEXT_*身份、Bucket、回源、域名、MySQL版本和GRANT均UNKNOWN，不能从模板名称推定隔离成立。

用户当前仅用生产库；本轮不新建或复用测试库。未来新库上线需另批原始迁移、快照、水位、账本核对和恢复演练，不能“先换库再看”。
香港桶与现有media域按生产保护，北京桶未经证明不使用。0.10美元历史付款反馈不是退款/争议验收，六笔404不结案。

## 6. 仍然阻断的事项与后续顺序

1. 用户审阅本地main修改，再决定是否授权提交；当前不提交、不自动推进构建。
2. 取回V27/V28原SQL和执行来源；明确未来新库目标与账号权限，不能repair/补造。
3. 可信干净源码上实际跑前后端全流程，缺离线依赖则BLOCKED；记录真实制品/契约/工具链和失败日志。
4. 提供获批的专用资源与受控测试条件后执行真实数据库、支付、媒体及跨模块回归；当前用户数据库约束不变时相关项继续BLOCKED。
5. 新生产资源、费用、密钥、数据迁移、回滚、部署和切流分别取得审批。保持旧环境唯一写入/任务执行者，避免双跑。
6. 不恢复匿名OSS访问作为回滚；新库有真实写入后不能把旧快照直接覆盖回去。

本轮完成的是本地确定性修复、可重复的门禁与新环境准备材料，不是现网修复或可上线验收。
